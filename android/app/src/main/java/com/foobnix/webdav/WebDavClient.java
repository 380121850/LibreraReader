package com.foobnix.webdav;

import com.burgstaller.okhttp.AuthenticationCacheInterceptor;
import com.burgstaller.okhttp.CachingAuthenticatorDecorator;
import com.burgstaller.okhttp.DispatchingAuthenticator;
import com.burgstaller.okhttp.basic.BasicAuthenticator;
import com.burgstaller.okhttp.digest.CachingAuthenticator;
import com.burgstaller.okhttp.digest.Credentials;
import com.burgstaller.okhttp.digest.DigestAuthenticator;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.thegrizzlylabs.sardineandroid.DavResource;
import com.thegrizzlylabs.sardineandroid.Sardine;
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;

/**
 * Thin wrapper around the Sardine-Android WebDAV client (com.thegrizzlylabs:
 * sardine-android 0.9). Listings and downloads only - the module is read-only.
 * No OPDS classes are used here.
 *
 * Connection hardening (home NAS servers): sardine 0.9 ships only a Basic
 * authenticator, so credentials are wired through okhttp-digest's
 * DispatchingAuthenticator (Basic + Digest, chosen by the server's
 * WWW-Authenticate challenge). Self-signed HTTPS servers can be accepted per
 * server with the trustAll flag.
 */
public class WebDavClient {

    /** Error kind of the last failed request: "", "auth", "ssl", "network", "other". */
    public static volatile String lastError = "";

    /**
     * Kept for existing callers: true when the last failure looked like an
     * HTTP 401/403 auth rejection.
     */
    public static volatile boolean lastErrorWasAuth = false;

    /** Reusable clients keyed by credential set: every periodic/debounced
     * sync used to build a fresh OkHttpSardine (own connection pool +
     * dispatcher threads) and never shut it down. */
    private static final Map<String, Sardine> CLIENTS = new ConcurrentHashMap<String, Sardine>();

    public static Sardine sardine(String login, String password) {
        return sardine(login, password, false);
    }

    public static Sardine sardine(String login, String password, boolean trustAll) {
        final String key = login + "|" + password + "|" + trustAll;
        final Sardine cached = CLIENTS.get(key);
        if (cached != null) {
            return cached;
        }
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS);

        if (trustAll) {
            applyTrustAll(builder);
        }

        if (TxtUtils.isNotEmpty(login)) {
            // sardine 0.9 only adds a Basic header via setCredentials; a
            // Digest-only server answers 401 forever. Route auth through
            // okhttp-digest which speaks both, selected by the challenge.
            Credentials credentials = new Credentials(login, password);
            DispatchingAuthenticator authenticator = new DispatchingAuthenticator.Builder()
                    .with("digest", new DigestAuthenticator(credentials))
                    .with("basic", new BasicAuthenticator(credentials))
                    .build();
            Map<String, CachingAuthenticator> authCache = new ConcurrentHashMap<String, CachingAuthenticator>();
            builder.authenticator(new CachingAuthenticatorDecorator(authenticator, authCache));
            builder.addInterceptor(new AuthenticationCacheInterceptor(authCache));
        }

        final Sardine created = new OkHttpSardine(builder.build());
        CLIENTS.put(key, created);
        return created;
    }

    /** Accept any certificate / hostname (opt-in per server, LAN self-signed). */
    private static void applyTrustAll(OkHttpClient.Builder builder) {
        try {
            final TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            builder.sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0]);
            builder.hostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /**
     * PROPFIND depth 1 listing of {@code url}.
     *
     * @return items (directories first, then files, alphabetical), or
     * {@code null} when the request failed (network / auth). Check
     * {@link #lastError} for the failure kind right after a null return.
     */
    public static List<WebDavItem> list(String url, String login, String password, boolean trustAll) {
        try {
            List<DavResource> resources = sardine(login, password, trustAll).list(url);
            List<WebDavItem> items = new ArrayList<WebDavItem>();
            for (DavResource r : resources) {
                URI href = r.getHref();
                if (href == null) {
                    continue;
                }
                String h = resolve(url, href.toString());
                if (isSelf(url, h)) {
                    continue;
                }
                WebDavItem item = new WebDavItem();
                item.href = h;
                item.isDir = r.isDirectory();
                item.name = r.getName();
                if (TxtUtils.isEmpty(item.name)) {
                    item.name = lastName(h);
                }
                Long len = r.getContentLength();
                item.size = len == null ? -1 : len;
                items.add(item);
            }
            sort(items);
            lastError = "";
            lastErrorWasAuth = false;
            return items;
        } catch (Exception e) {
            LOG.e(e);
            lastError = classifyError(e);
            lastErrorWasAuth = "auth".equals(lastError);
            return null;
        }
    }

    /** Categorize a failure so the UI can suggest the right remedy. */
    private static String classifyError(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof SSLException) {
                return "ssl";
            }
            if (c instanceof UnknownHostException) {
                return "network";
            }
            String msg = c.getMessage();
            if (msg != null && (msg.contains("401") || msg.contains("403"))) {
                return "auth";
            }
            String simple = c.getClass().getSimpleName();
            if (simple.contains("Unauthorized") || simple.contains("Forbidden")) {
                return "auth";
            }
            if (simple.contains("Timeout") || simple.contains("Connect") || simple.contains("Socket")) {
                return "network";
            }
        }
        return "other";
    }

    public static InputStream openStream(String url, String login, String password, boolean trustAll) throws IOException {
        return sardine(login, password, trustAll).get(url);
    }

    static String resolve(String base, String href) {
        if (href == null || href.isEmpty()) {
            return base;
        }
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href;
        }
        try {
            // Ensure the base path ends with '/' so URI.resolve treats the last
            // segment as a directory (RFC 3986 §5.3): without it,
            // "http://h/dav".resolve("sub/") wrongly drops "dav".
            String b = base.endsWith("/") ? base : base + "/";
            return URI.create(b).resolve(href).toString();
        } catch (Exception e) {
            return base;
        }
    }

    private static boolean isSelf(String url, String href) {
        return WebDavStore.trimSlash(url).equals(WebDavStore.trimSlash(href));
    }

    public static String lastName(String href) {
        String p = href;
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    private static void sort(List<WebDavItem> items) {
        Collections.sort(items, new Comparator<WebDavItem>() {
            @Override
            public int compare(WebDavItem lhs, WebDavItem rhs) {
                if (lhs.isDir != rhs.isDir) {
                    return lhs.isDir ? -1 : 1;
                }
                String a = lhs.name == null ? "" : lhs.name.toLowerCase();
                String b = rhs.name == null ? "" : rhs.name.toLowerCase();
                return a.compareTo(b);
            }
        });
    }
}

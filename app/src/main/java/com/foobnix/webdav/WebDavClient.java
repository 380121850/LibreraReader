package com.foobnix.webdav;

import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.thegrizzlylabs.sardineandroid.DavResource;
import com.thegrizzlylabs.sardineandroid.Sardine;
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Thin wrapper around the Sardine-Android WebDAV client (com.thegrizzlylabs:
 * sardine-android 0.9). Listings and downloads only - the module is read-only.
 * No OPDS classes are used here.
 */
public class WebDavClient {

    /**
     * Hint set by {@link #list} when the most recent failure looked like an
     * authentication rejection (HTTP 401/403). Callers read it right after a
     * {@code null} return to choose between "auth failed" and generic
     * "network error" messaging. Safe because WebDAV requests are serialized
     * by the fragment's in-progress guard.
     */
    public static volatile boolean lastErrorWasAuth = false;

    public static Sardine sardine(String login, String password) {
        OkHttpSardine s = new OkHttpSardine();
        if (TxtUtils.isNotEmpty(login)) {
            s.setCredentials(login, password);
        }
        return s;
    }

    /**
     * PROPFIND depth 1 listing of {@code url}.
     *
     * @return items (directories first, then files, alphabetical), or
     *         {@code null} when the request failed (network / 401 auth).
     */
    public static List<WebDavItem> list(String url, String login, String password) {
        try {
            List<DavResource> resources = sardine(login, password).list(url);
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
            lastErrorWasAuth = false;
            return items;
        } catch (Exception e) {
            LOG.e(e);
            lastErrorWasAuth = isAuthError(e);
            return null;
        }
    }

    /**
     * Heuristic: does this failure look like an HTTP 401/403 auth rejection?
     * Sardine wraps non-2xx responses in an IOException whose message carries
     * the status code, so we walk the cause chain looking for it.
     */
    private static boolean isAuthError(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            String msg = c.getMessage();
            if (msg != null && (msg.contains("401") || msg.contains("403"))) {
                return true;
            }
            String simple = c.getClass().getSimpleName();
            if (simple.contains("Unauthorized") || simple.contains("Forbidden")) {
                return true;
            }
        }
        return false;
    }

    public static InputStream openStream(String url, String login, String password) throws IOException {
        return sardine(login, password).get(url);
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

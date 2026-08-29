package com.foobnix.webdav;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.foobnix.android.utils.IO;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.AppsConfig;
import com.thegrizzlylabs.sardineandroid.Sardine;

import org.ebookdroid.common.settings.books.SharedBooks;
import org.librera.JSONException;
import org.librera.LinkedJSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.UnknownHostException;
import java.util.Iterator;
import java.util.List;

import javax.net.ssl.SSLException;

/**
 * Reading-data sync over WebDAV (reading progress + bookmarks), following the
 * Anx Reader incremental-sync analysis: entries are merged individually by
 * timestamp instead of overwriting whole files, so a device with an older
 * snapshot never clobbers a newer reading position from another device.
 *
 * Remote layout (under the configured server root):
 *   /Librera/progress.json    merged app-Progress.json  (key = book file name, "t" = update time)
 *   /Librera/bookmarks.json   merged app-Bookmarks.json (key = bookmark creation time)
 *
 * Merge rules:
 *   progress  - per book, the entry with the newer "t" wins
 *   bookmarks - union by creation-time key (both sides keep their bookmarks)
 */
public class WebDavSyncer {

    public static final String REMOTE_DIR = "Librera";
    public static final String REMOTE_PROGRESS = "progress.json";
    public static final String REMOTE_BOOKMARKS = "bookmarks.json";

    public static class SyncResult {
        public boolean ok = false;
        /** "", "auth", "ssl", "network", "no_server", "other" */
        public String error = "";
        public int progressUp, progressDown;
        public int bookmarksUp, bookmarksDown;
        public long durationMs;
    }

    public interface Listener {
        void onStep(String step);

        void onFinish(SyncResult result);
    }

    /** Run the sync on a background thread; callbacks arrive on the main thread. */
    public static void syncAsync(final Context c, final Listener listener) {
        final Handler main = new Handler(Looper.getMainLooper());
        AppsConfig.executorServiceSingle.execute(() -> {
            if (listener != null) {
                main.post(() -> listener.onStep("sync"));
            }
            final SyncResult result = doSync(c);
            if (listener != null) {
                main.post(() -> listener.onFinish(result));
            }
        });
    }

    /**
     * Effective sync server config: the sync settings when configured, else
     * (as a default only) the first browsing WebDAV server from "My files"
     * with its stored credentials.
     *
     * @return {url, login, password} or null when nothing is configured.
     */
    public static String[] resolveConfig(Context c) {
        String url = AppState.get().webdavSyncServer;
        if (TxtUtils.isEmpty(url)) {
            List<WebDavServer> servers = WebDavStore.load();
            if (!servers.isEmpty()) {
                url = servers.get(0).url;
            }
        }
        if (TxtUtils.isEmpty(url)) {
            return null;
        }
        String[] creds = WebDavCredentials.load(c, url);
        return new String[]{url, creds != null ? creds[0] : "", creds != null ? creds[1] : ""};
    }

    /** Configured server-side sync directory, cleaned of slashes. */
    public static String remoteDir() {
        String dir = AppState.get().webdavSyncRemoteDir;
        if (TxtUtils.isEmpty(dir)) {
            dir = REMOTE_DIR;
        }
        dir = dir.replace("\\", "/");
        while (dir.startsWith("/")) {
            dir = dir.substring(1);
        }
        while (dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        return TxtUtils.isEmpty(dir) ? REMOTE_DIR : dir;
    }

    public static SyncResult doSync(Context c) {
        SyncResult res = new SyncResult();
        long start = System.currentTimeMillis();
        try {
            String[] cfg = resolveConfig(c);
            if (cfg == null) {
                res.error = "no_server";
                return res;
            }
            String serverUrl = cfg[0];
            boolean trustAll = WebDavCredentials.isTrustAll(c, serverUrl);
            Sardine s = WebDavClient.sardine(cfg[1], cfg[2], trustAll);

            String root = WebDavStore.trimSlash(serverUrl) + "/" + remoteDir();
            // 405 "already exists" is the normal case on every sync after the first
            try {
                s.createDirectory(root);
            } catch (IOException e) {
                LOG.d("WebDavSyncer mkcol", e.getMessage());
            }

            // ---- reading progress: per-book merge (newer edit or further
            // position wins, per the configured conflict policy)
            boolean farther = "farther".equals(AppState.get().webdavSyncPolicy);
            LinkedJSONObject localP = IO.readJsonObject(AppProfile.syncProgress);
            LinkedJSONObject remoteP = fetchJson(s, root + "/" + REMOTE_PROGRESS);
            int pDown = mergeProgress(localP, remoteP, farther);
            int pTotal = localP.length();
            IO.writeObjSync(AppProfile.syncProgress, localP);

            // ---- bookmarks: union by creation-time key
            LinkedJSONObject localB = IO.readJsonObject(AppProfile.syncBookmarks);
            LinkedJSONObject remoteB = fetchJson(s, root + "/" + REMOTE_BOOKMARKS);
            int bDown = mergeBookmarks(localB, remoteB);
            int bTotal = localB.length();
            IO.writeObjSync(AppProfile.syncBookmarks, localB);

            // ---- publish the merged state so the next device downloads it too
            s.put(root + "/" + REMOTE_PROGRESS, localP.toString().getBytes("UTF-8"));
            s.put(root + "/" + REMOTE_BOOKMARKS, localB.toString().getBytes("UTF-8"));

            // in-memory progress cache is now stale
            SharedBooks.cache.clear();

            AppState.get().webdavLastSyncTime = System.currentTimeMillis();
            res.progressDown = pDown;
            res.progressUp = pTotal - pDown;
            res.bookmarksDown = bDown;
            res.bookmarksUp = bTotal - bDown;
            res.durationMs = System.currentTimeMillis() - start;
            AppState.get().webdavLastSyncInfo = "\u2191" + res.progressUp + " \u2193" + res.progressDown
                    + " \u00b7 " + res.durationMs + "ms";
            AppState.get().save(c);

            res.ok = true;
        } catch (Exception e) {
            LOG.e(e);
            res.error = classify(e);
            res.durationMs = System.currentTimeMillis() - start;
        }
        return res;
    }

    /** GET a remote JSON object; a missing file (404) or bad payload = empty. */
    static LinkedJSONObject fetchJson(Sardine s, String url) {
        InputStream is = null;
        try {
            is = s.get(url);
            return new LinkedJSONObject(IO.readString(is));
        } catch (Exception e) {
            LOG.d("WebDavSyncer fetch", url, e.getMessage());
            return new LinkedJSONObject();
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Keep the winning entry per book; returns how many remote entries won.
     * Default policy: newer edit time ("t"). Farther policy: the position
     * closer to the end of the book ("p") wins regardless of time.
     */
    static int mergeProgress(LinkedJSONObject local, LinkedJSONObject remote, boolean farther) throws JSONException {
        int taken = 0;
        Iterator<String> keys = remote.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            LinkedJSONObject r = remote.optJSONObject(key);
            if (r == null) {
                continue;
            }
            LinkedJSONObject l = local.optJSONObject(key);
            boolean remoteWins;
            if (l == null) {
                remoteWins = true;
            } else if (farther) {
                float rp = (float) r.optDouble("p", 0);
                float lp = (float) l.optDouble("p", 0);
                remoteWins = rp > lp || (rp == lp && r.optLong("t", 0) > l.optLong("t", 0));
            } else {
                remoteWins = r.optLong("t", 0) > l.optLong("t", 0);
            }
            if (remoteWins) {
                local.put(key, r);
                taken++;
            }
        }
        return taken;
    }

    /** Union of both sides by creation-time key; returns how many were new locally. */
    static int mergeBookmarks(LinkedJSONObject local, LinkedJSONObject remote) throws JSONException {
        int taken = 0;
        Iterator<String> keys = remote.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!local.has(key)) {
                LinkedJSONObject r = remote.optJSONObject(key);
                if (r != null) {
                    local.put(key, r);
                    taken++;
                }
            }
        }
        return taken;
    }

    static String classify(Throwable e) {
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
}

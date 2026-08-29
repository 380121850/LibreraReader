package com.foobnix.webdav;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.foobnix.android.utils.FileHash;
import com.foobnix.android.utils.IO;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppBookmark;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppState;
import com.foobnix.model.MyPath;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.BookmarksData;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.ui2.AppDB;
import com.thegrizzlylabs.sardineandroid.DavResource;
import com.thegrizzlylabs.sardineandroid.Sardine;

import org.ebookdroid.common.settings.books.SharedBooks;
import org.librera.LinkedJSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.SSLException;

/**
 * Reading-data sync over WebDAV, following the Anx Reader incremental-sync
 * idea: entries are merged individually instead of overwriting whole files,
 * so a device with an older snapshot never clobbers a newer reading position
 * from another device.
 *
 * Remote layout (under the configured server root):
 *   /&lt;dir&gt;/global/app-State.json   global settings (two-way, newer file wins;
 *                                      volatile fields webdavLastSync* excluded
 *                                      from the comparison to avoid echo loops)
 *   /&lt;dir&gt;/global/app-CSS.json     global book styling (two-way, newer wins)
 *   /&lt;dir&gt;/books/&lt;hash&gt;.json      per-book info: name + content hash identity,
 *                                      reading progress and bookmarks/AI notes.
 *                                      The book FILE itself is never synced.
 *
 * Book identity: MD5 of the book file content (FileHash, cached). On restore
 * each book info is applied independently ("per-book restore"): the local
 * candidate file is matched by name and confirmed by hash — a hash match
 * fully associates progress + bookmarks with that file (bookmark paths are
 * rewritten to the local file); a hash mismatch (same name, different file)
 * never overwrites local progress; books without a local file yet are kept
 * "pending" and associate automatically once the file appears. One corrupt
 * remote book info never aborts the rest.
 *
 * Legacy aggregate files (progress.json / bookmarks.json) are imported once
 * and then deleted from the server.
 */
public class WebDavSyncer {

    public static final String REMOTE_DIR = "Librera";
    private static final String REMOTE_GLOBAL = "global";
    private static final String REMOTE_BOOKS = "books";
    private static final String REMOTE_LEGACY_PROGRESS = "progress.json";
    private static final String REMOTE_LEGACY_BOOKMARKS = "bookmarks.json";
    private static final String STATE_FILE = "app-State.json";
    private static final String CSS_FILE = "app-CSS.json";

    public static class SyncResult {
        public boolean ok = false;
        /** "", "auth", "ssl", "network", "no_server", "other" */
        public String error = "";
        public int progressUp, progressDown;
        public int bookmarksUp, bookmarksDown;
        /** number of per-book info files merged or uploaded */
        public int booksSynced;
        /** number of books whose hash matched a local file (full restore) */
        public int booksAssociated;
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
            String globalUrl = root + "/" + REMOTE_GLOBAL;
            String booksUrl = root + "/" + REMOTE_BOOKS;
            mkDirs(s, root, globalUrl, booksUrl);

            // ---- global config: two-way, newer file wins (volatile fields excluded)
            syncGlobalFile(s, globalUrl, AppProfile.syncState, true);
            syncGlobalFile(s, globalUrl, AppProfile.syncCSS, false);

            // ---- local state: progress per book + bookmarks by creation time
            final boolean farther = "farther".equals(AppState.get().webdavSyncPolicy);
            final LinkedJSONObject localP = IO.readJsonObject(AppProfile.syncProgress);
            final LinkedJSONObject localB = IO.readJsonObject(AppProfile.syncBookmarks);

            // legacy aggregate files: import once, then remove from the server
            migrateLegacy(s, root, localP, localB, farther);

            // ---- local per-book info, keyed by book file name
            final Map<String, LinkedJSONObject> localBooks = buildLocalBooks(localP, localB);

            // ---- local files: name → existing file (bookmarks + library DB)
            final Map<String, File> candidates = buildLocalCandidates();

            // ---- remote per-book infos, hash → info
            final Map<String, LinkedJSONObject> remoteBooks = listRemoteBooks(s, booksUrl);

            int pDown = 0, bDown = 0, associated = 0;
            Set<String> namesCovered = new HashSet<>();
            Set<String> uploadedHashes = new HashSet<>();

            // ---- apply every remote book info INDEPENDENTLY (per-book restore:
            // one corrupt or conflicting book never blocks the others)
            for (Map.Entry<String, LinkedJSONObject> e : remoteBooks.entrySet()) {
                String rHash = e.getKey();
                LinkedJSONObject info = e.getValue();
                try {
                    String name = info.optString("name");
                    if (TxtUtils.isEmpty(name)) {
                        continue;
                    }
                    File localFile = candidates.get(name);
                    boolean matched = localFile != null && rHash.equals(FileHash.md5(localFile));
                    boolean conflict = localFile != null && !matched;
                    if (matched) {
                        associated++;
                    }
                    if (!conflict) {
                        // covered: hash-confirmed, or a book this device does not
                        // have yet (pending). A same-name DIFFERENT file is a
                        // separate book and is uploaded under its own hash below.
                        namesCovered.add(name);
                    }

                    // progress: only a hash-confirmed book (or a book this device
                    // does not have yet) may change the local reading position
                    LinkedJSONObject rp = info.optJSONObject("progress");
                    if (rp != null && (matched || localFile == null)) {
                        if (mergeProgressEntry(localP, name, rp, farther)) {
                            pDown++;
                        }
                    }

                    // bookmarks: union by creation-time key; on a hash match the
                    // incoming entries are re-pointed at the local file so notes
                    // and bookmarks follow the book whatever the source path was
                    LinkedJSONObject rbs = info.optJSONObject("bookmarks");
                    if (rbs != null) {
                        Iterator<String> keys = rbs.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            if (localB.has(key)) {
                                continue;
                            }
                            LinkedJSONObject bm = rbs.optJSONObject(key);
                            if (bm == null) {
                                continue;
                            }
                            if (matched) {
                                bm.put("path", MyPath.toRelative(localFile.getPath()));
                            }
                            localB.put(key, bm);
                            bDown++;
                        }
                    }

                    // publish the merged info so the next device converges too.
                    // Never merged into on a name conflict: that info file
                    // belongs to the OTHER book with the same name.
                    if (!conflict) {
                        LinkedJSONObject merged = buildInfoWithHash(name, rHash,
                                localP.optJSONObject(name), subsetFor(localB, name));
                        if (merged != null && !merged.toString().equals(info.toString())) {
                            putBookInfo(s, booksUrl, rHash, merged);
                            uploadedHashes.add(rHash);
                        }
                    }
                    res.booksSynced++;
                } catch (Exception bookError) {
                    LOG.e(bookError, "WebDavSyncer book", rHash);
                }
            }

            // ---- local books the server has not seen yet (or same-name
            // different-file variants that live under their own hash)
            for (Map.Entry<String, LinkedJSONObject> e : localBooks.entrySet()) {
                String name = e.getKey();
                try {
                    if (namesCovered.contains(name)) {
                        continue;
                    }
                    LinkedJSONObject info = e.getValue();
                    String hash = info.optString("hash");
                    if (TxtUtils.isEmpty(hash) || uploadedHashes.contains(hash)) {
                        continue;
                    }
                    LinkedJSONObject remote = remoteBooks.get(hash);
                    if (remote != null && remote.toString().equals(info.toString())) {
                        continue;
                    }
                    putBookInfo(s, booksUrl, hash, info);
                    res.booksSynced++;
                } catch (Exception bookError) {
                    LOG.e(bookError, "WebDavSyncer upload", name);
                }
            }

            IO.writeObjSync(AppProfile.syncProgress, localP);
            IO.writeObjSync(AppProfile.syncBookmarks, localB);

            // in-memory progress cache is now stale
            SharedBooks.cache.clear();

            AppState.get().webdavLastSyncTime = System.currentTimeMillis();
            res.progressDown = pDown;
            res.progressUp = Math.max(0, localP.length() - pDown);
            res.bookmarksDown = bDown;
            res.bookmarksUp = Math.max(0, localB.length() - bDown);
            res.booksAssociated = associated;
            res.durationMs = System.currentTimeMillis() - start;
            AppState.get().webdavLastSyncInfo = "\u2191" + res.booksSynced + "\u672c \u00b7 \u5173\u8054" + associated
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

    // ------------------------------------------------------------------ global

    /**
     * Two-way sync of one global config file: identical content = no-op,
     * otherwise the newer file (remote modified vs local lastModified) wins.
     * Volatile fields (webdavLastSync*) are excluded from the comparison so
     * per-sync status stamps never echo back and forth between devices.
     */
    static void syncGlobalFile(Sardine s, String globalUrl, File local, boolean stripVolatile) {
        try {
            if (local == null || !local.isFile()) {
                return;
            }
            final String localText = stripVolatile
                    ? withoutVolatile(IO.readJsonObject(local)).toString()
                    : readText(local);
            final String remoteText = fetchText(s, globalUrl + "/" + local.getName());
            // both sides compared without the volatile fields
            final String remoteCmp = remoteText == null
                    ? null
                    : (stripVolatile ? withoutVolatile(new LinkedJSONObject(remoteText)).toString() : remoteText);
            if (remoteCmp != null && normalize(remoteCmp).equals(normalize(localText))) {
                return;
            }
            final long remoteMod = remoteModified(s, globalUrl + "/" + local.getName());
            if (remoteText == null) {
                s.put(globalUrl + "/" + local.getName(), readText(local).getBytes("UTF-8"));
            } else if (remoteMod > local.lastModified()) {
                // remote wins: write through, keeping the local volatile fields
                if (stripVolatile) {
                    LinkedJSONObject remote = new LinkedJSONObject(remoteText);
                    keepLocalVolatile(remote);
                    IO.writeObjSync(local, remote);
                } else {
                    IO.writeObjSync(local, new LinkedJSONObject(remoteText));
                }
            } else {
                s.put(globalUrl + "/" + local.getName(), readText(local).getBytes("UTF-8"));
            }
        } catch (Exception e) {
            LOG.e(e, "WebDavSyncer global", local.getName());
        }
    }

    private static LinkedJSONObject withoutVolatile(LinkedJSONObject obj) {
        LinkedJSONObject copy = new LinkedJSONObject();
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if ("webdavLastSyncTime".equals(k) || "webdavLastSyncInfo".equals(k)) {
                continue;
            }
            try {
                copy.put(k, obj.get(k));
            } catch (Exception ignored) {
            }
        }
        return copy;
    }

    private static void keepLocalVolatile(LinkedJSONObject remote) {
        try {
            if (AppState.get().webdavLastSyncTime > 0) {
                remote.put("webdavLastSyncTime", AppState.get().webdavLastSyncTime);
            }
            if (TxtUtils.isNotEmpty(AppState.get().webdavLastSyncInfo)) {
                remote.put("webdavLastSyncInfo", AppState.get().webdavLastSyncInfo);
            }
        } catch (Exception ignored) {
        }
    }

    private static String normalize(String json) {
        try {
            return new LinkedJSONObject(json).toString();
        } catch (Exception e) {
            return json == null ? "" : json.trim();
        }
    }

    // ------------------------------------------------------------------ legacy

    /** Fold the pre-per-book aggregate files into the local state, then remove them. */
    static void migrateLegacy(Sardine s, String root, LinkedJSONObject localP, LinkedJSONObject localB, boolean farther) {
        try {
            LinkedJSONObject legacyP = fetchJson(s, root + "/" + REMOTE_LEGACY_PROGRESS);
            LinkedJSONObject legacyB = fetchJson(s, root + "/" + REMOTE_LEGACY_BOOKMARKS);
            if (legacyP.length() == 0 && legacyB.length() == 0) {
                return;
            }
            LOG.d("WebDavSyncer legacy migrate", legacyP.length(), legacyB.length());
            mergeProgress(localP, legacyP, farther);
            mergeBookmarks(localB, legacyB);
            try {
                s.delete(root + "/" + REMOTE_LEGACY_PROGRESS);
            } catch (Exception ignored) {
            }
            try {
                s.delete(root + "/" + REMOTE_LEGACY_BOOKMARKS);
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    // ------------------------------------------------------------------ books

    /**
     * Local per-book info keyed by book file name: reading progress (own
     * device entry) + bookmarks/AI notes grouped by book. The hash identifies
     * the book file; books whose file is missing get a stable name-based
     * fallback hash so their data still syncs ("pending" association).
     */
    static Map<String, LinkedJSONObject> buildLocalBooks(LinkedJSONObject localP, LinkedJSONObject localB) {
        final Map<String, LinkedJSONObject> books = new LinkedHashMap<String, LinkedJSONObject>();
        final Map<String, LinkedJSONObject> marks = new LinkedHashMap<String, LinkedJSONObject>();
        final Map<String, File> paths = new HashMap<String, File>();

        Iterator<String> bk = localB.keys();
        while (bk.hasNext()) {
            String key = bk.next();
            LinkedJSONObject bm = localB.optJSONObject(key);
            if (bm == null) {
                continue;
            }
            String path = bm.optString("path", null);
            if (TxtUtils.isEmpty(path)) {
                continue;
            }
            String name = ExtUtils.getFileName(MyPath.toAbsolute(path));
            if (TxtUtils.isEmpty(name)) {
                continue;
            }
            LinkedJSONObject set = marks.get(name);
            if (set == null) {
                set = new LinkedJSONObject();
                marks.put(name, set);
            }
            try {
                set.put(key, bm);
            } catch (Exception ignored) {
            }
            File f = new File(MyPath.toAbsolute(path));
            if (f.isFile()) {
                paths.put(name, f);
            }
        }

        Iterator<String> pk = localP.keys();
        while (pk.hasNext()) {
            String name = pk.next();
            LinkedJSONObject entry = localP.optJSONObject(name);
            if (entry == null) {
                continue;
            }
            LinkedJSONObject info = ensure(books, name);
            info.put("progress", entry);
            info.put("t", Math.max(info.optLong("t", 0), entry.optLong("t", 0)));
        }
        for (Map.Entry<String, LinkedJSONObject> e : marks.entrySet()) {
            LinkedJSONObject info = ensure(books, e.getKey());
            info.put("bookmarks", e.getValue());
            long newest = 0;
            Iterator<String> it = e.getValue().keys();
            while (it.hasNext()) {
                LinkedJSONObject bm = e.getValue().optJSONObject(it.next());
                if (bm != null) {
                    newest = Math.max(newest, bm.optLong("t", 0));
                }
            }
            info.put("t", Math.max(info.optLong("t", 0), newest));
        }
        // fill identity: name + content hash (real file when available)
        for (Map.Entry<String, LinkedJSONObject> e : books.entrySet()) {
            String name = e.getKey();
            File f = paths.containsKey(name) ? paths.get(name) : findInLibrary(name);
            String hash = f != null ? FileHash.md5(f) : FileHash.md5("librera-book:" + name);
            e.getValue().put("name", name);
            e.getValue().put("hash", hash);
        }
        return books;
    }

    private static LinkedJSONObject ensure(Map<String, LinkedJSONObject> map, String key) {
        LinkedJSONObject o = map.get(key);
        if (o == null) {
            o = new LinkedJSONObject();
            map.put(key, o);
        }
        return o;
    }

    /** Bookmark subset of one book, in the per-book info shape ({"t": bm}). */
    static LinkedJSONObject subsetFor(LinkedJSONObject localB, String name) {
        LinkedJSONObject out = new LinkedJSONObject();
        Iterator<String> keys = localB.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            LinkedJSONObject bm = localB.optJSONObject(key);
            if (bm == null) {
                continue;
            }
            String p = bm.optString("path", null);
            if (TxtUtils.isEmpty(p)) {
                continue;
            }
            if (name.equals(ExtUtils.getFileName(MyPath.toAbsolute(p)))) {
                try {
                    out.put(key, bm);
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    /** Existing local files by book file name: bookmark paths + library DB. */
    static Map<String, File> buildLocalCandidates() {
        final Map<String, File> candidates = new HashMap<String, File>();
        for (AppBookmark b : BookmarksData.get().getAll()) {
            String p = b.getPath();
            if (p == null) {
                continue;
            }
            File f = new File(p);
            if (f.isFile()) {
                candidates.put(ExtUtils.getFileName(p), f);
            }
        }
        try {
            for (FileMeta m : AppDB.get().getAll()) {
                String p = m.getPath();
                if (TxtUtils.isEmpty(p) || p.startsWith("content:")) {
                    continue;
                }
                File f = new File(p);
                if (f.isFile() && !candidates.containsKey(ExtUtils.getFileName(p))) {
                    candidates.put(ExtUtils.getFileName(p), f);
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        return candidates;
    }

    /** Library-DB lookup used when no bookmark path points at the book. */
    static File findInLibrary(String fileName) {
        try {
            for (FileMeta m : AppDB.get().getAll()) {
                String p = m.getPath();
                if (TxtUtils.isEmpty(p) || p.startsWith("content:")) {
                    continue;
                }
                File f = new File(p);
                if (f.isFile() && ExtUtils.getFileName(p).equals(fileName)) {
                    return f;
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        return null;
    }

    /** Remote per-book infos keyed by the hash in their file name. */
    static Map<String, LinkedJSONObject> listRemoteBooks(Sardine s, String booksUrl) {
        final Map<String, LinkedJSONObject> out = new LinkedHashMap<String, LinkedJSONObject>();
        List<DavResource> list;
        try {
            list = s.list(booksUrl, 1);
        } catch (Exception e) {
            LOG.d("WebDavSyncer list books", e.getMessage());
            return out;
        }
        for (DavResource r : list) {
            try {
                if (r.isDirectory()) {
                    continue;
                }
                String n = r.getName();
                if (n == null || !n.endsWith(".json")) {
                    continue;
                }
                String hash = n.substring(0, n.length() - ".json".length());
                LinkedJSONObject info = fetchJson(s, booksUrl + "/" + n);
                if (info.length() > 0) {
                    out.put(hash, info);
                }
            } catch (Exception e) {
                LOG.e(e, "WebDavSyncer remote book");
            }
        }
        return out;
    }

    static void putBookInfo(Sardine s, String booksUrl, String hash, LinkedJSONObject info) throws IOException {
        s.put(booksUrl + "/" + hash + ".json", info.toString().getBytes("UTF-8"));
    }

    /**
     * Per-book info document: the book file name and content hash (identity),
     * reading progress and the bookmarks/AI-notes map. "t" is the newest
     * update time among the entries, for display/debug.
     */
    static LinkedJSONObject buildInfoWithHash(String name, String hash, LinkedJSONObject progress,
                                              LinkedJSONObject bookmarks) {
        try {
            LinkedJSONObject info = new LinkedJSONObject();
            info.put("name", name);
            info.put("hash", hash == null ? "" : hash);
            info.put("t", System.currentTimeMillis());
            if (progress != null) {
                info.put("progress", progress);
            }
            if (bookmarks != null && bookmarks.length() > 0) {
                info.put("bookmarks", bookmarks);
            }
            return info;
        } catch (Exception e) {
            LOG.e(e);
            return null;
        }
    }

    /**
     * Apply one remote progress entry for one book; the configured policy
     * decides the winner: newer edit time ("t") or the position closer to the
     * end of the book ("p").
     */
    static boolean mergeProgressEntry(LinkedJSONObject localP, String name, LinkedJSONObject r, boolean farther) {
        LinkedJSONObject l = localP.optJSONObject(name);
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
            localP.put(name, r);
        }
        return remoteWins;
    }

    // ------------------------------------------------------------------ io helpers

    private static void mkDirs(Sardine s, String... dirs) {
        for (String dir : dirs) {
            // 405 "already exists" is the normal case on every sync after the first
            try {
                s.createDirectory(dir);
            } catch (IOException e) {
                LOG.d("WebDavSyncer mkcol", e.getMessage());
            }
        }
    }

    /** GET a remote JSON object; a missing file (404) or bad payload = empty. */
    static LinkedJSONObject fetchJson(Sardine s, String url) {
        try {
            String text = fetchText(s, url);
            return text == null ? new LinkedJSONObject() : new LinkedJSONObject(text);
        } catch (Exception e) {
            LOG.d("WebDavSyncer fetch", url, e.getMessage());
            return new LinkedJSONObject();
        }
    }

    /** GET a remote text file; null when it does not exist or cannot be read. */
    static String fetchText(Sardine s, String url) {
        InputStream is = null;
        try {
            is = s.get(url);
            return IO.readString(is);
        } catch (Exception e) {
            LOG.d("WebDavSyncer fetch", url, e.getMessage());
            return null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String readText(File f) throws IOException {
        InputStream is = null;
        try {
            is = new FileInputStream(f);
            return IO.readString(is);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Last-modified of a remote resource; 0 when the server does not tell. */
    static long remoteModified(Sardine s, String url) {
        try {
            List<DavResource> list = s.list(url, 0);
            if (!list.isEmpty()) {
                Date m = list.get(0).getModified();
                return m == null ? 0 : m.getTime();
            }
        } catch (Exception e) {
            LOG.d("WebDavSyncer modified", url, e.getMessage());
        }
        return 0;
    }

    // ------------------------------------------------------------------ legacy merge (kept for migration)

    /**
     * Keep the winning entry per book; returns how many remote entries won.
     * Default policy: newer edit time ("t"). Farther policy: the position
     * closer to the end of the book ("p") wins regardless of time.
     */
    static int mergeProgress(LinkedJSONObject local, LinkedJSONObject remote, boolean farther) {
        int taken = 0;
        Iterator<String> keys = remote.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            LinkedJSONObject r = remote.optJSONObject(key);
            if (r == null) {
                continue;
            }
            if (mergeProgressEntry(local, key, r, farther)) {
                taken++;
            }
        }
        return taken;
    }

    /** Union of both sides by creation-time key; returns how many were new locally. */
    static int mergeBookmarks(LinkedJSONObject local, LinkedJSONObject remote) {
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

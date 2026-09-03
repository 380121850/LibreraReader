package com.foobnix.webdav;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.foobnix.android.utils.Dips;
import com.foobnix.android.utils.FileHash;
import com.foobnix.android.utils.IO;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.dao2.FileMeta;
import com.foobnix.LibreraApp;
import com.foobnix.model.AppBookmark;
import com.foobnix.model.AppData;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.model.MyPath;
import com.foobnix.model.ProfileStateIO;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.BookmarksData;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.ui2.AppDB;
import com.thegrizzlylabs.sardineandroid.DavResource;
import com.thegrizzlylabs.sardineandroid.Sardine;

import org.ebookdroid.common.settings.books.SharedBooks;
import org.librera.JSONArray;
import org.librera.LinkedJSONObject;

import java.io.ByteArrayOutputStream;
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
import java.util.ArrayList;
import java.util.Arrays;

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

    public static final String REMOTE_DIR = "HowRead";
    /** sync folder name before the HowRead rebrand, still imported once */
    public static final String REMOTE_LEGACY_DIR = "Librera";
    private static final String REMOTE_GLOBAL = "global";
    private static final String REMOTE_BOOKS = "books";
    private static final String REMOTE_LEGACY_PROGRESS = "progress.json";
    private static final String REMOTE_LEGACY_BOOKMARKS = "bookmarks.json";
    private static final String STATE_FILE = "app-State.json";
    private static final String CSS_FILE = "app-CSS.json";

    /**
     * Device-bound app-State.json fields (screen metrics, absolute paths,
     * session residue, per-device credentials): excluded from the sync
     * comparison, stripped from the uploaded copy and re-applied locally
     * after a download, so devices never overwrite each other with values
     * that only make sense on one machine.
     */
    private static final Set<String> STATE_DEVICE_FIELDS = new HashSet<String>(Arrays.asList(
            "displayPath", "installationDate",
            "statusBarTextSizeAdv", "statusBarTextSizeEasy", "progressLineHeight",
            "tapzoneSize", "coverBigSize", "isReverseKeys", "pageQuality",
            "isCutRTL", "isRTLByDefault", "selectingByLetters",
            "fileToDelete", "myAutoCompleteDb",
            "bgImageDayPath", "bgImageNightPath",
            "proxyEnable", "proxyServer", "proxyPort", "proxyUser", "proxyPassword",
            "selectedText", "searchQuery", "isAutoScroll",
            "hashCode", "webdavLastSyncTime", "webdavLastSyncInfo"));

    /** Device-bound app-CSS.json fields (absolute paths and the SAF URI). */
    private static final Set<String> CSS_DEVICE_FIELDS = new HashSet<String>(Arrays.asList(
            "searchPathsJson", "cachePath", "downlodsPath", "ttsSpeakPath", "backupPath",
            "dictPath", "fontFolder", "dirLastPath", "pathSAF", "mp3BookPathJson",
            "syncDropboxPath", "syncGdrivePath", "syncOneDrivePath",
            "hashCode"));

    /**
     * The sync setup fields: setting them is what ENABLES the first sync on
     * a fresh device, so "just configured sync" must not make that device
     * look personalized — they are ignored by the default-config detection
     * and kept local when the server copy is adopted.
     */
    private static final Set<String> SYNC_CONFIG_FIELDS = new HashSet<String>(Arrays.asList(
            "webdavSyncEnabled", "webdavSyncServer", "webdavSyncRemoteDir",
            "webdavSyncPolicy", "webdavSyncIntervalMin"));

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
        /** server info files removed because their book was deleted locally */
        public int booksDeleted;
        public long durationMs;
    }

    public interface Listener {
        void onStep(String step);

        void onFinish(SyncResult result);
    }

    /** True while a doSync runs on the worker thread: the lastSync stamps
     * that doSync itself writes through AppState.save must never trigger
     * another automatic sync (that would never stop). */
    private static volatile boolean syncingNow;

    private static final Handler SYNC_SCHEDULER = new Handler(Looper.getMainLooper());
    private static final long CONFIG_SYNC_DEBOUNCE_MS = 10 * 1000;
    private static Runnable pendingConfigSync;
    private static Runnable pendingPeriodicSync;

    /** Run the sync on a background thread; callbacks arrive on the main thread. */
    public static void syncAsync(final Context c, final Listener listener) {
        final Handler main = new Handler(Looper.getMainLooper());
        AppsConfig.executorServiceSingle.execute(() -> {
            syncingNow = true;
            try {
                if (listener != null) {
                    main.post(() -> listener.onStep("sync"));
                }
                final SyncResult result = doSync(c);
                if (listener != null) {
                    main.post(() -> listener.onFinish(result));
                }
            } finally {
                syncingNow = false;
            }
        });
    }

    /**
     * The local configuration changed (AppState.save / BookCSS.save): run one
     * silent sync shortly after, coalescing a burst of saves into a single
     * run. Dropped while a sync is in progress — the only saves made inside
     * doSync are its own lastSync stamps — so periodic/manual syncs stay the
     * safety net for anything changed during those few seconds.
     */
    public static void notifyConfigChanged(final Context c) {
        try {
            if (syncingNow) {
                return;
            }
            if (pendingConfigSync != null) {
                SYNC_SCHEDULER.removeCallbacks(pendingConfigSync);
            }
            pendingConfigSync = () -> {
                pendingConfigSync = null;
                if (syncingNow) {
                    return;
                }
                if (AppState.get().webdavSyncEnabled && TxtUtils.isNotEmpty(AppState.get().webdavSyncServer)) {
                    syncAsync(c.getApplicationContext(), null);
                }
            };
            SYNC_SCHEDULER.postDelayed(pendingConfigSync, CONFIG_SYNC_DEBOUNCE_MS);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /**
     * Periodic background sync while the app is alive. The interval is
     * re-read on every cycle, so a new value picked in the dialog (or synced
     * from another device) applies from the next cycle on;
     * webdavSyncIntervalMin &lt;= 0 disables the periodic sync.
     */
    public static void scheduleNextPeriodic(final Context c) {
        if (pendingPeriodicSync != null) {
            SYNC_SCHEDULER.removeCallbacks(pendingPeriodicSync);
        }
        final int min = AppState.get().webdavSyncIntervalMin;
        if (min <= 0) {
            return;
        }
        pendingPeriodicSync = () -> {
            pendingPeriodicSync = null;
            final Context app = c.getApplicationContext();
            if (AppState.get().webdavSyncEnabled && TxtUtils.isNotEmpty(AppState.get().webdavSyncServer)) {
                syncAsync(app, null);
            }
            scheduleNextPeriodic(app);
        };
        SYNC_SCHEDULER.postDelayed(pendingPeriodicSync, min * 60 * 1000L);
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
        // the legacy default follows the rebrand; a stale "Librera" may also
        // come back through a synced app-State.json (importAppState)
        if (TxtUtils.isEmpty(dir) || REMOTE_LEGACY_DIR.equals(dir)) {
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
            importLegacyRemoteDir(s, WebDavStore.trimSlash(serverUrl), root);
            mkDirs(s, root, globalUrl, booksUrl);

            // ---- global config: two-way, newer file wins (volatile fields excluded)
            // capture the locally-configured network sources FIRST: a newer
            // remote app-State.json would otherwise overwrite them below
            ProfileStateIO.exportNetworkSources();
            syncGlobalFile(s, globalUrl, AppProfile.syncState, true);
            syncGlobalFile(s, globalUrl, AppProfile.syncCSS, false);

            // ---- whole-file state: mirror stats / AI key / misc to files, then
            // sync every list and config file as a WHOLE (newer mtime wins).
            // Simple and predictable: the last device that changed a file wins
            // and deletions propagate without tombstones. (Both sides editing
            // the same file between two syncs resolves to "later sync wins".)
            ProfileStateIO.exportStats();
            ProfileStateIO.exportAi(c);
            ProfileStateIO.exportMisc(c);
            boolean listsUpdated = syncWholeFile(s, globalUrl, AppProfile.syncRecent);
            listsUpdated |= syncWholeFile(s, globalUrl, AppProfile.syncFavorite);
            if (listsUpdated) {
                AppData.get().invalidateListCache();
            }
            syncMergedObjectFile(s, globalUrl, AppProfile.syncBookStates, WebDavSyncer::mergeStatesMaxWins);
            syncWholeFile(s, globalUrl, AppProfile.syncStats);
            // the AI key file is merged, not whole-file mtime-wins: a reset
            // device exports an empty key right before the sync, and plain
            // newer-wins would let that fresh empty file clobber the server
            // copy (and then importAi would have nothing to restore)
            syncMergedObjectFile(s, globalUrl, AppProfile.syncAI, ProfileStateIO::mergeAi);
            syncWholeFile(s, globalUrl, AppProfile.syncMisc);
            ProfileStateIO.importAi(c);
            ProfileStateIO.importMisc(c);
            // re-apply the synced reading statistics AFTER importMisc: the
            // misc import restores the whole AppSP object from app-Misc.json
            // and would otherwise overwrite the synced statistics with the
            // stale values carried inside it
            ProfileStateIO.importStats(c);
            // apply the synced global settings (AI model config, …) to the
            // running app; AppState.save() at the end would otherwise write
            // the stale in-memory state back over the synced file
            ProfileStateIO.importAppState();

            // ---- network sources (OPDS catalogs, WebDAV servers, 书库文件夹):
            // dedicated whole-file, applied AFTER the global files so the
            // newer-wins app-State.json import can never override the lists;
            // imported wholesale, which is how deletions propagate
            syncWholeFile(s, globalUrl, AppProfile.syncNetworkSources);
            ProfileStateIO.importNetworkSources();
            AppProfile.save(c);
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
            // locally deleted progress/bookmarks (marked-unread, bookmark
            // removal): never merged back, server file removed when nothing
            // remains to keep it alive
            final LinkedJSONObject deletedBooks = SharedBooks.DeletedBooks.all();
            final List<String> hashesToDelete = new ArrayList<String>();

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
                    final boolean delProgress = markKind(deletedBooks, name, "p");
                    final boolean delBookmarks = markKind(deletedBooks, name, "b");
                    // specific bookmark keys the user deleted for this book:
                    // never re-merged from the server, and dropped from the
                    // published info so the server converges (a partial delete
                    // whose book still has progress/other notes would otherwise
                    // be union-merged back on a later round)
                    final Set<String> deletedKeys = SharedBooks.DeletedBooks.keysOf(deletedBooks, name);
                    if ((delProgress || delBookmarks)
                            && !localP.has(name) && subsetFor(localB, name).length() == 0) {
                        // everything the user deleted locally is gone here too:
                        // remove the server copy instead of restoring it
                        hashesToDelete.add(rHash);
                        namesCovered.add(name);
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
                    if (rp != null && !delProgress && (matched || localFile == null)) {
                        if (mergeProgressEntry(localP, name, rp, farther)) {
                            pDown++;
                        }
                    }

                    // bookmarks: union by creation-time key; on a hash match the
                    // incoming entries are re-pointed at the local file so notes
                    // and bookmarks follow the book whatever the source path was
                    LinkedJSONObject rbs = info.optJSONObject("bookmarks");
                    if (rbs != null && !delBookmarks) {
                        Iterator<String> keys = rbs.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            if (localB.has(key) || deletedKeys.contains(key)) {
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
                        LinkedJSONObject pubBm = subsetFor(localB, name);
                        if (!deletedKeys.isEmpty()) {
                            for (String dk : deletedKeys) {
                                pubBm.remove(dk);
                            }
                        }
                        LinkedJSONObject merged = buildInfoWithHash(name, rHash,
                                localP.optJSONObject(name), pubBm);
                        if (merged != null && !merged.toString().equals(info.toString())) {
                            putBookInfo(s, booksUrl, rHash, merged);
                            uploadedHashes.add(rHash);
                            // the deleted keys are now gone from the server:
                            // stop carrying their tombstones (keep "p"/"b")
                            if (!deletedKeys.isEmpty()) {
                                SharedBooks.DeletedBooks.clearKeys(name);
                            }
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

            // remove server copies of books whose progress/bookmarks were
            // deleted locally and have nothing left to keep them alive
            for (String h : hashesToDelete) {
                try {
                    s.delete(booksUrl + "/" + h + ".json");
                    res.booksDeleted++;
                } catch (Exception delError) {
                    LOG.d("WebDavSyncer delete", h, delError.getMessage());
                }
            }
            SharedBooks.DeletedBooks.clear();

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
                    + (res.booksDeleted > 0 ? " \u00b7 \u5220" + res.booksDeleted : "")
                    + " \u00b7 " + res.durationMs + "ms";
            android.util.Log.i("BENCH", "sync books: synced=" + res.booksSynced + " associated=" + associated
                    + " deleted=" + res.booksDeleted);
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

    /** Merge callback for the global state files. */
    interface JsonMerger {
        LinkedJSONObject merge(LinkedJSONObject local, LinkedJSONObject remote);
    }

    /**
     * Union by key of the per-book read-state overrides (0 unread, 1 reading,
     * 2 read); the "further along" state wins, so marks converge on every
     * device instead of each device overwriting the server with its own copy.
     */
    static LinkedJSONObject mergeStatesMaxWins(LinkedJSONObject local, LinkedJSONObject remote) {
        LinkedJSONObject out = new LinkedJSONObject(local.toString());
        Iterator<String> keys = remote.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            int r = remote.optInt(k, -1);
            if (r < 0) {
                continue;
            }
            int l = out.optInt(k, -1);
            if (l < 0 || r > l) {
                out.put(k, r);
            }
        }
        return out;
    }

    /**
     * Two-way union of a SimpleMeta array file (recent / favorite): entries
     * keyed by path, the newer "time" wins. The merged array is written back
     * locally and published so every device converges.
     */
    /**
     * Whole-file two-way sync: identical content = no-op; the remote object
     * not existing yet ("") = seed-upload the local copy; any other fetch
     * failure (null) = touch nothing this round; otherwise the copy with the
     * newer modification time wins as a whole — the local file is overwritten
     * with the remote text, or the local text is uploaded.
     *
     * @return true when the LOCAL file was (re)written from the server.
     */
    static boolean syncWholeFile(Sardine s, String globalUrl, File local) {
        try {
            if (local == null || !local.isFile()) {
                return false;
            }
            final String url = globalUrl + "/" + local.getName();
            final String localText = readText(local);
            final String remoteText = fetchText(s, url);
            if (remoteText == null) {
                // transient GET failure: publishing local now could replace
                // the converged server copy with a stale subset for good
                android.util.Log.i("BENCH", "sync " + local.getName() + ": whole-file remote error, skipped");
                return false;
            }
            if (normalize(remoteText).equals(normalize(localText))) {
                return false;
            }
            if (remoteText.isEmpty()) {
                s.put(url, localText.getBytes("UTF-8"));
                android.util.Log.i("BENCH", "sync " + local.getName() + ": whole-file uploaded (new)");
                return false;
            }
            final long remoteMod = remoteModified(s, url);
            if (remoteMod > local.lastModified()) {
                IO.writeString(local, remoteText);
                android.util.Log.i("BENCH", "sync " + local.getName() + ": whole-file downloaded (remote newer)");
                return true;
            }
            s.put(url, localText.getBytes("UTF-8"));
            android.util.Log.i("BENCH", "sync " + local.getName() + ": whole-file uploaded (local newer)");
            return false;
        } catch (Exception e) {
            LOG.e(e, "WebDavSyncer whole", local == null ? "?" : local.getName());
            return false;
        }
    }

    /** Two-way merge of a JSON-object state file via the given merger. */
    static void syncMergedObjectFile(Sardine s, String globalUrl, File local, JsonMerger merger) {
        try {
            if (local == null || !local.isFile()) {
                return;
            }
            final String url = globalUrl + "/" + local.getName();
            LinkedJSONObject localObj = IO.readJsonObject(local);
            String remoteText = fetchText(s, url);
            if (remoteText == null) {
                // transient GET failure: touch neither the server nor the local file
                android.util.Log.i("BENCH", "sync " + local.getName() + ": remote error, skipped");
                return;
            }
            if (remoteText.isEmpty()) {
                if (localObj.length() > 0) {
                    s.put(url, localObj.toString().getBytes("UTF-8"));
                }
                return;
            }
            LinkedJSONObject remoteObj;
            try {
                remoteObj = new LinkedJSONObject(remoteText);
            } catch (Exception badPayload) {
                remoteObj = new LinkedJSONObject();
            }
            if (remoteObj.length() == 0) {
                if (localObj.length() > 0) {
                    s.put(url, localObj.toString().getBytes("UTF-8"));
                }
                return;
            }
            LinkedJSONObject merged = merger.merge(localObj, remoteObj);
            if (merged == null || merged.length() == 0) {
                return;
            }
            if (!merged.toString().equals(localObj.toString())) {
                IO.writeObjSync(local, merged);
            }
            if (!merged.toString().equals(remoteObj.toString())) {
                s.put(url, merged.toString().getBytes("UTF-8"));
            }
        } catch (Exception e) {
            LOG.e(e, "WebDavSyncer object", local.getName());
        }
    }

    /**
     * Two-way sync of one global config file: identical personal content =
     * no-op, otherwise the newer file (remote modified vs local lastModified)
     * wins. Device-bound fields (STATE_DEVICE_FIELDS / CSS_DEVICE_FIELDS)
     * never take part: they are excluded from the comparison, stripped from
     * the uploaded copy and re-applied locally after a download.
     *
     * A local file that still equals the out-of-the-box defaults never wins
     * the mtime race: a fresh install would otherwise overwrite the server
     * (and every other device) with default values.
     *
     * For app-State.json the AI model config fields are additionally union
     * merged (ProfileStateIO.mergeAiState): after a reset the freshly created
     * local file is always "newer" and plain newer-wins would clobber the
     * server copy with empty AI fields.
     */
    static void syncGlobalFile(Sardine s, String globalUrl, File local, boolean stateFile) {
        try {
            if (local == null || !local.isFile()) {
                return;
            }
            final Set<String> deviceFields = stateFile ? STATE_DEVICE_FIELDS : CSS_DEVICE_FIELDS;
            final String url = globalUrl + "/" + local.getName();
            final String localFull = readText(local);
            final String localCmp = withoutFields(new LinkedJSONObject(localFull), deviceFields).toString();
            final String remoteText = fetchText(s, url);
            if (remoteText == null) {
                // transient GET failure: skip the whole file this round
                android.util.Log.i("BENCH", "sync " + local.getName() + ": remote error, skipped");
                return;
            }
            final String remoteCmp = remoteText.isEmpty()
                    ? null
                    : withoutFields(new LinkedJSONObject(remoteText), deviceFields).toString();
            if (remoteCmp != null && normalize(remoteCmp).equals(normalize(localCmp))) {
                return;
            }
            final long remoteMod = remoteModified(s, url);
            if (remoteCmp == null) {
                // initial upload; the server copy never stores device-bound fields
                s.put(url, withoutFields(new LinkedJSONObject(localFull), deviceFields).toString().getBytes("UTF-8"));
                return;
            }
            final LinkedJSONObject localObj = new LinkedJSONObject(localFull);
            final LinkedJSONObject remoteObj = new LinkedJSONObject(remoteText);
            // a never-personalized local config must never overwrite the
            // server: a fresh install would otherwise win the mtime race
            // with default values (configuring the sync itself does not
            // count as personalization)
            final Set<String> defaultIgnore;
            if (stateFile) {
                defaultIgnore = new HashSet<String>(STATE_DEVICE_FIELDS);
                defaultIgnore.addAll(SYNC_CONFIG_FIELDS);
            } else {
                defaultIgnore = deviceFields;
            }
            final boolean localIsDefault = isDefaultConfig(localFull, defaultIgnore, stateFile);
            final boolean remoteNewer = remoteMod > local.lastModified() || localIsDefault;
            final LinkedJSONObject merged = stateFile
                    ? ProfileStateIO.mergeAiState(localObj, remoteObj, remoteNewer)
                    : (remoteNewer ? remoteObj : localObj);
            final boolean remoteChanged = !normalize(withoutFields(merged, deviceFields).toString())
                    .equals(normalize(remoteCmp));
            if (remoteNewer) {
                // remote wins: write through, keeping the local device-bound fields
                keepLocalFields(merged, localObj, deviceFields);
                if (localIsDefault) {
                    // the sync setup was just typed on this device: keep it
                    // while everything personal comes from the server
                    keepLocalFields(merged, localObj, SYNC_CONFIG_FIELDS);
                }
            }
            if (!normalize(merged.toString()).equals(normalize(localFull))) {
                IO.writeObjSync(local, merged);
            }
            if (remoteChanged) {
                s.put(url, withoutFields(merged, deviceFields).toString().getBytes("UTF-8"));
            }
        } catch (Exception e) {
            LOG.e(e, "WebDavSyncer global", local.getName());
        }
    }

    /** Copy of the object without the device-bound (and volatile) fields. */
    private static LinkedJSONObject withoutFields(LinkedJSONObject obj, Set<String> fields) {
        LinkedJSONObject copy = new LinkedJSONObject();
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (fields.contains(k)) {
                continue;
            }
            try {
                copy.put(k, obj.get(k));
            } catch (Exception ignored) {
            }
        }
        return copy;
    }

    /** Re-apply the local device-bound fields over an object won by the server. */
    private static void keepLocalFields(LinkedJSONObject merged, LinkedJSONObject localObj, Set<String> fields) {
        for (String k : fields) {
            try {
                if (localObj.has(k)) {
                    merged.put(k, localObj.get(k));
                } else {
                    merged.remove(k);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * True when the local file still equals the out-of-the-box defaults in
     * every personal (non device-bound) field: the user has never customized
     * this device, so the server copy must win the first sync instead of
     * being clobbered by freshly generated defaults.
     */
    static boolean isDefaultConfig(String localText, Set<String> deviceFields, boolean stateFile) {
        try {
            final LinkedJSONObject def;
            if (stateFile) {
                def = new LinkedJSONObject(com.foobnix.android.utils.Objects.toJSONString(defaultAppState()));
            } else {
                BookCSS css = new BookCSS();
                String hyphenLang = AppSP.get().hypenLang;
                css.resetToDefault(null);
                AppSP.get().hypenLang = hyphenLang; // resetToDefault touches the global AppSP
                def = new LinkedJSONObject(com.foobnix.android.utils.Objects.toJSONString(css));
            }
            return normalize(withoutFields(def, deviceFields).toString())
                    .equals(normalize(withoutFields(new LinkedJSONObject(localText), deviceFields).toString()));
        } catch (Exception e) {
            LOG.e(e, "WebDavSyncer isDefaultConfig");
            return false;
        }
    }

    /**
     * A fresh AppState as defaults() writes it on first run: the localized
     * mode labels, the theme following the system dark mode, the rebrand
     * day/night colors and the What's-New default. The accessibility and
     * e-ink adjustments of defaults() are deliberately not reproduced (they
     * mutate global singletons): on such devices default detection simply
     * stays false and the classic mtime rule applies.
     */
    private static AppState defaultAppState() {
        AppState st = new AppState();
        final Context a = LibreraApp.context;
        if (a != null) {
            st.nameVerticalMode = a.getString(R.string.mode_vertical);
            st.nameHorizontalMode = a.getString(R.string.mode_horizontally);
            st.nameMusicianMode = a.getString(R.string.mode_musician);
            st.musicText = a.getString(R.string.musician);
            final String pkg = com.foobnix.android.utils.Apps.getPackageName(a);
            if (!AppsConfig.LIBRERA_READER.equals(pkg) && !AppsConfig.PRO_LIBRERA_READER.equals(pkg)) {
                st.isShowWhatIsNewDialog = false;
            }
        }
        st.appTheme = Dips.isDarkThemeOn() ? AppState.THEME_DARK : AppState.THEME_LIGHT;
        // first-run migration in loadInit() flips this once (the tab merge);
        // the saved default file always carries the migrated value
        st.networkTabMerged = true;
        st.colorDayText = AppState.COLOR_BLACK;
        st.isUseBGImageDay = true;
        st.colorDayBg = AppState.COLOR_WHITE;
        st.colorDayForeground = AppState.COLOR_DAY_FG;
        st.isUseBGImageNight = true;
        st.colorNigthText = AppState.COLOR_WHITE;
        st.colorNigthBg = AppState.COLOR_BLACK;
        st.colorNigthForeground = AppState.COLOR_NIGHT_FG;
        return st;
    }

    private static String normalize(String json) {
        try {
            return new LinkedJSONObject(json).toString();
        } catch (Exception e) {
            return json == null ? "" : json.trim();
        }
    }

    // ------------------------------------------------------------------ legacy

    /**
     * One-time server-side rebrand import: the sync folder was renamed from
     * "/Librera" to "/HowRead". While the new folder is still empty, every
     * file of the old folder is copied over (never moved, so an old Librera
     * install keeps syncing against its own folder). Later syncs exit after a
     * cheap listing because the new folder then has content.
     */
    static void importLegacyRemoteDir(Sardine s, String serverRoot, String newRoot) {
        if (remoteDir().equals(REMOTE_LEGACY_DIR)) {
            return; // user deliberately points the sync at the legacy folder
        }
        try {
            if (s.list(newRoot, 1).size() > 1) {
                return; // new folder already has content
            }
        } catch (Exception e) {
            // listing may fail before the folder exists; treat as empty
        }
        try {
            final String legacyRoot = serverRoot + "/" + REMOTE_LEGACY_DIR;
            final int copied = copyRemoteTree(s, legacyRoot, newRoot, 0);
            if (copied > 0) {
                LOG.d("WebDavSyncer legacy dir import", REMOTE_LEGACY_DIR, "to", remoteDir(), copied);
            }
        } catch (Exception e) {
            // no legacy folder (404) or a copy error must never break the sync
            LOG.d("WebDavSyncer legacy dir import", e.getMessage());
        }
    }

    /** Depth-limited recursive GET→PUT copy of a remote WebDAV tree. */
    static int copyRemoteTree(Sardine s, String fromDir, String toDir, int depth) throws IOException {
        if (depth > 3) {
            return 0;
        }
        try {
            s.createDirectory(toDir);
        } catch (IOException e) {
            LOG.d("WebDavSyncer mkcol", e.getMessage()); // 405 = already exists
        }
        int copied = 0;
        final String selfName = fromDir.substring(fromDir.lastIndexOf('/') + 1);
        for (DavResource r : s.list(fromDir, 1)) {
            final String name = r.getName();
            if (name == null || name.equals(selfName)) {
                continue;
            }
            if (r.isDirectory()) {
                copied += copyRemoteTree(s, fromDir + "/" + name, toDir + "/" + name, depth + 1);
            } else {
                InputStream is = null;
                try {
                    is = s.get(fromDir + "/" + name);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    final byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    s.put(toDir + "/" + name, out.toByteArray());
                    copied++;
                } finally {
                    if (is != null) {
                        try {
                            is.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        }
        return copied;
    }

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

    /**
     * GET a remote text file. "" when it does not exist (404 — the caller may
     * seed-upload a local copy); null on any OTHER failure (auth, network,
     * SSL, timeout) — the caller must then touch neither the server nor the
     * local file, otherwise one transient error would publish a possibly
     * incomplete local list over the converged server copy for good.
     */
    static String fetchText(Sardine s, String url) {
        InputStream is = null;
        try {
            is = s.get(url);
            return IO.readString(is);
        } catch (Exception e) {
            LOG.d("WebDavSyncer fetch", url, e.getMessage());
            return isNotFound(e) ? "" : null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** True when the failure chain reports HTTP 404 / "not found". */
    private static boolean isNotFound(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            String msg = c.getMessage();
            if (msg != null && msg.contains("404")) {
                return true;
            }
            try {
                Object status = c.getClass().getMethod("getStatusCode").invoke(c);
                if (status instanceof Integer && (Integer) status == 404) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /** True when the deleted-books marker file records the given kind ("p"/"b") for the book. */
    static boolean markKind(LinkedJSONObject markers, String name, String kind) {
        LinkedJSONObject t = markers.optJSONObject(name);
        return t != null && t.optLong(kind, 0) > 0;
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

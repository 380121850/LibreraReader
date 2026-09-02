package com.foobnix.model;

import com.foobnix.android.utils.IO;
import com.foobnix.android.utils.LOG;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.sys.TempHolder;
import com.foobnix.ui2.AppDB;

import org.ebookdroid.common.settings.books.SharedBooks;
import org.librera.LinkedJSONObject;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tri-state book reading status (unread / reading / read).
 *
 * "Read" and "unread" are represented by the reading progress itself
 * (p >= 0.9999 is read, p <= 0 is unread) so every existing consumer of the
 * progress stays consistent. Only an explicit "reading" marker needs an
 * override, because progress alone cannot say "reading" for a book that is
 * at 0% or 100%.
 *
 * The derived half never reads the stale FILE_META.IS_RECENT_PROGRESS cache;
 * it builds a one-shot snapshot of the live app-Progress.json files (same
 * latest-t-wins merge as {@link SharedBooks#load}) so the dashboard count and
 * the library filter reflect a just-finished book immediately.
 */
public class BookStateStore {

    public static final int UNREAD = 0;
    public static final int READING = 1;
    public static final int READ = 2;

    private static final Map<String, Integer> overrides = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;

    private static volatile Map<String, Float> progressSnapshot = null;

    private static synchronized void loadIfNeed() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            for (File f : AppProfile.getAllFiles(AppProfile.APP_BOOK_STATES_JSON)) {
                LinkedJSONObject obj = IO.readJsonObject(f);
                Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    int state = obj.optInt(key, -1);
                    if (state >= UNREAD && state <= READ) {
                        overrides.put(key, state);
                    }
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** Drop the cached progress snapshot; the next effective() rebuilds it. */
    public static void invalidate() {
        progressSnapshot = null;
    }

    private static Map<String, Float> snapshot() {
        Map<String, Float> snap = progressSnapshot;
        if (snap != null) {
            return snap;
        }
        synchronized (BookStateStore.class) {
            if (progressSnapshot == null) {
                progressSnapshot = buildProgressSnapshot();
            }
            return progressSnapshot;
        }
    }

    private static Map<String, Float> buildProgressSnapshot() {
        Map<String, Float> res = new HashMap<>();
        Map<String, Long> times = new HashMap<>();
        try {
            for (File f : AppProfile.getAllFiles(AppProfile.APP_PROGRESS_JSON)) {
                LinkedJSONObject obj = IO.readJsonObject(f);
                Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    try {
                        LinkedJSONObject entry = obj.optJSONObject(key);
                        if (entry == null) {
                            continue;
                        }
                        float p = (float) entry.optDouble("p", 0);
                        long t = entry.optLong("t", 0);
                        Long prev = times.get(key);
                        if (prev == null || t >= prev) {
                            times.put(key, t);
                            res.put(key, p);
                        }
                    } catch (Exception e) {
                        LOG.e(e);
                    }
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        return res;
    }

    /**
     * Effective state: an explicit override wins, otherwise the state is
     * derived from the live reading progress.
     */
    public static int effective(String path) {
        loadIfNeed();
        String key = ExtUtils.getFileName(path);
        Integer o = overrides.get(key);
        if (o != null) {
            return o.intValue();
        }
        Float p = snapshot().get(key);
        float v = p == null ? 0f : p.floatValue();
        if (v >= 0.9999f) {
            return READ;
        }
        if (v > 0f) {
            return READING;
        }
        return UNREAD;
    }

    /** Mark finished: progress becomes 100% everywhere. */
    public static void markRead(String path) {
        try {
            AppBook book = SharedBooks.load(path);
            book.p = 1.0f;
            book.t = System.currentTimeMillis();
            SharedBooks.save(book);
        } catch (Exception e) {
            LOG.e(e);
        }
        updateDbProgress(path, 1.0f);
        removeOverride(path);
        invalidate();
        TempHolder.listHash++;
    }

    /** Mark unread AND wipe the reading progress (confirmed semantics). */
    public static void markUnread(String path) {
        try {
            SharedBooks.deleteProgress(path);
        } catch (Exception e) {
            LOG.e(e);
        }
        updateDbProgress(path, 0f);
        removeOverride(path);
        invalidate();
        TempHolder.listHash++;
    }

    /** Mark "reading" without touching the progress itself. */
    public static void markReading(String path) {
        loadIfNeed();
        overrides.put(ExtUtils.getFileName(path), READING);
        saveOwn();
        invalidate();
        TempHolder.listHash++;
    }

    public static void markAll(Collection<String> paths, int state) {
        for (String p : paths) {
            if (state == READ) {
                markRead(p);
            } else if (state == UNREAD) {
                markUnread(p);
            } else {
                markReading(p);
            }
        }
    }

    public static boolean isReadingOverride(String path) {
        loadIfNeed();
        Integer o = overrides.get(ExtUtils.getFileName(path));
        return o != null && o.intValue() == READING;
    }

    private static void removeOverride(String path) {
        loadIfNeed();
        if (overrides.remove(ExtUtils.getFileName(path)) != null) {
            saveOwn();
        }
    }

    private static void saveOwn() {
        try {
            LinkedJSONObject obj = new LinkedJSONObject();
            for (Map.Entry<String, Integer> e : overrides.entrySet()) {
                obj.put(e.getKey(), e.getValue().intValue());
            }
            IO.writeObjSync(AppProfile.syncBookStates, obj);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    private static void updateDbProgress(String path, float value) {
        try {
            com.foobnix.dao2.FileMeta meta = AppDB.get().load(path);
            if (meta != null) {
                meta.setIsRecentProgress(value);
                if (value >= 1.0f) {
                    meta.setIsRecentTime(System.currentTimeMillis());
                }
                AppDB.get().update(meta);
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

}

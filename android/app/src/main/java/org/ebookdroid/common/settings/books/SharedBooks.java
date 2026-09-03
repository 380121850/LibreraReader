package org.ebookdroid.common.settings.books;

import com.foobnix.android.utils.IO;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.Objects;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppBook;
import com.foobnix.model.AppProfile;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.ui2.AppDB;

import org.librera.LinkedJSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SharedBooks {


    public static void updateProgress(List<FileMeta> list1, boolean updateTime, int limit) {
        List<FileMeta> list;
        if (limit != -1 && list1.size() > limit) {
            list = new ArrayList<>(list1.subList(0, limit));
        } else {
            list = list1;
        }


        long a = System.currentTimeMillis();
        for (FileMeta meta : list) {
            try {
                AppBook book = SharedBooks.load(meta.getPath());
                meta.setIsRecentProgress(book.p);
                if (updateTime) {
                    meta.setIsRecentTime(book.t);
                }
            } catch (Exception e) {
                LOG.e(e);
            }
        }
        AppDB.get().updateAll(list);
        long b = System.currentTimeMillis() - a;
        LOG.d("updateProgress-time:", list.size(), b / 1000.0);
    }

    public static Map<String, AppBook> cache = new ConcurrentHashMap<>();

    public static void deleteProgress(String path) {
        cache.clear();
        DeletedBooks.record(path, "p");
        for (File fileName : AppProfile.getAllFiles(AppProfile.APP_PROGRESS_JSON)) {
            LinkedJSONObject linkedJsonObject = IO.readJsonObject(fileName);
            String key = ExtUtils.getFileName(path);
            if (linkedJsonObject.has(key)) {
                linkedJsonObject.remove(key);
                IO.writeObjSync(fileName, linkedJsonObject);
                LOG.d("deleteProgress", path);
            }
        }
    }

    /**
     * Locally deleted reading progress / bookmarks, consumed by the next
     * WebDAV sync: the deleted parts are not merged back from the server and
     * the server books/&lt;hash&gt;.json is removed when nothing remains for
     * the book. Value = {"p": time, "b": time, "keys": {"&lt;createTime&gt;": time}},
     * p = progress, b = bookmarks, keys = the specific bookmark creation-time
     * keys deleted for the book (so a partial delete propagates per-entry).
     */
    public static class DeletedBooks {

        public static void record(String path, String kind) {
            try {
                if (AppProfile.syncDeletedBooks == null || TxtUtils.isEmpty(path)) {
                    return;
                }
                final String name = ExtUtils.getFileName(path);
                LinkedJSONObject root = IO.readJsonObject(AppProfile.syncDeletedBooks);
                LinkedJSONObject marks = root.optJSONObject(name);
                if (marks == null) {
                    marks = new LinkedJSONObject();
                    root.put(name, marks);
                }
                marks.put(kind, System.currentTimeMillis());
                IO.writeObjSync(AppProfile.syncDeletedBooks, root);
            } catch (Exception e) {
                LOG.e(e);
            }
        }

        public static LinkedJSONObject all() {
            return AppProfile.syncDeletedBooks == null
                    ? new LinkedJSONObject()
                    : IO.readJsonObject(AppProfile.syncDeletedBooks);
        }

        /**
         * Record that a specific bookmark (by its creation-time key) was
         * deleted for the book. Accumulates in a per-book "keys" set so the
         * next WebDAV sync can drop exactly those entries from the server
         * instead of union-merging them back.
         */
        public static void recordKey(String path, long key) {
            try {
                if (AppProfile.syncDeletedBooks == null || TxtUtils.isEmpty(path)) {
                    return;
                }
                final String name = ExtUtils.getFileName(path);
                LinkedJSONObject root = IO.readJsonObject(AppProfile.syncDeletedBooks);
                LinkedJSONObject marks = root.optJSONObject(name);
                if (marks == null) {
                    marks = new LinkedJSONObject();
                    root.put(name, marks);
                }
                LinkedJSONObject keys = marks.optJSONObject("keys");
                if (keys == null) {
                    keys = new LinkedJSONObject();
                    marks.put("keys", keys);
                }
                keys.put("" + key, System.currentTimeMillis());
                IO.writeObjSync(AppProfile.syncDeletedBooks, root);
            } catch (Exception e) {
                LOG.e(e);
            }
        }

        /** The set of creation-time keys deleted for the book (empty when none). */
        public static Set<String> keysOf(LinkedJSONObject markers, String name) {
            Set<String> out = new HashSet<>();
            LinkedJSONObject marks = markers.optJSONObject(name);
            if (marks == null) {
                return out;
            }
            LinkedJSONObject keys = marks.optJSONObject("keys");
            if (keys == null) {
                return out;
            }
            Iterator<String> it = keys.keys();
            while (it.hasNext()) {
                out.add(it.next());
            }
            return out;
        }

        /**
         * Drop the per-book "keys" set once the sync has propagated it to the
         * server, keeping any "p"/"b" kind markers. The book entry is removed
         * entirely when nothing else remains.
         */
        public static void clearKeys(String path) {
            try {
                if (AppProfile.syncDeletedBooks == null || TxtUtils.isEmpty(path)) {
                    return;
                }
                final String name = ExtUtils.getFileName(path);
                LinkedJSONObject root = IO.readJsonObject(AppProfile.syncDeletedBooks);
                LinkedJSONObject marks = root.optJSONObject(name);
                if (marks == null) {
                    return;
                }
                marks.remove("keys");
                if (marks.length() == 0) {
                    root.remove(name);
                }
                IO.writeObjSync(AppProfile.syncDeletedBooks, root);
            } catch (Exception e) {
                LOG.e(e);
            }
        }

        public static void clear() {
            if (AppProfile.syncDeletedBooks != null) {
                IO.writeObjSync(AppProfile.syncDeletedBooks, new LinkedJSONObject());
            }
        }
    }

    public static AppBook load(String fileName) {
        LOG.d("SharedBooks-load", fileName);

        if (cache.containsKey(fileName)) {
            LOG.d("SharedBooks-load-from-cache", fileName);
            return cache.get(fileName);
        }

        AppBook res = new AppBook(fileName);
        AppBook original = null;

        for (File file : AppProfile.getAllFiles(AppProfile.APP_PROGRESS_JSON)) {
            final AppBook load = load(IO.readJsonObject(file), fileName);
            if (TxtUtils.isEmpty(load.path)) {
                continue;
            }
            load.path = fileName;

            if (file.equals(AppProfile.syncProgress)) {
                original = load;
            }

            if (load.t >= res.t) {
                res = load;
            }
        }
        if (original != null) {
            original.p = res.p;
            original.t = Math.max(res.t, original.t);
            LOG.d("SharedBooks-load1 original", fileName, res.p);
            cache.put(fileName, original);
            return original;
        }

        LOG.d("SharedBooks-load1 general", fileName, res.p);
        cache.put(fileName, res);
        return res;

    }

    private static AppBook load(LinkedJSONObject obj, String fileName) {
        AppBook bs = new AppBook(fileName);
        try {

            LOG.d("SharedBooks-load", bs.path);
            final String key = ExtUtils.getFileName(fileName);
            if (!obj.has(key)) {
                return bs;
            }
            final LinkedJSONObject rootObj = obj.getJSONObject(key);
            Objects.loadFromJson(bs, rootObj);
        } catch (Exception e) {
            LOG.e(e);
        }
        return bs;
    }

    public static void save(AppBook bs) {
        save(bs, true);
    }

    public static void saveAsync(AppBook bs) {
        save(bs, false);
    }

    static int phash = -1;

    private static void save(AppBook bs, boolean inThread) {
        if (bs == null) {
            LOG.d("SharedBooks-Save", "null");
            return;
        }

        int hash = bs.hashCode();
        if (phash == hash) {
            LOG.d("SharedBooks-Save", "skip", hash);
            return;
        }
        phash = hash;
        LOG.d("SharedBooks-Save", "inThread " + inThread);


        if (TxtUtils.isEmpty(bs.path)) {
            LOG.d("Can't save AppBook");
            return;
        }

        try {
            final LinkedJSONObject obj = IO.readJsonObject(AppProfile.syncProgress);

            if (bs.p > 1 || bs.p < 0) {
                bs.p = 0;
            }

            final String fileName = ExtUtils.getFileName(bs.path);
            final LinkedJSONObject value = Objects.toJSONObject(bs);
            obj.put(fileName, value);
            cache.put(fileName, bs);

            LOG.d("SharedBooks-Save", value);


            if (inThread) {
                IO.writeObj(AppProfile.syncProgress, obj);
            } else {
                IO.writeObjSync(AppProfile.syncProgress, obj);
            }
        } catch (Exception e) {
            LOG.e(e);
        }


    }


}

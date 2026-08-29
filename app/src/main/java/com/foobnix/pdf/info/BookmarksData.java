package com.foobnix.pdf.info;

import android.content.Context;

import com.foobnix.android.utils.IO;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.Objects;
import com.foobnix.model.AppBookmark;
import com.foobnix.model.AppData;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppState;

import org.librera.LinkedJSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;


public class BookmarksData {


    final static BookmarksData instance = new BookmarksData();

    public static BookmarksData get() {
        return instance;
    }


    public void add(AppBookmark bookmark) {
        LOG.d("BookmarksData", "add", bookmark.p, bookmark.text, bookmark.path);


        if (bookmark.p > 1) {
            bookmark.p = 0;
        }
        try {
            LinkedJSONObject obj = IO.readJsonObject(AppProfile.syncBookmarks);
            obj.put("" + bookmark.t, Objects.toJSONObject(bookmark));
            IO.writeObjSync(AppProfile.syncBookmarks, obj);
        } catch (Exception e) {
            LOG.e(e);
        }
    }


    public void remove(AppBookmark bookmark) {
        LOG.d("BookmarksData", "remove", bookmark.t, bookmark.file);

        try {
            LinkedJSONObject obj = IO.readJsonObject(bookmark.file);
            if (obj.has("" + bookmark.t)) {
                obj.remove("" + bookmark.t);
            }
            IO.writeObjSync(bookmark.file, obj);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    public List<AppBookmark> getBookmarksByBook(File file) {
        if (file == null) {
            return new ArrayList<>();
        }
        return getBookmarksByBook(file.getPath());
    }

    public synchronized List<AppBookmark> getAll(Context c) {
        LOG.d("AppBookmark-get","getAll");

        final List<AppBookmark> all = getAll();
        final Iterator<AppBookmark> iterator = all.iterator();
        String fast = c.getString(R.string.fast_bookmark);
        while (iterator.hasNext()) {
            final AppBookmark next = iterator.next();

            if (AppState.get().isShowOnlyAvailabeBooks) {
                if (!new File(next.getPath()).isFile()) {
                    iterator.remove();
                    continue;
                }
            }

            if (!AppState.get().isShowFastBookmarks) {
                if (fast.equals(next.text)) {
                    iterator.remove();
                }

            }

        }
        return all;
    }


    public List<AppBookmark> getAll() {

        List<AppBookmark> all = new ArrayList<>();

        try {

            List<File> allFiles = AppProfile.getAllFiles(AppProfile.APP_BOOKMARKS_JSON);
            for (File file : allFiles) {
                LinkedJSONObject obj = IO.readJsonObject(file);


                final Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    final String next = keys.next();

                    AppBookmark appBookmark = new AppBookmark();
                    appBookmark.file = file;
                    final LinkedJSONObject local = obj.getJSONObject(next);
                    Objects.loadFromJson(appBookmark, local);
                    all.add(appBookmark);
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }


//        Iterator<AppBookmark> iterator = all.iterator();
//        while (iterator.hasNext()) {
//            AppBookmark next = iterator.next();
//            if (next.getText().equals(quick)) {
//                iterator.remove();
//            }
//        }

        LOG.d("getAll-size", all.size());
        Collections.sort(all, BY_TIME);
        return all;
    }


    public List<AppBookmark> getBookmarksByBook(String path) {

        List<AppBookmark> all = new ArrayList<>();


        List<File> allFiles = AppProfile.getAllFiles(AppProfile.APP_BOOKMARKS_JSON);
        for (File file : allFiles) {
            LinkedJSONObject obj = IO.readJsonObject(file);
            try {
                final Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    final String next = keys.next();

                    AppBookmark appBookmark = new AppBookmark();
                    appBookmark.file = file;
                    final LinkedJSONObject local = obj.getJSONObject(next);
                    Objects.loadFromJson(appBookmark, local);
                    String path1 = ExtUtils.getFileName(path);
                    String path2 = ExtUtils.getFileName(appBookmark.getPath());
                    if (path1.equals(path2)) {
                        appBookmark.path = path;//update path
                        all.add(appBookmark);
                    }
                }
            } catch (Exception e) {
                LOG.e(e);
            }
        }


        LOG.d("getBookmarksByBook", path, all.size());
        Collections.sort(all, BY_PERCENT);
        return all;
    }

    public boolean hasBookmark(String lastBookPath, int page, int pages) {
        final List<AppBookmark> bookmarksByBook = getBookmarksByBook(lastBookPath);
        for (AppBookmark appBookmark : bookmarksByBook) {
            if (appBookmark.getPercent() * pages == page) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes all bookmarks & AI notes grouped by book (file name) into
     * app-BookmarksByBook.json, so the backup zip carries them per book.
     */
    public synchronized void saveByBook() {
        try {
            if (AppProfile.syncBookmarksByBook == null) return;
            LinkedJSONObject byBook = new LinkedJSONObject();
            for (AppBookmark b : getAll()) {
                String book = ExtUtils.getFileName(b.getPath());
                LinkedJSONObject entries = byBook.has(book)
                        ? byBook.getJSONObject(book)
                        : new LinkedJSONObject();
                entries.put("" + b.t, Objects.toJSONObject(b));
                byBook.put(book, entries);
            }
            IO.writeObjSync(AppProfile.syncBookmarksByBook, byBook);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /**
     * Merges the per-book backup file (app-BookmarksByBook.json) back into
     * app-Bookmarks.json after a restore. Entries are keyed by creation time,
     * so the merge is idempotent (existing keys are kept).
     */
    public synchronized void importByBook() {
        try {
            if (AppProfile.syncBookmarksByBook == null) return;
            LinkedJSONObject byBook = IO.readJsonObject(AppProfile.syncBookmarksByBook);
            LinkedJSONObject all = IO.readJsonObject(AppProfile.syncBookmarks);
            final Iterator<String> books = byBook.keys();
            while (books.hasNext()) {
                final String book = books.next();
                final LinkedJSONObject entries = byBook.getJSONObject(book);
                final Iterator<String> keys = entries.keys();
                while (keys.hasNext()) {
                    final String key = keys.next();
                    if (!all.has(key)) {
                        all.put(key, entries.get(key));
                    }
                }
            }
            IO.writeObjSync(AppProfile.syncBookmarks, all);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    static final Comparator<AppBookmark> BY_PERCENT = new Comparator<AppBookmark>() {

        @Override
        public int compare(AppBookmark o1, AppBookmark o2) {
            return Float.compare(o1.getPercent(), o2.getPercent());
        }
    };

    static final Comparator<AppBookmark> BY_TIME = new Comparator<AppBookmark>() {

        @Override
        public int compare(AppBookmark o1, AppBookmark o2) {
            return Float.compare(o2.getTime(), o1.getTime());
        }
    };


    public Map<String, List<AppBookmark>> getBookmarksMap() {
        return null;
    }


    public void cleanBookmarks() {
        //IO.writeObj(AppProfile.syncBookmarks.getPath(), "{}");
        AppData.get().clearAll(AppProfile.APP_BOOKMARKS_JSON);
    }


}

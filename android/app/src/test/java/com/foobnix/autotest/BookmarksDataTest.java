package com.foobnix.autotest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.foobnix.model.AppBookmark;
import com.foobnix.model.AppProfile;
import com.foobnix.pdf.info.BookmarksData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.List;

/**
 * 集成层（JVM + Robolectric, CI runner）：书签存储增查（JSON 文件真实读写）。
 * 存储 FILE 指向临时目录；remove() 涉及 WebDAV 通知不在本层覆盖（由真机 UI 层覆盖）。
 * application=空 Application：避免 LibreraApp.onCreate（广告初始化）干扰。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {30}, application = android.app.Application.class)
public class BookmarksDataTest {

    private File tempBookmarks;

    @Before
    public void setUp() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "howread-autotest-" + System.nanoTime());
        // getAllFiles 扫描 SYNC_FOLDER_PROFILE 下以 "device." 开头的子目录（AppProfile.DEVICE_PREFIX）
        File deviceDir = new File(dir, "device.test");
        deviceDir.mkdirs();
        tempBookmarks = new File(deviceDir, "app-Bookmarks.json");
        AppProfile.SYNC_FOLDER_PROFILE = dir;
        AppProfile.syncBookmarks = tempBookmarks;
    }

    private AppBookmark make(long t, String path, String text, float p) {
        AppBookmark b = new AppBookmark();
        b.t = t;
        b.path = path;
        b.text = text;
        b.p = p;
        return b;
    }

    @Test
    public void add_then_getAll() {
        BookmarksData db = BookmarksData.get();
        db.add(make(1001L, "/sdcard/Download/big25.pdf", "page bookmark", 0.25f));
        db.add(make(1002L, "/sdcard/Download/big25.pdf", "another note", 0.75f));

        List<AppBookmark> all = db.getAll();
        assertNotNull(all);
        assertTrue("应至少包含刚添加的 2 条", all.size() >= 2);

        boolean found1 = false;
        boolean found2 = false;
        for (AppBookmark b : all) {
            if (b.t == 1001L && "page bookmark".equals(b.text)) found1 = true;
            if (b.t == 1002L && "another note".equals(b.text)) found2 = true;
        }
        assertTrue(found1);
        assertTrue(found2);
    }

    @Test
    public void add_sameTimestamp_deduplicatedByKey() {
        BookmarksData db = BookmarksData.get();
        AppBookmark a = make(2001L, "/sdcard/book.epub", "first", 0.1f);
        AppBookmark b = make(2001L, "/sdcard/book.epub", "second", 0.2f);
        db.add(a);
        db.add(b);
        // 同一毫秒 key 冲突时 add() 内部会把 t+1，两条都应存在
        int hits = 0;
        for (AppBookmark x : db.getAll()) {
            if (x.path != null && x.path.contains("book.epub")) hits++;
        }
        assertEquals(2, hits);
    }

    @Test
    public void add_percentOverOne_resetToZero() {
        BookmarksData db = BookmarksData.get();
        AppBookmark b = make(3001L, "/sdcard/x.pdf", "weird", 5.0f);
        db.add(b);
        assertEquals(0.0f, b.p, 0.0001f);
    }
}

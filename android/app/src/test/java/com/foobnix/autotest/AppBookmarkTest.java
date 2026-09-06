package com.foobnix.autotest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.foobnix.model.AppBookmark;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 单元层（JVM, CI runner）：AppBookmark POJO（页码换算/相等语义）。
 * getPage 走 LOG 框架，需 Robolectric 提供 Build.*。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {30}, application = android.app.Application.class)
public class AppBookmarkTest {

    private AppBookmark make(float percent) {
        AppBookmark b = new AppBookmark();
        b.p = percent;
        return b;
    }

    @Test
    public void getPage_half() {
        assertEquals(50, make(0.5f).getPage(100));
    }

    @Test
    public void getPage_clampsNegativePercent() {
        AppBookmark b = make(-0.5f);
        assertEquals(1, b.getPage(100));
    }

    @Test
    public void getPage_clampsOverOne() {
        AppBookmark b = make(1.5f);
        assertEquals(100, b.getPage(100));
    }

    @Test
    public void getPage_invalidPages_returnsOne() {
        assertEquals(1, make(0.5f).getPage(0));
        assertEquals(1, make(0.5f).getPage(-3));
    }

    @Test
    public void getPage_roundsToNearest() {
        assertEquals(33, make(0.33f).getPage(100));
        assertEquals(34, make(0.335f).getPage(100));
    }

    @Test
    public void equals_sameTimePathText() {
        AppBookmark a = new AppBookmark();
        a.t = 1000L;
        a.path = "/sdcard/book.pdf";
        a.text = "chapter 1";
        AppBookmark b = new AppBookmark();
        b.t = 1000L;
        b.path = "/sdcard/book.pdf";
        b.text = "chapter 1";
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void equals_ignoresPercentDelta() {
        AppBookmark a = new AppBookmark();
        a.t = 1000L;
        a.path = "p";
        a.text = "t";
        a.p = 0.1f;
        AppBookmark b = new AppBookmark();
        b.t = 1000L;
        b.path = "p";
        b.text = "t";
        b.p = 0.9f;
        assertEquals(a, b);
    }

    @Test
    public void notEquals_differentText() {
        AppBookmark a = new AppBookmark();
        a.t = 1L;
        a.path = "p";
        a.text = "t1";
        AppBookmark b = new AppBookmark();
        b.t = 1L;
        b.path = "p";
        b.text = "t2";
        assertNotEquals(a, b);
    }
}

package com.foobnix.autotest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.foobnix.android.utils.TxtUtils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 单元层（JVM, CI runner）：TxtUtils 纯字符串方法。
 * LOG 框架需要 Build.*（纯 JVM 为 null），以 Robolectric 运行。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {30}, application = android.app.Application.class)
public class TxtUtilsTest {

    @Test
    public void formatInt_null() {
        assertEquals("0", TxtUtils.formatInt(null));
    }

    @Test
    public void formatInt_value() {
        assertEquals("5", TxtUtils.formatInt(5));
    }

    @Test
    public void getStringInTag_basic() {
        assertEquals("content", TxtUtils.getStringInTag("<a>content</a>", "a"));
    }

    @Test
    public void getStringInTag_missing() {
        assertEquals("", TxtUtils.getStringInTag("no tags here", "a"));
    }

    @Test
    public void toBionicText_longWords() {
        // 长度>4：前半加粗；奇数长度多偏移一位（5→3）
        assertEquals("<b>hel</b>lo", TxtUtils.toBionicText("hello"));
    }

    @Test
    public void toBionicText_sentence() {
        String out = TxtUtils.toBionicText("hello world");
        assertEquals("<b>hel</b>lo <b>wor</b>ld", out);
    }

    @Test
    public void toBionicText_singleLetterA_bold() {
        assertEquals("<b>a</b>", TxtUtils.toBionicText("a"));
    }

    @Test
    public void toBionicText_empty() {
        assertEquals("", TxtUtils.toBionicText(""));
    }

    @Test
    public void lastWord_basic() {
        assertEquals("world", TxtUtils.lastWord("hello world"));
    }

    @Test
    public void lastWord_singleWord() {
        assertEquals("hello", TxtUtils.lastWord("hello"));
    }

    @Test
    public void lastWord_empty() {
        assertEquals("", TxtUtils.lastWord(""));
    }

    @Test
    public void trim_mixedWhitespace() {
        // \s 各替换为一个空格后 trim：制表/换行成为单空格，中间产生连续空格
        assertEquals("a  b", TxtUtils.trim("  a\t\nb "));
    }

    @Test
    public void trim_null() {
        assertNull(TxtUtils.trim(null));
    }

    @Test
    public void getProgressPercent_quarter() {
        assertEquals("25.0%", TxtUtils.getProgressPercent(1, 4));
    }

    @Test
    public void getProgressPercent_zeroMax_returnsInfinity() {
        // float 除零不抛异常，得到 Infinity（JVM 下格式化为 "Infinity%"）
        assertEquals("Infinity%", TxtUtils.getProgressPercent(1, 0));
    }

    @Test
    public void percentFormatInt_rounds() {
        assertEquals("50%", TxtUtils.percentFormatInt(0.5f));
    }

    @Test
    public void toLowerCase_usLocale() {
        assertEquals("pdf", TxtUtils.toLowerCase("PDF"));
    }

    @Test
    public void toLowerCase_null() {
        assertNull(TxtUtils.toLowerCase(null));
    }
}

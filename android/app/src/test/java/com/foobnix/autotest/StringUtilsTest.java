package com.foobnix.autotest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.emdev.utils.StringUtils;

import org.junit.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

/**
 * 单元层（JVM, CI runner）：org.emdev.utils.StringUtils 纯逻辑（不依赖 Android/LOG，纯 JUnit）。
 */
public class StringUtilsTest {

    @Test
    public void md5_knownVector() {
        assertEquals("5d41402abc4b2a76b9719d911017c592", StringUtils.md5("hello"));
    }

    @Test
    public void md5_emptyString() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", StringUtils.md5(""));
    }

    @Test
    public void split_basic() {
        Set<String> r = StringUtils.split(",", "a,b,c");
        assertEquals(new TreeSet<String>(Arrays.asList("a", "b", "c")), new TreeSet<String>(r));
        assertEquals(3, r.size());
    }

    @Test
    public void split_dropsEmptyItems() {
        Set<String> r = StringUtils.split(",", "a,,b,");
        assertEquals(2, r.size());
        assertTrue(r.contains("a"));
        assertTrue(r.contains("b"));
    }

    @Test
    public void split_deduplicates() {
        Set<String> r = StringUtils.split(",", "a,a,b");
        assertEquals(2, r.size());
    }

    @Test
    public void merge_basic() {
        assertEquals("a,b,c", StringUtils.merge(",", "a", "b", "c"));
    }

    @Test
    public void merge_skipsEmptyItems() {
        assertEquals("a,c", StringUtils.merge(",", "a", "", "c"));
    }

    @Test
    public void merge_collection() {
        assertEquals("x|y", StringUtils.merge("|", Arrays.asList("x", "y")));
    }

    @Test
    public void compareNatural_numbersAsNumbers() {
        assertTrue(StringUtils.compareNatural("a2", "a10") < 0);
    }

    @Test
    public void compareNatural_equal() {
        assertEquals(0, StringUtils.compareNatural("book7.pdf", "book7.pdf"));
    }

    @Test
    public void compareNatural_prefixShorterFirst() {
        assertTrue(StringUtils.compareNatural("abc", "abcd") < 0);
    }

    @Test
    public void splitCharArray_whitespaceTokens() {
        char[] in = "hello world  pdf".toCharArray();
        int[] starts = new int[8];
        int[] lens = new int[8];
        int n = StringUtils.split(in, 0, in.length, starts, lens);
        assertEquals(3, n);
        assertEquals("hello", new String(in, starts[0], lens[0]));
        assertEquals("world", new String(in, starts[1], lens[1]));
        assertEquals("pdf", new String(in, starts[2], lens[2]));
    }
}

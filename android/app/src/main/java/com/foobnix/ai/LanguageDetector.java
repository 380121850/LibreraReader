package com.foobnix.ai;

import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.dao2.FileMeta;

import java.util.List;

/**
 * Detects the source language of a book for the AI translation feature.
 *
 * Strategy (no external dependency):
 * 1. Book metadata: {@link FileMeta#getLang()} (EPUB/MOBI carry dc:language).
 * 2. Fallback: sample the first pages' text and score CJK / kana / Latin
 *    character ratios to pick between zh-CN, ja and en.
 *
 * Returns a BCP-47-ish code: "en", "zh-CN" or "ja".
 */
public class LanguageDetector {

    public static final String EN = "en";
    public static final String ZH = "zh-CN";
    public static final String JA = "ja";

    /**
     * Detect the language of a book.
     *
     * @param meta     book metadata (may be null); its lang field is used first
     * @param samples  sampled page text (may be null/empty); used as the fallback
     * @return one of {@link #EN}, {@link #ZH}, {@link #JA}; defaults to EN
     */
    public static String detect(FileMeta meta, List<String> samples) {
        // 1. metadata first
        if (meta != null) {
            String lang = normalize(meta.getLang());
            if (lang != null) {
                return lang;
            }
        }
        // 2. content sampling fallback
        if (samples != null && !samples.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String s : samples) {
                if (TxtUtils.isNotEmpty(s)) {
                    sb.append(s).append(' ');
                }
            }
            String text = sb.toString();
            if (text.length() > 0) {
                return score(text);
            }
        }
        return EN;
    }

    /** Map an arbitrary ISO-639 tag to one of the supported codes, or null. */
    private static String normalize(String lang) {
        if (TxtUtils.isEmpty(lang)) {
            return null;
        }
        String l = lang.trim().toLowerCase(java.util.Locale.US);
        if (l.startsWith("ja")) {
            return JA;
        }
        if (l.startsWith("zh") || l.startsWith("cmn") || l.startsWith("yue")) {
            return ZH;
        }
        if (l.startsWith("en")) {
            return EN;
        }
        return null;
    }

    /** Character-ratio heuristic over the sampled text. */
    private static String score(String text) {
        int cjk = 0, kana = 0, latin = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            } else if ((c >= 0x3040 && c <= 0x309F) || (c >= 0x30A0 && c <= 0x30FF)) {
                kana++;
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                latin++;
            }
        }
        // kana is the tell-tale of Japanese (Chinese has none)
        if (kana > 0 && kana >= cjk / 4) {
            return JA;
        }
        if (cjk > latin) {
            return ZH;
        }
        return EN;
    }
}

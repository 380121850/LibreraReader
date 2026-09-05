package com.foobnix.ai;

/**
 * Implemented by the two reader activities that can show the "正在翻译中…"
 * bottom hint while the in-page bilingual mode is active.
 */
public interface BilingualHintUi {

    /**
     * Show the bottom hint with the given text (live queue counters), or hide
     * it when the text is null/empty.
     */
    void setBilingualHint(String text);
}

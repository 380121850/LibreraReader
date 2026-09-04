package com.foobnix.ai;

/**
 * Implemented by the two reader activities that can show the "正在翻译中…"
 * bottom hint while the in-page bilingual mode is active.
 */
public interface BilingualHintUi {

    /** Show/hide the bottom "正在翻译中…" hint for the current page. */
    void setBilingualHint(boolean show);
}

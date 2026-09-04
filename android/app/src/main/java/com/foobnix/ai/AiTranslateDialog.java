package com.foobnix.ai;

import android.app.Activity;
import android.app.Dialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.foobnix.android.utils.TxtUtils;
import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.wrapper.DocumentController;
import com.foobnix.ui2.AppDB;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The AI translation entry point (replaces the old "replace text" button).
 * Lets the user pick the source language (auto-detected by default, editable)
 * and the target language (en / zh-CN / ja), then opens the translation panel.
 */
public class AiTranslateDialog {

    // spinner position -> BCP-47 code (kept in sync with the array resource)
    private static final String[] CODES = {LanguageDetector.EN, LanguageDetector.ZH, LanguageDetector.JA};

    public static void show(final Activity a, final DocumentController dc) {
        if (a == null || dc == null) {
            return;
        }
        final File book = dc.getCurrentBook();
        if (!AiTranslator.isSupportedFormat(book == null ? null : book.getPath())) {
            Toast.makeText(a, R.string.ai_translate_unsupported_format, Toast.LENGTH_SHORT).show();
            return;
        }
        if (TxtUtils.isEmpty(AppState.get().aiBaseUrl) || TxtUtils.isEmpty(AppState.get().aiModel)
                || TxtUtils.isEmpty(AiCredentials.load(a))) {
            Toast.makeText(a, R.string.ai_translate_not_configured, Toast.LENGTH_LONG).show();
            return;
        }

        final View view = LayoutInflater.from(a).inflate(R.layout.ai_translate_dialog, null, false);
        final Spinner srcSpinner = (Spinner) view.findViewById(R.id.aiTranslateSrc);
        final Spinner tgtSpinner = (Spinner) view.findViewById(R.id.aiTranslateTgt);
        final TextView start = (TextView) view.findViewById(R.id.aiTranslateStart);
        final TextView cancel = (TextView) view.findViewById(R.id.aiTranslateCancel);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(a,
                R.array.ai_translate_langs, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        srcSpinner.setAdapter(adapter);
        tgtSpinner.setAdapter(adapter);
        // default target: Chinese (the common case for this app's users)
        tgtSpinner.setSelection(1);

        // Use AlertDialog (like AiConfigDialog / WebDavSyncDialog) rather than a
        // bare Dialog: a bare Dialog sizes to wrap_content, which is only as wide
        // as the spinners, so the two 96dp-min buttons (取消 + 开始翻译) overflow
        // and the 开始翻译 button gets clipped off the edge — leaving the user
        // with no way to start. AlertDialog sizes the window to a proper width.
        final Dialog dialog = new android.app.AlertDialog.Builder(a)
                .setView(view)
                .create();
        dialog.show();

        // detect the source language in the background (metadata, then sampling)
        new Thread(new Runnable() {
            @Override public void run() {
                String detected = LanguageDetector.EN;
                try {
                    FileMeta meta = AppDB.get().getOrCreate(book.getPath());
                    List<String> samples = samplePages(dc);
                    detected = LanguageDetector.detect(meta, samples);
                } catch (Throwable t) {
                    // keep the default
                }
                final int idx = indexOf(detected);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (dialog.isShowing()) {
                            srcSpinner.setSelection(idx);
                        }
                    }
                });
            }
        }, "AiLangDetect").start();

        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
            }
        });

        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String src = CODES[srcSpinner.getSelectedItemPosition()];
                String tgt = CODES[tgtSpinner.getSelectedItemPosition()];
                if (src.equals(tgt)) {
                    Toast.makeText(a, R.string.ai_translate_same_lang, Toast.LENGTH_SHORT).show();
                    return;
                }
                dialog.dismiss();
                startTranslation(a, dc, src, tgt);
            }
        });
    }

    private static int indexOf(String code) {
        for (int i = 0; i < CODES.length; i++) {
            if (CODES[i].equals(code)) {
                return i;
            }
        }
        return 0;
    }

    /** First few pages' text, for the language-detection fallback. */
    private static List<String> samplePages(DocumentController dc) {
        List<String> out = new ArrayList<String>();
        int n = Math.min(3, dc.getPageCount());
        for (int i = 1; i <= n; i++) {
            String[] paras = dc.getPageParagraphs(i - 1);
            if (paras != null) {
                for (String p : paras) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    private static void startTranslation(final Activity a, final DocumentController dc,
            final String src, final String tgt) {
        final TranslatePanel panel = new TranslatePanel(a);
        panel.setTitle(a.getString(R.string.ai_translate) + " → "
                + AiTranslator.targetLangName(tgt));
        panel.setTranslating(true);
        AiTranslator.translate(a, dc, src, tgt, new AiTranslator.Listener() {
            @Override public void onParagraph(String pid, String orig, String tran, String status) {
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        panel.addParagraph(orig, tran, status);
                    }
                });
            }

            @Override public void onFinished(boolean ok) {
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        panel.setTranslating(false);
                    }
                });
            }
        });
    }
}

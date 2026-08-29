package com.foobnix.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.foobnix.android.utils.AsyncTasks;
import com.foobnix.android.utils.Keyboards;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.view.MyProgressBar;

/**
 * AI provider settings (Anx Reader style: unified fields + test-then-save):
 * protocol picker (OpenAI-compatible / Claude / Gemini), base URL, API key
 * (stored encrypted via AiCredentials) and model name, with a real minimal
 * "ping" request as the connection test.
 */
public class AiConfigDialog {

    public static void showDialog(final Activity a, final Runnable onRefresh) {

        final View view = LayoutInflater.from(a).inflate(R.layout.dialog_ai_config, null, false);
        final TextView protocolValue = (TextView) view.findViewById(R.id.aiProtocolValue);
        final EditText url = (EditText) view.findViewById(R.id.aiBaseUrl);
        final EditText apiKey = (EditText) view.findViewById(R.id.aiApiKey);
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText model = (EditText) view.findViewById(R.id.aiModel);
        final EditText maxTokens = (EditText) view.findViewById(R.id.aiMaxTokens);
        final CheckBox thinking = (CheckBox) view.findViewById(R.id.aiThinking);
        final TextView modelList = (TextView) view.findViewById(R.id.aiModelList);
        final TextView test = (TextView) view.findViewById(R.id.aiTestConnection);
        final TextView status = (TextView) view.findViewById(R.id.aiTestStatus);
        final EditText chatInput = (EditText) view.findViewById(R.id.aiChatInput);
        final TextView chatSend = (TextView) view.findViewById(R.id.aiChatSend);
        final android.view.View chatResultScroll = view.findViewById(R.id.aiChatResultScroll);
        final TextView chatResult = (TextView) view.findViewById(R.id.aiChatResult);
        final MyProgressBar progress = (MyProgressBar) view.findViewById(R.id.aiProgress);

        TxtUtils.underlineTextView(protocolValue);
        TxtUtils.underlineTextView(test);
        TxtUtils.underlineTextView(modelList);

        // protocol chosen inside the dialog; persisted on save only
        savedLocal = TxtUtils.isEmpty(AppState.get().aiProtocol)
                ? AiClient.PROTOCOL_OPENAI : AppState.get().aiProtocol;
        final String openProtocol = savedLocal;
        String key = AiCredentials.load(a);
        if (TxtUtils.isNotEmpty(AppState.get().aiBaseUrl)) {
            url.setText(AppState.get().aiBaseUrl);
        } else {
            url.setText(AiClient.defaultUrl(openProtocol));
        }
        apiKey.setText(key);
        model.setText(AppState.get().aiModel);
        maxTokens.setText(String.valueOf(
                AppState.get().aiMaxTokens > 0 ? AppState.get().aiMaxTokens : 4096));
        thinking.setChecked(AppState.get().aiThinking);
        refreshProtocolLabel(protocolValue);

        protocolValue.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PopupMenu popup = new PopupMenu(a, v);
                popup.getMenu().add(R.string.ai_protocol_openai);
                popup.getMenu().add(R.string.ai_protocol_anthropic);
                popup.getMenu().add(R.string.ai_protocol_google);
                popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override public boolean onMenuItemClick(android.view.MenuItem item) {
                        String protocol;
                        if (item.getTitle().equals(a.getString(R.string.ai_protocol_anthropic))) {
                            protocol = AiClient.PROTOCOL_ANTHROPIC;
                        } else if (item.getTitle().equals(a.getString(R.string.ai_protocol_google))) {
                            protocol = AiClient.PROTOCOL_GOOGLE;
                        } else {
                            protocol = AiClient.PROTOCOL_OPENAI;
                        }
                        // switching protocol pre-fills its default endpoint
                        String current = url.getText().toString().trim();
                        if (TxtUtils.isEmpty(current) || current.equals(AiClient.defaultUrl(openProtocol))
                                || current.equals(AiClient.defaultUrl(AiClient.PROTOCOL_ANTHROPIC))
                                || current.equals(AiClient.defaultUrl(AiClient.PROTOCOL_GOOGLE))
                                || current.equals(AiClient.defaultUrl(AiClient.PROTOCOL_OPENAI))) {
                            url.setText(AiClient.defaultUrl(protocol));
                        }
                        savedLocal = protocol;
                        refreshProtocolLabel(protocolValue);
                        return true;
                    }
                });
                popup.show();
            }
        });

        // fetch the provider's model list and let the user pick one; manual
        // typing stays possible when the endpoint has no /models
        modelList.setOnClickListener(new View.OnClickListener() {
            AsyncTask asyncTask;

            @Override public void onClick(View v) {
                final String u = url.getText().toString().trim();
                final String k = apiKey.getText().toString().trim();
                if (TxtUtils.isEmpty(u)) {
                    Toast.makeText(a, R.string.incorrect_value, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (AsyncTasks.isRunning(asyncTask)) {
                    AsyncTasks.toastPleaseWait(a);
                    return;
                }
                progress.setVisibility(View.VISIBLE);
                asyncTask = new AsyncTask() {
                    @Override protected Object doInBackground(Object[] params) {
                        return AiClient.listModels(savedLocal, u, k);
                    }

                    @Override protected void onPostExecute(Object result) {
                        progress.setVisibility(View.GONE);
                        java.util.List<String> ids = (java.util.List<String>) result;
                        if (ids == null || ids.isEmpty()) {
                            String err = AiClient.lastError;
                            String kind = err.isEmpty() ? "other" : err.split(" ")[0];
                            Toast.makeText(a, resultErrorText(a, kind, err), Toast.LENGTH_LONG).show();
                            return;
                        }
                        PopupMenu popup = new PopupMenu(a, v);
                        for (final String id : ids) {
                            popup.getMenu().add(id).setOnMenuItemClickListener(item -> {
                                model.setText(id);
                                return true;
                            });
                        }
                        popup.show();
                    }
                }.execute();
            }
        });

        test.setOnClickListener(new View.OnClickListener() {
            AsyncTask asyncTask;

            @Override public void onClick(View v) {
                final String u = url.getText().toString().trim();
                final String k = apiKey.getText().toString().trim();
                final String m = model.getText().toString().trim();
                if (TxtUtils.isEmpty(u) || TxtUtils.isEmpty(k) || TxtUtils.isEmpty(m)) {
                    Toast.makeText(a, R.string.incorrect_value, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (AsyncTasks.isRunning(asyncTask)) {
                    AsyncTasks.toastPleaseWait(a);
                    return;
                }
                progress.setVisibility(View.VISIBLE);
                status.setText(R.string.webdav_syncing);
                final boolean th = thinking.isChecked();
                asyncTask = new AsyncTask() {
                    @Override protected Object doInBackground(Object[] params) {
                        return AiClient.chat(a, savedLocal, u, k, m, "ping", 5, th);
                    }

                    @Override protected void onPostExecute(Object result) {
                        progress.setVisibility(View.GONE);
                        AiClient.TestResult r = (AiClient.TestResult) result;
                        if (r.ok) {
                            status.setText(R.string.webdav_sync_test_ok);
                        } else {
                            status.setText(resultErrorText(a, r.error, r.detail));
                        }
                    }
                }.execute();
            }
        });

        // chat test: type arbitrary text and see whether the configured model
        // responds — uses the values currently typed in the fields, so the
        // config can be validated before saving
        chatSend.setOnClickListener(new View.OnClickListener() {
            AsyncTask asyncTask;

            @Override public void onClick(View v) {
                final String u = url.getText().toString().trim();
                final String k = apiKey.getText().toString().trim();
                final String m = model.getText().toString().trim();
                final String text = chatInput.getText().toString().trim();
                if (TxtUtils.isEmpty(u) || TxtUtils.isEmpty(k) || TxtUtils.isEmpty(m)
                        || TxtUtils.isEmpty(text)) {
                    Toast.makeText(a, R.string.incorrect_value, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (AsyncTasks.isRunning(asyncTask)) {
                    AsyncTasks.toastPleaseWait(a);
                    return;
                }
                int budget = 4096;
                try {
                    budget = Integer.parseInt(maxTokens.getText().toString().trim());
                } catch (Exception ignored) {
                }
                if (budget <= 0) {
                    budget = 4096;
                }
                final int b = budget;
                final boolean th = thinking.isChecked();
                // free the dialog from the keyboard so the result is visible;
                // must use the dialog view's token (the activity token is ignored)
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) a.getSystemService(
                                android.content.Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(chatInput.getWindowToken(), 0);
                progress.setVisibility(View.VISIBLE);
                chatSend.setEnabled(false);
                chatResultScroll.setVisibility(View.VISIBLE);
                chatResult.setText(R.string.ai_ask_thinking);
                asyncTask = new AsyncTask() {
                    @Override protected Object doInBackground(Object[] params) {
                        return AiClient.chat(a, savedLocal, u, k, m, text, b, th);
                    }

                    @Override protected void onPostExecute(Object result) {
                        progress.setVisibility(View.GONE);
                        chatSend.setEnabled(true);
                        AiClient.TestResult r = (AiClient.TestResult) result;
                        if (r.ok && TxtUtils.isNotEmpty(r.reply)) {
                            chatResult.setText(r.truncated
                                    ? r.reply + "\n\n" + a.getString(R.string.ai_reply_truncated)
                                    : r.reply);
                        } else {
                            chatResult.setText(resultErrorText(a, r.error, r.detail));
                        }
                    }
                }.execute();
            }
        });

        final AlertDialog.Builder builder = new AlertDialog.Builder(a);
        builder.setTitle(R.string.ai_config_title);
        builder.setView(view);
        builder.setPositiveButton(R.string.webdav_sync_save, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                AppState.get().aiProtocol = savedLocal;
                AppState.get().aiBaseUrl = url.getText().toString().trim();
                AppState.get().aiModel = model.getText().toString().trim();
                int budget = 4096;
                try {
                    budget = Integer.parseInt(maxTokens.getText().toString().trim());
                } catch (Exception ignored) {
                }
                if (budget <= 0) {
                    budget = 4096;
                }
                AppState.get().aiMaxTokens = budget;
                AppState.get().aiThinking = thinking.isChecked();
                AiCredentials.save(a, apiKey.getText().toString());
                AppProfile.save(a);
                Keyboards.close(a);
                if (onRefresh != null) {
                    onRefresh.run();
                }
            }
        });
        builder.setNegativeButton(R.string.close, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                Keyboards.close(a);
            }
        });
        builder.show();
    }

    /** protocol chosen inside the dialog (persisted on save only) */
    private static String savedLocal = AiClient.PROTOCOL_OPENAI;

    /** AI-specific error text (never reuse the WebDAV strings) + raw detail. */
    public static String resultErrorText(Activity a, String error, String detail) {
        int res;
        if ("no_config".equals(error)) {
            res = R.string.ai_err_no_config;
        } else if ("auth".equals(error)) {
            res = R.string.ai_err_auth;
        } else if ("rate".equals(error)) {
            res = R.string.ai_err_rate;
        } else if ("timeout".equals(error)) {
            res = R.string.ai_err_timeout;
        } else if ("network".equals(error)) {
            res = R.string.ai_err_network;
        } else if ("model".equals(error)) {
            res = R.string.ai_err_model;
        } else if ("empty".equals(error)) {
            res = R.string.ai_err_empty;
        } else {
            res = R.string.ai_err_other;
        }
        String text = a.getString(res);
        if (TxtUtils.isNotEmpty(detail)) {
            text += "\n" + detail;
        }
        return text;
    }

    public static String testErrorText(Activity a, String error) {
        return resultErrorText(a, error, "");
    }

    private static void refreshProtocolLabel(TextView protocolValue) {
        if (AiClient.PROTOCOL_ANTHROPIC.equals(savedLocal)) {
            protocolValue.setText(R.string.ai_protocol_anthropic);
        } else if (AiClient.PROTOCOL_GOOGLE.equals(savedLocal)) {
            protocolValue.setText(R.string.ai_protocol_google);
        } else {
            protocolValue.setText(R.string.ai_protocol_openai);
        }
    }
}

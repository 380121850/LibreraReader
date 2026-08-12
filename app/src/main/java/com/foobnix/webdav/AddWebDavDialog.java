package com.foobnix.webdav;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import com.foobnix.android.utils.AsyncTasks;
import com.foobnix.android.utils.Keyboards;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.view.MyProgressBar;

/**
 * Add / edit a WebDAV server (URL, name, login, password). The URL is verified
 * with a PROPFIND on save; when the check fails the user can still force-add.
 * Credentials are stored encrypted via {@link WebDavCredentials}.
 */
public class AddWebDavDialog {

    public static void showDialog(final Activity a, final Runnable onRefresh, final WebDavServer edit) {

        final AlertDialog.Builder builder = new AlertDialog.Builder(a);
        final View dialog = LayoutInflater.from(a).inflate(R.layout.dialog_add_webdav, null, false);

        final EditText url = (EditText) dialog.findViewById(R.id.url);
        final EditText name = (EditText) dialog.findViewById(R.id.name);
        final EditText login = (EditText) dialog.findViewById(R.id.login);
        final EditText password = (EditText) dialog.findViewById(R.id.password);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final MyProgressBar progress = (MyProgressBar) dialog.findViewById(R.id.MyProgressBarAddWebDav);

        final String editAppState = edit == null ? null : edit.appState;
        if (edit != null) {
            url.setText(edit.url);
            name.setText(edit.title);
            String[] creds = WebDavCredentials.load(a, edit.url);
            if (creds != null) {
                login.setText(creds[0]);
                password.setText(creds[1]);
            }
        } else {
            url.setText("http://");
            url.setSelection(url.getText().length());
        }

        builder.setView(dialog);
        builder.setTitle(R.string.add_webdav_server);
        builder.setPositiveButton(R.string.add, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int id) {
            }
        });
        builder.setNegativeButton(R.string.close, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int id) {
                Keyboards.close(a);
            }
        });

        final AlertDialog infoDialog = builder.create();
        infoDialog.show();

        final boolean[] force = {false};
        infoDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            AsyncTask asyncTask;

            @Override
            public void onClick(View v) {
                final String feedUrl = url.getText().toString().trim();
                final String title = name.getText().toString().trim();
                final String loginText = login.getText().toString().trim();
                final String passwordText = password.getText().toString().trim();
                if (TxtUtils.isEmpty(feedUrl)) {
                    Toast.makeText(a, R.string.incorrect_value, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (force[0]) {
                    save(a, feedUrl, title, loginText, passwordText, editAppState, onRefresh, infoDialog);
                    return;
                }
                if (AsyncTasks.isRunning(asyncTask)) {
                    AsyncTasks.toastPleaseWait(a);
                    return;
                }
                progress.setVisibility(View.VISIBLE);
                asyncTask = new AsyncTask() {
                    @Override
                    protected Object doInBackground(Object... params) {
                        return WebDavClient.list(feedUrl, loginText, passwordText);
                    }

                    @Override
                    protected void onPostExecute(Object result) {
                        progress.setVisibility(View.GONE);
                        if (result != null) {
                            save(a, feedUrl, title, loginText, passwordText, editAppState, onRefresh, infoDialog);
                        } else {
                            force[0] = true;
                            infoDialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(R.string.add_anyway);
                            Toast.makeText(a, R.string.webdav_auth_failed, Toast.LENGTH_LONG).show();
                        }
                    }
                }.execute();
            }
        });
    }

    private static void save(Activity a, String url, String title, String login, String password,
                             String editAppState, Runnable onRefresh, AlertDialog dialog) {
        if (editAppState != null) {
            AppState.get().allWebDavLinks = AppState.get().allWebDavLinks.replace(editAppState, "");
        }
        WebDavServer s = new WebDavServer(url, TxtUtils.isNotEmpty(title) ? title : url);
        s.appState = WebDavServer.buildLine(url, s.title);
        WebDavStore.add(s);
        WebDavCredentials.save(a, url, login, password);
        AppProfile.save(a);
        Keyboards.close(a);
        dialog.dismiss();
        if (onRefresh != null) {
            onRefresh.run();
        }
    }
}

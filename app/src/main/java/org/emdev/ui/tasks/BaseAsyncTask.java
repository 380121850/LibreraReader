package org.emdev.ui.tasks;

import android.app.AlertDialog;
import android.content.Context;

import com.foobnix.android.utils.LOG;
import com.foobnix.pdf.CopyAsyncTask;
import com.foobnix.pdf.info.view.Dialogs;

public abstract class BaseAsyncTask<Params, Result> extends CopyAsyncTask<Params, String, Result> {

    protected final Context context;
    protected AlertDialog progressDialog;
    // when set, onPostExecute leaves the dialog open (FirstPaintGate dismisses it)
    protected boolean holdProgressDialog;

    public BaseAsyncTask(Context context) {
        this.context = context;
    }

    public void onBookCancel() {
        try {
            progressDialog.dismiss();
        } catch (Exception e) {
            LOG.e(e);
        }

    }

    @Override
    protected void onPreExecute() {
        progressDialog = Dialogs.loadingBook(context, new Runnable() {

            @Override
            public void run() {
                onBookCancel();
            }
        });
    }

    @Override
    protected void onPostExecute(Result result) {
        super.onPostExecute(result);
        if (holdProgressDialog) {
            return;
        }
        try {
            if (progressDialog != null) {
                progressDialog.dismiss();
            }
        } catch (Exception e) {
        }
    }

}

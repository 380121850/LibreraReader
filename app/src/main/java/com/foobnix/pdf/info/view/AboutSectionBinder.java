package com.foobnix.pdf.info.view;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import com.foobnix.android.utils.Apps;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.pdf.info.AndroidWhatsNew;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.TintUtil;
import com.foobnix.pdf.info.Urls;

/**
 * Binds the shared about_section layout (version header pill, Librera pro,
 * changelog, licence, support mail, web and rate links). Used by both the
 * bottom of the preferences page and the drawer 软件说明 dialog so the two
 * surfaces look and behave identically.
 */
public class AboutSectionBinder {

    /**
     * The drawer-style 软件说明 entry point: a collapsed row opens this
     * dialog with the full about_section (version pill, changelog, licences,
     * support links).
     */
    public static void showDialog(final Activity a) {
        View content = LayoutInflater.from(a).inflate(R.layout.dialog_about, null);
        bind(a, content);
        new AlertDialog.Builder(a)
                .setView(content)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    public static void bind(final Activity a, View root) {
        try {
            PackageInfo packageInfo = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            String version = packageInfo.versionName + "";
            ((TextView) root.findViewById(R.id.pVersion)).setText(
                    String.format("%s: %s", a.getString(R.string.version), version));
            TextView section6 = root.findViewById(R.id.section6);
            section6.setText(
                    String.format("%s: %s", Apps.getApplicationName(a), version));
            TintUtil.setBackgroundFillColor(section6, TintUtil.color);
        } catch (PackageManager.NameNotFoundException e) {
            LOG.e(e);
        }

        TextView whatIsNew = root.findViewById(R.id.whatIsNew);
        whatIsNew.setText(a.getString(R.string.what_is_new_in) + " " + Apps.getApplicationName(a) + " " + Apps.getVersionName(a));
        TxtUtils.underlineTextView(whatIsNew);
        whatIsNew.setOnClickListener(v -> AndroidWhatsNew.show2(a));

        TextView licenses = root.findViewById(R.id.libraryLicenses);
        TxtUtils.underlineTextView(licenses);
        licenses.setOnClickListener(v -> showLicenses(a));

        TextView onMail = root.findViewById(R.id.onMailSupport);
        onMail.setText(TxtUtils.underline(a.getString(R.string.my_email)));
        onMail.setOnClickListener(v -> onEmailSupport(a));

        TextView openWeb = root.findViewById(R.id.openWeb);
        // the project site is intentionally left empty — hide the link when unset
        if (TxtUtils.isEmpty(a.getString(R.string.my_site))) {
            openWeb.setVisibility(View.GONE);
        } else {
            TxtUtils.underlineTextView(openWeb);
            openWeb.setOnClickListener(v -> Urls.open(a, a.getString(R.string.my_site)));
        }

        TextView proText = root.findViewById(R.id.downloadPRO);
        TxtUtils.underlineTextView(proText);
        ((View) proText.getParent()).setOnClickListener(v -> Urls.openPdfPro(a));

        TextView rateIt = root.findViewById(R.id.onRateIt);
        TxtUtils.underlineTextView(rateIt);
        rateIt.setOnClickListener(v -> Urls.rateIT(a));
    }

    public static void showLicenses(final Activity a) {
        AlertDialog.Builder alert = new AlertDialog.Builder(a);
        alert.setTitle(R.string.licenses_for_libraries);
        WebView wv = new WebView(a);
        wv.loadUrl("file:///android_asset/licenses.html");
        wv.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }
        });
        alert.setView(wv);
        alert.setNegativeButton(R.string.close, (dialog, id) -> dialog.dismiss());
        AlertDialog dialog = alert.create();
        // release the WebView instead of leaking it on every open
        dialog.setOnDismissListener(d -> {
            wv.loadUrl("about:blank");
            wv.destroy();
        });
        dialog.show();
    }

    public static void onEmailSupport(final Activity a) {
        Intent emailIntent = new Intent(Intent.ACTION_SEND);
        String address = a.getString(R.string.my_email).replace("<u>", "").replace("</u>", "");
        emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{address});
        emailIntent.putExtra(Intent.EXTRA_SUBJECT,
                Apps.getApplicationName(a) + " " + Apps.getVersionName(a));
        emailIntent.setType("plain/text");
        emailIntent.putExtra(Intent.EXTRA_TEXT, "Hi Support, ");
        try {
            a.startActivity(Intent.createChooser(emailIntent, a.getString(R.string.send_mail)));
        } catch (android.content.ActivityNotFoundException ex) {
            Toast.makeText(a, R.string.there_are_no_email_applications_installed_, Toast.LENGTH_SHORT).show();
        }
    }
}

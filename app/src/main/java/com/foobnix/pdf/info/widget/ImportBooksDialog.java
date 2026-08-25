package com.foobnix.pdf.info.widget;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Environment;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import com.foobnix.android.utils.Dips;
import com.foobnix.android.utils.LOG;
import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppData;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.TintUtil;
import com.foobnix.pdf.search.activity.msg.UpdateAllFragments;
import com.foobnix.ui2.AppDB;

import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ImportBooksDialog {

    public static void showDialog(final FragmentActivity a, final Runnable onImported) {
        final Set<String> selected = new LinkedHashSet<String>();
        File root = Environment.getExternalStorageDirectory();
        if (root == null || !root.canRead()) {
            root = a.getExternalFilesDir(null);
        }
        final File[] currentDir = {root};

        final LinearLayout rootLayout = new LinearLayout(a);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        int pad = Dips.dpToPx(10);
        rootLayout.setPadding(pad, pad, pad, 0);

        final TextView pathText = new TextView(a);
        pathText.setSingleLine(true);
        pathText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        pathText.setTextColor(Color.DKGRAY);

        final Button upButton = new Button(a);
        upButton.setText(R.string.moon_parent_folder);
        upButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                File parent = currentDir[0].getParentFile();
                if (parent != null && parent.canRead()) {
                    currentDir[0] = parent;
                }
            }
        });

        final Button scanFolderButton = new Button(a);
        scanFolderButton.setText(R.string.moon_scan_folder);
        scanFolderButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PrefDialogs.chooseFolderDialog(a, new Runnable() {
                    @Override
                    public void run() {
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        EventBus.getDefault().post(new UpdateAllFragments());
                    }
                });
            }
        });

        LinearLayout pathRow = new LinearLayout(a);
        pathRow.setOrientation(LinearLayout.HORIZONTAL);
        pathRow.setGravity(Gravity.CENTER_VERTICAL);
        pathRow.addView(pathText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pathRow.addView(upButton);
        pathRow.addView(scanFolderButton);
        rootLayout.addView(pathRow);

        final ListView listView = new ListView(a);
        listView.setDivider(null);
        rootLayout.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final TextView selectedCount = new TextView(a);
        selectedCount.setTextColor(TintUtil.color);
        selectedCount.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Button clearButton = new Button(a);
        clearButton.setText(R.string.reset);
        LinearLayout selectedRow = new LinearLayout(a);
        selectedRow.setOrientation(LinearLayout.HORIZONTAL);
        selectedRow.setGravity(Gravity.CENTER_VERTICAL);
        selectedRow.setPadding(0, Dips.dpToPx(6), 0, 0);
        selectedRow.addView(selectedCount);
        selectedRow.addView(clearButton);
        rootLayout.addView(selectedRow);

        final ImportListAdapter adapter = new ImportListAdapter(a, currentDir, selected, pathText, selectedCount);
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selected.clear();
                adapter.refresh();
            }
        });

        listView.setAdapter(adapter);

        final AlertDialog.Builder builder = new AlertDialog.Builder(a);
        builder.setTitle(R.string.moon_import_books);
        builder.setView(rootLayout);
        builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
            }
        });
        builder.setPositiveButton(R.string.import_, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                int count = 0;
                for (String path : selected) {
                    try {
                        FileMeta load = AppDB.get().getOrCreate(path);
                        load.setIsSearchBook(true);
                        AppDB.get().update(load);
                        AppData.get().removeExcluded(load);
                        count++;
                    } catch (Exception e) {
                        LOG.e(e);
                    }
                }
                EventBus.getDefault().post(new UpdateAllFragments());
                Toast.makeText(a, a.getString(R.string.moon_imported_n_books, count), Toast.LENGTH_SHORT).show();
                if (onImported != null) {
                    onImported.run();
                }
            }
        });

        final AlertDialog dialog = builder.create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(TintUtil.color);
        adapter.refresh();
    }

    private static class ImportListAdapter extends BaseAdapter {
        private final FragmentActivity activity;
        private final File[] currentDir;
        private final Set<String> selected;
        private final TextView pathText;
        private final TextView selectedCount;
        private final List<File> files = new ArrayList<File>();

        ImportListAdapter(FragmentActivity activity, File[] currentDir, Set<String> selected, TextView pathText, TextView selectedCount) {
            this.activity = activity;
            this.currentDir = currentDir;
            this.selected = selected;
            this.pathText = pathText;
            this.selectedCount = selectedCount;
        }

        void refresh() {
            reload();
            notifyDataSetChanged();
            pathText.setText(currentDir[0].getAbsolutePath());
            selectedCount.setText(activity.getString(R.string.moon_selected_n, selected.size()));
        }

        void reload() {
            files.clear();
            File[] list = currentDir[0].listFiles();
            if (list != null) {
                for (File file : list) {
                    if (file.isDirectory()) {
                        files.add(file);
                    } else if (isSupportedBook(file)) {
                        files.add(file);
                    }
                }
            }
            Collections.sort(files, new Comparator<File>() {
                @Override
                public int compare(File lhs, File rhs) {
                    if (lhs.isDirectory() != rhs.isDirectory()) {
                        return lhs.isDirectory() ? -1 : 1;
                    }
                    return lhs.getName().compareToIgnoreCase(rhs.getName());
                }
            });
        }

        @Override
        public int getCount() {
            return files.size();
        }

        @Override
        public File getItem(int position) {
            return files.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final File file = getItem(position);
            final LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Dips.dpToPx(6), 0, Dips.dpToPx(6));

            if (file.isDirectory()) {
                TextView folderMark = new TextView(activity);
                folderMark.setText("[+]");
                folderMark.setTextColor(TintUtil.color);
                folderMark.setPadding(Dips.dpToPx(4), 0, Dips.dpToPx(10), 0);
                row.addView(folderMark);
            } else {
                CheckBox checkBox = new CheckBox(activity);
                checkBox.setChecked(selected.contains(file.getPath()));
                row.addView(checkBox);
            }

            TextView extText = new TextView(activity);
            extText.setText(getShortExt(file));
            extText.setTextColor(Color.GRAY);
            extText.setTextSize(12);
            LinearLayout.LayoutParams extParams = new LinearLayout.LayoutParams(Dips.dpToPx(46), ViewGroup.LayoutParams.WRAP_CONTENT);
            extParams.rightMargin = Dips.dpToPx(6);
            row.addView(extText, extParams);

            TextView nameText = new TextView(activity);
            nameText.setText(file.getName());
            nameText.setSingleLine(true);
            nameText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.addView(nameText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView sizeText = new TextView(activity);
            if (file.isFile()) {
                sizeText.setText(Formatter.formatFileSize(activity, file.length()));
            }
            sizeText.setTextColor(Color.GRAY);
            sizeText.setTextSize(12);
            row.addView(sizeText);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (file.isDirectory() && file.canRead()) {
                        currentDir[0] = file;
                        refresh();
                    } else {
                        View first = ((LinearLayout) v).getChildAt(0);
                        if (first instanceof CheckBox) {
                            ((CheckBox) first).setChecked(!((CheckBox) first).isChecked());
                        }
                    }
                }
            });

            if (!file.isDirectory() && row.getChildAt(0) instanceof CheckBox) {
                CheckBox checkBox = (CheckBox) row.getChildAt(0);
                checkBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        if (isChecked) {
                            selected.add(file.getPath());
                        } else {
                            selected.remove(file.getPath());
                        }
                        selectedCount.setText(activity.getString(R.string.moon_selected_n, selected.size()));
                    }
                });
            }

            return row;
        }
    }

    private static boolean isSupportedBook(File file) {
        String name = file.getName().toLowerCase();
        for (String ext : ExtUtils.seachExts) {
            if (name.endsWith(ext.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static String getShortExt(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1);
            return ext.length() > 5 ? ext.substring(0, 5) : ext;
        }
        return "";
    }
}

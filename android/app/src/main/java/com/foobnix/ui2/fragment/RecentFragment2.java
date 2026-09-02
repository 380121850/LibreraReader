package com.foobnix.ui2.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.util.Pair;
import androidx.recyclerview.widget.RecyclerView;

import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.ResultResponse;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.dao2.FileMeta;
import com.foobnix.ext.CacheZipUtils;
import com.foobnix.model.AppData;
import com.foobnix.model.AppState;
import com.foobnix.model.BookStateStore;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.TintUtil;
import com.foobnix.pdf.info.view.AlertDialogs;
import com.foobnix.pdf.info.view.MyPopupMenu;
import com.foobnix.pdf.info.wrapper.PopupHelper;
import com.foobnix.sys.TempHolder;
import com.foobnix.ui2.AppDB;
import com.foobnix.ui2.adapter.DefaultListeners;
import com.foobnix.ui2.adapter.FileMetaAdapter;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class RecentFragment2 extends UIFragment<FileMeta> {
    public static final Pair<Integer, Integer> PAIR = new Pair<Integer, Integer>(R.string.recent, R.drawable.glyphicons_422_book_library);
    FileMetaAdapter recentAdapter;
    ImageView onListGrid;
    View panelRecent;
    TextView recentName;

    // multi-select (same mechanics as the library page)
    boolean selectionMode = false;
    final Set<String> selectedPaths = new LinkedHashSet<String>();
    SelectionBarController selectionBarController;

    ResultResponse<FileMeta> onDeleteRecentClick = new ResultResponse<FileMeta>() {

        @Override
        public boolean onResultRecive(FileMeta result) {
            result.setIsRecent(false);
            AppDB.get().update(result);

            if (result.getPath().startsWith(CacheZipUtils.CACHE_RECENT.getPath())) {
                new File(result.getPath()).delete();
                LOG.d("Delete cache recent file", result.getPath());
            }


            AppData.get().removeRecent(result);

            populate();


            return false;
        }
    };
    Runnable clearAllRecent = new Runnable() {

        @Override
        public void run() {
            AppDB.get().clearAllRecent();


            CacheZipUtils.removeFiles(CacheZipUtils.CACHE_RECENT.listFiles());

            AppData.get().clearRecents();

            populate();
        }
    };
    int count = 0;

    @Override
    public Pair<Integer, Integer> getNameAndIconRes() {
        return PAIR;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_recent, container, false);

        recyclerView = (RecyclerView) view.findViewById(R.id.recyclerView);
        panelRecent = view.findViewById(R.id.panelRecent);
        recentName = view.findViewById(R.id.recentName);

        onListGrid = (ImageView) view.findViewById(R.id.onListGrid);
        onListGrid.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                popupMenu(onListGrid);
            }
        });

        TxtUtils.underlineTextView((TextView) view.findViewById(R.id.clearAllRecent)).setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                AlertDialogs.showDialog(getActivity(), getString(R.string.do_you_want_to_clear_everything_), getString(R.string.ok), new Runnable() {

                    @Override
                    public void run() {
                        clearAllRecent.run();

                    }
                });

            }
        });

        recentAdapter = new FileMetaAdapter();
        recentAdapter.tempValue = FileMetaAdapter.TEMP_VALUE_FOLDER_PATH;
        bindAdapter(recentAdapter);
        bindAuthorsSeriesAdapter(recentAdapter);

        recentAdapter.setOnDeleteClickListener(onDeleteRecentClick);

        setupSelectionBar(view);
        wrapItemClickListenerForSelection();
        // long-press on a book enters multi-select
        recentAdapter.setOnItemLongClickListener(new ResultResponse<FileMeta>() {
            @Override public boolean onResultRecive(FileMeta meta) {
                if (meta != null && meta.getPath() != null && !AppDB.get().isFolder(meta)) {
                    startSelection(meta.getPath());
                    return true;
                }
                return DefaultListeners.onLongClickChooser(getActivity(), recentAdapter).onResultRecive(meta);
            }
        });

        onGridList();
        populate();

        TintUtil.setBackgroundFillColor(panelRecent, TintUtil.color);

        return view;
    }

    @Override
    public void onTintChanged() {
        TintUtil.setBackgroundFillColor(panelRecent, TintUtil.color);
    }

    // ------------------------------------------------------------------ batch selection

    private void setupSelectionBar(View view) {
        selectionBarController = SelectionBarController.bind(view, new SelectionBarController.Callbacks() {
            @Override public void onSelectAll() {
                for (FileMeta m : recentAdapter.getItemsList()) {
                    if (m != null && m.getPath() != null && !AppDB.get().isFolder(m)) {
                        selectedPaths.add(m.getPath());
                    }
                }
                updateSelectionCount();
                recentAdapter.notifyDataSetChanged();
            }

            @Override public void onCancel() {
                exitSelection();
            }

            @Override public void onApplyState(int state) {
                applySelection(state);
            }
        });
    }

    /**
     * In selection mode a tap toggles the book's selection instead of opening
     * it; otherwise the default listener (open book) runs unchanged.
     */
    private void wrapItemClickListenerForSelection() {
        final ResultResponse<FileMeta> defaultClick = recentAdapter.getOnItemClickListener();
        recentAdapter.setOnItemClickListener(new ResultResponse<FileMeta>() {
            @Override public boolean onResultRecive(FileMeta meta) {
                if (selectionMode && meta != null && meta.getPath() != null) {
                    toggleSelection(meta.getPath());
                    return true;
                }
                return defaultClick != null && defaultClick.onResultRecive(meta);
            }
        });
    }

    public void startSelection(String path) {
        selectionMode = true;
        selectedPaths.clear();
        if (path != null) {
            selectedPaths.add(path);
        }
        recentAdapter.selectionPaths = selectedPaths;
        if (selectionBarController != null) {
            selectionBarController.setVisible(true);
        }
        updateSelectionCount();
        recentAdapter.notifyDataSetChanged();
    }

    private void toggleSelection(String path) {
        if (selectedPaths.contains(path)) {
            selectedPaths.remove(path);
        } else {
            selectedPaths.add(path);
        }
        updateSelectionCount();
        recentAdapter.notifyDataSetChanged();
    }

    private void updateSelectionCount() {
        if (selectionBarController != null) {
            selectionBarController.setCount(selectedPaths.size());
        }
    }

    public void exitSelection() {
        selectionMode = false;
        selectedPaths.clear();
        if (recentAdapter != null) {
            recentAdapter.selectionPaths = null;
            recentAdapter.notifyDataSetChanged();
        }
        if (selectionBarController != null) {
            selectionBarController.setVisible(false);
        }
    }

    private void applySelection(final int state) {
        if (selectedPaths.isEmpty()) {
            exitSelection();
            return;
        }
        final List<String> paths = new ArrayList<String>(selectedPaths);
        exitSelection();
        AppsConfig.executorService.execute(new Runnable() {
            @Override public void run() {
                try {
                    BookStateStore.markAll(paths, state);
                } catch (Exception e) {
                    LOG.e(e);
                }
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (getActivity() != null) {
                            TempHolder.listHash++;
                            resetFragment();
                        }
                    }
                });
            }
        });
    }

    @Override
    public boolean isBackPressed() {
        if (selectionMode) {
            exitSelection();
            return true;
        }
        return false;
    }


    public boolean onBackAction() {
        return false;
    }

    @Override
    public List<FileMeta> prepareDataInBackground() {
        LOG.d("RecentFragment2","prepareDataInBackground");
        List<FileMeta> allRecent = AppData.get().getAllRecent(true);
        int oldSize = allRecent.size();
        ExtUtils.removeReadBooks(allRecent);
        count = oldSize - allRecent.size();
        return allRecent;
    }

    @Override
    public void populateDataInUI(List<FileMeta> items) {
        if (recentAdapter != null) {
            recentAdapter.getItemsList().clear();
            recentAdapter.getItemsList().addAll(items);
            recentAdapter.notifyDataSetChanged();
        }
        if (AppState.get().isHideReadBook) {
            recentName.setText(getString(R.string.recent) + " (" + (items.size() + count) + "/" + count + ")");
        } else {
            recentName.setText(getString(R.string.recent) + " (" + items.size() + ")");
        }
    }

    public void onGridList() {
        LOG.d("onGridList");
        onGridList(AppState.get().recentMode, onListGrid, recentAdapter, null);
    }

    private void popupMenu(final ImageView onGridList) {
        MyPopupMenu p = new MyPopupMenu(getActivity(), onGridList);
        PopupHelper.addPROIcon(p, getActivity());

        List<Integer> names = Arrays.asList(R.string.list, R.string.compact, R.string.grid, R.string.cover);
        final List<Integer> icons = Arrays.asList(R.drawable.my_glyphicons_114_paragraph_justify, R.drawable.my_glyphicons_114_justify_compact, R.drawable.glyphicons_157_thumbnails, R.drawable.glyphicons_158_thumbnails_small);
        final List<Integer> actions = Arrays.asList(AppState.MODE_LIST, AppState.MODE_LIST_COMPACT, AppState.MODE_GRID, AppState.MODE_COVERS);


        p.getMenu().addCheckbox(getString(R.string.hide_read_books), AppState.get().isHideReadBook, new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                AppState.get().isHideReadBook = isChecked;
                TempHolder.listHash++;

                populate();
            }
        });

        for (int i = 0; i < names.size(); i++) {
            final int index = i;
            p.getMenu().add(names.get(i)).setIcon(icons.get(i)).setOnMenuItemClickListener(new OnMenuItemClickListener() {

                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    AppState.get().recentMode = actions.get(index);
                    onGridList.setImageResource(icons.get(index));
                    onGridList();
                    return false;
                }
            });
        }


        p.show();
    }



    @Override
    public void notifyFragment() {
        LOG.d("RecentFragment2","notifyFragment");
        populate();
    }

    @Override
    public void resetFragment() {
        LOG.d("RecentFragment2","resetFragment");
        onGridList();
        populate();
    }

}

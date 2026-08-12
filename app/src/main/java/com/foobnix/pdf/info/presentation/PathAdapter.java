package com.foobnix.pdf.info.presentation;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.foobnix.android.utils.ResultResponse;
import com.foobnix.pdf.info.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class PathAdapter extends BaseAdapter {

    private List<Uri> uris = Collections.emptyList();
    private ResultResponse<Uri> onDeleClick;
    private Map<String, Long> counts;

    @Override
    public int getCount() {
        return uris.size();
    }

    @Override
    public Object getItem(int position) {
        return null;
    }

    @Override
    public long getItemId(int position) {
        return 0;
    }

    /**
     * Optional per-path file counts shown at the right of each row (the count
     * TextView is hidden when no counts are set).
     */
    public void setCounts(Map<String, Long> counts) {
        this.counts = counts;
        notifyDataSetChanged();
    }

    public void setPaths(List<String> paths) {
        List<Uri> uris = new ArrayList<Uri>();
        for (String str : paths) {
                uris.add(Uri.fromFile(new File(str)));
        }
        Collections.sort(uris, comparator);
        this.uris = uris;
        notifyDataSetChanged();
    }

    private final static Comparator<Uri> comparator = new Comparator<Uri>() {

        @Override
        public int compare(Uri lhs, Uri rhs) {
            return lhs.getPath().compareTo(rhs.getPath());
        }
    };
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View browserItem = LayoutInflater.from(parent.getContext()).inflate(R.layout.path_item, parent, false);

        TextView textPath = (TextView) browserItem.findViewById(R.id.browserPath);
        final Uri uri = uris.get(position);

        textPath.setText(uri.getPath());

        final TextView pathCount = (TextView) browserItem.findViewById(R.id.pathCount);
        if (pathCount != null) {
            if (counts != null) {
                Long count = counts.get(uri.getPath());
                pathCount.setText(count == null ? "" : "" + count);
                pathCount.setVisibility(View.VISIBLE);
            } else {
                pathCount.setVisibility(View.GONE);
            }
        }

        final View deleteView = browserItem.findViewById(R.id.delete);
        if (deleteView != null) {
            deleteView.setVisibility(View.VISIBLE);
            deleteView.setOnClickListener(new OnClickListener() {

                @Override
                public void onClick(View v) {
                    if (onDeleClick != null) {
                        onDeleClick.onResultRecive(uri);
                    }
                }
            });
        }

        return browserItem;
    }

    public ResultResponse<Uri> getOnDeleClick() {
        return onDeleClick;
    }

    public void setOnDeleClick(ResultResponse<Uri> onDeleClick) {
        this.onDeleClick = onDeleClick;
    }

}

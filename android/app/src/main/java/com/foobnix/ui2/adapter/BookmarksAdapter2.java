package com.foobnix.ui2.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import com.foobnix.android.utils.ResultResponse;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppBookmark;
import com.foobnix.model.AppState;
import com.foobnix.model.MyPath;
import com.foobnix.pdf.info.Clouds;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.IMG;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.TintUtil;
import com.foobnix.ui2.AppDB;
import com.foobnix.ui2.AppRecycleAdapter;
import com.foobnix.ui2.adapter.BookmarksAdapter2.BookmarksViewHolder;

public class BookmarksAdapter2 extends AppRecycleAdapter<AppBookmark, BookmarksViewHolder> {


    public boolean withTitle = true;
    public boolean withPageNumber = true;
    private ResultResponse<AppBookmark> onDeleteClickListener;
    private OnMoreClickListener onMoreClickListener;

    /** "⋮" menu on a merged-notes row; carries the anchor view so the popup opens at the button. */
    public interface OnMoreClickListener {
        void onMoreClick(AppBookmark item, View anchor);
    }

    // Formats note timestamps as "yyyy-MM-dd HH:mm"
    private static final java.text.SimpleDateFormat NOTE_TIME_FORMAT =
            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US);

    @Override
    public BookmarksViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.bookmark_item, parent, false);
        return new BookmarksViewHolder(itemView);
    }

    /** Returns true if this item is a book header entry */
    private boolean isBookHeader(int position) {
        AppBookmark item = getItem(position);
        // Header rows carry a dedicated flag set when the summary entry is
        // built — text content is user data and must not be matched here.
        return item != null && item.isBookHeader;
    }

    @Override
    public void onBindViewHolder(final BookmarksViewHolder holder, final int position) {
        final AppBookmark item = getItem(position);
        boolean header = isBookHeader(position);

        // For book headers: use a simplified display; for regular bookmarks: full details
        if (header) {
            // Book header: show filename in title, hide page number, hide text body
            String fileName = ExtUtils.getFileName(item.getPath());
            int countStart = item.text.lastIndexOf("(");
            int countEnd = item.text.lastIndexOf(" items)");
            holder.title.setText(countStart > 0 ? item.text.substring(0, countStart).trim() : fileName);
            holder.page.setVisibility(View.GONE);
            holder.remove.setVisibility(View.GONE);
            holder.moreMenu.setVisibility(View.GONE);

            // Book headers use full-height text area for the title
            holder.text.setVisibility(View.GONE);
        } else {
            // Regular bookmark: show page number and all details
            // Restore visibility in case the view holder was previously bound
            // to a book header row (RecyclerView reuses view holders).
            holder.text.setVisibility(View.VISIBLE);
            holder.remove.setVisibility(View.VISIBLE);
            if (item.isAiNote) {
                // AI notes have no page: show a note marker instead of a page number
                holder.page.setText(R.string.reading_note);
            } else {
                holder.page.setText(TxtUtils.percentFormatInt(item.getPercent()));
                FileMeta m = AppDB.get().load(MyPath.toAbsolute(item.path));

                if (m != null && m.getPages() != null && m.getPages() > 0) {
                    holder.page.setText("" + Math.round(item.getPercent() * m.getPages()));
                }
            }

            // The merged-notes row (isAiNote with a per-note list) is a notes
            // aggregate, not a bookmark: show its own "笔记 (N)" label instead of
            // the book file name so the row identity matches its type.
            holder.title.setText(item.isAiNote && item.notes != null ? item.text
                    : ExtUtils.getFileName(item.getPath()));
            if (item.isAiNote) {
                // AI reading note: show a short title in the list; the full merged
                // content (all notes, newest first) is shown in a dialog on click.
                String time = NOTE_TIME_FORMAT.format(item.getTime());
                holder.text.setText("[" + time + "] " + item.text);
            } else {
                holder.text.setText(item.text);
            }
            holder.remove.setOnClickListener(new OnClickListener() {

                @Override
                public void onClick(View v) {
                    onDeleteClickListener.onResultRecive(item);
                }
            });
            holder.remove.setImageResource(withPageNumber ? R.drawable.glyphicons_599_menu_close : R.drawable.glyphicons_578_share);
            TintUtil.setTintImageNoAlpha(holder.remove, holder.remove.getResources().getColor(R.color.lt_grey_dima));

            // The "⋮" menu is only offered on the merged-notes row (a notes
            // aggregate); regular bookmarks and single notes keep it hidden.
            boolean isMergedNotes = item.isAiNote && item.notes != null;
            holder.moreMenu.setVisibility(isMergedNotes ? View.VISIBLE : View.GONE);
            if (isMergedNotes) {
                holder.moreMenu.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (onMoreClickListener != null) {
                            onMoreClickListener.onMoreClick(item, v);
                        }
                    }
                });
                TintUtil.setTintImageNoAlpha(holder.moreMenu, holder.moreMenu.getResources().getColor(R.color.lt_grey_dima));
            }

            if (withTitle) {
                //holder.title.setVisibility(View.VISIBLE);
                //holder.title.setVisibility(View.GONE);
            } else {
                holder.title.setVisibility(View.GONE);
            }

            TintUtil.setTintBgSimple(holder.page, 240);
            holder.page.setTextColor(Color.WHITE);
            if (withPageNumber) {
                holder.page.setVisibility(View.VISIBLE);
            } else {
                holder.page.setVisibility(View.GONE);
            }
        }

        IMG.getCoverPageWithEffect(holder.image.getContext(), item.getPath(), null).into(holder.image);

        Clouds.showHideCloudImage(holder.cloudImage, item.getPath());


        if (!AppState.get().isBorderAndShadow) {
            holder.parent.setBackgroundColor(Color.TRANSPARENT);
        }

        bindItemClickAndLongClickListeners(holder.parent, getItem(position));

        if (AppState.get().appTheme == AppState.THEME_DARK_OLED) {
            holder.parent.setBackgroundColor(Color.BLACK);
        }
    }

    public void setOnDeleteClickListener(ResultResponse<AppBookmark> onDeleteClickListener) {
        this.onDeleteClickListener = onDeleteClickListener;
    }

    public void setOnMoreClickListener(OnMoreClickListener onMoreClickListener) {
        this.onMoreClickListener = onMoreClickListener;
    }

    public class BookmarksViewHolder extends RecyclerView.ViewHolder {
        public TextView page, text, title;
        public ImageView remove;
        public ImageView moreMenu;
        public CardView parent;
        public ImageView image, cloudImage;

        public BookmarksViewHolder(View view) {
            super(view);
            page = (TextView) view.findViewById(R.id.page);
            title = (TextView) view.findViewById(R.id.title);
            text = (TextView) view.findViewById(R.id.text);
            image = (ImageView) view.findViewById(R.id.image);
            cloudImage = (ImageView) view.findViewById(R.id.cloudImage);
            remove = view.findViewById(R.id.remove);
            moreMenu = view.findViewById(R.id.moreMenu);
            parent = (CardView) view;
        }
    }

}
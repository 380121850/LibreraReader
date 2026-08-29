package com.foobnix.ui2.fragment;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.foobnix.android.utils.Dips;
import com.foobnix.model.AppState;
import com.foobnix.model.BookStateStore;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.TintUtil;

/**
 * Shared multi-select chip bar ("N selected · mark read/unread/reading ·
 * select all · cancel") used by the library and the recent pages. Binds to
 * the selectionBar/selectionTopRow/selectionCount/selectionActions ids that
 * both fragment layouts carry; {@link #bind} returns null (inert) when the
 * host layout has no bar.
 */
public class SelectionBarController {

    public interface Callbacks {
        /** "Select all" chip: select every book of the current list. */
        void onSelectAll();

        /** "Cancel" chip: leave multi-select without applying anything. */
        void onCancel();

        /** One of the mark-state chips (BookStateStore.READ/UNREAD/READING). */
        void onApplyState(int state);
    }

    private final LinearLayout bar;
    private final TextView count;

    private SelectionBarController(LinearLayout bar, TextView count) {
        this.bar = bar;
        this.count = count;
    }

    public static SelectionBarController bind(View root, final Callbacks cb) {
        if (root == null || cb == null) {
            return null;
        }
        LinearLayout bar = root.findViewById(R.id.selectionBar);
        final TextView count = root.findViewById(R.id.selectionCount);
        LinearLayout actions = root.findViewById(R.id.selectionActions);
        LinearLayout topRow = root.findViewById(R.id.selectionTopRow);
        if (bar == null || count == null || actions == null || topRow == null) {
            return null;
        }
        boolean dark = AppState.get().appTheme == AppState.THEME_DARK
                || AppState.get().appTheme == AppState.THEME_DARK_OLED;
        bar.setBackgroundColor(dark ? 0xFF26262A : 0xFFF0F0F2);

        addStateAction(actions, R.string.moon_mark_read, BookStateStore.READ, cb);
        addStateAction(actions, R.string.moon_mark_unread, BookStateStore.UNREAD, cb);
        addStateAction(actions, R.string.moon_mark_reading, BookStateStore.READING, cb);

        TextView selectAll = makeChip(root.getContext(), R.string.moon_select_all, false);
        selectAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                cb.onSelectAll();
            }
        });
        actions.addView(selectAll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView cancel = makeChip(root.getContext(), R.string.cancel, false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                cb.onCancel();
            }
        });
        topRow.addView(cancel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return new SelectionBarController(bar, count);
    }

    private static void addStateAction(LinearLayout actions, int textRes, final int state, final Callbacks cb) {
        TextView chip = makeChip(actions.getContext(), textRes, false);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                cb.onApplyState(state);
            }
        });
        actions.addView(chip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Chip factory shared with the pages (their filter chips look the same). */
    public static TextView makeChip(Context c, int textRes, boolean selected) {
        TextView chip = new TextView(c);
        if (textRes != 0) {
            chip.setText(textRes);
        }
        chip.setTextSize(14);
        chip.setSingleLine(true);
        chip.setAllCaps(false);
        chip.setPadding(Dips.dpToPx(14), Dips.dpToPx(6), Dips.dpToPx(14), Dips.dpToPx(6));
        styleChip(chip, selected);
        return chip;
    }

    public static void styleChip(TextView chip, boolean selected) {
        if (selected) {
            chip.setBackgroundColor(TintUtil.color);
            chip.setTextColor(Color.WHITE);
        } else {
            chip.setBackgroundResource(R.drawable.bg_search_edit);
            chip.setTextColor(chip.getResources().getColor(R.color.tint_gray));
        }
    }

    public void setVisible(boolean visible) {
        bar.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public boolean isVisible() {
        return bar.getVisibility() == View.VISIBLE;
    }

    public void setCount(int n) {
        count.setText(count.getResources().getString(R.string.moon_selected_count, n));
    }
}

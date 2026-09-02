package com.foobnix.pdf.info.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import com.foobnix.android.utils.Dips;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.TintUtil;

/**
 * Simple bar chart for the reading-time history shown from the dashboard's
 * "total reading time" card. No chart library — one onDraw pass: rounded
 * bars sized against the largest bucket, a compact duration label above
 * each bar and the bucket label below it. Works for any bucket count
 * (7 days, 30 days, 12 months); when there are many bars only every
 * labelStep-th label is drawn to avoid overlap.
 */
public class MonthlyBarsView extends View {

    private long[] valuesMs = new long[0];
    private String[] labels = new String[0];
    private int labelStep = 1;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();

    public MonthlyBarsView(Context context) {
        super(context);
        boolean dark = AppState.get().appTheme == AppState.THEME_DARK
                || AppState.get().appTheme == AppState.THEME_DARK_OLED;
        int textColor = dark ? 0xFFE0E0E0 : 0xFF424242;
        int labelColor = dark ? 0xFF9E9E9E : 0xFF757575;

        barPaint.setColor(TintUtil.color);
        textPaint.setColor(textColor);
        textPaint.setTextSize(Dips.dpToPx(11));
        textPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(labelColor);
        labelPaint.setTextSize(Dips.dpToPx(11));
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    /** @param labelStep draw only every n-th bottom label (1 = all of them) */
    public void setData(long[] valuesMs, String[] labels, int labelStep) {
        this.valuesMs = valuesMs;
        this.labels = labels;
        this.labelStep = Math.max(1, labelStep);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Dips.dpToPx(300));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int columns = valuesMs.length;
        if (columns == 0) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        float valueH = Dips.dpToPx(14);
        float labelH = Dips.dpToPx(18);
        float plotTop = getPaddingTop() + valueH;
        float plotBottom = h - getPaddingBottom() - labelH;
        float plotH = plotBottom - plotTop;
        float colW = (w - getPaddingLeft() - getPaddingRight()) / (float) columns;

        long max = 1;
        for (long v : valuesMs) {
            if (v > max) {
                max = v;
            }
        }

        for (int i = 0; i < columns; i++) {
            float cx = getPaddingLeft() + colW * (i + 0.5f);
            long ms = valuesMs[i];

            float barW = colW * 0.55f;
            // 30 thin columns get proportionally slimmer bars
            if (columns > 16) {
                barW = colW * 0.45f;
            }
            float barH = ms <= 0
                    ? Dips.dpToPx(3)
                    : Math.max(Dips.dpToPx(3), plotH * (ms / (float) max));
            barRect.set(cx - barW / 2, plotBottom - barH, cx + barW / 2, plotBottom);
            int prevColor = barPaint.getColor();
            if (ms <= 0) {
                barPaint.setColor(0x33999999);
            }
            canvas.drawRoundRect(barRect, barW / 4, barW / 4, barPaint);
            barPaint.setColor(prevColor);

            // value labels only fit when the column is wide enough
            if (colW >= Dips.dpToPx(26)) {
                canvas.drawText(compactDuration(ms), cx, plotTop - Dips.dpToPx(3), textPaint);
            }
            if (i % labelStep == 0) {
                canvas.drawText(labels[i] == null ? "" : labels[i], cx,
                        plotBottom + Dips.dpToPx(13), labelPaint);
            }
        }
    }

    /** "3.5h" above an hour, "12m" below, "0" when empty — language-neutral. */
    private static String compactDuration(long ms) {
        if (ms <= 0) {
            return "0";
        }
        long minutes = ms / 60000;
        if (minutes >= 60) {
            long tenths = minutes * 10 / 60;
            return tenths % 10 == 0 ? (tenths / 10) + "h" : (tenths / 10f) + "h";
        }
        return minutes + "m";
    }
}

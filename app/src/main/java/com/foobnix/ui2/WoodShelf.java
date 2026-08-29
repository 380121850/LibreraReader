package com.foobnix.ui2;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.PaintDrawable;

import java.util.Random;

/**
 * Procedural wooden bookshelf texture (Moon+ Reader style) for the library
 * page in covers/grid mode: horizontal planks with wavy grain, plank seams
 * with a lit top edge, slight per-plank tint variation and rare knots. No
 * image assets — the tile is drawn once per process and reused.
 */
public class WoodShelf {

    private static volatile Drawable cached;

    /** Tiled plank texture sized in pixels (no context-dependent sizing). */
    public static Drawable texture(Context c) {
        if (cached != null) {
            return cached;
        }
        synchronized (WoodShelf.class) {
            if (cached != null) {
                return cached;
            }
            final Bitmap tile = makeTile();
            PaintDrawable d = new PaintDrawable();
            d.setIntrinsicWidth(tile.getWidth());
            d.setIntrinsicHeight(tile.getHeight());
            d.getPaint().setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
            cached = d;
            return cached;
        }
    }

    /** Horizontal planks, seam to seam. Must tile vertically without a visible joint. */
    private static Bitmap makeTile() {
        final int w = 512, h = 512;
        final int plank = 128;
        final Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bmp);
        // fixed seed: every device shows the same shelf
        final Random rnd = new Random(20260830L);

        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        for (int top = 0; top < h; top += plank) {
            // per-plank base tint: warm oak with slight variation
            final int tint = rnd.nextInt(3);
            final int base = tint == 0 ? 0xFF9A6B3F : tint == 1 ? 0xFF93643A : 0xFFA17348;

            // vertical light falloff inside the plank
            paint.setShader(new LinearGradient(0, top, 0, top + plank,
                    lighten(base, 1.10f), darken(base, 0.86f), Shader.TileMode.CLAMP));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(0, top, w, top + plank, paint);
            paint.setShader(null);

            // wavy grain lines
            for (int g = 0; g < 14; g++) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1f + rnd.nextInt(2));
                final int alpha = 22 + rnd.nextInt(26);
                paint.setColor(rnd.nextBoolean() ? Color.argb(alpha, 62, 36, 14) : Color.argb(alpha, 226, 190, 140));
                float y = top + 10 + rnd.nextFloat() * (plank - 22);
                float amp = 2f + rnd.nextFloat() * 4f;
                float period = 60f + rnd.nextFloat() * 130f;
                float phase = rnd.nextFloat() * (float) Math.PI * 2;
                android.graphics.Path p = new android.graphics.Path();
                p.moveTo(0, y);
                for (float x = 0; x <= w; x += 8) {
                    p.lineTo(x, y + amp * (float) Math.sin(x / period + phase));
                }
                canvas.drawPath(p, paint);
            }

            // rare knot
            if (rnd.nextInt(4) == 0) {
                float kx = 40 + rnd.nextFloat() * (w - 80);
                float ky = top + 24 + rnd.nextFloat() * (plank - 48);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(60, 58, 32, 12));
                canvas.drawOval(kx - 9, ky - 5, kx + 9, ky + 5, paint);
                paint.setColor(Color.argb(70, 48, 26, 10));
                canvas.drawOval(kx - 4, ky - 2.5f, kx + 4, ky + 2.5f, paint);
            }

            // plank seam: dark groove + lit top edge of the next plank
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(255, 52, 30, 12));
            canvas.drawRect(0, top + plank - 4, w, top + plank, paint);
            paint.setColor(Color.argb(255, 214, 178, 128));
            canvas.drawRect(0, top, w, top + 3, paint);
        }
        return bmp;
    }

    private static int lighten(int color, float f) {
        return Color.argb(255,
                Math.min(255, (int) (Color.red(color) * f)),
                Math.min(255, (int) (Color.green(color) * f)),
                Math.min(255, (int) (Color.blue(color) * f)));
    }

    private static int darken(int color, float f) {
        return lighten(color, f);
    }
}

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
 * Procedural wooden bookshelf texture (Moon+ Reader style): a light-walnut
 * board with fine vertical grain. ONE continuous board — no plank seams and
 * no per-plank tint bands. The pattern tiles in both directions: the grain
 * strokes are periodic over the tile height and never cross the left/right
 * edges. No image assets — the tile is drawn once per process and reused.
 */
public class WoodShelf {

    private static volatile Drawable cached;
    private static volatile Bitmap tileCache;

    /** Tiled wood texture as a drawable (fixed background, covers/grid modes). */
    public static Drawable texture(Context c) {
        if (cached != null) {
            return cached;
        }
        synchronized (WoodShelf.class) {
            if (cached != null) {
                return cached;
            }
            final Bitmap tile = tile();
            PaintDrawable d = new PaintDrawable();
            d.setIntrinsicWidth(tile.getWidth());
            d.setIntrinsicHeight(tile.getHeight());
            d.getPaint().setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
            cached = d;
            return cached;
        }
    }

    /** The raw tile bitmap, shared by the scrolling shelf decoration. */
    public static Bitmap tile() {
        if (tileCache != null) {
            return tileCache;
        }
        synchronized (WoodShelf.class) {
            if (tileCache != null) {
                return tileCache;
            }
            tileCache = makeTile();
            return tileCache;
        }
    }

    private static Bitmap makeTile() {
        final int w = 512, h = 512;
        final Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bmp);
        // fixed seed: every device shows the same shelf
        final Random rnd = new Random(20260831L);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // light walnut base — one single tone, no horizontal bands
        canvas.drawColor(0xFF94745A);

        // broad, soft vertical grain bands
        for (int i = 0; i < 10; i++) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(20 + rnd.nextInt(40));
            paint.setColor(rnd.nextBoolean() ? Color.argb(14, 74, 46, 24) : Color.argb(12, 232, 202, 158));
            drawGrainLine(canvas, paint, w, h, rnd, 6f + rnd.nextFloat() * 12f);
        }
        // fine grain streaks
        for (int i = 0; i < 60; i++) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1f + rnd.nextInt(2));
            int alpha = 16 + rnd.nextInt(26);
            paint.setColor(rnd.nextBoolean() ? Color.argb(alpha, 70, 44, 22) : Color.argb(alpha - 8, 238, 210, 166));
            drawGrainLine(canvas, paint, w, h, rnd, 1.5f + rnd.nextFloat() * 4f);
        }

        // rare small knots
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 2; i++) {
            if (rnd.nextInt(3) == 0) {
                float kx = 60 + rnd.nextFloat() * (w - 120);
                float ky = 60 + rnd.nextFloat() * (h - 120);
                paint.setColor(Color.argb(42, 70, 44, 22));
                canvas.drawOval(kx - 9, ky - 4.5f, kx + 9, ky + 4.5f, paint);
                paint.setColor(Color.argb(52, 56, 34, 16));
                canvas.drawOval(kx - 4, ky - 2.5f, kx + 4, ky + 2.5f, paint);
            }
        }
        return bmp;
    }

    /**
     * One wavy vertical grain stroke; the wave is periodic over the tile
     * height and the whole stroke stays inside the horizontal bounds, so the
     * texture tiles seamlessly in both directions.
     */
    private static void drawGrainLine(Canvas c, Paint p, int w, int h, Random rnd, float amp) {
        final float x0 = amp + rnd.nextFloat() * (w - 2 * amp);
        final int k = 1 + rnd.nextInt(2);
        final float period = h / (float) k;
        final float phase = rnd.nextFloat() * (float) Math.PI * 2;
        android.graphics.Path path = new android.graphics.Path();
        for (float y = -p.getStrokeWidth(); y <= h + p.getStrokeWidth(); y += 6) {
            float x = x0 + amp * (float) Math.sin((y / period) * (float) Math.PI * 2 + phase);
            if (x < 0) {
                x = 0;
            }
            if (x > w) {
                x = w;
            }
            if (path.isEmpty()) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        c.drawPath(path, p);
    }
}

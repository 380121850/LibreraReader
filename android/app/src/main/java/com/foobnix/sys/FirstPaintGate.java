package com.foobnix.sys;

import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;

import com.foobnix.android.utils.LOG;

/**
 * Holds the "please wait" loading dialog on screen until the first screen
 * is fully decoded, so the user never sees empty "Page N" placeholders
 * flash before the content appears. The dialog drops when no new page
 * bitmap has arrived for a short quiet period (the whole visible screen is
 * then painted), with a hard cap as a safety net.
 */
public class FirstPaintGate {

    private static final long QUIET_MS = 500;
    private static final long NO_DECODE_MS = 2000;
    private static final long HARD_CAP_MS = 8000;
    private static final long TICK_MS = 200;

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static volatile AlertDialog dialog;
    private static volatile boolean armed;
    private static volatile long armAt;
    private static volatile long firstDecodeAt;
    private static volatile long lastDecodeAt;

    private static final Runnable TICK = new Runnable() {

        @Override
        public void run() {
            tick();
        }
    };

    private static final Runnable HARD_CAP = new Runnable() {

        @Override
        public void run() {
            android.util.Log.i("BENCH", "FirstPaintGate hard cap");
            release();
        }
    };

    /** Keep the loading dialog visible until the first screen is decoded. */
    public static void arm(final AlertDialog loadingDialog) {
        UI.removeCallbacks(TICK);
        UI.removeCallbacks(HARD_CAP);
        dialog = loadingDialog;
        armed = loadingDialog != null;
        if (armed) {
            armAt = android.os.SystemClock.elapsedRealtime();
            firstDecodeAt = 0;
            lastDecodeAt = 0;
            android.util.Log.i("BENCH", "FirstPaintGate arm");
            UI.postDelayed(TICK, TICK_MS);
            UI.postDelayed(HARD_CAP, HARD_CAP_MS);
        }
    }

    /** Called on the UI thread when a page bitmap has been set. */
    public static void notifyDecoded() {
        if (armed) {
            final long now = android.os.SystemClock.elapsedRealtime();
            if (firstDecodeAt == 0) {
                firstDecodeAt = now;
            }
            lastDecodeAt = now;
        }
    }

    /** Disarm and dismiss the held dialog, if still showing. */
    public static void cancel() {
        release();
    }

    private static void tick() {
        if (!armed) {
            return;
        }
        final long now = android.os.SystemClock.elapsedRealtime();
        if (firstDecodeAt > 0 && now - lastDecodeAt >= QUIET_MS) {
            android.util.Log.i("BENCH", "FirstPaintGate release (screen decoded "
                                                + (now - armAt) + "ms after open)");
            release();
            return;
        }
        if (firstDecodeAt == 0 && now - armAt >= NO_DECODE_MS) {
            // nothing is decoding (everything already rendered): show it
            android.util.Log.i("BENCH", "FirstPaintGate release (no decodes pending)");
            release();
            return;
        }
        UI.postDelayed(TICK, TICK_MS);
    }

    private static void release() {
        armed = false;
        UI.removeCallbacks(TICK);
        UI.removeCallbacks(HARD_CAP);
        final AlertDialog d = dialog;
        dialog = null;
        if (d != null && d.isShowing()) {
            try {
                d.dismiss();
            } catch (final Exception e) {
                LOG.e(e);
            }
        }
    }
}

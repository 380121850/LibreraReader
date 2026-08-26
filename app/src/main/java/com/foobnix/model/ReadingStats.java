package com.foobnix.model;

import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Dashboard reading-stats accounting, shared by both reader activities.
 *
 * onResume() anchors a monotonic clock; onFlip() counts a page turn while a
 * session is active; onPause() folds the elapsed time into the cumulative
 * counter, the today counter (reset when the calendar day changed) and the
 * cumulative page-flip counter. Persistence happens through the caller's
 * existing AppProfile.save() call — onPause() itself only mutates AppSP.
 *
 * A session spanning midnight books its whole duration into the new day; a
 * process kill loses only the un-flushed tail (same trade-off as readTimeMs).
 *
 * Manual state changes (BookStateStore.markRead/markUnread/markReading) never
 * pass through this class, so these counters only accumulate real reader
 * sessions — marking a book finished by hand adds no time or pages.
 */
public final class ReadingStats {

    private static long resumeAt = 0;
    private static long pendingFlips = 0;

    private ReadingStats() {
    }

    public static void onResume() {
        resumeAt = SystemClock.elapsedRealtime();
    }

    /** Count one page turn. No-op outside an active reading session, which
     *  also excludes the initial position restore that happens before onResume. */
    public static void onFlip() {
        if (resumeAt > 0) {
            pendingFlips++;
        }
    }

    public static void onPause() {
        if (resumeAt <= 0) {
            return;
        }
        long delta = SystemClock.elapsedRealtime() - resumeAt;
        resumeAt = 0;

        AppSP sp = AppSP.get();
        sp.readTimeMs += delta;

        String key = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if (!key.equals(sp.readDayKey)) {
            sp.readDayKey = key;
            sp.readDayMs = 0;
        }
        sp.readDayMs += delta;

        sp.readPages += pendingFlips;
        pendingFlips = 0;
    }
}

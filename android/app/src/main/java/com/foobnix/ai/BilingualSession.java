package com.foobnix.ai;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.model.AppState;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drives the in-page bilingual AI-translate session for one book.
 *
 * While the session is active on a book, a single background worker keeps
 * translating the source paragraphs around the current reading position
 * (paragraph window, ordered current page first). Every finished paragraph is
 * upserted into the {@link TranslationCache}; after a short merge debounce the
 * {@link BilingualBuilder} regenerates the bilingual edition and the host
 * (reader activity) is asked to restart so the translation actually renders
 * under its source paragraph ("每段译完就地刷新", merged into one rebuild).
 *
 * The session survives reader-activity restarts (the restart is exactly how a
 * rebuilt edition gets shown), so it is held as a per-book singleton and is
 * only stopped when the mode is turned off or another book is opened.
 */
public class BilingualSession {

    /** Backend-independent "please re-open the current book" hook. */
    public interface Host {
        Context getAppContext();

        void requestRestart();
    }

    // paragraph window around the current reading position (source indices)
    private static final int WINDOW_BACK = 10;
    private static final int WINDOW_AHEAD = 26;
    // merge window for rebuilds triggered by finished paragraphs
    private static final long REBUILD_DEBOUNCE_MS = 3000;

    private static final Map<String, BilingualSession> SESSIONS = new HashMap<String, BilingualSession>();

    public static synchronized BilingualSession attach(String bookPath, File book, String src, String tgt) {
        BilingualSession s = SESSIONS.get(bookPath);
        if (s == null) {
            s = new BilingualSession(book, src, tgt);
            SESSIONS.put(bookPath, s);
        }
        return s;
    }

    /** The session for a book path, or null when it was never created/stopped. */
    public static synchronized BilingualSession attachOrNull(String bookPath) {
        if (bookPath == null) {
            return null;
        }
        return SESSIONS.get(bookPath);
    }

    public static synchronized void pauseAllExcept(String bookPath) {
        for (Map.Entry<String, BilingualSession> e : SESSIONS.entrySet()) {
            boolean active = e.getKey().equals(bookPath);
            e.getValue().setActive(active);
        }
    }

    public static synchronized void stop(String bookPath) {
        BilingualSession s = SESSIONS.remove(bookPath);
        if (s != null) {
            s.dispose();
        }
    }

    public static synchronized boolean isActive(String bookPath) {
        BilingualSession s = SESSIONS.get(bookPath);
        return s != null && s.active.get() && !s.stopped.get();
    }

    /**
     * Reader-controller level attach used by both reading modes: creates (or
     * re-points) the session for the book behind {@code dc} when the in-page
     * bilingual mode is on for it, otherwise pauses every session.
     */
    public static void attachForController(final android.app.Activity activity,
            final com.foobnix.pdf.info.wrapper.DocumentController dc) {
        try {
            if (activity == null || dc == null || dc.getCurrentBook() == null) {
                return;
            }
            final String path = dc.getCurrentBook().getPath();
            final AppState st = AppState.get();
            BilingualSession existing = attachOrNull(path);
            if (existing != null) {
                existing.attachHost(new Host() {
                    @Override public android.content.Context getAppContext() {
                        return activity.getApplicationContext();
                    }

                    @Override public void requestRestart() {
                        if (dc != null) {
                            dc.restartActivity();
                        }
                    }
                });
                pauseAllExcept(path);
                existing.onView(dc.getCurentPageFirst1() - 1, dc.getPageCount());
                return;
            }
            if (!st.aiBilingual || TxtUtils.isEmpty(st.aiBilingualBook)
                    || !st.aiBilingualBook.equals(path)) {
                pauseAllExcept(null);
                return;
            }
            BilingualSession created = attach(path, dc.getCurrentBook(), st.aiBilingualSrc, st.aiBilingualTgt);
            created.attachHost(new Host() {
                @Override public android.content.Context getAppContext() {
                    return activity.getApplicationContext();
                }

                @Override public void requestRestart() {
                    if (dc != null) {
                        dc.restartActivity();
                    }
                }
            });
            pauseAllExcept(path);
            created.onView(dc.getCurentPageFirst1() - 1, dc.getPageCount());
        } catch (Throwable t) {
            LOG.e(t);
        }
    }

    /** Feed the current page to the session of the book behind {@code dc}. */
    public static void feedForController(com.foobnix.pdf.info.wrapper.DocumentController dc) {
        try {
            if (dc == null || dc.getCurrentBook() == null) {
                return;
            }
            BilingualSession s = attachOrNull(dc.getCurrentBook().getPath());
            if (s != null) {
                s.onView(dc.getCurentPageFirst1() - 1, dc.getPageCount());
                s.logCurrentPageText(dc);
            }
        } catch (Throwable t) {
            LOG.e(t);
        }
    }

    // debug aid: rate-limited dump of the rendered text layer of the current
    // page, so a bilingual page can be verified without a screenshot
    private long lastPageLogMs = 0;

    private void logCurrentPageText(com.foobnix.pdf.info.wrapper.DocumentController dc) {
        try {
            long now = System.currentTimeMillis();
            if (now - lastPageLogMs < 15000) {
                return;
            }
            lastPageLogMs = now;
            int p = dc.getCurentPageFirst1() - 1;
            String[] paras = dc.getPageParagraphs(p);
            if (paras == null) {
                android.util.Log.i("BENCH", "BilingualPageText p=" + p + " paras=null");
                return;
            }
            StringBuilder sb = new StringBuilder();
            int n = Math.min(paras.length, 6);
            for (int i = 0; i < n; i++) {
                String t = paras[i];
                if (t != null && t.length() > 90) {
                    t = t.substring(0, 90);
                }
                sb.append('[').append(i).append("]").append(t).append(" || ");
            }
            android.util.Log.i("BENCH", "BilingualPageText p=" + p + " count=" + paras.length + " " + sb.toString());
        } catch (Throwable t) {
            LOG.e(t);
        }
    }

    private final File book;
    private final String src;
    private final String tgt;
    private final TranslationCache cache;

    private volatile Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean building = new AtomicBoolean(false);

    private Thread worker;
    private final Object workerLock = new Object();

    // source paragraphs (lazily enumerated from the base file the doc opened)
    private volatile List<BilingualBuilder.Para> paras;
    private volatile Map<String, BilingualBuilder.Para> paraByMd5;
    private final AtomicBoolean ensuring = new AtomicBoolean(false);
    private final Set<String> pending = new HashSet<String>();
    private final Set<String> inFlight = new HashSet<String>();
    private final ArrayDeque<String> queue = new ArrayDeque<String>();
    private final Map<String, Integer> attempts = new HashMap<String, Integer>();

    private int lastPage0 = -1;
    private int lastPageCount = 0;

    private final Runnable rebuildRunnable = new Runnable() {
        @Override public void run() {
            rebuild();
        }
    };
    private boolean rebuildScheduled = false;

    private BilingualSession(File book, String src, String tgt) {
        this.book = book;
        this.src = src;
        this.tgt = tgt;
        this.cache = new TranslationCache(book);
    }

    public File getBook() {
        return book;
    }

    public String getSrc() {
        return src;
    }

    public String getTgt() {
        return tgt;
    }

    /** Attach the reader activity; also activates this session and pauses others. */
    public void attachHost(Host h) {
        this.host = h;
        setActive(true);
        startWorker();
    }

    public void detachHost() {
        this.host = null;
        setActive(false);
    }

    private void setActive(boolean on) {
        active.set(on);
        if (on) {
            pending.clear();
        }
        wake();
    }

    private void wake() {
        synchronized (workerLock) {
            workerLock.notifyAll();
        }
    }

    private void startWorker() {
        synchronized (workerLock) {
            if (worker == null) {
                worker = new Thread(new Runnable() {
                    @Override public void run() {
                        workerLoop();
                    }
                }, "BiTran");
                worker.setDaemon(true);
                worker.start();
            }
        }
    }

    /** Reader position changed: recompute the translation window. page = 0-based. */
    public void onView(int page0, int pageCount) {
        lastPage0 = page0;
        lastPageCount = pageCount;
        if (paras == null) {
            // first enumeration reads the whole base file: do it off the UI
            if (ensuring.compareAndSet(false, true)) {
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            recomputeWindow();
                        } finally {
                            ensuring.set(false);
                        }
                    }
                }, "BiEnum").start();
            }
            return;
        }
        recomputeWindow();
        wake();
    }

    private synchronized void ensureParas() {
        if (paras != null) {
            return;
        }
        File base = BilingualBuilder.baseFor(book);
        paras = BilingualBuilder.enumerateParagraphs(base == null ? book : base);
        paraByMd5 = new HashMap<String, BilingualBuilder.Para>();
        for (BilingualBuilder.Para p : paras) {
            if (p.md5 != null && !paraByMd5.containsKey(p.md5)) {
                paraByMd5.put(p.md5, p);
            }
        }
        android.util.Log.i("BENCH", "BilingualSession paras total=" + paras.size() + " base="
                + (base == null ? book : base).getPath());
    }

    private void recomputeWindow() {
        try {
            ensureParas();
            int total = paras.size();
            if (total == 0 || lastPageCount <= 0 || lastPage0 < 0) {
                return;
            }
            long idx = (long) (total * (lastPage0 + 0.5f) / lastPageCount);
            int from = Math.max(0, (int) idx - WINDOW_BACK);
            int to = Math.min(total - 1, (int) idx + WINDOW_AHEAD);
            Map<String, String> done = cache.doneByTextHash(src, tgt);
            int added = 0;
            synchronized (pending) {
                synchronized (queue) {
                    for (int i = from; i <= to; i++) {
                        BilingualBuilder.Para p = paras.get(i);
                        if (p == null || p.md5 == null || TxtUtils.isEmpty(p.text)) {
                            continue;
                        }
                        if (done.containsKey(p.md5) || pending.contains(p.md5) || inFlight.contains(p.md5)) {
                            continue;
                        }
                        pending.add(p.md5);
                        queue.addLast(p.md5);
                        added++;
                    }
                }
            }
            android.util.Log.i("BENCH", "BilingualSession onView page=" + lastPage0 + "/" + lastPageCount
                    + " idx=" + idx + " window=[" + from + "," + to + "] queued=" + added
                    + " done=" + done.size() + " pending=" + pending.size());
        } catch (Throwable t) {
            LOG.e(t);
        }
    }

    private void workerLoop() {
        while (!stopped.get()) {
            String md5 = null;
            synchronized (workerLock) {
                if (!active.get()) {
                    try {
                        workerLock.wait(800);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }
            }
            synchronized (queue) {
                md5 = queue.pollFirst();
            }
            if (md5 == null) {
                try {
                    synchronized (workerLock) {
                        if (!stopped.get() && queue.isEmpty()) {
                            workerLock.wait(800);
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }
            if (!active.get() || host == null) {
                // paused (activity restarting/detached): keep the item, wait
                synchronized (queue) {
                    queue.addFirst(md5);
                }
                try {
                    synchronized (workerLock) {
                        if (!stopped.get()) {
                            workerLock.wait(800);
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }
            synchronized (pending) {
                pending.remove(md5);
            }
            translateOne(md5);
        }
    }

    private void translateOne(final String md5) {
        final BilingualBuilder.Para p;
        synchronized (this) {
            if (paraByMd5 == null) {
                return;
            }
            p = paraByMd5.get(md5);
        }
        if (p == null || TxtUtils.isEmpty(p.text)) {
            return;
        }
        inFlight.add(md5);
        try {
            if (cache.doneByTextHash(src, tgt).containsKey(md5)) {
                android.util.Log.i("BENCH", "BilingualSession " + md5 + " cache HIT");
                onParagraphDone(md5, false);
                return;
            }
            String suffix = "请把这段文字翻译成" + AiTranslator.targetLangName(tgt)
                    + "，不要启用思考过程，直接回复翻译内容";
            String prompt = p.text + "\n\n" + suffix;
            android.util.Log.i("BENCH", "BilingualSession " + md5 + " AI ask ord=" + p.ordinal
                    + " len=" + p.text.length());
            long t0 = System.currentTimeMillis();
            AiClient.TestResult res = AiClient.ask(host == null ? null : host.getAppContext(), prompt);
            android.util.Log.i("BENCH", "BilingualSession " + md5 + " AI res ok=" + res.ok + " ms="
                    + (System.currentTimeMillis() - t0) + " err=" + res.error + " reply="
                    + (res.reply == null ? -1 : res.reply.length()));
            if (res.ok && TxtUtils.isNotEmpty(res.reply)) {
                cache.save("h" + md5, src, tgt, p.text, res.reply.trim(), "done");
                cache.flush();
                onParagraphDone(md5, true);
                return;
            }
            // failure: retry a bounded number of times, tail of the queue
            Integer n = attempts.get(md5);
            int attempt = n == null ? 0 : n;
            attempts.put(md5, attempt + 1);
            if (attempt < 2) {
                synchronized (queue) {
                    if (!pending.contains(md5)) {
                        pending.add(md5);
                        queue.addLast(md5);
                    }
                }
                android.util.Log.i("BENCH", "BilingualSession " + md5 + " retry attempt=" + attempt);
            } else {
                android.util.Log.i("BENCH", "BilingualSession " + md5 + " FAILED err=" + res.error);
            }
        } catch (Throwable t) {
            LOG.e(t);
        } finally {
            inFlight.remove(md5);
        }
    }

    /** A paragraph finished: schedule one merged rebuild (on the UI thread). */
    private void onParagraphDone(String md5, boolean newly) {
        android.util.Log.i("BENCH", "BilingualSession paragraph done=" + md5 + " new=" + newly);
        if (!newly) {
            return; // nothing changed, nothing to rebuild
        }
        ui.post(new Runnable() {
            @Override public void run() {
                if (stopped.get()) {
                    return;
                }
                if (rebuildScheduled) {
                    ui.removeCallbacks(rebuildRunnable);
                }
                rebuildScheduled = true;
                ui.postDelayed(rebuildRunnable, REBUILD_DEBOUNCE_MS);
            }
        });
    }

    /**
     * Regenerate the bilingual edition with the latest cached translations and
     * ask the host to re-open the book so the page shows the new translation.
     */
    private void rebuild() {
        if (stopped.get() || host == null) {
            return;
        }
        rebuildScheduled = false;
        if (!building.compareAndSet(false, true)) {
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File base = BilingualBuilder.baseFor(book);
                    if (base == null) {
                        base = book;
                    }
                    long t0 = System.currentTimeMillis();
                    final File target = BilingualBuilder.ensure(book, base, cache, src, tgt);
                    android.util.Log.i("BENCH", "BilingualSession rebuild ensure ms="
                            + (System.currentTimeMillis() - t0) + " target=" + (target == null ? "null" : target.getName()));
                    if (target != null) {
                        final Host h = host;
                        if (h != null && !stopped.get()) {
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    if (!stopped.get() && host != null) {
                                        host.requestRestart();
                                    }
                                }
                            });
                        }
                    }
                } catch (Throwable t) {
                    LOG.e(t);
                } finally {
                    building.set(false);
                }
            }
        }, "BiRebuild").start();
    }

    private void dispose() {
        stopped.set(true);
        ui.removeCallbacks(rebuildRunnable);
        synchronized (workerLock) {
            workerLock.notifyAll();
        }
        try {
            if (worker != null) {
                worker.interrupt();
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        worker = null;
    }
}

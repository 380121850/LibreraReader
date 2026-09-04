package com.foobnix.ai;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.wrapper.DocumentController;

import java.io.File;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drives the in-page bilingual AI-translate session for one book.
 *
 * A single background worker keeps translating the source paragraphs around the
 * current reading position, ordered "current page first, then ahead pages, then
 * back pages" (page window: +10 ahead / -3 back). Every finished paragraph is
 * upserted into the {@link TranslationCache}; after a short merge debounce the
 * {@link BilingualBuilder} regenerates the bilingual edition and the host
 * (reader activity) is asked to re-open the book *in place* (silently, anchored
 * to the top paragraph of the current page) so the translation renders without
 * the full Activity-restart flashing of the old approach.
 *
 * The session survives reader-activity restarts, so it is held as a per-book
 * singleton and only stopped when the mode is turned off or another book opens.
 */
public class BilingualSession {

    /** Backend-independent "please re-open the current book" hook. */
    public interface Host {
        Context getAppContext();

        void requestRestart();

        /** Re-open the book in place (or silently) landing on the same content. */
        void requestReload(int page0, String anchorMd5);

        /** Re-evaluate the "正在翻译中…" bottom hint for the current page. */
        void requestHintUpdate();
    }

    // page window around the current reading position
    private static final int BACK_PAGES = 3;
    private static final int AHEAD_PAGES = 10;
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

    /** The top-paragraph anchor currently remembered for a book, or null. */
    public static String anchorOf(String bookPath) {
        BilingualSession s = attachOrNull(bookPath);
        if (s == null) {
            return null;
        }
        if (s.anchorMd5 == null) {
            // first enumeration may have finished after the page was shown:
            // recompute the anchor on the spot so a reload can re-land
            s.onPageShown(s.lastDc);
        }
        return s.anchorMd5;
    }

    /**
     * Find the rendered page whose text layer contains the anchor paragraph,
     * scanning a small band around {@code approxPage0} (0-based). Returns -1
     * when the anchor is unknown or not found.
     */
    public static int locateAnchorPage(DocumentController dc, String anchorMd5, int approxPage0) {
        try {
            if (dc == null || dc.getCurrentBook() == null || TxtUtils.isEmpty(anchorMd5)) {
                return -1;
            }
            int pageCount = dc.getPageCount();
            if (pageCount <= 0) {
                return -1;
            }
            BilingualSession s = attachOrNull(dc.getCurrentBook().getPath());
            if (s == null || s.paraByMd5 == null) {
                return -1;
            }
            BilingualBuilder.Para p = s.paraByMd5.get(anchorMd5);
            if (p == null) {
                return -1;
            }
            String nSource = norm(p.text);
            if (nSource.length() < 4) {
                return -1;
            }
            int start = Math.max(0, approxPage0 - 10);
            int end = Math.min(pageCount - 1, approxPage0 + 10);
            int bestPage = -1;
            int bestLen = 0;
            for (int page = start; page <= end; page++) {
                String[] frags = dc.getPageParagraphs(page);
                if (frags == null) {
                    continue;
                }
                for (String f : frags) {
                    if (TxtUtils.isEmpty(f)) {
                        continue;
                    }
                    String nf = norm(f);
                    if (nf.length() < 6) {
                        continue;
                    }
                    int ov = nf.indexOf(nSource) >= 0 ? nSource.length()
                            : nSource.indexOf(nf) >= 0 ? nf.length() : 0;
                    if (ov >= Math.min(8, nSource.length()) && nf.length() > bestLen) {
                        bestLen = nf.length();
                        bestPage = page;
                    }
                }
            }
            return bestPage;
        } catch (Throwable t) {
            LOG.e(t);
            return -1;
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    /**
     * Reader-controller level attach used by both reading modes: creates (or
     * re-points) the session for the book behind {@code dc} when the in-page
     * bilingual mode is on for it, otherwise pauses every session.
     */
    public static void attachForController(final Activity activity,
            final DocumentController dc) {
        try {
            if (activity == null || dc == null || dc.getCurrentBook() == null) {
                return;
            }
            final String path = dc.getCurrentBook().getPath();
            final AppState st = AppState.get();
            BilingualSession existing = attachOrNull(path);
            if (existing != null) {
                existing.attachHost(hostFor(activity, dc, path));
                pauseAllExcept(path);
                existing.onView(dc.getCurentPageFirst1() - 1, dc.getPageCount());
                existing.updateHint();
                return;
            }
            if (!st.aiBilingual || TxtUtils.isEmpty(st.aiBilingualBook)
                    || !st.aiBilingualBook.equals(path)) {
                pauseAllExcept(null);
                return;
            }
            BilingualSession created = attach(path, dc.getCurrentBook(), st.aiBilingualSrc, st.aiBilingualTgt);
            created.attachHost(hostFor(activity, dc, path));
            pauseAllExcept(path);
            created.onView(dc.getCurentPageFirst1() - 1, dc.getPageCount());
            created.updateHint();
        } catch (Throwable t) {
            LOG.e(t);
        }
    }

    private static Host hostFor(final Activity activity, final DocumentController dc, final String path) {
        return new Host() {
            @Override public Context getAppContext() {
                return activity.getApplicationContext();
            }

            @Override public void requestRestart() {
                if (dc != null) {
                    dc.restartActivity();
                }
            }

            @Override public void requestReload(int page0, String anchorMd5) {
                reloadForController(activity, dc, page0, anchorMd5);
            }

            @Override public void requestHintUpdate() {
                try {
                    BilingualSession s = attachOrNull(path);
                    boolean show = s != null && s.isCurrentPagePending();
                    if (activity instanceof BilingualHintUi) {
                        ((BilingualHintUi) activity).setBilingualHint(show);
                    }
                } catch (Throwable t) {
                    LOG.e(t);
                }
            }
        };
    }

    /**
     * In-place silent reload for the horizontal reader, silent restart for the
     * vertical one; any failure degrades to the classic full restart.
     */
    private static void reloadForController(Activity activity, DocumentController dc, int page0, String anchorMd5) {
        try {
            if (activity instanceof com.foobnix.pdf.search.activity.HorizontalViewActivity) {
                ((com.foobnix.pdf.search.activity.HorizontalViewActivity) activity).reloadBilingualInPlace();
            } else if (dc != null) {
                dc.restartActivitySilently(page0, anchorMd5);
            }
        } catch (Throwable t) {
            LOG.e(t);
            try {
                if (dc != null) {
                    dc.restartActivity();
                }
            } catch (Throwable t2) {
                LOG.e(t2);
            }
        }
    }

    /** Feed the current page to the session of the book behind {@code dc}. */
    public static void feedForController(DocumentController dc) {
        try {
            if (dc == null || dc.getCurrentBook() == null) {
                return;
            }
            BilingualSession s = attachOrNull(dc.getCurrentBook().getPath());
            if (s != null) {
                s.lastDc = dc;
                s.onView(dc.getCurentPageFirst1() - 1, dc.getPageCount());
                s.onPageShown(dc);
                s.maybeRefreshCurrentPage();
            }
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
    private final Set<String> queued = new HashSet<String>();
    private final Set<String> inFlight = new HashSet<String>();
    private final Set<String> failed = new HashSet<String>();
    private final ArrayDeque<String> queue = new ArrayDeque<String>();
    private final Map<String, Integer> attempts = new HashMap<String, Integer>();

    private int lastPage0 = -1;
    private int lastPageCount = 0;

    // top paragraph md5 of the current page, used to re-land after a rebuild
    private volatile String anchorMd5;
    // the last controller that fed this session (to re-run the page-anchor
    // computation once the first paragraph enumeration has finished)
    private volatile DocumentController lastDc;

    // the md5 set the currently open book was built with (translations it shows)
    private volatile Set<String> builtMd5s = new HashSet<String>();
    private volatile boolean reloading = false;
    private volatile boolean reloadPending = false;

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
        try {
            // the open book was just built from the cached translations
            builtMd5s = new HashSet<String>(cache.doneByTextHash(src, tgt).keySet());
        } catch (Throwable t) {
            LOG.e(t);
        }
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
            synchronized (queue) {
                pending.clear();
            }
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
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    if (stopped.get()) {
                                        return;
                                    }
                                    // the anchor/hint could not be computed
                                    // before the enumeration finished
                                    onPageShown(lastDc);
                                    updateHint();
                                    maybeRefreshCurrentPage();
                                }
                            });
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

    /** 0-based page index -> global source paragraph index (linear estimate). */
    private int pageStart(int page0, int pageCount, int total) {
        if (pageCount <= 0 || total <= 0) {
            return 0;
        }
        long v = (long) total * page0 / pageCount;
        if (v < 0) {
            v = 0;
        }
        if (v > total) {
            v = total;
        }
        return (int) v;
    }

    private void recomputeWindow() {
        try {
            ensureParas();
            int total = paras.size();
            if (total == 0 || lastPageCount <= 0 || lastPage0 < 0) {
                return;
            }
            int from = pageStart(lastPage0 - BACK_PAGES, lastPageCount, total);
            int to = pageStart(lastPage0 + AHEAD_PAGES + 1, lastPageCount, total) - 1;
            Map<String, String> done = cache.doneByTextHash(src, tgt);
            int added = 0;
            synchronized (pending) {
                synchronized (queue) {
                    // current page first, then ahead pages, then back pages
                    added += enqueuePageRange(done, lastPage0, lastPage0 + 1, true);
                    for (int d = 1; d <= AHEAD_PAGES; d++) {
                        added += enqueuePageRange(done, lastPage0 + d, lastPage0 + d + 1, false);
                    }
                    for (int d = 1; d <= BACK_PAGES; d++) {
                        added += enqueuePageRange(done, lastPage0 - d, lastPage0 - d + 1, false);
                    }
                }
            }
            android.util.Log.i("BENCH", "BilingualSession onView page=" + lastPage0 + "/" + lastPageCount
                    + " winPages=[" + (lastPage0 - BACK_PAGES) + "," + (lastPage0 + AHEAD_PAGES) + "]"
                    + " winParas=[" + from + "," + to + "] queued=" + added
                    + " done=" + done.size() + " pending=" + pending.size() + " queuedSet=" + queued.size());
            updateHint();
        } catch (Throwable t) {
            LOG.e(t);
        }
    }

    private int enqueuePageRange(Map<String, String> done, int pageFrom, int pageTo, boolean currentPage) {
        if (lastPageCount <= 0) {
            return 0;
        }
        int total = paras.size();
        int from = pageStart(pageFrom, lastPageCount, total);
        int to = pageStart(pageTo, lastPageCount, total) - 1;
        int added = 0;
        for (int i = Math.max(0, from); i <= Math.min(total - 1, to); i++) {
            BilingualBuilder.Para p = paras.get(i);
            if (p == null || p.md5 == null || TxtUtils.isEmpty(p.text)) {
                continue;
            }
            if (done.containsKey(p.md5) || pending.contains(p.md5) || inFlight.contains(p.md5)
                    || queued.contains(p.md5)) {
                continue;
            }
            if (currentPage) {
                // the reader is looking at it: allow one more retry after failure
                failed.remove(p.md5);
            }
            pending.add(p.md5);
            queued.add(p.md5);
            queue.addLast(p.md5);
            added++;
        }
        return added;
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
                if (md5 != null) {
                    queued.remove(md5);
                    pending.remove(md5);
                }
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
                    queued.add(md5);
                    pending.add(md5);
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
                    if (!pending.contains(md5) && !queued.contains(md5)) {
                        pending.add(md5);
                        queued.add(md5);
                        queue.addLast(md5);
                    }
                }
                android.util.Log.i("BENCH", "BilingualSession " + md5 + " retry attempt=" + attempt);
            } else {
                synchronized (queue) {
                    failed.add(md5);
                }
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
        updateHint();
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
     * ask the host to re-open the book silently (in place) so the new page
     * shows the translation without flashing.
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
                        try {
                            builtMd5s = new HashSet<String>(cache.doneByTextHash(src, tgt).keySet());
                        } catch (Throwable t) {
                            LOG.e(t);
                        }
                        final int page0 = lastPage0;
                        final String anchor = anchorMd5;
                        final Host h = host;
                        if (h != null && !stopped.get()) {
                            requestReload(page0, anchor);
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

    /** Coalesced request to the host: at most one reload in flight at a time. */
    private void requestReload(final int page0, final String anchorMd5) {
        synchronized (this) {
            if (reloading) {
                reloadPending = true;
                return;
            }
            reloading = true;
        }
        final Host h = host;
        if (h == null || stopped.get()) {
            synchronized (this) {
                reloading = false;
            }
            return;
        }
        ui.post(new Runnable() {
            @Override public void run() {
                if (stopped.get() || host == null) {
                    synchronized (BilingualSession.this) {
                        reloading = false;
                    }
                    return;
                }
                host.requestReload(page0, anchorMd5);
                synchronized (BilingualSession.this) {
                    reloading = false;
                    if (reloadPending) {
                        reloadPending = false;
                        scheduleRebuild();
                    }
                }
                updateHint();
            }
        });
    }

    private void scheduleRebuild() {
        if (stopped.get()) {
            return;
        }
        if (rebuildScheduled) {
            ui.removeCallbacks(rebuildRunnable);
        }
        rebuildScheduled = true;
        ui.postDelayed(rebuildRunnable, 50);
    }

    /**
     * Remember the top paragraph of the currently shown page (content anchor
     * used to re-land after a rebuild) and dump the page text layer for debug.
     */
    private long lastPageLogMs = 0;

    private void onPageShown(DocumentController dc) {
        try {
            if (paras == null) {
                return;
            }
            int p = dc.getCurentPageFirst1() - 1;
            if (p < 0) {
                return;
            }
            String[] frags = dc.getPageParagraphs(p);
            String anchor = anchorOfFragments(frags);
            if (anchor == null) {
                anchor = fallbackAnchor();
            }
            if (anchor != null) {
                anchorMd5 = anchor;
                android.util.Log.i("BENCH", "BilingualSession anchor p=" + p + " md5=" + anchor);
            }
            long now = System.currentTimeMillis();
            if (now - lastPageLogMs >= 15000) {
                lastPageLogMs = now;
                if (frags == null) {
                    android.util.Log.i("BENCH", "BilingualPageText p=" + p + " paras=null");
                } else {
                    StringBuilder sb = new StringBuilder();
                    int n = Math.min(frags.length, 6);
                    for (int i = 0; i < n; i++) {
                        String t = frags[i];
                        if (t != null && t.length() > 90) {
                            t = t.substring(0, 90);
                        }
                        sb.append('[').append(i).append("]").append(t).append(" || ");
                    }
                    android.util.Log.i("BENCH", "BilingualPageText p=" + p + " count=" + frags.length
                            + " anchor=" + anchor + " " + sb.toString());
                }
            }
        } catch (Throwable t) {
            LOG.e(t);
        }
    }

    private String anchorOfFragments(String[] frags) {
        try {
            if (frags == null || frags.length == 0) {
                return null;
            }
            List<BilingualBuilder.Para> ps = paras;
            if (ps == null || ps.isEmpty()) {
                return null;
            }
            for (String f : frags) {
                if (TxtUtils.isEmpty(f)) {
                    continue;
                }
                String nf = norm(f);
                if (nf.length() < 6) {
                    continue;
                }
                String best = null;
                int bestLen = 0;
                for (BilingualBuilder.Para p : ps) {
                    if (p.md5 == null || TxtUtils.isEmpty(p.text)) {
                        continue;
                    }
                    String np = norm(p.text);
                    if (np.length() >= nf.length() && np.indexOf(nf) >= 0 && np.length() > bestLen) {
                        bestLen = np.length();
                        best = p.md5;
                    }
                }
                if (best != null) {
                    return best;
                }
            }
        } catch (Throwable t) {
            LOG.e(t);
        }
        return null;
    }

    private String fallbackAnchor() {
        try {
            List<BilingualBuilder.Para> ps = paras;
            if (ps == null || ps.isEmpty() || lastPageCount <= 0 || lastPage0 < 0) {
                return null;
            }
            long idx = (long) (ps.size() * (lastPage0 + 0.5f) / lastPageCount);
            int i = Math.max(0, Math.min(ps.size() - 1, (int) idx));
            BilingualBuilder.Para p = ps.get(i);
            return p == null ? null : p.md5;
        } catch (Throwable t) {
            LOG.e(t);
            return null;
        }
    }

    /** True when the current page still has paragraphs not shown bilingually. */
    public boolean isCurrentPagePending() {
        try {
            List<BilingualBuilder.Para> ps = paras;
            if (ps == null || ps.isEmpty() || lastPageCount <= 0 || lastPage0 < 0) {
                return false;
            }
            int total = ps.size();
            int from = pageStart(lastPage0, lastPageCount, total);
            int to = pageStart(lastPage0 + 1, lastPageCount, total) - 1;
            Map<String, String> done = cache.doneByTextHash(src, tgt);
            Set<String> built = builtMd5s;
            synchronized (queue) {
                for (int i = Math.max(0, from); i <= Math.min(total - 1, to); i++) {
                    BilingualBuilder.Para p = ps.get(i);
                    if (p == null || p.md5 == null) {
                        continue;
                    }
                    if (done.containsKey(p.md5)) {
                        // translated but the open book predates it
                        if (!built.contains(p.md5)) {
                            return true;
                        }
                    } else if (!failed.contains(p.md5)) {
                        // still translating / queued / not started
                        return true;
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            LOG.e(t);
            return false;
        }
    }

    /** If the current page has translations ready that the open book lacks, refresh it. */
    private void maybeRefreshCurrentPage() {
        try {
            if (!needsRefreshForCurrentPage()) {
                return;
            }
            android.util.Log.i("BENCH", "BilingualSession current page stale -> refresh");
            ui.post(new Runnable() {
                @Override public void run() {
                    if (!stopped.get() && host != null) {
                        scheduleRebuild();
                    }
                }
            });
        } catch (Throwable t) {
            LOG.e(t);
        }
    }

    private boolean needsRefreshForCurrentPage() {
        try {
            List<BilingualBuilder.Para> ps = paras;
            if (ps == null || ps.isEmpty() || lastPageCount <= 0 || lastPage0 < 0) {
                return false;
            }
            int total = ps.size();
            int from = pageStart(lastPage0, lastPageCount, total);
            int to = pageStart(lastPage0 + 1, lastPageCount, total) - 1;
            Map<String, String> done = cache.doneByTextHash(src, tgt);
            Set<String> built = builtMd5s;
            for (int i = Math.max(0, from); i <= Math.min(total - 1, to); i++) {
                BilingualBuilder.Para p = ps.get(i);
                if (p == null || p.md5 == null) {
                    continue;
                }
                if (done.containsKey(p.md5) && !built.contains(p.md5)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            LOG.e(t);
            return false;
        }
    }

    private void updateHint() {
        if (host == null) {
            return;
        }
        ui.post(new Runnable() {
            @Override public void run() {
                if (!stopped.get() && host != null) {
                    host.requestHintUpdate();
                }
            }
        });
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

package com.foobnix.ai;

import com.foobnix.android.utils.FileHash;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.model.AppProfile;

import org.librera.LinkedJSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-book translation cache, one JSONL file per book, keyed by the book
 * content SHA-256 (so a renamed/moved book still hits its translations).
 *
 * Line format (one JSON object per paragraph):
 * {"pid":"ch1_p003","src":"en","tgt":"zh-CN","orig":"...","tran":"...","ts":1717500000,"status":"done"}
 *
 * pid = chapter + paragraph anchor (stable across reflow); src/tgt = the
 * language pair (each pair is cached independently); orig = original snapshot
 * (drift guard); status = pending/done/failed (allows incremental backfill).
 */
public class TranslationCache {

    private final File file;
    // key = pid|src|tgt  ->  raw JSON line
    private final Map<String, String> lines = new LinkedHashMap<String, String>();
    private boolean dirty = false;
    // in-memory mode (see inMemory): lookups work, nothing is ever written
    private boolean ephemeral = false;
    // md5 of the cleaned original -> translated text, lazily rebuilt per
    // language pair from `lines` (see doneByTextHash). The bilingual in-page
    // pipeline (BilingualBuilder/BilingualSession) identifies paragraphs by
    // their content md5 only ("h<md5>"), while the legacy list panel keys them
    // as "ch<chapter>_h<md5>", so lookups must match on the md5 tail.
    private Map<String, Map<String, String>> md5Index;

    public TranslationCache(File book) {
        String hash = FileHash.sha256(book);
        File dir = new File(AppProfile.SYNC_FOLDER_DEVICE_PROFILE, "ai-translation");
        dir.mkdirs();
        file = new File(dir, hash + ".jsonl");
        load();
    }

    /**
     * Session-only cache: still reads the existing per-book file so already
     * saved translations keep hitting within this session, but save()/flush()
     * never touch the disk. Used when the user turns off "save AI translation
     * results" — callers need no null handling, the API is identical.
     */
    public static TranslationCache inMemory(File book) {
        TranslationCache c = new TranslationCache(book);
        c.ephemeral = true;
        return c;
    }

    /** Extract the content-md5 tail from a pid (both conventions). */
    public static String md5FromPid(String pid) {
        if (pid == null) {
            return null;
        }
        int i = pid.lastIndexOf("_h");
        if (i >= 0 && i + 2 < pid.length()) {
            return pid.substring(i + 2);
        }
        if (pid.startsWith("h") && pid.length() > 1) {
            return pid.substring(1);
        }
        return null;
    }

    public File getFile() {
        return file;
    }

    private static String key(String pid, String src, String tgt) {
        return pid + "|" + src + "|" + tgt;
    }

    private void load() {
        lines.clear();
        md5Index = null;
        if (!file.isFile()) {
            return;
        }
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    LinkedJSONObject o = new LinkedJSONObject(line);
                    String pid = o.optString("pid");
                    String src = o.optString("src");
                    String tgt = o.optString("tgt");
                    if (TxtUtils.isNotEmpty(pid)) {
                        lines.put(key(pid, src, tgt), line);
                    }
                } catch (Exception ignored) {
                    // skip malformed line
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Return the cached translation for (pid, src, tgt) when it is done and the
     * original text has not drifted; null otherwise.
     */
    public synchronized String lookup(String pid, String src, String tgt, String orig) {
        String line = lines.get(key(pid, src, tgt));
        if (line == null) {
            return null;
        }
        try {
            LinkedJSONObject o = new LinkedJSONObject(line);
            if (!"done".equals(o.optString("status"))) {
                return null;
            }
            String storedOrig = o.optString("orig");
            // drift guard: if we know the original and it changed, it is a miss
            if (TxtUtils.isNotEmpty(orig) && TxtUtils.isNotEmpty(storedOrig)
                    && !orig.equals(storedOrig)) {
                return null;
            }
            String tran = o.optString("tran");
            return TxtUtils.isEmpty(tran) ? null : tran;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * All done translations for one language pair, keyed by the md5 of the
     * cleaned original paragraph (across both pid conventions). Cheap enough to
     * call once per paragraph only if the caller caches the result — the index
     * is rebuilt on demand after every save().
     */
    public synchronized Map<String, String> doneByTextHash(String src, String tgt) {
        if (md5Index == null) {
            md5Index = new LinkedHashMap<String, Map<String, String>>();
        }
        String pair = src + "|" + tgt;
        Map<String, String> index = md5Index.get(pair);
        if (index == null) {
            index = new LinkedHashMap<String, String>();
            for (String line : lines.values()) {
                try {
                    LinkedJSONObject o = new LinkedJSONObject(line);
                    if (!"done".equals(o.optString("status"))) {
                        continue;
                    }
                    String pid = o.optString("pid");
                    String src2 = o.optString("src");
                    String tgt2 = o.optString("tgt");
                    if (!pair.equals(src2 + "|" + tgt2)) {
                        continue;
                    }
                    String tran = o.optString("tran");
                    String md5 = md5FromPid(pid);
                    if (md5 != null && TxtUtils.isNotEmpty(tran) && !index.containsKey(md5)) {
                        index.put(md5, tran);
                    }
                } catch (Exception ignored) {
                    // skip malformed line
                }
            }
            md5Index.put(pair, index);
        }
        return index;
    }

    /** Record a paragraph translation (upsert by pid|src|tgt). */
    public synchronized void save(String pid, String src, String tgt, String orig, String tran, String status) {
        try {
            LinkedJSONObject o = new LinkedJSONObject();
            o.put("pid", pid);
            o.put("src", src);
            o.put("tgt", tgt);
            o.put("orig", orig == null ? "" : orig);
            o.put("tran", tran == null ? "" : tran);
            o.put("ts", System.currentTimeMillis() / 1000L);
            o.put("status", status);
            lines.put(key(pid, src, tgt), o.toString());
            dirty = true;
            md5Index = null;
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** Write pending changes to disk (no-op in in-memory mode). The whole
     * JSONL is rewritten, so an interrupted direct write would truncate the
     * cache — build a temp file first and rename it atomically. */
    public synchronized void flush() {
        if (ephemeral || !dirty) {
            return;
        }
        File tmp = null;
        BufferedWriter out = null;
        try {
            new File(file.getParent()).mkdirs();
            tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8"));
            for (String line : lines.values()) {
                out.write(line);
                out.newLine();
            }
            out.flush();
            out.close();
            out = null;
            if (!tmp.renameTo(file)) {
                // fall back to an in-place rewrite (rename can fail if the
                // target is held by a reader on some filesystems)
                BufferedWriter direct = new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(file), "UTF-8"));
                for (String line : lines.values()) {
                    direct.write(line);
                    direct.newLine();
                }
                direct.flush();
                direct.close();
                tmp.delete();
            }
            dirty = false;
        } catch (Exception e) {
            LOG.e(e);
            if (tmp != null) {
                tmp.delete();
            }
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}

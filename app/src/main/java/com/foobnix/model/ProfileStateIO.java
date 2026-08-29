package com.foobnix.model;

import android.content.Context;

import com.foobnix.ai.AiCredentials;
import com.foobnix.android.utils.IO;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;

import org.librera.JSONArray;
import org.librera.LinkedJSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Iterator;

/**
 * Read/write/merge helpers for the state files that complete the backup and
 * WebDAV sync: reading statistics (app-Stats.json, mirrors the AppSP read*
 * fields), the AI API key (app-AI.json) and union merges for the per-device
 * SimpleMeta arrays (recent / favorite).
 *
 * app-AI.json carries the plain API key so a restore on the same device (or a
 * user-trusted server) restores the working AI setup; WebDavCredentials stay
 * device-bound and are NOT exported.
 */
public class ProfileStateIO {

    private static final String K_API_KEY = "apiKey";
    private static final String K_MONTHLY = "readMonthlyJson";
    private static final String K_DAILY = "readDailyJson";
    private static final String K_DAY_KEY = "readDayKey";
    private static final String K_DAY_MS = "readDayMs";

    private static final String SEC_APPSP = "AppSP";
    private static final String SEC_OPDS = "opds";
    private static final String SEC_PASSWORD = "PasswordState";
    private static final String SEC_POPUPS = "DraggingPopups";

    // ------------------------------------------------------------------ stats

    /** Mirror the AppSP reading statistics into app-Stats.json (before export / sync). */
    public static void exportStats() {
        try {
            if (AppProfile.syncStats == null) {
                return;
            }
            AppSP sp = AppSP.get();
            LinkedJSONObject o = new LinkedJSONObject();
            o.put("readTimeMs", sp.readTimeMs);
            o.put(K_DAY_KEY, sp.readDayKey);
            o.put(K_DAY_MS, sp.readDayMs);
            o.put("readPages", sp.readPages);
            o.put(K_MONTHLY, TxtUtils.isEmpty(sp.readMonthlyJson) ? "{}" : sp.readMonthlyJson);
            o.put(K_DAILY, TxtUtils.isEmpty(sp.readDailyJson) ? "{}" : sp.readDailyJson);
            IO.writeObjSync(AppProfile.syncStats, o);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** Restore reading statistics from app-Stats.json into AppSP (after import / sync). */
    public static void importStats(Context c) {
        try {
            if (AppProfile.syncStats == null || !AppProfile.syncStats.isFile()) {
                return;
            }
            LinkedJSONObject o = IO.readJsonObject(AppProfile.syncStats);
            if (o.length() == 0) {
                return;
            }
            applyStats(o);
            if (c != null) {
                AppSP.get().save();
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** Write one stats object into the AppSP fields (max of both sides). */
    static void applyStats(LinkedJSONObject o) {
        AppSP sp = AppSP.get();
        sp.readTimeMs = Math.max(sp.readTimeMs, o.optLong("readTimeMs", 0));
        sp.readPages = Math.max(sp.readPages, o.optLong("readPages", 0));
        if (o.optLong(K_DAY_MS, 0) > sp.readDayMs) {
            sp.readDayMs = o.optLong(K_DAY_MS, 0);
            sp.readDayKey = o.optString(K_DAY_KEY, sp.readDayKey);
        }
        sp.readMonthlyJson = mergeBuckets(sp.readMonthlyJson, o.optString(K_MONTHLY, "{}"));
        sp.readDailyJson = mergeBuckets(sp.readDailyJson, o.optString(K_DAILY, "{}"));
    }

    /**
     * Merge two stats objects: numeric fields take the larger value, the
     * month/day bucket strings are parsed and merged per key (larger wins).
     */
    public static LinkedJSONObject mergeStats(LinkedJSONObject local, LinkedJSONObject remote) {
        try {
            applyStats(remote);
            LinkedJSONObject out = new LinkedJSONObject();
            out.put("readTimeMs", AppSP.get().readTimeMs);
            out.put(K_DAY_KEY, AppSP.get().readDayKey);
            out.put(K_DAY_MS, AppSP.get().readDayMs);
            out.put("readPages", AppSP.get().readPages);
            out.put(K_MONTHLY, TxtUtils.isEmpty(AppSP.get().readMonthlyJson) ? "{}" : AppSP.get().readMonthlyJson);
            out.put(K_DAILY, TxtUtils.isEmpty(AppSP.get().readDailyJson) ? "{}" : AppSP.get().readDailyJson);
            return out;
        } catch (Exception e) {
            LOG.e(e);
            return local;
        }
    }

    /** Per-key max of two JSON-in-string bucket maps ({"2026-08": ms, ...}). */
    static String mergeBuckets(String a, String b) {
        try {
            LinkedJSONObject ja = new LinkedJSONObject(TxtUtils.isEmpty(a) ? "{}" : a);
            LinkedJSONObject jb = new LinkedJSONObject(TxtUtils.isEmpty(b) ? "{}" : b);
            LinkedJSONObject out = new LinkedJSONObject(ja.toString());
            Iterator<String> keys = jb.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                out.put(k, Math.max(ja.optLong(k, 0), jb.optLong(k, 0)));
            }
            return out.toString();
        } catch (Exception e) {
            return TxtUtils.isEmpty(a) ? b : a;
        }
    }

    // ------------------------------------------------------------------ misc (remaining configurable state)

    /**
     * Snapshot of the remaining configurable state into app-Misc.json: the
     * whole AppSP object (last book, reading mode, sync flags, statistics
     * fields…), OPDS server logins, the app/book passwords and the reader
     * button-layout cache. Written before export / sync, restored after.
     */
    public static void exportMisc(Context c) {
        try {
            if (AppProfile.syncMisc == null) {
                return;
            }
            LinkedJSONObject root = new LinkedJSONObject();
            root.put(SEC_APPSP, com.foobnix.android.utils.Objects.toJSONObject(AppSP.get()));
            root.put(SEC_OPDS, snapshotPrefs(c, SEC_OPDS));
            root.put(SEC_PASSWORD, snapshotPrefs(c, SEC_PASSWORD));
            root.put(SEC_POPUPS, snapshotPrefs(c, SEC_POPUPS));
            IO.writeObjSync(AppProfile.syncMisc, root);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** Restore the app-Misc.json sections into AppSP / SharedPreferences. */
    public static void importMisc(Context c) {
        try {
            if (AppProfile.syncMisc == null || !AppProfile.syncMisc.isFile() || c == null) {
                return;
            }
            LinkedJSONObject root = IO.readJsonObject(AppProfile.syncMisc);
            if (root.length() == 0) {
                return;
            }
            LinkedJSONObject sp = root.optJSONObject(SEC_APPSP);
            if (sp != null && sp.length() > 0) {
                com.foobnix.android.utils.Objects.loadFromJson(AppSP.get(), sp);
                AppSP.get().save();
            }
            restorePrefs(c, SEC_OPDS, root.optJSONObject(SEC_OPDS));
            restorePrefs(c, SEC_PASSWORD, root.optJSONObject(SEC_PASSWORD));
            restorePrefs(c, SEC_POPUPS, root.optJSONObject(SEC_POPUPS));
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** Every key-value pair of one SharedPreferences file, as JSON. */
    public static LinkedJSONObject snapshotPrefs(Context c, String prefsName) {
        LinkedJSONObject out = new LinkedJSONObject();
        try {
            if (c == null) {
                return out;
            }
            android.content.SharedPreferences sp = c.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            for (java.util.Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                Object v = e.getValue();
                if (v instanceof java.util.Set) {
                    out.put(e.getKey(), new JSONArray((java.util.Set<?>) v));
                } else if (v != null) {
                    out.put(e.getKey(), v);
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        return out;
    }

    /** Write every entry of the JSON object back into one SharedPreferences file. */
    public static void restorePrefs(Context c, String prefsName, LinkedJSONObject data) {
        try {
            if (c == null || data == null || data.length() == 0) {
                return;
            }
            android.content.SharedPreferences sp = c.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor edit = sp.edit();
            Iterator<String> keys = data.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object v = data.get(k);
                if (v instanceof Boolean) {
                    edit.putBoolean(k, (Boolean) v);
                } else if (v instanceof Integer) {
                    edit.putInt(k, (Integer) v);
                } else if (v instanceof Long) {
                    edit.putLong(k, (Long) v);
                } else if (v instanceof Float) {
                    edit.putFloat(k, (Float) v);
                } else if (v instanceof Double) {
                    // numbers without a decimal point parse as int/long upstream
                    edit.putFloat(k, (float) (double) (Double) v);
                } else if (v instanceof JSONArray) {
                    java.util.Set<String> set = new java.util.HashSet<>();
                    JSONArray arr = (JSONArray) v;
                    for (int i = 0; i < arr.length(); i++) {
                        set.add(arr.optString(i));
                    }
                    edit.putStringSet(k, set);
                } else {
                    edit.putString(k, v == null ? null : v.toString());
                }
            }
            edit.commit();
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /**
     * Section-wise union for the WebDAV sync: a section present on this device
     * wins; sections missing locally are taken from the remote file.
     */
    public static LinkedJSONObject mergeMisc(LinkedJSONObject local, LinkedJSONObject remote) {
        try {
            LinkedJSONObject out = new LinkedJSONObject();
            String[] sections = {SEC_APPSP, SEC_OPDS, SEC_PASSWORD, SEC_POPUPS};
            for (String section : sections) {
                LinkedJSONObject l = local.optJSONObject(section);
                LinkedJSONObject r = remote.optJSONObject(section);
                LinkedJSONObject pick = l != null && l.length() > 0 ? l : r;
                if (pick != null && pick.length() > 0) {
                    out.put(section, pick);
                }
            }
            return out;
        } catch (Exception e) {
            return local;
        }
    }

    // ------------------------------------------------------------------ AI

    /** Mirror the AI API key into app-AI.json (before export / sync). */
    public static void exportAi(Context c) {
        try {
            if (AppProfile.syncAI == null || c == null) {
                return;
            }
            LinkedJSONObject o = new LinkedJSONObject();
            o.put(K_API_KEY, AiCredentials.load(c));
            IO.writeObjSync(AppProfile.syncAI, o);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** Fill a missing local AI key from app-AI.json (after import / sync). */
    public static void importAi(Context c) {
        try {
            if (AppProfile.syncAI == null || !AppProfile.syncAI.isFile() || c == null) {
                return;
            }
            LinkedJSONObject o = IO.readJsonObject(AppProfile.syncAI);
            String remote = o.optString(K_API_KEY, "");
            if (TxtUtils.isNotEmpty(remote) && TxtUtils.isEmpty(AiCredentials.load(c))) {
                AiCredentials.save(c, remote);
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** Union of the AI key file: whichever side holds a non-empty key wins. */
    public static LinkedJSONObject mergeAi(Context c, LinkedJSONObject remote) {
        try {
            String localKey = c == null ? "" : AiCredentials.load(c);
            String remoteKey = remote == null ? "" : remote.optString(K_API_KEY, "");
            LinkedJSONObject out = new LinkedJSONObject();
            out.put(K_API_KEY, TxtUtils.isNotEmpty(localKey) ? localKey : remoteKey);
            return out;
        } catch (Exception e) {
            return remote;
        }
    }

    // ------------------------------------------------------------------ simple-meta arrays (recent / favorite)

    /**
     * Union merge of two SimpleMeta arrays ([{"name":..,"path":..,"time":..}])
     * keyed by path; the newer entry wins. Order: newest first.
     */
    public static JSONArray mergeSimpleMetaArrays(JSONArray local, JSONArray remote) {
        try {
            JSONArray both = new JSONArray();
            appendAll(both, local);
            appendAll(both, remote);
            JSONArray out = new JSONArray();
            // selection sort by time desc (the arrays are small)
            for (int i = 0; i < both.length(); i++) {
                int best = i;
                for (int j = i + 1; j < both.length(); j++) {
                    if (both.optJSONObject(j).optLong("time", 0) > both.optJSONObject(best).optLong("time", 0)) {
                        best = j;
                    }
                }
                if (best != i) {
                    Object tmp = both.opt(i);
                    both.put(i, both.opt(best));
                    both.put(best, tmp);
                }
                LinkedJSONObject it = both.optJSONObject(best);
                String path = it == null ? "" : it.optString("path", "");
                boolean dup = false;
                for (int k = 0; k < out.length(); k++) {
                    if (path.equals(out.optJSONObject(k).optString("path", ""))) {
                        dup = true;
                        break;
                    }
                }
                if (!dup && it != null) {
                    out.put(it);
                }
            }
            return out;
        } catch (Exception e) {
            LOG.e(e);
            return local;
        }
    }

    /** Reads a profile SimpleMeta file ([{...}]); an empty array when missing. */
    public static JSONArray readSimpleMetaArray(File f) {
        InputStream is = null;
        try {
            if (f == null || !f.isFile()) {
                return new JSONArray();
            }
            is = new FileInputStream(f);
            String text = IO.readString(is);
            return new JSONArray(TxtUtils.isEmpty(text) ? "[]" : text);
        } catch (Exception e) {
            return new JSONArray();
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void appendAll(JSONArray dst, JSONArray src) {
        for (int i = 0; i < src.length(); i++) {
            try {
                dst.put(src.get(i));
            } catch (Exception ignored) {
            }
        }
    }
}

package com.foobnix.android.utils;

import android.text.Spanned;

import com.foobnix.model.MyPath;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

public class JsonDB {


    public static Spanned fromHtml(String db) {
        StringBuilder res = new StringBuilder();
        for (String item : get(db)) {
            //res.append(item.replace(MyPath.INTERNAL_ROOT, "...") + "<br>");
            res.append(item + "<br>");
        }
        String text = res.toString();
        text = TxtUtils.replaceLast(text, "<br>", "");
        return TxtUtils.fromHtml(text);
    }

    public static String set(List<String> list) {
        JSONArray array = new JSONArray();
        for (String s : list) {
            array.put(s);
        }
        return array.toString();
    }

    public static boolean contains(String db, String item) {
        return get(db).contains(item);
    }

    public static String add(String db, String line) {
        final List<String> list = get(db);
        list.add(line);
        return set(list);
    }

    public static String remove(String db, String line) {
        final List<String> list = get(db);
        list.remove(line);
        return set(list);
    }

    public static boolean isEmpty(String db) {
        return get(db).isEmpty();
    }

    public static List<String> get(String db) {
        try {

            List<String> res = new ArrayList<>();
            JSONArray array = TxtUtils.isEmpty(db) ? new JSONArray() : new JSONArray(db);
            for (int i = 0; i < array.length(); i++) {
                res.add(array.getString(i));
            }
            // keep the stored order: the list is persisted verbatim on every
            // add/remove, so re-sorting here silently reshuffled the user's
            // 书库文件夹 order on each round-trip
            return res;
        } catch (Exception e) {
            LOG.e(e);

        }
        // must be mutable: add()/remove() call list.add()/remove() on the
        // result and crashed with UnsupportedOperationException on the
        // previous immutable emptyList() when the stored JSON was corrupt
        return new ArrayList<>();
    }
}

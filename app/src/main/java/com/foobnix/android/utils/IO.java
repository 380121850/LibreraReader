package com.foobnix.android.utils;

import com.foobnix.mobi.parser.IOUtils;

import com.foobnix.LibreraApp;
import com.foobnix.pdf.info.AppsConfig;

import org.librera.JSONArray;
import org.librera.JSONException;
import org.librera.LinkedJSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;

public class IO {

    static HashMap<Integer, Object> locks = new HashMap<>();

    public static Object getLock(File file) {
        Object l = locks.get(file.hashCode());
        if (l == null) {
            l = new Object();
            locks.put(file.hashCode(), l);
        } else {
            LOG.d("mylock", file.getPath(), l);
        }
        return l;
    }


    public static void writeObj(File file, Object o) {
        //new Thread(() -> writeObjAsync(file, o), "@T writeObj").start();
        AppsConfig.executorServiceSingle.execute(() -> writeObjSync(file, o));
    }

    public static void writeObjSync(File file, Object o) {
        LOG.d("writeObjAsync", file.getPath());
        if (o instanceof LinkedJSONObject || o instanceof JSONArray) {
            LOG.d("writeObjAsync", "LinkedJSONObject");
            IO.writeString(file, o.toString());
        } else if (o instanceof String) {
            LOG.d("writeObjAsync", "String");
            IO.writeString(file, (String) o);
        } else {
            //LOG.d("writeObjAsync", "Class", o.getClass().getName());
            IO.writeString(file, Objects.toJSONString(o));
        }
    }

    public static void readObj(File file, Object o) {

        try {
            if (!file.exists()) {
                LOG.d("readObj not exsits", file.getPath());
                return;
            }

            Objects.loadFromJson(o, readString(file));

        } catch (Exception e) {
            LOG.e(e);
        }

    }


    public static LinkedJSONObject readJsonObject(File file) {

        final String s = readString(file);
        if (TxtUtils.isEmpty(s)) {
            return new LinkedJSONObject();
        }

        try {
            return new LinkedJSONObject(s);
        } catch (JSONException e) {
            return new LinkedJSONObject();
        }

    }

    /**
     * Single-slot content cache published as ONE immutable path+content pair.
     * The previous two volatile fields were updated in two separate steps and
     * could interleave between concurrent writers of different files, so a
     * reader got the NEW content under the OLD file name — one list (recent)
     * read the other's (favorite) content and wrote it back, destroying data.
     */
    private static volatile String[] cachePair = new String[]{null, null};

    public static String readString(File file) {
        return readString(file, false);
    }

    public static String readStringFromAsset(String assetName) throws IOException {
        InputStream open = LibreraApp.context.getAssets().open(assetName);
        return readString(open);
    }

    public static String readString(InputStream open) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IOUtils.copy(open, out);
        return out.toString().trim();
    }


    public static String readString(File file, boolean withSeparator) {
        final String path = file.getPath();
        final String[] cached = cachePair;
        if (path.equals(cached[0])) {
            LOG.d("lib-IO", "read cache", file);
            return cached[1];
        }
        synchronized (getLock(file)) {

            String content;
            try {
                if (!file.exists()) {
                    content = "";
                } else {
                    LOG.d("lib-IO", "read file", file);
                    StringBuilder builder = new StringBuilder();
                    String aux = "";
                    BufferedReader reader = new BufferedReader(new FileReader(file));
                    String separator = System.getProperty("line.separator");

                    while ((aux = reader.readLine()) != null) {
                        builder.append(aux);
                        if (withSeparator) {
                            builder.append(separator);
                        }
                    }
                    reader.close();
                    content = builder.toString();
                }
            } catch (Exception e) {
                LOG.e(e);
                content = "";
            }
            cachePair = new String[]{path, content};
            return content;
        }
    }

    public static boolean writeString(File file, String string) {

        synchronized (getLock(file)) {

            try {
                if (string == null) {
                    string = "";
                }
                LOG.d("lib-IO", "write file", file);
                new File(file.getParent()).mkdirs();

                OutputStream out = new BufferedOutputStream(new FileOutputStream(file));
                out.write(string.getBytes());
                out.flush();
                out.close();

                cachePair = new String[]{file.getPath(), string};

            } catch (Exception e) {
                LOG.e(e);
                return false;
            }
            return true;
        }
    }

    public static boolean copyFile(File from, File to) {
        try {
            new File(to.getParent()).mkdirs();

            InputStream input = new BufferedInputStream(new FileInputStream(from));
            OutputStream output = new BufferedOutputStream(new FileOutputStream(to));

            IOUtils.copyClose(input, output);

            LOG.d("Copy file form to", from, to);
        } catch (IOException e) {
            LOG.e(e);
            return false;
        }
        return true;
    }

    public static boolean copyFile(InputStream from, File to) {
        try {
            new File(to.getParent()).mkdirs();

            InputStream input = new BufferedInputStream(from);
            OutputStream output = new BufferedOutputStream(new FileOutputStream(to));

            IOUtils.copyClose(input, output);

            LOG.d("Copy file form to", from, to);
        } catch (IOException e) {
            LOG.e(e);
            return false;
        }
        return true;
    }
}

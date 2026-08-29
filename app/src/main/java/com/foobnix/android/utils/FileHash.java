package com.foobnix.android.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Content hash of a book file, built on the platform MessageDigest (MD5).
 * Used as the device-independent identity of a book by the WebDAV per-book
 * sync: the same physical book hashes identically on every device even when
 * the library paths differ.
 *
 * Results are cached per path+lastModified+length, so unchanged (multi-MB)
 * books are hashed only once; the cache is in-memory per process.
 */
public class FileHash {

    private static final int BUF = 8192;

    // path → {lastModified, length, hash}
    private static final Map<String, String[]> CACHE = new ConcurrentHashMap<String, String[]>();

    /** MD5 of the file content; "" when the file is missing or unreadable. */
    public static String md5(File f) {
        if (f == null || !f.isFile()) {
            return "";
        }
        final String key = f.getPath();
        final String[] c = CACHE.get(key);
        if (c != null && c.length == 3) {
            try {
                if (Long.parseLong(c[0]) == f.lastModified() && Long.parseLong(c[1]) == f.length()) {
                    return c[2];
                }
            } catch (NumberFormatException ignored) {
            }
        }
        final String hash = compute(f, "MD5");
        if (hash != null) {
            CACHE.put(key, new String[]{"" + f.lastModified(), "" + f.length(), hash});
            return hash;
        }
        return "";
    }

    /** MD5 of a short text (fallback identity for books whose file is not on this device). */
    public static String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return hex(md.digest(text.getBytes("UTF-8")));
        } catch (Exception e) {
            LOG.e(e);
            return "";
        }
    }

    private static String compute(File f, String algo) {
        InputStream in = null;
        try {
            final MessageDigest md = MessageDigest.getInstance(algo);
            in = new FileInputStream(f);
            final byte[] buf = new byte[BUF];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return hex(md.digest());
        } catch (Exception e) {
            LOG.e(e);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String hex(byte[] digest) {
        final StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}

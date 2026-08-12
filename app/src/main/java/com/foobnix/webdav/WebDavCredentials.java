package com.foobnix.webdav;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * WebDAV credentials, stored per server URL in SharedPreferences encrypted
 * with an AndroidKeyStore AES/GCM key. When the key is invalidated (e.g. after
 * a device lock change) decryption fails and the entry is treated as absent.
 */
public class WebDavCredentials {

    private static final String PREFS = "webdav";
    private static final String KEYSTORE_ALIAS = "webdav_creds";
    private static final String SPLIT = "\u001F";
    private static final int GCM_IV_LENGTH = 12;

    public static void save(Context c, String serverUrl, String login, String password) {
        if (c == null || TxtUtils.isEmpty(serverUrl)) {
            return;
        }
        try {
            byte[] data = encrypt(login + SPLIT + password);
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit().putString(WebDavStore.trimSlash(serverUrl), encode(data)).commit();
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** @return {login, password} or null when absent / undecryptable */
    public static String[] load(Context c, String serverUrl) {
        if (c == null || TxtUtils.isEmpty(serverUrl)) {
            return null;
        }
        String stored = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(WebDavStore.trimSlash(serverUrl), "");
        if (TxtUtils.isEmpty(stored)) {
            return null;
        }
        try {
            String plain = new String(decrypt(decode(stored)), "UTF-8");
            int i = plain.indexOf(SPLIT);
            if (i <= 0) {
                return null;
            }
            return new String[]{plain.substring(0, i), plain.substring(i + 1)};
        } catch (Exception e) {
            LOG.e(e);
            return null;
        }
    }

    public static void clear(Context c, String serverUrl) {
        if (c == null || serverUrl == null) {
            return;
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(WebDavStore.trimSlash(serverUrl)).commit();
    }

    private static byte[] encrypt(String plain) throws Exception {
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] data = cipher.doFinal(plain.getBytes("UTF-8"));
        byte[] out = new byte[iv.length + data.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(data, 0, out, iv.length, data.length);
        return out;
    }

    private static byte[] decrypt(byte[] bytes) throws Exception {
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, bytes, 0, GCM_IV_LENGTH));
        return cipher.doFinal(bytes, GCM_IV_LENGTH, bytes.length - GCM_IV_LENGTH);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(KEYSTORE_ALIAS, null);
        if (entry != null) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return kg.generateKey();
    }

    private static String encode(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    private static byte[] decode(String s) {
        return Base64.decode(s, Base64.NO_WRAP);
    }
}

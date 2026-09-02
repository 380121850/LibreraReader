package com.foobnix.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.foobnix.android.utils.LOG;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AI provider API key, stored in SharedPreferences encrypted with an
 * AndroidKeyStore AES/GCM key (same scheme as WebDavCredentials). The key is
 * deliberately kept out of app-State.json so profile backups never carry the
 * plain secret.
 */
public class AiCredentials {

    private static final String PREFS = "ai";
    private static final String PREF_KEY = "api_key";
    private static final String KEYSTORE_ALIAS = "ai_api_key";
    private static final int GCM_IV_LENGTH = 12;

    public static void save(Context c, String plainKey) {
        if (c == null) {
            return;
        }
        try {
            if (plainKey == null) {
                plainKey = "";
            }
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (plainKey.isEmpty()) {
                sp.edit().remove(PREF_KEY).commit();
                return;
            }
            byte[] data = encrypt(plainKey);
            sp.edit().putString(PREF_KEY, Base64.encodeToString(data, Base64.NO_WRAP)).commit();
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** @return the stored key, or "" when absent / undecryptable */
    public static String load(Context c) {
        if (c == null) {
            return "";
        }
        String stored = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_KEY, "");
        if (stored.isEmpty()) {
            return "";
        }
        try {
            byte[] bytes = Base64.decode(stored, Base64.NO_WRAP);
            return new String(decrypt(bytes), "UTF-8");
        } catch (Exception e) {
            LOG.e(e);
            return "";
        }
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
}

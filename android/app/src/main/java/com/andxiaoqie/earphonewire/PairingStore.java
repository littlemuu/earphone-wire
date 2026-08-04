package com.andxiaoqie.earphonewire;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores only a normalized origin and an Android Keystore-encrypted upload token. */
public final class PairingStore {
    private static final String PREFS = "secure_pairing";
    private static final String ORIGIN = "origin";
    private static final String TOKEN = "encrypted_token";
    private static final String KEY_ALIAS = "earphone_wire_upload_token_v1";
    private static final String LAST_UPLOAD_STATUS = "last_upload_status";
    private static final String LAST_UPLOAD_TIME = "last_upload_time";
    private final SharedPreferences preferences;

    public PairingStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void save(String origin, String token) throws Exception {
        String normalized = PairingOrigin.normalize(origin);
        if (token == null || token.isEmpty()) throw new IllegalArgumentException("Upload token is required.");
        preferences.edit().putString(ORIGIN, normalized).putString(TOKEN, encrypt(token)).apply();
    }

    public synchronized Pairing load() {
        String origin = preferences.getString(ORIGIN, null);
        String packed = preferences.getString(TOKEN, null);
        if (origin == null || packed == null) return null;
        try {
            return new Pairing(origin, decrypt(packed));
        } catch (Exception error) {
            clear();
            return null;
        }
    }

    public synchronized String savedOrigin() {
        return preferences.getString(ORIGIN, "");
    }

    public synchronized boolean isPaired() {
        return preferences.contains(ORIGIN) && preferences.contains(TOKEN);
    }

    public synchronized void clear() {
        preferences.edit().remove(ORIGIN).remove(TOKEN).remove(LAST_UPLOAD_STATUS).remove(LAST_UPLOAD_TIME).apply();
    }

    public void recordUploadResult(String status) {
        preferences.edit().putString(LAST_UPLOAD_STATUS, status)
                .putLong(LAST_UPLOAD_TIME, System.currentTimeMillis()).apply();
    }

    public String lastUploadStatus() {
        return preferences.getString(LAST_UPLOAD_STATUS, null);
    }

    public long lastUploadTime() {
        return preferences.getLong(LAST_UPLOAD_TIME, 0L);
    }

    private String encrypt(String plaintext) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        return Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    private String decrypt(String packed) throws Exception {
        byte[] combined = Base64.decode(packed, Base64.NO_WRAP);
        if (combined.length <= 12) throw new IllegalArgumentException("Invalid stored pairing.");
        byte[] iv = new byte[12];
        System.arraycopy(combined, 0, iv, 0, iv.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(combined, iv.length, combined.length - iv.length), StandardCharsets.UTF_8);
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey key = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (key != null) return key;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    public static final class Pairing {
        public final String origin;
        public final String token;

        Pairing(String origin, String token) {
            this.origin = origin;
            this.token = token;
        }
    }
}

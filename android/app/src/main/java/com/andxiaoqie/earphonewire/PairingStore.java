package com.andxiaoqie.earphonewire;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.KeyStore;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Stores only a normalized origin and an Android Keystore-encrypted upload token. */
public final class PairingStore implements NowPlayingReporter.PairingAccess {
    private static final String PREFS = "secure_pairing";
    private static final String ORIGIN = "origin";
    private static final String TOKEN = "encrypted_token";
    private static final String KEY_ALIAS = "earphone_wire_upload_token_v1";
    private static final String LAST_UPLOAD_STATUS = "last_upload_status";
    private static final String LAST_UPLOAD_TIME = "last_upload_time";
    private static final String REPAIR_REQUIRED = "repair_required";
    private static final String PAIRING_VERSION = "pairing_version";
    private final SharedPreferences preferences;

    public PairingStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void save(String origin, String token) throws Exception {
        String normalized = PairingOrigin.normalize(origin);
        if (token == null || token.isEmpty()) throw new IllegalArgumentException("必须输入上传令牌。");
        long version = nextVersion();
        saveAtomically(normalized, token, version, this::encrypt, (savedOrigin, packed, savedVersion) ->
                preferences.edit().putString(ORIGIN, savedOrigin).putString(TOKEN, packed)
                        .putBoolean(REPAIR_REQUIRED, false).putLong(PAIRING_VERSION, savedVersion).apply());
    }

    public synchronized Pairing load() {
        String origin = preferences.getString(ORIGIN, null);
        String packed = preferences.getString(TOKEN, null);
        if (origin == null || packed == null) return null;
        try {
            return new Pairing(origin, decrypt(packed), preferences.getLong(PAIRING_VERSION, 0L));
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

    public synchronized boolean requiresRePairing() {
        return preferences.getBoolean(REPAIR_REQUIRED, false);
    }

    @Override public synchronized Pairing loadForUpload() {
        return requiresRePairing() ? null : load();
    }

    @Override public synchronized boolean requireRePairing(Pairing expected) {
        if (expected == null || expected.version != preferences.getLong(PAIRING_VERSION, 0L)) {
            return false;
        }
        preferences.edit().putBoolean(REPAIR_REQUIRED, true).apply();
        return true;
    }

    public synchronized void clear() {
        preferences.edit().remove(ORIGIN).remove(TOKEN).remove(LAST_UPLOAD_STATUS)
                .remove(LAST_UPLOAD_TIME).remove(REPAIR_REQUIRED).putLong(PAIRING_VERSION, nextVersion()).apply();
    }

    @Override public void recordUploadResult(String status) {
        preferences.edit().putString(LAST_UPLOAD_STATUS, status)
                .putLong(LAST_UPLOAD_TIME, System.currentTimeMillis()).apply();
    }

    public String lastUploadStatus() {
        return preferences.getString(LAST_UPLOAD_STATUS, null);
    }

    public long lastUploadTime() {
        return preferences.getLong(LAST_UPLOAD_TIME, 0L);
    }

    private long nextVersion() {
        return preferences.getLong(PAIRING_VERSION, 0L) + 1L;
    }

    interface Encryptor { String encrypt(String plaintext) throws Exception; }
    interface PairingCommit { void commit(String origin, String packed, long version); }

    static void saveAtomically(String origin, String token, long version, Encryptor encryptor,
                               PairingCommit commit) throws Exception {
        String packed = encryptor.encrypt(token);
        commit.commit(origin, packed, version);
    }

    private String encrypt(String plaintext) throws Exception {
        return PairingCipher.encrypt(key(), plaintext, PairingCipher.jcaFactory());
    }

    private String decrypt(String packed) throws Exception {
        return PairingCipher.decrypt(key(), packed, PairingCipher.jcaFactory());
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
        public final long version;

        Pairing(String origin, String token, long version) {
            this.origin = origin;
            this.token = token;
            this.version = version;
        }
    }
}

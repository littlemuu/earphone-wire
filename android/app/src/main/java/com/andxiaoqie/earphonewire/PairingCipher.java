package com.andxiaoqie.earphonewire;

import android.util.Base64;

import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-GCM packing compatible with the persisted IV || ciphertext pairing format. */
final class PairingCipher {
    static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    interface Adapter {
        void initEncrypt(SecretKey key) throws Exception;
        byte[] getIV();
        byte[] doFinal(byte[] input) throws Exception;
        void initDecrypt(SecretKey key, byte[] iv) throws Exception;
    }

    interface AdapterFactory { Adapter create() throws Exception; }

    private PairingCipher() {
    }

    static String encrypt(SecretKey key, String plaintext, AdapterFactory factory) throws Exception {
        return Base64.encodeToString(encryptBytes(key, plaintext, factory), Base64.NO_WRAP);
    }

    static byte[] encryptBytes(SecretKey key, String plaintext, AdapterFactory factory) throws Exception {
        Adapter cipher = factory.create();
        // Do not provide an IV: Android Keystore owns randomized IV generation.
        cipher.initEncrypt(key);
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_BYTES) throw new IllegalStateException("Invalid generated IV.");
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
        return combined;
    }

    static String decrypt(SecretKey key, String packed, AdapterFactory factory) throws Exception {
        return decryptBytes(key, Base64.decode(packed, Base64.NO_WRAP), factory);
    }

    static String decryptBytes(SecretKey key, byte[] combined, AdapterFactory factory) throws Exception {
        if (combined.length <= IV_BYTES) throw new IllegalArgumentException("Invalid stored pairing.");
        byte[] iv = new byte[IV_BYTES];
        System.arraycopy(combined, 0, iv, 0, iv.length);
        Adapter cipher = factory.create();
        cipher.initDecrypt(key, iv);
        byte[] ciphertext = new byte[combined.length - iv.length];
        System.arraycopy(combined, iv.length, ciphertext, 0, ciphertext.length);
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    static AdapterFactory jcaFactory() {
        return JcaAdapter::new;
    }

    private static final class JcaAdapter implements Adapter {
        private final Cipher cipher;

        JcaAdapter() throws Exception {
            cipher = Cipher.getInstance("AES/GCM/NoPadding");
        }

        @Override public void initEncrypt(SecretKey key) throws Exception {
            cipher.init(Cipher.ENCRYPT_MODE, key);
        }

        @Override public byte[] getIV() {
            return cipher.getIV();
        }

        @Override public byte[] doFinal(byte[] input) throws Exception {
            return cipher.doFinal(input);
        }

        @Override public void initDecrypt(SecretKey key, byte[] iv) throws Exception {
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        }
    }
}

package com.andxiaoqie.earphonewire;

import org.junit.Test;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PairingCipherTest {
    private static final SecretKey TEST_KEY = new SecretKeySpec(new byte[32], "AES");

    @Test public void encryptionUsesTheCipherGeneratedIvAndPacksItBeforeCiphertext() throws Exception {
        RecordingAdapter cipher = new RecordingAdapter();
        byte[] packed = PairingCipher.encryptBytes(TEST_KEY, "sample", () -> cipher);

        assertTrue(cipher.encryptInitializedWithoutCallerIv);
        assertArrayEquals(cipher.generatedIv, Arrays.copyOf(packed, PairingCipher.IV_BYTES));
        assertEquals("sample", new String(Arrays.copyOfRange(packed, PairingCipher.IV_BYTES, packed.length)));
    }

    @Test public void aesGcmRoundTripsUsingTheStoredIvAndCiphertextFormat() throws Exception {
        byte[] packed = PairingCipher.encryptBytes(TEST_KEY, "sample", PairingCipher.jcaFactory());
        assertEquals("sample", PairingCipher.decryptBytes(TEST_KEY, packed, PairingCipher.jcaFactory()));
        assertEquals(PairingCipher.IV_BYTES, Arrays.copyOf(packed, PairingCipher.IV_BYTES).length);
    }

    @Test public void encryptionFailureDoesNotCommitAnyPairingFields() {
        AtomicBoolean committed = new AtomicBoolean(false);
        try {
            PairingStore.saveAtomically("https://relay.test", "sample", 7L,
                    plaintext -> { throw new GeneralSecurityException("test encryption failure"); },
                    (origin, packed, version) -> committed.set(true));
            throw new AssertionError("Expected encryption failure");
        } catch (Exception expected) {
            assertFalse(committed.get());
        }
    }

    @Test public void completeCiphertextCommitsAsOnePairingValueAfterSuccessfulEncryption() throws Exception {
        AtomicReference<String> committed = new AtomicReference<>();
        PairingStore.saveAtomically("https://relay.test", "sample", 8L,
                plaintext -> "complete-ciphertext",
                (origin, packed, version) -> committed.set(origin + "|" + packed + "|" + version));
        assertEquals("https://relay.test|complete-ciphertext|8", committed.get());
    }

    private static final class RecordingAdapter implements PairingCipher.Adapter {
        final byte[] generatedIv = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 };
        boolean encryptInitializedWithoutCallerIv;

        @Override public void initEncrypt(SecretKey key) {
            encryptInitializedWithoutCallerIv = true;
        }

        @Override public byte[] getIV() {
            return generatedIv;
        }

        @Override public byte[] doFinal(byte[] input) {
            return input;
        }

        @Override public void initDecrypt(SecretKey key, byte[] iv) {
            throw new UnsupportedOperationException("Not used by this test");
        }
    }
}

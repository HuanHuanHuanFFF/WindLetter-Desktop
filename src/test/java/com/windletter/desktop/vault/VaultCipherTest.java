package com.windletter.desktop.vault;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VaultCipherTest {

    private final VaultCipher cipher = new VaultCipher();
    private final VaultKdfParameters kdf = VaultKdfParameters.minimumSupported();

    @Test
    void shouldSealAndOpenBinaryPayload() throws Exception {
        char[] password = "correct horse battery staple".toCharArray();
        byte[] payload = "身份：風笺 · 𠮷 · \u0000".getBytes(StandardCharsets.UTF_8);
        byte[] envelope = null;
        byte[] opened = null;
        try {
            envelope = cipher.seal(payload, password, kdf);
            opened = cipher.open(envelope, password);

            assertArrayEquals(payload, opened);
            assertFalse(Arrays.equals(payload, envelope));
        } finally {
            clear(password);
            clear(payload);
            clear(opened);
            clear(envelope);
        }
    }

    @Test
    void shouldUseFreshSaltVaultIdAndNonceForEverySeal() throws Exception {
        char[] password = "correct horse battery staple".toCharArray();
        byte[] payload = new byte[]{0x01, 0x02, 0x03, 0x04};
        byte[] first = null;
        byte[] second = null;
        try {
            first = cipher.seal(payload, password, kdf);
            second = cipher.seal(payload, password, kdf);

            assertFalse(Arrays.equals(first, second));
        } finally {
            clear(password);
            clear(payload);
            clear(first);
            clear(second);
        }
    }

    @Test
    void shouldReturnSameGenericFailureForWrongPasswordTamperingAndMalformedInput() throws Exception {
        char[] password = "correct horse battery staple".toCharArray();
        char[] wrongPassword = "this password is definitely wrong".toCharArray();
        byte[] payload = new byte[]{0x11, 0x22, 0x33};
        byte[] envelope = null;
        byte[] tampered = null;
        try {
            envelope = cipher.seal(payload, password, kdf);
            tampered = envelope.clone();
            tampered[tampered.length - 1] ^= 0x01;
            byte[] sealedEnvelope = envelope;
            byte[] changedEnvelope = tampered;

            VaultOpenException wrong = assertThrows(
                VaultOpenException.class,
                () -> cipher.open(sealedEnvelope, wrongPassword)
            );
            VaultOpenException changed = assertThrows(
                VaultOpenException.class,
                () -> cipher.open(changedEnvelope, password)
            );
            VaultOpenException malformed = assertThrows(
                VaultOpenException.class,
                () -> cipher.open(new byte[]{0x01, 0x02}, password)
            );

            assertEquals(VaultOpenException.USER_MESSAGE, wrong.getMessage());
            assertEquals(wrong.getMessage(), changed.getMessage());
            assertEquals(wrong.getMessage(), malformed.getMessage());
            assertNull(wrong.getCause());
            assertNull(changed.getCause());
            assertNull(malformed.getCause());
        } finally {
            clear(password);
            clear(wrongPassword);
            clear(payload);
            clear(tampered);
            clear(envelope);
        }
    }

    @Test
    void shouldRejectUnsafeKdfParameters() {
        assertThrows(IllegalArgumentException.class, () -> new VaultKdfParameters(65535, 3, 1));
        assertThrows(IllegalArgumentException.class, () -> new VaultKdfParameters(65536, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new VaultKdfParameters(65536, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> new VaultKdfParameters(524289, 3, 1));
        assertThrows(IllegalArgumentException.class, () -> new VaultKdfParameters(65536, 11, 1));
        assertThrows(IllegalArgumentException.class, () -> new VaultKdfParameters(65536, 3, 5));
    }

    private static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }
}

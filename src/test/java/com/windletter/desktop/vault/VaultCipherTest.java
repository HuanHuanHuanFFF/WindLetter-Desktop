package com.windletter.desktop.vault;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultCipherTest {

    private final VaultCipher cipher = new VaultCipher();
    private final VaultKdfParameters kdf = VaultKdfParameters.minimumSupported();

    @Test
    void shouldSealAndOpenBinaryPayload() throws Exception {
        char[] password = "correct horse battery staple".toCharArray();
        byte[] vaultId = filled(16, (byte) 0x41);
        byte[] payload = "身份：風笺 · 𠮷 · \u0000".getBytes(StandardCharsets.UTF_8);
        byte[] envelope = null;
        try {
            envelope = cipher.seal(payload, vaultId, password, kdf);
            try (OpenedVault opened = cipher.open(envelope, password)) {
                byte[] openedVaultId = opened.vaultId();
                byte[] openedPlaintext = opened.plaintext();
                try {
                    assertArrayEquals(vaultId, openedVaultId);
                    assertArrayEquals(payload, openedPlaintext);
                } finally {
                    clear(openedPlaintext);
                    clear(openedVaultId);
                }

            }
            assertFalse(Arrays.equals(payload, envelope));
        } finally {
            clear(password);
            clear(vaultId);
            clear(payload);
            clear(envelope);
        }
    }

    @Test
    void shouldUseFreshSaltVaultIdAndNonceForEverySeal() throws Exception {
        char[] password = "correct horse battery staple".toCharArray();
        byte[] payload = new byte[]{0x01, 0x02, 0x03, 0x04};
        byte[] first = null;
        byte[] second = null;
        byte[] vaultId = filled(16, (byte) 0x51);
        try {
            first = cipher.seal(payload, vaultId, password, kdf);
            second = cipher.seal(payload, vaultId, password, kdf);

            assertFalse(Arrays.equals(first, second));
        } finally {
            clear(password);
            clear(vaultId);
            clear(payload);
            clear(first);
            clear(second);
        }
    }

    @Test
    void shouldClearOwnedPlaintextWhenOpenedVaultCloses() throws Exception {
        char[] password = "correct horse battery staple".toCharArray();
        byte[] vaultId = filled(16, (byte) 0x56);
        byte[] payload = filled(64, (byte) 0x57);
        byte[] envelope = null;
        OpenedVault opened = null;
        try {
            envelope = cipher.seal(payload, vaultId, password, kdf);
            opened = cipher.open(envelope, password);
            java.lang.reflect.Field field = OpenedVault.class
                .getDeclaredField("plaintext");
            field.setAccessible(true);
            byte[] ownedPlaintext = (byte[]) field.get(opened);

            opened.close();

            assertThrows(IllegalStateException.class, opened::plaintext);
            assertTrue(isAllZero(ownedPlaintext));
        } finally {
            if (opened != null) {
                opened.close();
            }
            clear(envelope);
            clear(payload);
            clear(vaultId);
            clear(password);
        }
    }

    @Test
    void shouldReturnSameGenericFailureForWrongPasswordTamperingAndMalformedInput() throws Exception {
        char[] password = "correct horse battery staple".toCharArray();
        char[] wrongPassword = "this password is definitely wrong".toCharArray();
        byte[] payload = new byte[]{0x11, 0x22, 0x33};
        byte[] vaultId = filled(16, (byte) 0x61);
        byte[] envelope = null;
        byte[] tampered = null;
        try {
            envelope = cipher.seal(payload, vaultId, password, kdf);
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
            clear(vaultId);
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

    private static byte[] filled(int length, byte value) {
        byte[] result = new byte[length];
        Arrays.fill(result, value);
        return result;
    }

    private static boolean isAllZero(byte[] value) {
        for (byte element : value) {
            if (element != 0) {
                return false;
            }
        }
        return true;
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

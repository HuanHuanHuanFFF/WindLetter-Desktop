package com.windletter.desktop.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VaultFileStoreTest {

    @TempDir
    Path directory;

    @Test
    void shouldCreateReplaceAndReadEncryptedEnvelopeAtomically() throws Exception {
        VaultFileStore store = new VaultFileStore();
        Path target = directory.resolve("vault.wlv");
        byte[] first = filled(128, (byte) 0x11);
        byte[] second = filled(256, (byte) 0x22);
        byte[] opened = null;
        try {
            store.writeAtomically(target, first);
            first[0] ^= 0x01;
            store.writeAtomically(target, second);
            opened = store.read(target);

            assertArrayEquals(second, opened);
            assertEquals(List.of("vault.wlv"), fileNames(directory));
        } finally {
            clear(opened);
            clear(second);
            clear(first);
        }
    }

    @Test
    void shouldPreserveOldVaultAndRemoveTempWhenAtomicMoveFails() throws Exception {
        Path target = directory.resolve("vault.wlv");
        byte[] oldEnvelope = filled(64, (byte) 0x31);
        byte[] newEnvelope = filled(64, (byte) 0x32);
        Files.write(target, oldEnvelope);
        VaultFileStore store = new VaultFileStore((source, destination) -> {
            throw new IOException("simulated move failure");
        });
        try {
            VaultWriteException failure = assertThrows(
                VaultWriteException.class,
                () -> store.writeAtomically(target, newEnvelope)
            );

            assertEquals(VaultWriteException.USER_MESSAGE, failure.getMessage());
            assertNull(failure.getCause());
            assertArrayEquals(oldEnvelope, Files.readAllBytes(target));
            assertEquals(List.of("vault.wlv"), fileNames(directory));
        } finally {
            clear(newEnvelope);
            clear(oldEnvelope);
        }
    }

    @Test
    void shouldCreateExactEncryptedBackupWithoutChangingSource() throws Exception {
        VaultFileStore store = new VaultFileStore();
        Path source = directory.resolve("vault.wlv");
        Path backup = directory.resolve("vault-backup.wlv");
        byte[] envelope = filled(512, (byte) 0x41);
        byte[] backupBytes = null;
        try {
            store.writeAtomically(source, envelope);
            store.copyAtomically(source, backup);
            backupBytes = store.read(backup);

            assertArrayEquals(envelope, backupBytes);
            assertTrueFileExists(source);
            assertTrueFileExists(backup);
        } finally {
            clear(backupBytes);
            clear(envelope);
        }
    }

    @Test
    void shouldReturnGenericFailureForMissingEmptyAndOversizedFiles() throws Exception {
        VaultFileStore store = new VaultFileStore();
        Path missing = directory.resolve("missing.wlv");
        Path empty = directory.resolve("empty.wlv");
        Path oversized = directory.resolve("oversized.wlv");
        Files.write(empty, new byte[0]);
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
            oversized,
            java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE
        )) {
            channel.position(VaultFileStore.MAX_ENVELOPE_BYTES);
            channel.write(java.nio.ByteBuffer.wrap(new byte[]{0x01}));
        }

        VaultOpenException missingFailure = assertThrows(
            VaultOpenException.class,
            () -> store.read(missing)
        );
        VaultOpenException emptyFailure = assertThrows(
            VaultOpenException.class,
            () -> store.read(empty)
        );
        VaultOpenException oversizedFailure = assertThrows(
            VaultOpenException.class,
            () -> store.read(oversized)
        );

        assertEquals(VaultOpenException.USER_MESSAGE, missingFailure.getMessage());
        assertEquals(missingFailure.getMessage(), emptyFailure.getMessage());
        assertEquals(missingFailure.getMessage(), oversizedFailure.getMessage());
        assertNull(missingFailure.getCause());
        assertNull(emptyFailure.getCause());
        assertNull(oversizedFailure.getCause());
    }

    private static List<String> fileNames(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();
        }
    }

    private static void assertTrueFileExists(Path path) {
        assertFalse(Files.notExists(path));
    }

    private static byte[] filled(int length, byte value) {
        byte[] result = new byte[length];
        Arrays.fill(result, value);
        return result;
    }

    private static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}

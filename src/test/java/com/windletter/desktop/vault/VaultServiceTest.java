package com.windletter.desktop.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-23T09:00:00Z");

    @TempDir
    Path directory;

    @Test
    void shouldCreateSaveWithoutRetainingPasswordAndReopenAfterLock() throws Exception {
        Path vaultPath = directory.resolve("vault.wlv");
        VaultService service = service(vaultPath);
        char[] password = "correct horse battery staple".toCharArray();
        byte[] vaultId = null;
        byte[] firstEnvelope = null;
        byte[] secondEnvelope = null;
        try {
            VaultSession created = service.create(password, 15);
            try {
                vaultId = created.payload().vaultId();
                assertTrue(created.payload().identities().isEmpty());
                assertTrue(created.payload().contacts().isEmpty());
                assertEquals(15, created.payload().settings().autoLockMinutes());
                firstEnvelope = Files.readAllBytes(vaultPath);

                Arrays.fill(password, '\0');
                service.save(created);
                secondEnvelope = Files.readAllBytes(vaultPath);
                assertFalse(Arrays.equals(firstEnvelope, secondEnvelope));
            } finally {
                created.close();
            }
            assertThrows(IllegalStateException.class, created::payload);

            password = "correct horse battery staple".toCharArray();
            try (VaultSession reopened = service.open(password)) {
                assertArrayEquals(vaultId, reopened.payload().vaultId());
                assertEquals(15, reopened.payload().settings().autoLockMinutes());
            }
        } finally {
            clear(secondEnvelope);
            clear(firstEnvelope);
            clear(vaultId);
            clear(password);
        }
    }

    @Test
    void shouldRejectWrongPasswordAndRefuseToOverwriteExistingVault() throws Exception {
        Path vaultPath = directory.resolve("vault.wlv");
        VaultService service = service(vaultPath);
        char[] password = "correct horse battery staple".toCharArray();
        char[] wrongPassword = "this is definitely the wrong password".toCharArray();
        byte[] before = null;
        try {
            try (VaultSession ignored = service.create(password, 15)) {
                before = Files.readAllBytes(vaultPath);
            }

            VaultOpenException wrong = assertThrows(
                VaultOpenException.class,
                () -> service.open(wrongPassword)
            );
            VaultWriteException existing = assertThrows(
                VaultWriteException.class,
                () -> service.create(password, 15)
            );

            assertEquals(VaultOpenException.USER_MESSAGE, wrong.getMessage());
            assertEquals(VaultWriteException.USER_MESSAGE, existing.getMessage());
            assertNull(wrong.getCause());
            assertNull(existing.getCause());
            assertArrayEquals(before, Files.readAllBytes(vaultPath));
        } finally {
            clear(before);
            clear(wrongPassword);
            clear(password);
        }
    }

    @Test
    void shouldValidateBackupBeforeReplacingCurrentVault() throws Exception {
        Path vaultPath = directory.resolve("vault.wlv");
        Path backupPath = directory.resolve("backup.wlv");
        Path damagedBackupPath = directory.resolve("damaged-backup.wlv");
        VaultService service = service(vaultPath);
        char[] password = "correct horse battery staple".toCharArray();
        byte[] currentBeforeFailure = null;
        byte[] validBackup = null;
        try {
            try (VaultSession ignored = service.create(password, 15)) {
                service.backup(backupPath);
            }
            validBackup = Files.readAllBytes(backupPath);
            byte[] damaged = validBackup.clone();
            damaged[damaged.length - 1] ^= 0x01;
            Files.write(damagedBackupPath, damaged);
            clear(damaged);
            currentBeforeFailure = Files.readAllBytes(vaultPath);

            assertThrows(
                VaultOpenException.class,
                () -> service.restore(damagedBackupPath, password)
            );
            assertArrayEquals(currentBeforeFailure, Files.readAllBytes(vaultPath));

            Files.write(vaultPath, new byte[]{0x01, 0x02, 0x03});
            try (VaultSession restored = service.restore(backupPath, password)) {
                assertTrue(restored.payload().identities().isEmpty());
            }
            assertArrayEquals(validBackup, Files.readAllBytes(vaultPath));
        } finally {
            clear(validBackup);
            clear(currentBeforeFailure);
            clear(password);
        }
    }

    @Test
    void shouldClearRetainedKekWhenSessionLocks() throws Exception {
        VaultService service = service(directory.resolve("vault.wlv"));
        char[] password = "correct horse battery staple".toCharArray();
        VaultSession session = null;
        try {
            session = service.create(password, 15);
            VaultSessionKey sessionKey = session.sessionKey();
            java.lang.reflect.Field field = VaultSessionKey.class
                .getDeclaredField("key");
            field.setAccessible(true);
            byte[] ownedKek = (byte[]) field.get(sessionKey);

            session.close();

            assertTrue(isAllZero(ownedKek));
            assertThrows(IllegalStateException.class, sessionKey::key);
        } finally {
            if (session != null) {
                session.close();
            }
            clear(password);
        }
    }

    @Test
    void shouldAcceptPasswordsFromEightTo256UnicodeCodePoints() throws Exception {
        char[] eightSupplementaryCodePoints = "😀".repeat(8).toCharArray();
        char[] twoHundredFiftySixCodePoints = "密".repeat(256).toCharArray();
        try {
            VaultService minimumService = service(
                directory.resolve("minimum-password-vault.wlv")
            );
            try (VaultSession ignored = minimumService.create(
                eightSupplementaryCodePoints,
                15
            )) {
                assertTrue(Files.exists(
                    directory.resolve("minimum-password-vault.wlv")
                ));
            }

            VaultService maximumService = service(
                directory.resolve("maximum-password-vault.wlv")
            );
            try (VaultSession ignored = maximumService.create(
                twoHundredFiftySixCodePoints,
                15
            )) {
                assertTrue(Files.exists(
                    directory.resolve("maximum-password-vault.wlv")
                ));
            }
        } finally {
            clear(twoHundredFiftySixCodePoints);
            clear(eightSupplementaryCodePoints);
        }
    }

    @Test
    void shouldEnforcePasswordPolicyButKeepOpenFailureGeneric() {
        VaultService service = service(directory.resolve("vault.wlv"));
        char[] shortPassword = "😀".repeat(7).toCharArray();
        char[] longPassword = "密".repeat(257).toCharArray();
        char[] nulPassword = "足够长的密码短语\u0000但有空字符".toCharArray();
        try {
            assertThrows(
                IllegalArgumentException.class,
                () -> service.create(shortPassword, 15)
            );
            assertThrows(
                IllegalArgumentException.class,
                () -> service.create(longPassword, 15)
            );
            assertThrows(
                IllegalArgumentException.class,
                () -> service.create(nulPassword, 15)
            );
            VaultOpenException openFailure = assertThrows(
                VaultOpenException.class,
                () -> service.open(shortPassword)
            );
            assertEquals(VaultOpenException.USER_MESSAGE, openFailure.getMessage());
            assertNull(openFailure.getCause());
        } finally {
            clear(nulPassword);
            clear(longPassword);
            clear(shortPassword);
        }
    }

    private static VaultService service(Path vaultPath) {
        return new VaultService(
            vaultPath,
            () -> new VaultKdfCalibration(
                VaultKdfParameters.minimumSupported(),
                1,
                VaultKdfCalibrator.DEFAULT_TARGET_MILLIS
            ),
            new SecureRandom(),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
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

    private static boolean isAllZero(byte[] value) {
        for (byte element : value) {
            if (element != 0) {
                return false;
            }
        }
        return true;
    }
}

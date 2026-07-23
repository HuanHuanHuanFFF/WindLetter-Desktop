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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopVaultTest {

    private static final Instant NOW = Instant.parse("2026-07-24T05:00:00Z");

    @TempDir
    Path directory;

    @Test
    void shouldExposeOnlySafeViewsAcrossIdentityAndContactFlows() throws Exception {
        Path vaultPath = directory.resolve("vault.wlv");
        DesktopVault desktopVault = desktopVault(vaultPath);
        char[] password = "correct horse battery staple".toCharArray();
        try (desktopVault) {
            assertFalse(desktopVault.exists());
            desktopVault.create(password, 15);
            assertCleared(password);
            assertTrue(desktopVault.exists());
            assertTrue(desktopVault.isUnlocked());

            UUID identityId = desktopVault.createIdentity("小岚", "仅本地身份备注");
            DesktopVault.Snapshot identitySnapshot = desktopVault.snapshot();
            assertEquals(identityId, identitySnapshot.defaultIdentityId());
            assertEquals(1, identitySnapshot.identities().size());
            DesktopVault.IdentityView identity = identitySnapshot.identities().get(0);
            assertEquals("小岚", identity.displayName());
            assertEquals("仅本地身份备注", identity.note());
            assertEquals(DesktopVault.IdentityOrigin.GENERATED, identity.origin());
            assertTrue(identity.defaultIdentity());
            assertThreeKidFingerprint(identity.fingerprint());

            String publicIdentity = desktopVault.exportPublicIdentity(identityId);
            assertTrue(publicIdentity.contains("\"displayName\":\"小岚\""));
            assertFalse(publicIdentity.contains("仅本地身份备注"));
            assertFalse(publicIdentity.contains("privateKey"));
            assertFalse(publicIdentity.contains(identityId.toString()));

            UUID contactId = desktopVault.importContact(publicIdentity);
            desktopVault.updateContact(
                contactId,
                "小岚（工作）",
                "线下核对",
                true
            );
            DesktopVault.ContactView contact = desktopVault.snapshot()
                .contacts()
                .get(0);
            assertEquals("小岚", contact.claimedDisplayName());
            assertEquals("小岚（工作）", contact.localDisplayName());
            assertEquals("小岚（工作）", contact.displayName());
            assertEquals("线下核对", contact.note());
            assertEquals(
                DesktopVault.ContactVerification.FINGERPRINT_VERIFIED,
                contact.verification()
            );
            assertThreeKidFingerprint(contact.fingerprint());

            desktopVault.updateIdentity(identityId, "小岚新名", "新备注");
            assertEquals(
                "小岚新名",
                desktopVault.snapshot().identities().get(0).displayName()
            );
            desktopVault.deleteContact(contactId);
            assertTrue(desktopVault.snapshot().contacts().isEmpty());
        }
    }

    @Test
    void shouldBackupInspectImportRestoreAndClearEveryPassword() throws Exception {
        Path sourcePath = directory.resolve("source.wlv");
        Path backupPath = directory.resolve("backup.wlv");
        char[] sourcePassword = "source correct horse battery".toCharArray();
        UUID sourceIdentityId;
        try (DesktopVault source = desktopVault(sourcePath)) {
            source.create(sourcePassword, 30);
            sourceIdentityId = source.createIdentity("来源身份", "随加密备份迁移");
            source.backup(backupPath);
        }
        assertCleared(sourcePassword);
        assertTrue(Files.isRegularFile(backupPath));

        char[] inspectPassword = "source correct horse battery".toCharArray();
        Path targetPath = directory.resolve("target.wlv");
        try (DesktopVault target = desktopVault(targetPath)) {
            char[] targetPassword = "target correct horse battery".toCharArray();
            target.create(targetPassword, 10);
            target.createIdentity("目标身份", null);

            var backupIdentities = target.inspectBackup(
                backupPath,
                inspectPassword
            );
            assertCleared(inspectPassword);
            assertEquals(1, backupIdentities.size());
            assertEquals(sourceIdentityId, backupIdentities.get(0).identityId());
            assertEquals("来源身份", backupIdentities.get(0).displayName());

            char[] importPassword = "source correct horse battery".toCharArray();
            UUID importedId = target.importIdentityFromBackup(
                backupPath,
                importPassword,
                sourceIdentityId
            );
            assertCleared(importPassword);
            assertEquals(2, target.snapshot().identities().size());
            DesktopVault.IdentityView imported = target.snapshot()
                .identities()
                .stream()
                .filter(candidate -> candidate.identityId().equals(importedId))
                .findFirst()
                .orElseThrow();
            assertEquals(DesktopVault.IdentityOrigin.IMPORTED, imported.origin());

            target.lock();
            char[] restorePassword = "source correct horse battery".toCharArray();
            target.restore(backupPath, restorePassword);
            assertCleared(restorePassword);
            DesktopVault.Snapshot restored = target.snapshot();
            assertEquals(30, restored.autoLockMinutes());
            assertEquals(1, restored.identities().size());
            assertEquals(sourceIdentityId, restored.identities().get(0).identityId());
        }
    }

    @Test
    void shouldExplainTheNewPasswordBoundaryAndClearRejectedInput() {
        char[] shortPassword = "😀".repeat(7).toCharArray();
        try (DesktopVault desktopVault = desktopVault(
            directory.resolve("vault.wlv")
        )) {
            DesktopVaultException failure = assertThrows(
                DesktopVaultException.class,
                () -> desktopVault.create(shortPassword, 15)
            );

            assertCleared(shortPassword);
            assertEquals(DesktopVaultProblem.INVALID_PASSWORD, failure.problem());
            assertEquals(
                "密码须包含 8–256 个 Unicode 字符。",
                failure.getMessage()
            );
            assertFalse(desktopVault.exists());
        }
    }

    @Test
    void shouldKeepOpenFailuresGenericAndRequireLockedRestore() throws Exception {
        Path vaultPath = directory.resolve("vault.wlv");
        Path backupPath = directory.resolve("backup.wlv");
        try (DesktopVault desktopVault = desktopVault(vaultPath)) {
            char[] password = "correct horse battery staple".toCharArray();
            desktopVault.create(password, 15);
            desktopVault.backup(backupPath);

            DesktopVaultException unlockedRestore = assertThrows(
                DesktopVaultException.class,
                () -> desktopVault.restore(
                    backupPath,
                    "correct horse battery staple".toCharArray()
                )
            );
            assertEquals(
                DesktopVaultProblem.LOCK_BEFORE_RESTORE,
                unlockedRestore.problem()
            );
            assertNull(unlockedRestore.getCause());

            desktopVault.lock();
            char[] wrongPassword = "definitely wrong password value".toCharArray();
            DesktopVaultException wrong = assertThrows(
                DesktopVaultException.class,
                () -> desktopVault.unlock(wrongPassword)
            );
            assertCleared(wrongPassword);
            assertEquals(DesktopVaultProblem.OPEN_FAILED, wrong.problem());
            assertEquals(
                "无法解锁保险库。请检查密码或备份文件。",
                wrong.getMessage()
            );
            assertNull(wrong.getCause());
            assertFalse(desktopVault.isUnlocked());
        }
    }

    private static DesktopVault desktopVault(Path path) {
        return new DesktopVault(
            path,
            () -> new VaultKdfCalibration(
                VaultKdfParameters.minimumSupported(),
                1,
                VaultKdfCalibrator.DEFAULT_TARGET_MILLIS
            ),
            new SecureRandom(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            new ScheduledVaultAutoLockScheduler()
        );
    }

    private static void assertThreeKidFingerprint(String fingerprint) {
        assertTrue(fingerprint.contains("X25519"));
        assertTrue(fingerprint.contains("ML-KEM-768"));
        assertTrue(fingerprint.contains("Ed25519"));
        assertEquals(3, fingerprint.lines().count());
    }

    private static void assertCleared(char[] value) {
        for (char element : value) {
            assertEquals('\0', element);
        }
    }
}

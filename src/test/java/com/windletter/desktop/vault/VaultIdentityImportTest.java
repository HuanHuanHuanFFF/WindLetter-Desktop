package com.windletter.desktop.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

class VaultIdentityImportTest {

    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");

    @TempDir
    Path directory;

    @Test
    void shouldImportOneIdentityFromEncryptedVaultBackupOnly() throws Exception {
        Path sourcePath = directory.resolve("source.wlv");
        Path backupPath = directory.resolve("source-backup.wlv");
        Path targetPath = directory.resolve("target.wlv");
        VaultService sourceService = service(sourcePath);
        VaultService targetService = service(targetPath);
        VaultIdentityManager sourceIdentities = manager(sourceService);
        VaultIdentityManager targetIdentities = manager(targetService);
        char[] sourcePassword = "source vault password phrase".toCharArray();
        char[] targetPassword = "target vault password phrase".toCharArray();
        UUID sourceIdentityId;
        try {
            try (VaultSession source = sourceService.create(sourcePassword, 15)) {
                sourceIdentityId = sourceIdentities.generateAndSave(
                    source,
                    "要迁移的身份",
                    "私有备注也随本人备份迁移"
                );
                sourceService.backup(backupPath);
            }
            String encryptedBackup = new String(
                Files.readAllBytes(backupPath),
                StandardCharsets.UTF_8
            );
            assertFalse(encryptedBackup.contains("要迁移的身份"));
            assertFalse(encryptedBackup.contains("私有备注也随本人备份迁移"));

            UUID importedId;
            try (VaultSession target = targetService.create(targetPassword, 15)) {
                importedId = targetIdentities.importFromVaultAndSave(
                    target,
                    backupPath,
                    sourcePassword,
                    sourceIdentityId
                );
                VaultIdentity imported = target.payload().identities().get(0);
                assertFalse(importedId.equals(sourceIdentityId));
                assertEquals(importedId, imported.identityId());
                assertEquals(VaultIdentityOrigin.IMPORTED, imported.origin());
                assertEquals("要迁移的身份", imported.displayName());
                assertEquals("私有备注也随本人备份迁移", imported.note());

                assertThrows(
                    IllegalArgumentException.class,
                    () -> targetIdentities.importFromVaultAndSave(
                        target,
                        backupPath,
                        sourcePassword,
                        sourceIdentityId
                    )
                );
                assertEquals(1, target.payload().identities().size());
            }

            try (VaultSession reopened = targetService.open(targetPassword)) {
                VaultIdentity imported = reopened.payload().identities().get(0);
                assertEquals(importedId, imported.identityId());
                assertEquals(VaultIdentityOrigin.IMPORTED, imported.origin());
                assertEquals(3, imported.keys().size());
            }
        } finally {
            clear(targetPassword);
            clear(sourcePassword);
        }
    }

    @Test
    void shouldNotChangeTargetWhenSourcePasswordIsWrong() throws Exception {
        Path sourcePath = directory.resolve("source.wlv");
        Path targetPath = directory.resolve("target.wlv");
        VaultService sourceService = service(sourcePath);
        VaultService targetService = service(targetPath);
        VaultIdentityManager sourceIdentities = manager(sourceService);
        VaultIdentityManager targetIdentities = manager(targetService);
        char[] sourcePassword = "source vault password phrase".toCharArray();
        char[] wrongPassword = "wrong source password phrase".toCharArray();
        char[] targetPassword = "target vault password phrase".toCharArray();
        try {
            UUID sourceIdentityId;
            try (VaultSession source = sourceService.create(sourcePassword, 15)) {
                sourceIdentityId = sourceIdentities.generateAndSave(
                    source,
                    "来源身份",
                    null
                );
            }
            try (VaultSession target = targetService.create(targetPassword, 15)) {
                assertThrows(
                    VaultOpenException.class,
                    () -> targetIdentities.importFromVaultAndSave(
                        target,
                        sourcePath,
                        wrongPassword,
                        sourceIdentityId
                    )
                );
                assertEquals(0, target.payload().identities().size());
            }
        } finally {
            clear(targetPassword);
            clear(wrongPassword);
            clear(sourcePassword);
        }
    }

    private static VaultIdentityManager manager(VaultService service) {
        return new VaultIdentityManager(
            service,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
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

    private static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }
}

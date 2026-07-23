package com.windletter.desktop.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultIdentityManagerTest {

    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");

    @TempDir
    Path directory;

    @Test
    void shouldGeneratePersistReopenSelectAndDeleteRealIdentity() throws Exception {
        VaultService service = service(directory.resolve("vault.wlv"));
        VaultIdentityManager identities = new VaultIdentityManager(
            service,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        char[] password = "correct horse battery staple".toCharArray();
        UUID firstId;
        UUID secondId;
        try {
            try (VaultSession session = service.create(password, 15)) {
                firstId = identities.generateAndSave(
                    session,
                    "日常身份",
                    "只在本机显示"
                );
                assertEquals(firstId, session.payload().settings().defaultIdentityId());
                assertEquals(1, session.payload().identities().size());
            }

            try (VaultSession reopened = service.open(password)) {
                VaultIdentity first = reopened.payload().identities().get(0);
                assertEquals(firstId, first.identityId());
                assertEquals("日常身份", first.displayName());
                assertEquals("只在本机显示", first.note());
                assertEquals(3, first.keys().size());
                assertTrue(first.keys().stream().allMatch(
                    key -> !isAllZero(key.privateKey())
                ));

                secondId = identities.generateAndSave(
                    reopened,
                    "工作身份",
                    null
                );
                identities.selectDefaultAndSave(reopened, secondId);
                assertEquals(
                    secondId,
                    reopened.payload().settings().defaultIdentityId()
                );

                identities.deleteAndSave(reopened, secondId);
                assertEquals(1, reopened.payload().identities().size());
                assertEquals(
                    firstId,
                    reopened.payload().settings().defaultIdentityId()
                );
            }

            try (VaultSession reopenedAgain = service.open(password)) {
                assertEquals(1, reopenedAgain.payload().identities().size());
                assertEquals(
                    firstId,
                    reopenedAgain.payload().identities().get(0).identityId()
                );
                assertEquals(
                    firstId,
                    reopenedAgain.payload().settings().defaultIdentityId()
                );
            }
        } finally {
            clear(password);
        }
    }

    @Test
    void shouldAllowDeletingLastIdentityAndClearDefault() throws Exception {
        VaultService service = service(directory.resolve("vault.wlv"));
        VaultIdentityManager identities = new VaultIdentityManager(
            service,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        char[] password = "correct horse battery staple".toCharArray();
        try {
            try (VaultSession session = service.create(password, 15)) {
                UUID identityId = identities.generateAndSave(
                    session,
                    "临时身份",
                    null
                );
                identities.deleteAndSave(session, identityId);

                assertTrue(session.payload().identities().isEmpty());
                assertNull(session.payload().settings().defaultIdentityId());
            }
            try (VaultSession reopened = service.open(password)) {
                assertTrue(reopened.payload().identities().isEmpty());
                assertNull(reopened.payload().settings().defaultIdentityId());
            }
        } finally {
            clear(password);
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

    private static boolean isAllZero(byte[] value) {
        try {
            for (byte element : value) {
                if (element != 0) {
                    return false;
                }
            }
            return true;
        } finally {
            clear(value);
        }
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

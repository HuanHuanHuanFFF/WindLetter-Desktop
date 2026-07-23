package com.windletter.desktop.vault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicIdentityContactManagerTest {

    private static final Instant NOW = Instant.parse("2026-07-23T11:00:00Z");

    @TempDir
    Path directory;

    @Test
    void shouldExportOnlyPublicIdentityThenImportAndManageContact() throws Exception {
        VaultService service = service(directory.resolve("vault.wlv"));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        VaultIdentityManager identities = new VaultIdentityManager(service, clock);
        PublicIdentityCodec publicIdentities = new PublicIdentityCodec();
        VaultContactManager contacts = new VaultContactManager(
            service,
            publicIdentities,
            clock
        );
        char[] password = "correct horse battery staple".toCharArray();
        String exported;
        UUID contactId;
        try {
            try (VaultSession session = service.create(password, 15)) {
                UUID identityId = identities.generateAndSave(
                    session,
                    "给朋友看的身份",
                    "绝不能公开的本地备注"
                );
                VaultIdentity identity = session.payload().identities().stream()
                    .filter(candidate -> candidate.identityId().equals(identityId))
                    .findFirst()
                    .orElseThrow();

                exported = publicIdentities.encode(identity);

                assertTrue(exported.contains("给朋友看的身份"));
                assertFalse(exported.contains("绝不能公开的本地备注"));
                assertFalse(exported.contains("privateKey"));
                assertFalse(exported.contains("identityId"));
                assertFalse(exported.contains("origin"));

                PublicIdentity decoded = publicIdentities.decode(exported);
                assertEquals("给朋友看的身份", decoded.displayName());
                assertEquals(3, decoded.publicKeys().size());

                contactId = contacts.importAndSave(session, exported);
                VaultContact contact = session.payload().contacts().get(0);
                assertEquals(contactId, contact.contactId());
                assertEquals("给朋友看的身份", contact.claimedDisplayName());
                assertNull(contact.localDisplayName());
                assertEquals(
                    VaultVerificationStatus.UNVERIFIED,
                    contact.verificationStatus()
                );

                contacts.updateNoteAndVerificationAndSave(
                    session,
                    contactId,
                    "线下核对过完整指纹",
                    VaultVerificationStatus.FINGERPRINT_VERIFIED
                );
                VaultContact updated = session.payload().contacts().get(0);
                assertNull(updated.localDisplayName());
                assertEquals("线下核对过完整指纹", updated.note());
                assertEquals(
                    VaultVerificationStatus.FINGERPRINT_VERIFIED,
                    updated.verificationStatus()
                );
                assertEquals(NOW, updated.verifiedAt());

                assertThrows(
                    IllegalArgumentException.class,
                    () -> contacts.importAndSave(session, exported)
                );
                assertNull(session.payload().contacts().get(0).localDisplayName());
            }

            try (VaultSession reopened = service.open(password)) {
                VaultContact contact = reopened.payload().contacts().get(0);
                assertEquals(contactId, contact.contactId());
                assertEquals("给朋友看的身份", contact.claimedDisplayName());
                assertNull(contact.localDisplayName());
                assertEquals(
                    VaultVerificationStatus.FINGERPRINT_VERIFIED,
                    contact.verificationStatus()
                );
                contacts.deleteAndSave(reopened, contactId);
                assertTrue(reopened.payload().contacts().isEmpty());
            }
            try (VaultSession reopenedAgain = service.open(password)) {
                assertTrue(reopenedAgain.payload().contacts().isEmpty());
            }
        } finally {
            clear(password);
        }
    }

    @Test
    void shouldRejectUnknownPrivateFieldsDuplicateAlgorithmsAndWrongKids() throws Exception {
        VaultService service = service(directory.resolve("vault.wlv"));
        VaultIdentityManager identities = new VaultIdentityManager(
            service,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        PublicIdentityCodec codec = new PublicIdentityCodec();
        ObjectMapper json = new ObjectMapper();
        char[] password = "correct horse battery staple".toCharArray();
        try {
            String exported;
            try (VaultSession session = service.create(password, 15)) {
                UUID identityId = identities.generateAndSave(session, "身份", null);
                VaultIdentity identity = session.payload().identities().stream()
                    .filter(candidate -> candidate.identityId().equals(identityId))
                    .findFirst()
                    .orElseThrow();
                exported = codec.encode(identity);
            }

            JsonNode privateFieldTree = json.readTree(exported);
            ((com.fasterxml.jackson.databind.node.ObjectNode) privateFieldTree
                .path("keys").path(0))
                .put("privateKey", "forbidden");

            JsonNode duplicateTree = json.readTree(exported);
            com.fasterxml.jackson.databind.node.ArrayNode duplicateKeys =
                (com.fasterxml.jackson.databind.node.ArrayNode) duplicateTree.path("keys");
            duplicateKeys.set(1, duplicateKeys.path(0).deepCopy());

            JsonNode wrongKidTree = json.readTree(exported);
            ((com.fasterxml.jackson.databind.node.ObjectNode) wrongKidTree
                .path("keys").path(2))
                .put(
                    "kid",
                    Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(new byte[32])
                );

            JsonNode wrongLengthTree = json.readTree(exported);
            ((com.fasterxml.jackson.databind.node.ObjectNode) wrongLengthTree
                .path("keys").path(0))
                .put(
                    "publicKey",
                    Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(new byte[31])
                );

            JsonNode paddedTree = json.readTree(exported);
            com.fasterxml.jackson.databind.node.ObjectNode paddedKey =
                (com.fasterxml.jackson.databind.node.ObjectNode) paddedTree
                    .path("keys").path(0);
            paddedKey.put("kid", paddedKey.path("kid").asText() + "=");

            String duplicateField = exported.replaceFirst(
                "\"version\":1",
                "\"version\":1,\"version\":1"
            );

            assertGenericFailure(codec, json.writeValueAsString(privateFieldTree));
            assertGenericFailure(codec, json.writeValueAsString(duplicateTree));
            assertGenericFailure(codec, json.writeValueAsString(wrongKidTree));
            assertGenericFailure(codec, json.writeValueAsString(wrongLengthTree));
            assertGenericFailure(codec, json.writeValueAsString(paddedTree));
            assertGenericFailure(codec, duplicateField);
        } finally {
            clear(password);
        }
    }

    private static void assertGenericFailure(
        PublicIdentityCodec codec,
        String input
    ) {
        PublicIdentityException failure = assertThrows(
            PublicIdentityException.class,
            () -> codec.decode(input)
        );
        assertEquals(PublicIdentityException.USER_MESSAGE, failure.getMessage());
        assertNull(failure.getCause());
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

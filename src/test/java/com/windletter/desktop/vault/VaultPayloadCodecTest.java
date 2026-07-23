package com.windletter.desktop.vault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultPayloadCodecTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-23T01:02:03Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-07-23T04:05:06Z");

    private final VaultPayloadCodec codec = new VaultPayloadCodec();
    private final ObjectMapper rawCbor = new ObjectMapper(new CBORFactory());

    @Test
    void shouldRoundTripStrictPayloadWithBinaryPrivateKeys() throws Exception {
        byte[] vaultId = filled(16, (byte) 0x11);
        byte[] encoded = null;
        try (VaultPayload original = payload(vaultId)) {
            encoded = codec.encode(original);

            JsonNode tree = rawCbor.readTree(encoded);
            JsonNode privateKey = tree.path("identities")
                .path(0)
                .path("keys")
                .path(0)
                .path("privateKey");
            assertTrue(privateKey.isBinary());
            assertFalse(privateKey.isTextual());

            try (VaultPayload decoded = codec.decode(encoded, vaultId)) {
                assertArrayEquals(vaultId, decoded.vaultId());
                assertEquals("風笺 · 𠮷", decoded.identities().get(0).displayName());
                assertEquals("仅本地备注", decoded.identities().get(0).note());
                assertEquals(3, decoded.identities().get(0).keys().size());
                assertEquals(1, decoded.contacts().size());
                assertEquals(15, decoded.settings().autoLockMinutes());
            }
        } finally {
            clear(encoded);
            clear(vaultId);
        }
    }

    @Test
    void shouldRejectMismatchedVaultIdWithGenericFailure() throws Exception {
        byte[] vaultId = filled(16, (byte) 0x21);
        byte[] otherVaultId = filled(16, (byte) 0x22);
        byte[] encoded = null;
        try (VaultPayload original = payload(vaultId)) {
            encoded = codec.encode(original);
            byte[] sealed = encoded;

            VaultPayloadException failure = assertThrows(
                VaultPayloadException.class,
                () -> codec.decode(sealed, otherVaultId)
            );

            assertEquals(VaultPayloadException.USER_MESSAGE, failure.getMessage());
            assertNull(failure.getCause());
        } finally {
            clear(encoded);
            clear(otherVaultId);
            clear(vaultId);
        }
    }

    @Test
    void shouldRejectDuplicateAlgorithmsUnknownFieldsAndTrailingData() throws Exception {
        byte[] vaultId = filled(16, (byte) 0x31);
        byte[] encoded = null;
        byte[] duplicateAlgorithm = null;
        byte[] unknownField = null;
        byte[] trailing = null;
        try (VaultPayload original = payload(vaultId)) {
            encoded = codec.encode(original);

            JsonNode duplicateTree = rawCbor.readTree(encoded);
            JsonNode firstKey = duplicateTree.path("identities").path(0).path("keys").path(0);
            ((com.fasterxml.jackson.databind.node.ArrayNode) duplicateTree
                .path("identities").path(0).path("keys"))
                .set(1, firstKey.deepCopy());
            duplicateAlgorithm = rawCbor.writeValueAsBytes(duplicateTree);

            JsonNode unknownTree = rawCbor.readTree(encoded);
            ((com.fasterxml.jackson.databind.node.ObjectNode) unknownTree)
                .put("unexpected", true);
            unknownField = rawCbor.writeValueAsBytes(unknownTree);

            trailing = Arrays.copyOf(encoded, encoded.length + 1);
            trailing[trailing.length - 1] = (byte) 0xf6;

            byte[] duplicateInput = duplicateAlgorithm;
            byte[] unknownInput = unknownField;
            byte[] trailingInput = trailing;
            assertThrows(
                VaultPayloadException.class,
                () -> codec.decode(duplicateInput, vaultId)
            );
            assertThrows(
                VaultPayloadException.class,
                () -> codec.decode(unknownInput, vaultId)
            );
            assertThrows(
                VaultPayloadException.class,
                () -> codec.decode(trailingInput, vaultId)
            );
        } finally {
            clear(trailing);
            clear(unknownField);
            clear(duplicateAlgorithm);
            clear(encoded);
            clear(vaultId);
        }
    }

    @Test
    void shouldRejectTextPrivateKeyWrongLengthsAndCollectionOverflow() throws Exception {
        byte[] vaultId = filled(16, (byte) 0x35);
        byte[] encoded = null;
        byte[] textPrivateKey = null;
        byte[] wrongLength = null;
        byte[] tooManyIdentities = null;
        try (VaultPayload original = payload(vaultId)) {
            encoded = codec.encode(original);

            JsonNode textTree = rawCbor.readTree(encoded);
            ((com.fasterxml.jackson.databind.node.ObjectNode) textTree
                .path("identities").path(0).path("keys").path(0))
                .put("privateKey", "not-a-CBOR-byte-string");
            textPrivateKey = rawCbor.writeValueAsBytes(textTree);

            JsonNode lengthTree = rawCbor.readTree(encoded);
            ((com.fasterxml.jackson.databind.node.ObjectNode) lengthTree
                .path("contacts").path(0).path("publicKeys").path(0))
                .put("publicKey", filled(31, (byte) 0x44));
            wrongLength = rawCbor.writeValueAsBytes(lengthTree);

            JsonNode overflowTree = rawCbor.readTree(encoded);
            com.fasterxml.jackson.databind.node.ArrayNode identities =
                (com.fasterxml.jackson.databind.node.ArrayNode) overflowTree.path("identities");
            JsonNode identity = identities.path(0).deepCopy();
            while (identities.size() <= VaultModelChecks.MAX_IDENTITIES) {
                identities.add(identity.deepCopy());
            }
            tooManyIdentities = rawCbor.writeValueAsBytes(overflowTree);

            assertGenericFailure(textPrivateKey, vaultId);
            assertGenericFailure(wrongLength, vaultId);
            assertGenericFailure(tooManyIdentities, vaultId);
        } finally {
            clear(tooManyIdentities);
            clear(wrongLength);
            clear(textPrivateKey);
            clear(encoded);
            clear(vaultId);
        }
    }

    @Test
    void shouldClearOwnedPrivateKeyStateWhenLocked() throws Exception {
        byte[] vaultId = filled(16, (byte) 0x41);
        VaultPayload payload = payload(vaultId);
        VaultPrivateKey x25519 = payload.identities().get(0).keys().get(0);
        byte[] callerCopy = x25519.privateKey();
        java.lang.reflect.Field field = VaultPrivateKey.class
            .getDeclaredField("privateKey");
        field.setAccessible(true);
        byte[] ownedState = (byte[]) field.get(x25519);

        payload.close();

        assertThrows(IllegalStateException.class, x25519::privateKey);
        assertTrue(isAllZero(ownedState));
        assertFalse(isAllZero(callerCopy));
        clear(callerCopy);
        clear(vaultId);
    }

    private static VaultPayload payload(byte[] vaultId) {
        UUID identityId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        VaultIdentity identity = new VaultIdentity(
            identityId,
            "風笺 · 𠮷",
            "仅本地备注",
            VaultIdentityOrigin.GENERATED,
            CREATED_AT,
            UPDATED_AT,
            List.of(
                privateKey(VaultKeyAlgorithm.X25519, (byte) 0x01),
                privateKey(VaultKeyAlgorithm.ML_KEM_768, (byte) 0x02),
                privateKey(VaultKeyAlgorithm.ED25519, (byte) 0x03)
            )
        );
        VaultContact contact = new VaultContact(
            UUID.fromString("11111111-2222-3333-4444-555555555555"),
            "对方自述名称",
            "我的联系人名称",
            "核验备注",
            VaultVerificationStatus.FINGERPRINT_VERIFIED,
            CREATED_AT,
            CREATED_AT,
            UPDATED_AT,
            List.of(
                publicKey(VaultKeyAlgorithm.X25519, (byte) 0x11),
                publicKey(VaultKeyAlgorithm.ML_KEM_768, (byte) 0x12),
                publicKey(VaultKeyAlgorithm.ED25519, (byte) 0x13)
            )
        );
        return new VaultPayload(
            vaultId,
            CREATED_AT,
            UPDATED_AT,
            List.of(identity),
            List.of(contact),
            new VaultSettings(identityId, 15)
        );
    }

    private static VaultPrivateKey privateKey(
        VaultKeyAlgorithm algorithm,
        byte value
    ) {
        return new VaultPrivateKey(
            algorithm,
            filled(32, value),
            filled(algorithm.publicKeyBytes(), (byte) (value + 1)),
            filled(algorithm.privateKeyBytes(), (byte) (value + 2))
        );
    }

    private static VaultPublicKey publicKey(
        VaultKeyAlgorithm algorithm,
        byte value
    ) {
        return new VaultPublicKey(
            algorithm,
            filled(32, value),
            filled(algorithm.publicKeyBytes(), (byte) (value + 1))
        );
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

    private void assertGenericFailure(byte[] encoded, byte[] vaultId) {
        VaultPayloadException failure = assertThrows(
            VaultPayloadException.class,
            () -> codec.decode(encoded, vaultId)
        );
        assertEquals(VaultPayloadException.USER_MESSAGE, failure.getMessage());
        assertNull(failure.getCause());
    }

    private static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}

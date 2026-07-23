package com.windletter.desktop.vault;

import com.windletter.crypto.api.Ed25519PrivateKeyHandle;
import com.windletter.crypto.api.MLKem768PrivateKeyHandle;
import com.windletter.crypto.api.X25519PrivateKeyHandle;
import com.windletter.crypto.bc.BouncyCastleEd25519Crypto;
import com.windletter.crypto.bc.BouncyCastleMLKem768Crypto;
import com.windletter.crypto.bc.BouncyCastleX25519Crypto;
import com.windletter.protocol.key.Ed25519KeyId;
import com.windletter.protocol.key.MLKem768KeyId;
import com.windletter.protocol.key.X25519KeyId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VaultKeyMaterialValidatorTest {

    private static final Instant NOW = Instant.parse("2026-07-23T08:00:00Z");

    private final VaultKeyMaterialValidator validator = new VaultKeyMaterialValidator();

    @Test
    void shouldValidateRealCorePrivateKeysAndContactKidsTwice() throws Exception {
        try (VaultPayload payload = payload(false, false)) {
            validator.validate(payload);
            validator.validate(payload);
        }
    }

    @Test
    void shouldRejectPrivateKeyWhoseStoredPublicKeyDoesNotMatch() throws Exception {
        try (VaultPayload payload = payload(true, false)) {
            assertGenericFailure(payload);
        }
    }

    @Test
    void shouldRejectContactWhoseKidDoesNotMatchPublicKey() throws Exception {
        try (VaultPayload payload = payload(false, true)) {
            assertGenericFailure(payload);
        }
    }

    private void assertGenericFailure(VaultPayload payload) {
        VaultPayloadException failure = assertThrows(
            VaultPayloadException.class,
            () -> validator.validate(payload)
        );
        assertEquals(VaultPayloadException.USER_MESSAGE, failure.getMessage());
        assertNull(failure.getCause());
    }

    private static VaultPayload payload(
        boolean mismatchedPrivatePublic,
        boolean mismatchedContactKid
    ) {
        UUID identityId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        List<VaultPrivateKey> keys = generatedKeys(mismatchedPrivatePublic);
        VaultIdentity identity = new VaultIdentity(
            identityId,
            "核心校验身份",
            null,
            VaultIdentityOrigin.GENERATED,
            NOW,
            NOW,
            keys
        );
        VaultContact contact = new VaultContact(
            UUID.fromString("11111111-2222-3333-4444-555555555555"),
            "联系人",
            null,
            null,
            VaultVerificationStatus.UNVERIFIED,
            null,
            NOW,
            NOW,
            publicKeys(keys, mismatchedContactKid)
        );
        return new VaultPayload(
            filled(16, (byte) 0x61),
            NOW,
            NOW,
            List.of(identity),
            List.of(contact),
            new VaultSettings(identityId, 15)
        );
    }

    private static List<VaultPrivateKey> generatedKeys(
        boolean mismatchedPrivatePublic
    ) {
        List<VaultPrivateKey> keys = new ArrayList<>();
        keys.add(generateX25519(mismatchedPrivatePublic));
        keys.add(generateMlKem768());
        keys.add(generateEd25519());
        return keys;
    }

    private static VaultPrivateKey generateX25519(boolean mismatchPublic) {
        BouncyCastleX25519Crypto crypto = new BouncyCastleX25519Crypto();
        byte[] privateKey = null;
        byte[] publicKey = null;
        byte[] kid = null;
        try (X25519PrivateKeyHandle handle = crypto.generatePrivateKey()) {
            privateKey = crypto.exportPrivateKey(handle);
            publicKey = handle.publicKey();
            if (mismatchPublic) {
                publicKey[0] ^= 0x01;
            }
            kid = decodeKid(X25519KeyId.derive(publicKey));
            return new VaultPrivateKey(
                VaultKeyAlgorithm.X25519,
                kid,
                publicKey,
                privateKey
            );
        } finally {
            clear(kid);
            clear(publicKey);
            clear(privateKey);
        }
    }

    private static VaultPrivateKey generateMlKem768() {
        BouncyCastleMLKem768Crypto crypto = new BouncyCastleMLKem768Crypto();
        byte[] privateKey = null;
        byte[] publicKey = null;
        byte[] kid = null;
        try (MLKem768PrivateKeyHandle handle = crypto.generatePrivateKey()) {
            privateKey = crypto.exportPrivateKey(handle);
            publicKey = handle.publicKey();
            kid = decodeKid(MLKem768KeyId.derive(publicKey));
            return new VaultPrivateKey(
                VaultKeyAlgorithm.ML_KEM_768,
                kid,
                publicKey,
                privateKey
            );
        } finally {
            clear(kid);
            clear(publicKey);
            clear(privateKey);
        }
    }

    private static VaultPrivateKey generateEd25519() {
        BouncyCastleEd25519Crypto crypto = new BouncyCastleEd25519Crypto();
        byte[] privateKey = null;
        byte[] publicKey = null;
        byte[] kid = null;
        try (Ed25519PrivateKeyHandle handle = crypto.generatePrivateKey()) {
            privateKey = crypto.exportPrivateKey(handle);
            publicKey = handle.publicKey();
            kid = decodeKid(Ed25519KeyId.derive(publicKey));
            return new VaultPrivateKey(
                VaultKeyAlgorithm.ED25519,
                kid,
                publicKey,
                privateKey
            );
        } finally {
            clear(kid);
            clear(publicKey);
            clear(privateKey);
        }
    }

    private static List<VaultPublicKey> publicKeys(
        List<VaultPrivateKey> privateKeys,
        boolean mismatchEd25519Kid
    ) {
        List<VaultPublicKey> result = new ArrayList<>();
        for (VaultPrivateKey privateKey : privateKeys) {
            byte[] kid = privateKey.kid();
            byte[] publicKey = privateKey.publicKey();
            try {
                if (mismatchEd25519Kid
                    && privateKey.algorithm() == VaultKeyAlgorithm.ED25519) {
                    kid[0] ^= 0x01;
                }
                result.add(new VaultPublicKey(
                    privateKey.algorithm(),
                    kid,
                    publicKey
                ));
            } finally {
                clear(publicKey);
                clear(kid);
            }
        }
        return result;
    }

    private static byte[] decodeKid(String kid) {
        return Base64.getUrlDecoder().decode(kid);
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

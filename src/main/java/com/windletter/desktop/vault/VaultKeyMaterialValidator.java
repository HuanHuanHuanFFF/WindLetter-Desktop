package com.windletter.desktop.vault;

import com.windletter.crypto.api.Ed25519Crypto;
import com.windletter.crypto.api.Ed25519PrivateKeyHandle;
import com.windletter.crypto.api.MLKem768Crypto;
import com.windletter.crypto.api.MLKem768PrivateKeyHandle;
import com.windletter.crypto.api.X25519Crypto;
import com.windletter.crypto.api.X25519PrivateKeyHandle;
import com.windletter.crypto.bc.BouncyCastleEd25519Crypto;
import com.windletter.crypto.bc.BouncyCastleMLKem768Crypto;
import com.windletter.crypto.bc.BouncyCastleX25519Crypto;
import com.windletter.protocol.key.Ed25519KeyId;
import com.windletter.protocol.key.MLKem768KeyId;
import com.windletter.protocol.key.X25519KeyId;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * Re-imports every persisted private key through the real core provider and
 * re-derives every KID through the core protocol API.
 */
final class VaultKeyMaterialValidator {

    private final X25519Crypto x25519;
    private final MLKem768Crypto mlKem768;
    private final Ed25519Crypto ed25519;

    VaultKeyMaterialValidator() {
        this(
            new BouncyCastleX25519Crypto(),
            new BouncyCastleMLKem768Crypto(),
            new BouncyCastleEd25519Crypto()
        );
    }

    VaultKeyMaterialValidator(
        X25519Crypto x25519,
        MLKem768Crypto mlKem768,
        Ed25519Crypto ed25519
    ) {
        this.x25519 = Objects.requireNonNull(x25519, "x25519");
        this.mlKem768 = Objects.requireNonNull(mlKem768, "mlKem768");
        this.ed25519 = Objects.requireNonNull(ed25519, "ed25519");
    }

    void validate(VaultPayload payload) throws VaultPayloadException {
        Objects.requireNonNull(payload, "payload");
        try {
            for (VaultIdentity identity : payload.identities()) {
                for (VaultPrivateKey key : identity.keys()) {
                    validatePrivateKey(key);
                }
            }
            for (VaultContact contact : payload.contacts()) {
                for (VaultPublicKey key : contact.publicKeys()) {
                    validatePublicKey(key);
                }
            }
        } catch (RuntimeException failure) {
            throw new VaultPayloadException();
        }
    }

    private void validatePrivateKey(VaultPrivateKey key) {
        byte[] privateKey = null;
        byte[] storedPublicKey = null;
        byte[] storedKid = null;
        byte[] derivedPublicKey = null;
        byte[] derivedKid = null;
        try {
            privateKey = key.privateKey();
            storedPublicKey = key.publicKey();
            storedKid = key.kid();
            derivedPublicKey = importAndReadPublicKey(key.algorithm(), privateKey);
            derivedKid = deriveKid(key.algorithm(), derivedPublicKey);

            if (!MessageDigest.isEqual(storedPublicKey, derivedPublicKey)
                || !MessageDigest.isEqual(storedKid, derivedKid)) {
                throw new IllegalArgumentException(
                    "persisted private-key material is inconsistent"
                );
            }
        } finally {
            clear(derivedKid);
            clear(derivedPublicKey);
            clear(storedKid);
            clear(storedPublicKey);
            clear(privateKey);
        }
    }

    private void validatePublicKey(VaultPublicKey key) {
        byte[] publicKey = null;
        byte[] storedKid = null;
        byte[] derivedKid = null;
        try {
            publicKey = key.publicKey();
            storedKid = key.kid();
            derivedKid = deriveKid(key.algorithm(), publicKey);
            if (!MessageDigest.isEqual(storedKid, derivedKid)) {
                throw new IllegalArgumentException(
                    "persisted public-key material is inconsistent"
                );
            }
        } finally {
            clear(derivedKid);
            clear(storedKid);
            clear(publicKey);
        }
    }

    private byte[] importAndReadPublicKey(
        VaultKeyAlgorithm algorithm,
        byte[] privateKey
    ) {
        return switch (algorithm) {
            case X25519 -> importX25519(privateKey);
            case ML_KEM_768 -> importMlKem768(privateKey);
            case ED25519 -> importEd25519(privateKey);
        };
    }

    private byte[] importX25519(byte[] privateKey) {
        try (X25519PrivateKeyHandle handle = x25519.importPrivateKey(privateKey)) {
            return handle.publicKey();
        }
    }

    private byte[] importMlKem768(byte[] privateKey) {
        try (MLKem768PrivateKeyHandle handle = mlKem768.importPrivateKey(privateKey)) {
            return handle.publicKey();
        }
    }

    private byte[] importEd25519(byte[] privateKey) {
        try (Ed25519PrivateKeyHandle handle = ed25519.importPrivateKey(privateKey)) {
            return handle.publicKey();
        }
    }

    private static byte[] deriveKid(
        VaultKeyAlgorithm algorithm,
        byte[] publicKey
    ) {
        String encoded = switch (algorithm) {
            case X25519 -> X25519KeyId.derive(publicKey);
            case ML_KEM_768 -> MLKem768KeyId.derive(publicKey);
            case ED25519 -> Ed25519KeyId.derive(publicKey);
        };
        return Base64.getUrlDecoder().decode(encoded);
    }

    private static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}

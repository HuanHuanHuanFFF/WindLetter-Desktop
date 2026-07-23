package com.windletter.desktop.vault;

import java.util.Arrays;
import java.util.Objects;

/** Owned private-key bytes. Accessors return caller-owned defensive copies. */
final class VaultPrivateKey implements AutoCloseable, VaultModelChecks.AlgorithmKey {

    private final VaultKeyAlgorithm algorithm;
    private final byte[] kid;
    private final byte[] publicKey;
    private final byte[] privateKey;
    private boolean closed;

    VaultPrivateKey(
        VaultKeyAlgorithm algorithm,
        byte[] kid,
        byte[] publicKey,
        byte[] privateKey
    ) {
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
        VaultModelChecks.requireExact(
            kid,
            VaultModelChecks.KID_BYTES,
            "kid"
        );
        VaultModelChecks.requireExact(
            publicKey,
            algorithm.publicKeyBytes(),
            "publicKey"
        );
        VaultModelChecks.requireExact(
            privateKey,
            algorithm.privateKeyBytes(),
            "privateKey"
        );
        this.kid = kid.clone();
        this.publicKey = publicKey.clone();
        this.privateKey = privateKey.clone();
    }

    @Override
    public VaultKeyAlgorithm algorithm() {
        return algorithm;
    }

    byte[] kid() {
        ensureOpen();
        return kid.clone();
    }

    byte[] publicKey() {
        ensureOpen();
        return publicKey.clone();
    }

    byte[] privateKey() {
        ensureOpen();
        return privateKey.clone();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            Arrays.fill(privateKey, (byte) 0);
            Arrays.fill(publicKey, (byte) 0);
            Arrays.fill(kid, (byte) 0);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Vault private key is locked");
        }
    }
}

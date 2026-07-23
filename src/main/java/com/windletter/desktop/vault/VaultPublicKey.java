package com.windletter.desktop.vault;

import java.util.Objects;

final class VaultPublicKey implements VaultModelChecks.AlgorithmKey {

    private final VaultKeyAlgorithm algorithm;
    private final byte[] kid;
    private final byte[] publicKey;

    VaultPublicKey(
        VaultKeyAlgorithm algorithm,
        byte[] kid,
        byte[] publicKey
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
        this.kid = kid.clone();
        this.publicKey = publicKey.clone();
    }

    @Override
    public VaultKeyAlgorithm algorithm() {
        return algorithm;
    }

    byte[] kid() {
        return kid.clone();
    }

    byte[] publicKey() {
        return publicKey.clone();
    }
}

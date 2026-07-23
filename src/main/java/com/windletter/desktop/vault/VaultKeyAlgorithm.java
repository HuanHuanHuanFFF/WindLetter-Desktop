package com.windletter.desktop.vault;

/** The three independent key algorithms owned by every V1 identity. */
enum VaultKeyAlgorithm {
    X25519("X25519", "RAW-32", "RAW-32", 32, 32),
    ML_KEM_768("ML-KEM-768", "FIPS203-DK-2400", "RAW-1184", 1_184, 2_400),
    ED25519("Ed25519", "SEED-32", "RAW-32", 32, 32);

    private final String wireName;
    private final String privateEncoding;
    private final String publicEncoding;
    private final int publicKeyBytes;
    private final int privateKeyBytes;

    VaultKeyAlgorithm(
        String wireName,
        String privateEncoding,
        String publicEncoding,
        int publicKeyBytes,
        int privateKeyBytes
    ) {
        this.wireName = wireName;
        this.privateEncoding = privateEncoding;
        this.publicEncoding = publicEncoding;
        this.publicKeyBytes = publicKeyBytes;
        this.privateKeyBytes = privateKeyBytes;
    }

    String wireName() {
        return wireName;
    }

    String privateEncoding() {
        return privateEncoding;
    }

    String publicEncoding() {
        return publicEncoding;
    }

    int publicKeyBytes() {
        return publicKeyBytes;
    }

    int privateKeyBytes() {
        return privateKeyBytes;
    }

    static VaultKeyAlgorithm fromWireName(String value) {
        for (VaultKeyAlgorithm algorithm : values()) {
            if (algorithm.wireName.equals(value)) {
                return algorithm;
            }
        }
        throw new IllegalArgumentException("unsupported Vault key algorithm");
    }
}

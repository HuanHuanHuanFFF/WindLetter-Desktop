package com.windletter.desktop.vault;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.util.Arrays;
import java.util.Objects;

/** Single Argon2id implementation seam shared by Vault encryption and calibration. */
final class VaultKeyDerivation {

    static final int SALT_BYTES = 16;
    static final int KEY_BYTES = 32;

    private VaultKeyDerivation() {
    }

    static byte[] derive(
        byte[] password,
        byte[] salt,
        VaultKdfParameters parameters
    ) {
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(salt, "salt");
        Objects.requireNonNull(parameters, "parameters");
        if (salt.length != SALT_BYTES) {
            throw new IllegalArgumentException("salt must be 16 bytes");
        }

        Argon2Parameters argon2Parameters = new Argon2Parameters.Builder(
            Argon2Parameters.ARGON2_id
        )
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(parameters.memoryKiB())
            .withIterations(parameters.iterations())
            .withParallelism(parameters.parallelism())
            .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(argon2Parameters);

        byte[] key = new byte[KEY_BYTES];
        boolean success = false;
        try {
            generator.generateBytes(password, key);
            success = true;
            return key;
        } finally {
            if (!success) {
                Arrays.fill(key, (byte) 0);
            }
        }
    }
}

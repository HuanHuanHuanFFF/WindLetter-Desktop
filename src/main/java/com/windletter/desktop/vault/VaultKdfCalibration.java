package com.windletter.desktop.vault;

import java.util.Objects;

/** Measured Argon2id parameters selected for one target machine. */
record VaultKdfCalibration(
    VaultKdfParameters parameters,
    long measuredMillis,
    long targetMillis
) {

    VaultKdfCalibration {
        Objects.requireNonNull(parameters, "parameters");
        if (measuredMillis <= 0) {
            throw new IllegalArgumentException("measuredMillis must be positive");
        }
        if (targetMillis <= 0) {
            throw new IllegalArgumentException("targetMillis must be positive");
        }
    }
}

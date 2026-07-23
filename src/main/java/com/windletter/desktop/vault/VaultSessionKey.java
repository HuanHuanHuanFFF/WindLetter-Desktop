package com.windletter.desktop.vault;

import java.util.Arrays;
import java.util.Objects;

/** Unlock-state KEK and authenticated KDF metadata. Never contains a password. */
final class VaultSessionKey implements AutoCloseable {

    private final VaultKdfParameters parameters;
    private final byte[] salt;
    private final byte[] key;
    private boolean closed;

    VaultSessionKey(
        VaultKdfParameters parameters,
        byte[] salt,
        byte[] key
    ) {
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        VaultModelChecks.requireExact(
            salt,
            VaultKeyDerivation.SALT_BYTES,
            "salt"
        );
        VaultModelChecks.requireExact(
            key,
            VaultKeyDerivation.KEY_BYTES,
            "key"
        );
        this.salt = salt.clone();
        this.key = key.clone();
    }

    VaultKdfParameters parameters() {
        ensureOpen();
        return parameters;
    }

    byte[] salt() {
        ensureOpen();
        return salt.clone();
    }

    byte[] key() {
        ensureOpen();
        return key.clone();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            Arrays.fill(key, (byte) 0);
            Arrays.fill(salt, (byte) 0);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Vault session key is locked");
        }
    }
}

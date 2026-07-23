package com.windletter.desktop.vault;

import java.util.Arrays;
import java.util.Objects;

/** Caller-owned decrypted envelope data. Close immediately after payload decoding. */
final class OpenedVault implements AutoCloseable {

    private final byte[] vaultId;
    private final byte[] plaintext;
    private VaultSessionKey sessionKey;
    private boolean closed;

    OpenedVault(
        byte[] vaultId,
        byte[] plaintext,
        VaultSessionKey sessionKey
    ) {
        this.vaultId = VaultModelChecks.copyExact(
            vaultId,
            VaultModelChecks.VAULT_ID_BYTES,
            "vaultId"
        );
        this.plaintext = plaintext.clone();
        this.sessionKey = Objects.requireNonNull(sessionKey, "sessionKey");
    }

    byte[] vaultId() {
        ensureOpen();
        return vaultId.clone();
    }

    byte[] plaintext() {
        ensureOpen();
        return plaintext.clone();
    }

    VaultSessionKey takeSessionKey() {
        ensureOpen();
        if (sessionKey == null) {
            throw new IllegalStateException("Vault session key was already transferred");
        }
        VaultSessionKey transferred = sessionKey;
        sessionKey = null;
        return transferred;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(vaultId, (byte) 0);
            if (sessionKey != null) {
                sessionKey.close();
                sessionKey = null;
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Vault plaintext is locked");
        }
    }
}

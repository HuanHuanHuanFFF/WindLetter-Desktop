package com.windletter.desktop.vault;

import java.util.Arrays;

/** Caller-owned decrypted envelope data. Close immediately after payload decoding. */
final class OpenedVault implements AutoCloseable {

    private final byte[] vaultId;
    private final byte[] plaintext;
    private boolean closed;

    OpenedVault(byte[] vaultId, byte[] plaintext) {
        this.vaultId = VaultModelChecks.copyExact(
            vaultId,
            VaultModelChecks.VAULT_ID_BYTES,
            "vaultId"
        );
        this.plaintext = plaintext.clone();
    }

    byte[] vaultId() {
        ensureOpen();
        return vaultId.clone();
    }

    byte[] plaintext() {
        ensureOpen();
        return plaintext.clone();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(vaultId, (byte) 0);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Vault plaintext is locked");
        }
    }
}

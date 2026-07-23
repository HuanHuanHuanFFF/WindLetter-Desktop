package com.windletter.desktop.vault;

import java.util.Objects;
import java.security.MessageDigest;
import java.util.Arrays;

/** Complete unlocked state. Closing it clears private keys and the retained KEK. */
final class VaultSession implements AutoCloseable {

    private VaultPayload payload;
    private final VaultSessionKey sessionKey;
    private boolean closed;

    VaultSession(
        VaultPayload payload,
        VaultSessionKey sessionKey
    ) {
        this.payload = Objects.requireNonNull(payload, "payload");
        this.sessionKey = Objects.requireNonNull(sessionKey, "sessionKey");
    }

    VaultPayload payload() {
        ensureOpen();
        return payload;
    }

    VaultSessionKey sessionKey() {
        ensureOpen();
        return sessionKey;
    }

    void requireSameVault(VaultPayload candidate) {
        ensureOpen();
        Objects.requireNonNull(candidate, "candidate");
        byte[] currentVaultId = payload.vaultId();
        byte[] candidateVaultId = candidate.vaultId();
        try {
            if (!MessageDigest.isEqual(currentVaultId, candidateVaultId)) {
                throw new IllegalArgumentException(
                    "candidate belongs to a different Vault"
                );
            }
        } finally {
            Arrays.fill(candidateVaultId, (byte) 0);
            Arrays.fill(currentVaultId, (byte) 0);
        }
    }

    void acceptPayload(VaultPayload candidate) {
        requireSameVault(candidate);
        VaultPayload previous = payload;
        payload = candidate;
        previous.close();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            try {
                payload.close();
            } finally {
                sessionKey.close();
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Vault session is locked");
        }
    }
}

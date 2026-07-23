package com.windletter.desktop.vault;

import java.util.Objects;

/** Complete unlocked state. Closing it clears private keys and the retained KEK. */
final class VaultSession implements AutoCloseable {

    private final VaultPayload payload;
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

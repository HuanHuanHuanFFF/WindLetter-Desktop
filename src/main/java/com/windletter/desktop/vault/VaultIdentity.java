package com.windletter.desktop.vault;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class VaultIdentity implements AutoCloseable, VaultModelChecks.Identified {

    private final UUID identityId;
    private final String displayName;
    private final String note;
    private final VaultIdentityOrigin origin;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final List<VaultPrivateKey> keys;

    VaultIdentity(
        UUID identityId,
        String displayName,
        String note,
        VaultIdentityOrigin origin,
        Instant createdAt,
        Instant updatedAt,
        List<VaultPrivateKey> keys
    ) {
        this.identityId = Objects.requireNonNull(identityId, "identityId");
        this.displayName = VaultModelChecks.displayName(displayName, "displayName");
        this.note = VaultModelChecks.note(note, "note");
        this.origin = Objects.requireNonNull(origin, "origin");
        VaultModelChecks.timestamps(createdAt, updatedAt);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.keys = VaultModelChecks.boundedList(keys, 3, "keys");
        VaultModelChecks.requireAlgorithmOrder(this.keys, "keys");
    }

    @Override
    public UUID id() {
        return identityId;
    }

    UUID identityId() {
        return identityId;
    }

    String displayName() {
        return displayName;
    }

    String note() {
        return note;
    }

    VaultIdentityOrigin origin() {
        return origin;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant updatedAt() {
        return updatedAt;
    }

    List<VaultPrivateKey> keys() {
        return keys;
    }

    @Override
    public void close() {
        for (VaultPrivateKey key : keys) {
            key.close();
        }
    }
}

package com.windletter.desktop.vault;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Complete decrypted V1 state. Closing it locks and clears owned private keys. */
final class VaultPayload implements AutoCloseable {

    private final byte[] vaultId;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final List<VaultIdentity> identities;
    private final List<VaultContact> contacts;
    private final VaultSettings settings;

    VaultPayload(
        byte[] vaultId,
        Instant createdAt,
        Instant updatedAt,
        List<VaultIdentity> identities,
        List<VaultContact> contacts,
        VaultSettings settings
    ) {
        VaultModelChecks.requireExact(
            vaultId,
            VaultModelChecks.VAULT_ID_BYTES,
            "vaultId"
        );
        VaultModelChecks.timestamps(createdAt, updatedAt);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.identities = VaultModelChecks.boundedList(
            identities,
            VaultModelChecks.MAX_IDENTITIES,
            "identities"
        );
        this.contacts = VaultModelChecks.boundedList(
            contacts,
            VaultModelChecks.MAX_CONTACTS,
            "contacts"
        );
        VaultModelChecks.requireUniqueIds(this.identities, "identities");
        VaultModelChecks.requireUniqueIds(this.contacts, "contacts");
        this.settings = Objects.requireNonNull(settings, "settings");
        validateDefaultIdentity(settings.defaultIdentityId(), this.identities);
        this.vaultId = vaultId.clone();
    }

    byte[] vaultId() {
        return vaultId.clone();
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant updatedAt() {
        return updatedAt;
    }

    List<VaultIdentity> identities() {
        return identities;
    }

    List<VaultContact> contacts() {
        return contacts;
    }

    VaultSettings settings() {
        return settings;
    }

    @Override
    public void close() {
        for (VaultIdentity identity : identities) {
            identity.close();
        }
        java.util.Arrays.fill(vaultId, (byte) 0);
    }

    private static void validateDefaultIdentity(
        UUID defaultIdentityId,
        List<VaultIdentity> identities
    ) {
        if (defaultIdentityId == null) {
            return;
        }
        boolean present = identities.stream()
            .anyMatch(identity -> identity.identityId().equals(defaultIdentityId));
        if (!present) {
            throw new IllegalArgumentException(
                "defaultIdentityId must refer to an existing identity"
            );
        }
    }
}

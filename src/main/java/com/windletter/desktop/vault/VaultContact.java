package com.windletter.desktop.vault;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class VaultContact implements VaultModelChecks.Identified {

    private final UUID contactId;
    private final String claimedDisplayName;
    private final String localDisplayName;
    private final String note;
    private final VaultVerificationStatus verificationStatus;
    private final Instant verifiedAt;
    private final Instant addedAt;
    private final Instant updatedAt;
    private final List<VaultPublicKey> publicKeys;

    VaultContact(
        UUID contactId,
        String claimedDisplayName,
        String localDisplayName,
        String note,
        VaultVerificationStatus verificationStatus,
        Instant verifiedAt,
        Instant addedAt,
        Instant updatedAt,
        List<VaultPublicKey> publicKeys
    ) {
        this.contactId = Objects.requireNonNull(contactId, "contactId");
        this.claimedDisplayName = VaultModelChecks.displayName(
            claimedDisplayName,
            "claimedDisplayName"
        );
        this.localDisplayName = VaultModelChecks.optionalDisplayName(
            localDisplayName,
            "localDisplayName"
        );
        this.note = VaultModelChecks.note(note, "note");
        this.verificationStatus = Objects.requireNonNull(
            verificationStatus,
            "verificationStatus"
        );
        Objects.requireNonNull(addedAt, "addedAt");
        VaultModelChecks.timestamps(addedAt, updatedAt);
        validateVerificationTime(verificationStatus, verifiedAt, addedAt, updatedAt);
        this.verifiedAt = verifiedAt;
        this.addedAt = addedAt;
        this.updatedAt = updatedAt;
        this.publicKeys = VaultModelChecks.boundedList(publicKeys, 3, "publicKeys");
        VaultModelChecks.requireAlgorithmOrder(this.publicKeys, "publicKeys");
    }

    @Override
    public UUID id() {
        return contactId;
    }

    UUID contactId() {
        return contactId;
    }

    String claimedDisplayName() {
        return claimedDisplayName;
    }

    String localDisplayName() {
        return localDisplayName;
    }

    String note() {
        return note;
    }

    VaultVerificationStatus verificationStatus() {
        return verificationStatus;
    }

    Instant verifiedAt() {
        return verifiedAt;
    }

    Instant addedAt() {
        return addedAt;
    }

    Instant updatedAt() {
        return updatedAt;
    }

    List<VaultPublicKey> publicKeys() {
        return publicKeys;
    }

    private static void validateVerificationTime(
        VaultVerificationStatus status,
        Instant verifiedAt,
        Instant addedAt,
        Instant updatedAt
    ) {
        if (status == VaultVerificationStatus.FINGERPRINT_VERIFIED) {
            Objects.requireNonNull(verifiedAt, "verifiedAt");
            if (verifiedAt.isBefore(addedAt) || verifiedAt.isAfter(updatedAt)) {
                throw new IllegalArgumentException(
                    "verifiedAt must be within the contact lifetime"
                );
            }
        } else if (verifiedAt != null) {
            throw new IllegalArgumentException(
                "unverified contacts must not contain verifiedAt"
            );
        }
    }
}

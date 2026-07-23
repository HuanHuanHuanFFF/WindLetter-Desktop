package com.windletter.desktop.vault;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Transactional contact import and local trust-metadata mutations. */
final class VaultContactManager {

    private final VaultService vaultService;
    private final PublicIdentityCodec publicIdentityCodec;
    private final Clock clock;

    VaultContactManager(
        VaultService vaultService,
        PublicIdentityCodec publicIdentityCodec,
        Clock clock
    ) {
        this.vaultService = Objects.requireNonNull(vaultService, "vaultService");
        this.publicIdentityCodec = Objects.requireNonNull(
            publicIdentityCodec,
            "publicIdentityCodec"
        );
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    UUID importAndSave(
        VaultSession session,
        String encodedPublicIdentity
    ) throws PublicIdentityException, VaultWriteException {
        Objects.requireNonNull(session, "session");
        PublicIdentity publicIdentity = publicIdentityCodec.decode(
            encodedPublicIdentity
        );
        VaultPayload source = session.payload();
        if (source.contacts().stream().anyMatch(
            contact -> hasSameKeys(contact, publicIdentity)
        )) {
            throw new IllegalArgumentException(
                "a contact with the same public keys already exists"
            );
        }

        Instant now = clock.instant();
        List<VaultPublicKey> publicKeys = copyKeys(publicIdentity.publicKeys());
        VaultContact imported = new VaultContact(
            UUID.randomUUID(),
            publicIdentity.displayName(),
            null,
            null,
            VaultVerificationStatus.UNVERIFIED,
            null,
            now,
            now,
            publicKeys
        );
        List<VaultContact> contacts = VaultPayloadCopies.contacts(
            source.contacts()
        );
        contacts.add(imported);
        VaultPayload candidate = buildCandidate(source, contacts);
        commitCandidate(session, candidate);
        return imported.contactId();
    }

    void updateAndSave(
        VaultSession session,
        UUID contactId,
        String localDisplayName,
        String note,
        VaultVerificationStatus verificationStatus
    ) throws VaultWriteException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(contactId, "contactId");
        Objects.requireNonNull(verificationStatus, "verificationStatus");

        VaultPayload source = session.payload();
        List<VaultContact> contacts = new ArrayList<>();
        boolean found = false;
        for (VaultContact contact : source.contacts()) {
            if (contact.contactId().equals(contactId)) {
                found = true;
                Instant verifiedAt =
                    verificationStatus == VaultVerificationStatus.FINGERPRINT_VERIFIED
                        ? verifiedAt(contact)
                        : null;
                contacts.add(new VaultContact(
                    contact.contactId(),
                    contact.claimedDisplayName(),
                    localDisplayName,
                    note,
                    verificationStatus,
                    verifiedAt,
                    contact.addedAt(),
                    updatedAt(source.updatedAt()),
                    copyKeys(contact.publicKeys())
                ));
            } else {
                contacts.add(VaultPayloadCopies.contacts(List.of(contact)).get(0));
            }
        }
        if (!found) {
            throw new IllegalArgumentException("contactId does not exist");
        }

        VaultPayload candidate = buildCandidate(source, contacts);
        commitCandidate(session, candidate);
    }

    void deleteAndSave(
        VaultSession session,
        UUID contactId
    ) throws VaultWriteException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(contactId, "contactId");

        VaultPayload source = session.payload();
        List<VaultContact> contacts = new ArrayList<>();
        boolean found = false;
        for (VaultContact contact : source.contacts()) {
            if (contact.contactId().equals(contactId)) {
                found = true;
            } else {
                contacts.add(VaultPayloadCopies.contacts(List.of(contact)).get(0));
            }
        }
        if (!found) {
            throw new IllegalArgumentException("contactId does not exist");
        }

        VaultPayload candidate = buildCandidate(source, contacts);
        commitCandidate(session, candidate);
    }

    private VaultPayload buildCandidate(
        VaultPayload source,
        List<VaultContact> contacts
    ) {
        byte[] vaultId = source.vaultId();
        List<VaultIdentity> identities = VaultPayloadCopies.identities(
            source.identities()
        );
        boolean success = false;
        try {
            VaultPayload candidate = new VaultPayload(
                vaultId,
                source.createdAt(),
                updatedAt(source.updatedAt()),
                identities,
                contacts,
                source.settings()
            );
            success = true;
            return candidate;
        } finally {
            clear(vaultId);
            if (!success) {
                identities.forEach(VaultIdentity::close);
            }
        }
    }

    private void commitCandidate(
        VaultSession session,
        VaultPayload candidate
    ) throws VaultWriteException {
        boolean committed = false;
        try {
            vaultService.commit(session, candidate);
            committed = true;
        } finally {
            if (!committed) {
                candidate.close();
            }
        }
    }

    private Instant verifiedAt(VaultContact contact) {
        if (contact.verificationStatus()
            == VaultVerificationStatus.FINGERPRINT_VERIFIED) {
            return contact.verifiedAt();
        }
        return updatedAt(contact.updatedAt());
    }

    private Instant updatedAt(Instant current) {
        Instant now = clock.instant();
        return now.isBefore(current) ? current : now;
    }

    private static boolean hasSameKeys(
        VaultContact contact,
        PublicIdentity publicIdentity
    ) {
        for (int index = 0; index < contact.publicKeys().size(); index++) {
            byte[] existingKid = contact.publicKeys().get(index).kid();
            byte[] importedKid = publicIdentity.publicKeys().get(index).kid();
            try {
                if (!MessageDigest.isEqual(existingKid, importedKid)) {
                    return false;
                }
            } finally {
                clear(importedKid);
                clear(existingKid);
            }
        }
        return true;
    }

    private static List<VaultPublicKey> copyKeys(List<VaultPublicKey> source) {
        List<VaultPublicKey> copies = new ArrayList<>();
        for (VaultPublicKey key : source) {
            copies.add(VaultPayloadCopies.publicKey(key));
        }
        return copies;
    }

    private static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}

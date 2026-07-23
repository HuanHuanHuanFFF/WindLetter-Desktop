package com.windletter.desktop.vault;

import com.windletter.crypto.api.Ed25519PrivateKeyHandle;
import com.windletter.crypto.api.MLKem768PrivateKeyHandle;
import com.windletter.crypto.api.X25519PrivateKeyHandle;
import com.windletter.crypto.bc.BouncyCastleEd25519Crypto;
import com.windletter.crypto.bc.BouncyCastleMLKem768Crypto;
import com.windletter.crypto.bc.BouncyCastleX25519Crypto;
import java.time.Clock;
import java.time.Instant;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Transactional identity mutations backed by the real core crypto provider. */
final class VaultIdentityManager {

    private final VaultService vaultService;
    private final Clock clock;
    private final BouncyCastleX25519Crypto x25519 =
        new BouncyCastleX25519Crypto();
    private final BouncyCastleMLKem768Crypto mlKem768 =
        new BouncyCastleMLKem768Crypto();
    private final BouncyCastleEd25519Crypto ed25519 =
        new BouncyCastleEd25519Crypto();

    VaultIdentityManager(VaultService vaultService, Clock clock) {
        this.vaultService = Objects.requireNonNull(vaultService, "vaultService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    UUID generateAndSave(
        VaultSession session,
        String displayName,
        String note
    ) throws VaultWriteException {
        Objects.requireNonNull(session, "session");
        String checkedDisplayName = VaultModelChecks.displayName(
            displayName,
            "displayName"
        );
        String checkedNote = VaultModelChecks.note(note, "note");

        VaultPayload source = session.payload();
        List<VaultIdentity> identities = VaultPayloadCopies.identities(
            source.identities()
        );
        VaultIdentity generated = null;
        try {
            generated = generateIdentity(checkedDisplayName, checkedNote);
            identities.add(generated);
            UUID generatedId = generated.identityId();
            UUID defaultIdentityId = source.settings().defaultIdentityId() == null
                ? generatedId
                : source.settings().defaultIdentityId();
            VaultPayload candidate = buildCandidate(
                source,
                identities,
                new VaultSettings(
                    defaultIdentityId,
                    source.settings().autoLockMinutes()
                )
            );
            identities = List.of();
            commitCandidate(session, candidate);
            return generatedId;
        } catch (RuntimeException failure) {
            throw new VaultWriteException();
        } finally {
            identities.forEach(VaultIdentity::close);
        }
    }

    void selectDefaultAndSave(
        VaultSession session,
        UUID identityId
    ) throws VaultWriteException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(identityId, "identityId");
        VaultPayload source = session.payload();
        boolean present = source.identities().stream()
            .anyMatch(identity -> identity.identityId().equals(identityId));
        if (!present) {
            throw new IllegalArgumentException("identityId does not exist");
        }
        VaultPayload candidate = buildCandidate(
            source,
            VaultPayloadCopies.identities(source.identities()),
            new VaultSettings(
                identityId,
                source.settings().autoLockMinutes()
            )
        );
        commitCandidate(session, candidate);
    }

    void deleteAndSave(
        VaultSession session,
        UUID identityId
    ) throws VaultWriteException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(identityId, "identityId");
        VaultPayload source = session.payload();
        List<VaultIdentity> identities = new ArrayList<>();
        boolean found = false;
        try {
            for (VaultIdentity identity : source.identities()) {
                if (identity.identityId().equals(identityId)) {
                    found = true;
                } else {
                    identities.add(VaultPayloadCopies.identity(identity));
                }
            }
            if (!found) {
                throw new IllegalArgumentException("identityId does not exist");
            }

            UUID defaultIdentityId = source.settings().defaultIdentityId();
            if (identityId.equals(defaultIdentityId)) {
                defaultIdentityId = identities.isEmpty()
                    ? null
                    : identities.get(0).identityId();
            }
            VaultPayload candidate = buildCandidate(
                source,
                identities,
                new VaultSettings(
                    defaultIdentityId,
                    source.settings().autoLockMinutes()
                )
            );
            identities = List.of();
            commitCandidate(session, candidate);
        } finally {
            identities.forEach(VaultIdentity::close);
        }
    }

    void updateNoteAndSave(
        VaultSession session,
        UUID identityId,
        String note
    ) throws VaultWriteException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(identityId, "identityId");
        String checkedNote = VaultModelChecks.note(note, "note");

        VaultPayload source = session.payload();
        List<VaultIdentity> identities = new ArrayList<>();
        boolean found = false;
        try {
            for (VaultIdentity identity : source.identities()) {
                if (identity.identityId().equals(identityId)) {
                    found = true;
                    identities.add(updatedIdentity(
                        identity,
                        identity.displayName(),
                        checkedNote,
                        source.updatedAt()
                    ));
                } else {
                    identities.add(VaultPayloadCopies.identity(identity));
                }
            }
            if (!found) {
                throw new IllegalArgumentException("identityId does not exist");
            }
            VaultPayload candidate = buildCandidate(
                source,
                identities,
                source.settings()
            );
            identities = List.of();
            commitCandidate(session, candidate);
        } finally {
            identities.forEach(VaultIdentity::close);
        }
    }

    UUID importFromVaultAndSave(
        VaultSession targetSession,
        Path sourceVaultPath,
        char[] sourcePassword,
        UUID sourceIdentityId
    ) throws VaultOpenException, VaultWriteException {
        Objects.requireNonNull(targetSession, "targetSession");
        Objects.requireNonNull(sourceVaultPath, "sourceVaultPath");
        Objects.requireNonNull(sourcePassword, "sourcePassword");
        Objects.requireNonNull(sourceIdentityId, "sourceIdentityId");

        VaultService sourceService = new VaultService(sourceVaultPath);
        try (VaultSession sourceSession = sourceService.open(sourcePassword)) {
            VaultIdentity sourceIdentity = sourceSession.payload()
                .identities()
                .stream()
                .filter(identity -> identity.identityId().equals(sourceIdentityId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                    "sourceIdentityId does not exist"
                ));
            VaultPayload targetPayload = targetSession.payload();
            if (targetPayload.identities().stream().anyMatch(
                identity -> hasSameKeys(identity, sourceIdentity)
            )) {
                throw new IllegalArgumentException(
                    "an identity with the same keys already exists"
                );
            }

            List<VaultIdentity> identities = VaultPayloadCopies.identities(
                targetPayload.identities()
            );
            try {
                VaultIdentity imported = importedIdentity(sourceIdentity);
                identities.add(imported);
                UUID importedId = imported.identityId();
                UUID defaultIdentityId =
                    targetPayload.settings().defaultIdentityId() == null
                        ? importedId
                        : targetPayload.settings().defaultIdentityId();
                VaultPayload candidate = buildCandidate(
                    targetPayload,
                    identities,
                    new VaultSettings(
                        defaultIdentityId,
                        targetPayload.settings().autoLockMinutes()
                    )
                );
                identities = List.of();
                commitCandidate(targetSession, candidate);
                return importedId;
            } finally {
                identities.forEach(VaultIdentity::close);
            }
        }
    }

    private VaultIdentity generateIdentity(
        String displayName,
        String note
    ) {
        List<VaultPrivateKey> keys = new ArrayList<>();
        Instant now = clock.instant();
        try (
            X25519PrivateKeyHandle x25519Handle = x25519.generatePrivateKey();
            MLKem768PrivateKeyHandle mlKemHandle = mlKem768.generatePrivateKey();
            Ed25519PrivateKeyHandle ed25519Handle = ed25519.generatePrivateKey()
        ) {
            keys.add(x25519Key(x25519Handle));
            keys.add(mlKem768Key(mlKemHandle));
            keys.add(ed25519Key(ed25519Handle));
            return new VaultIdentity(
                UUID.randomUUID(),
                displayName,
                note,
                VaultIdentityOrigin.GENERATED,
                now,
                now,
                keys
            );
        } catch (RuntimeException failure) {
            keys.forEach(VaultPrivateKey::close);
            throw failure;
        }
    }

    private VaultPrivateKey x25519Key(X25519PrivateKeyHandle handle) {
        byte[] privateKey = null;
        byte[] publicKey = null;
        byte[] kid = null;
        try {
            privateKey = x25519.exportPrivateKey(handle);
            publicKey = handle.publicKey();
            kid = VaultKeyIds.derive(VaultKeyAlgorithm.X25519, publicKey);
            return new VaultPrivateKey(
                VaultKeyAlgorithm.X25519,
                kid,
                publicKey,
                privateKey
            );
        } finally {
            clear(kid);
            clear(publicKey);
            clear(privateKey);
        }
    }

    private VaultPrivateKey mlKem768Key(MLKem768PrivateKeyHandle handle) {
        byte[] privateKey = null;
        byte[] publicKey = null;
        byte[] kid = null;
        try {
            privateKey = mlKem768.exportPrivateKey(handle);
            publicKey = handle.publicKey();
            kid = VaultKeyIds.derive(VaultKeyAlgorithm.ML_KEM_768, publicKey);
            return new VaultPrivateKey(
                VaultKeyAlgorithm.ML_KEM_768,
                kid,
                publicKey,
                privateKey
            );
        } finally {
            clear(kid);
            clear(publicKey);
            clear(privateKey);
        }
    }

    private VaultPrivateKey ed25519Key(Ed25519PrivateKeyHandle handle) {
        byte[] privateKey = null;
        byte[] publicKey = null;
        byte[] kid = null;
        try {
            privateKey = ed25519.exportPrivateKey(handle);
            publicKey = handle.publicKey();
            kid = VaultKeyIds.derive(VaultKeyAlgorithm.ED25519, publicKey);
            return new VaultPrivateKey(
                VaultKeyAlgorithm.ED25519,
                kid,
                publicKey,
                privateKey
            );
        } finally {
            clear(kid);
            clear(publicKey);
            clear(privateKey);
        }
    }

    private VaultPayload buildCandidate(
        VaultPayload source,
        List<VaultIdentity> identities,
        VaultSettings settings
    ) {
        byte[] vaultId = source.vaultId();
        boolean success = false;
        try {
            VaultPayload candidate = new VaultPayload(
                vaultId,
                source.createdAt(),
                updatedAt(source.updatedAt()),
                identities,
                VaultPayloadCopies.contacts(source.contacts()),
                settings
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

    private Instant updatedAt(Instant current) {
        Instant now = clock.instant();
        return now.isBefore(current) ? current : now;
    }

    private VaultIdentity updatedIdentity(
        VaultIdentity source,
        String displayName,
        String note,
        Instant payloadUpdatedAt
    ) {
        List<VaultPrivateKey> keys = VaultPayloadCopies.privateKeys(
            source.keys()
        );
        try {
            return new VaultIdentity(
                source.identityId(),
                displayName,
                note,
                source.origin(),
                source.createdAt(),
                updatedAt(payloadUpdatedAt),
                keys
            );
        } catch (RuntimeException failure) {
            keys.forEach(VaultPrivateKey::close);
            throw failure;
        }
    }

    private VaultIdentity importedIdentity(VaultIdentity source) {
        List<VaultPrivateKey> keys = VaultPayloadCopies.privateKeys(
            source.keys()
        );
        Instant now = clock.instant();
        try {
            return new VaultIdentity(
                UUID.randomUUID(),
                source.displayName(),
                source.note(),
                VaultIdentityOrigin.IMPORTED,
                now,
                now,
                keys
            );
        } catch (RuntimeException failure) {
            keys.forEach(VaultPrivateKey::close);
            throw failure;
        }
    }

    private static boolean hasSameKeys(
        VaultIdentity first,
        VaultIdentity second
    ) {
        for (int index = 0; index < first.keys().size(); index++) {
            byte[] firstKid = first.keys().get(index).kid();
            byte[] secondKid = second.keys().get(index).kid();
            try {
                if (!MessageDigest.isEqual(firstKid, secondKid)) {
                    return false;
                }
            } finally {
                clear(secondKid);
                clear(firstKid);
            }
        }
        return true;
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

    private static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}

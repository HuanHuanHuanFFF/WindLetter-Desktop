package com.windletter.desktop.vault;

import com.windletter.crypto.api.Ed25519PrivateKeyHandle;
import com.windletter.crypto.api.MLKem768PrivateKeyHandle;
import com.windletter.crypto.api.X25519PrivateKeyHandle;
import com.windletter.crypto.bc.BouncyCastleEd25519Crypto;
import com.windletter.crypto.bc.BouncyCastleMLKem768Crypto;
import com.windletter.crypto.bc.BouncyCastleX25519Crypto;
import com.windletter.protocol.key.Ed25519KeyId;
import com.windletter.protocol.key.MLKem768KeyId;
import com.windletter.protocol.key.X25519KeyId;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
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
            kid = decodeKid(X25519KeyId.derive(publicKey));
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
            kid = decodeKid(MLKem768KeyId.derive(publicKey));
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
            kid = decodeKid(Ed25519KeyId.derive(publicKey));
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
                clock.instant(),
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

    private static byte[] decodeKid(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}

package com.windletter.desktop.vault;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Public desktop boundary for Vault operations.
 *
 * <p>Views never expose private or public key bytes. Password arrays are
 * consumed and cleared before every password-taking method returns.</p>
 */
public final class DesktopVault implements AutoCloseable {

    private static final Base64.Encoder KID_ENCODER =
        Base64.getUrlEncoder().withoutPadding();

    private final Path vaultPath;
    private final Supplier<VaultKdfCalibration> calibrationSupplier;
    private final SecureRandom secureRandom;
    private final Clock clock;
    private final VaultService service;
    private final VaultIdentityManager identities;
    private final PublicIdentityCodec publicIdentities;
    private final VaultContactManager contacts;
    private final VaultSessionController sessions;

    public DesktopVault() {
        this(DesktopDataPaths.defaultVaultPath());
    }

    public DesktopVault(Path vaultPath) {
        this(
            vaultPath,
            new VaultKdfCalibrator()::calibrate,
            new SecureRandom(),
            Clock.systemUTC(),
            new ScheduledVaultAutoLockScheduler()
        );
    }

    DesktopVault(
        Path vaultPath,
        Supplier<VaultKdfCalibration> calibrationSupplier,
        SecureRandom secureRandom,
        Clock clock,
        VaultAutoLockScheduler scheduler
    ) {
        this.vaultPath = Objects.requireNonNull(vaultPath, "vaultPath")
            .toAbsolutePath()
            .normalize();
        this.calibrationSupplier = Objects.requireNonNull(
            calibrationSupplier,
            "calibrationSupplier"
        );
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.service = new VaultService(
            this.vaultPath,
            calibrationSupplier,
            secureRandom,
            clock
        );
        this.identities = new VaultIdentityManager(service, clock);
        this.publicIdentities = new PublicIdentityCodec();
        this.contacts = new VaultContactManager(
            service,
            publicIdentities,
            clock
        );
        this.sessions = new VaultSessionController(
            Objects.requireNonNull(scheduler, "scheduler")
        );
    }

    public Path vaultPath() {
        return vaultPath;
    }

    public boolean exists() {
        return Files.exists(vaultPath);
    }

    public boolean isUnlocked() {
        return sessions.isUnlocked();
    }

    public void recordActivity() {
        if (sessions.isUnlocked()) {
            sessions.activity();
        }
    }

    public void create(char[] password, int autoLockMinutes)
        throws DesktopVaultException {
        try {
            sessions.unlock(service.create(password, autoLockMinutes));
        } catch (VaultWriteException failure) {
            throw problem(DesktopVaultProblem.SAVE_FAILED);
        } catch (IllegalArgumentException failure) {
            throw problem(DesktopVaultProblem.INVALID_PASSWORD);
        } catch (RuntimeException failure) {
            throw mapRuntime(failure);
        } finally {
            clear(password);
        }
    }

    public void unlock(char[] password) throws DesktopVaultException {
        try {
            sessions.unlock(service.open(password));
        } catch (VaultOpenException failure) {
            throw problem(DesktopVaultProblem.OPEN_FAILED);
        } catch (RuntimeException failure) {
            throw mapRuntime(failure);
        } finally {
            clear(password);
        }
    }

    public void lock() {
        if (sessions.isUnlocked()) {
            sessions.lock();
        }
    }

    public Snapshot snapshot() throws DesktopVaultException {
        return use(session -> snapshot(session.payload()));
    }

    public UUID createIdentity(String displayName, String note)
        throws DesktopVaultException {
        return use(session -> identities.generateAndSave(
            session,
            displayName,
            emptyToNull(note)
        ));
    }

    public void updateIdentity(
        UUID identityId,
        String displayName,
        String note
    ) throws DesktopVaultException {
        use(session -> {
            identities.updateMetadataAndSave(
                session,
                identityId,
                displayName,
                emptyToNull(note)
            );
            return null;
        });
    }

    public void selectDefaultIdentity(UUID identityId)
        throws DesktopVaultException {
        use(session -> {
            identities.selectDefaultAndSave(session, identityId);
            return null;
        });
    }

    public void deleteIdentity(UUID identityId)
        throws DesktopVaultException {
        use(session -> {
            identities.deleteAndSave(session, identityId);
            return null;
        });
    }

    public String exportPublicIdentity(UUID identityId)
        throws DesktopVaultException {
        return use(session -> {
            VaultIdentity identity = findIdentity(
                session.payload(),
                identityId
            );
            return publicIdentities.encode(identity);
        });
    }

    public UUID importContact(String encodedPublicIdentity)
        throws DesktopVaultException {
        try {
            return sessions.use(session -> contacts.importAndSave(
                session,
                encodedPublicIdentity
            ));
        } catch (PublicIdentityException failure) {
            throw problem(DesktopVaultProblem.INVALID_PUBLIC_IDENTITY);
        } catch (VaultWriteException failure) {
            throw problem(DesktopVaultProblem.SAVE_FAILED);
        } catch (IllegalStateException failure) {
            throw problem(DesktopVaultProblem.LOCKED);
        } catch (RuntimeException failure) {
            throw mapRuntime(failure);
        } catch (Exception failure) {
            throw problem(DesktopVaultProblem.INVALID_INPUT);
        }
    }

    public void updateContact(
        UUID contactId,
        String localDisplayName,
        String note,
        boolean fingerprintVerified
    ) throws DesktopVaultException {
        use(session -> {
            contacts.updateAndSave(
                session,
                contactId,
                emptyToNull(localDisplayName),
                emptyToNull(note),
                fingerprintVerified
                    ? VaultVerificationStatus.FINGERPRINT_VERIFIED
                    : VaultVerificationStatus.UNVERIFIED
            );
            return null;
        });
    }

    public void deleteContact(UUID contactId) throws DesktopVaultException {
        use(session -> {
            contacts.deleteAndSave(session, contactId);
            return null;
        });
    }

    public void backup(Path backupPath) throws DesktopVaultException {
        use(session -> {
            service.backup(backupPath);
            return null;
        });
    }

    public List<IdentityView> inspectBackup(
        Path backupPath,
        char[] password
    ) throws DesktopVaultException {
        try {
            return sessions.use(currentSession -> {
                try (
                    VaultSession backupSession =
                        serviceFor(backupPath).open(password)
                ) {
                    return identityViews(backupSession.payload());
                }
            });
        } catch (VaultOpenException failure) {
            throw problem(DesktopVaultProblem.OPEN_FAILED);
        } catch (IllegalStateException failure) {
            throw problem(DesktopVaultProblem.LOCKED);
        } catch (RuntimeException failure) {
            throw mapRuntime(failure);
        } catch (Exception failure) {
            throw problem(DesktopVaultProblem.INVALID_INPUT);
        } finally {
            clear(password);
        }
    }

    public UUID importIdentityFromBackup(
        Path backupPath,
        char[] password,
        UUID sourceIdentityId
    ) throws DesktopVaultException {
        try {
            return sessions.use(session -> identities.importFromVaultAndSave(
                session,
                backupPath,
                password,
                sourceIdentityId
            ));
        } catch (VaultOpenException failure) {
            throw problem(DesktopVaultProblem.OPEN_FAILED);
        } catch (VaultWriteException failure) {
            throw problem(DesktopVaultProblem.SAVE_FAILED);
        } catch (IllegalStateException failure) {
            throw problem(DesktopVaultProblem.LOCKED);
        } catch (RuntimeException failure) {
            throw mapRuntime(failure);
        } catch (Exception failure) {
            throw problem(DesktopVaultProblem.INVALID_INPUT);
        } finally {
            clear(password);
        }
    }

    public void restore(Path backupPath, char[] password)
        throws DesktopVaultException {
        try {
            if (sessions.isUnlocked()) {
                throw problem(DesktopVaultProblem.LOCK_BEFORE_RESTORE);
            }
            sessions.unlock(service.restore(backupPath, password));
        } catch (DesktopVaultException failure) {
            throw failure;
        } catch (VaultOpenException failure) {
            throw problem(DesktopVaultProblem.OPEN_FAILED);
        } catch (VaultWriteException failure) {
            throw problem(DesktopVaultProblem.SAVE_FAILED);
        } catch (RuntimeException failure) {
            throw mapRuntime(failure);
        } finally {
            clear(password);
        }
    }

    @Override
    public void close() {
        sessions.close();
    }

    private <T> T use(VaultSessionController.SessionOperation<T> operation)
        throws DesktopVaultException {
        try {
            return sessions.use(operation);
        } catch (PublicIdentityException failure) {
            throw problem(DesktopVaultProblem.INVALID_PUBLIC_IDENTITY);
        } catch (VaultWriteException failure) {
            throw problem(DesktopVaultProblem.SAVE_FAILED);
        } catch (IllegalStateException failure) {
            throw problem(DesktopVaultProblem.LOCKED);
        } catch (RuntimeException failure) {
            throw mapRuntime(failure);
        } catch (Exception failure) {
            throw problem(DesktopVaultProblem.INVALID_INPUT);
        }
    }

    private VaultService serviceFor(Path path) {
        return new VaultService(
            path,
            calibrationSupplier,
            secureRandom,
            clock
        );
    }

    private static Snapshot snapshot(VaultPayload payload) {
        return new Snapshot(
            payload.settings().autoLockMinutes(),
            payload.settings().defaultIdentityId(),
            identityViews(payload),
            contactViews(payload)
        );
    }

    private static List<IdentityView> identityViews(VaultPayload payload) {
        UUID defaultIdentityId = payload.settings().defaultIdentityId();
        List<IdentityView> views = new ArrayList<>();
        for (VaultIdentity identity : payload.identities()) {
            views.add(new IdentityView(
                identity.identityId(),
                identity.displayName(),
                identity.note(),
                identity.origin() == VaultIdentityOrigin.GENERATED
                    ? IdentityOrigin.GENERATED
                    : IdentityOrigin.IMPORTED,
                identity.identityId().equals(defaultIdentityId),
                privateKeyFingerprint(identity.keys())
            ));
        }
        return List.copyOf(views);
    }

    private static List<ContactView> contactViews(VaultPayload payload) {
        List<ContactView> views = new ArrayList<>();
        for (VaultContact contact : payload.contacts()) {
            views.add(new ContactView(
                contact.contactId(),
                contact.claimedDisplayName(),
                contact.localDisplayName(),
                contact.note(),
                contact.verificationStatus()
                    == VaultVerificationStatus.FINGERPRINT_VERIFIED
                        ? ContactVerification.FINGERPRINT_VERIFIED
                        : ContactVerification.UNVERIFIED,
                publicKeyFingerprint(contact.publicKeys())
            ));
        }
        return List.copyOf(views);
    }

    private static VaultIdentity findIdentity(
        VaultPayload payload,
        UUID identityId
    ) {
        Objects.requireNonNull(identityId, "identityId");
        return payload.identities()
            .stream()
            .filter(identity -> identity.identityId().equals(identityId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "identityId does not exist"
            ));
    }

    private static String privateKeyFingerprint(List<VaultPrivateKey> keys) {
        List<String> lines = new ArrayList<>();
        for (VaultPrivateKey key : keys) {
            byte[] kid = key.kid();
            try {
                lines.add(key.algorithm().wireName()
                    + "  "
                    + KID_ENCODER.encodeToString(kid));
            } finally {
                clear(kid);
            }
        }
        return String.join("\n", lines);
    }

    private static String publicKeyFingerprint(List<VaultPublicKey> keys) {
        List<String> lines = new ArrayList<>();
        for (VaultPublicKey key : keys) {
            byte[] kid = key.kid();
            try {
                lines.add(key.algorithm().wireName()
                    + "  "
                    + KID_ENCODER.encodeToString(kid));
            } finally {
                clear(kid);
            }
        }
        return String.join("\n", lines);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static DesktopVaultException mapRuntime(RuntimeException failure) {
        if (failure instanceof IllegalStateException) {
            return problem(DesktopVaultProblem.LOCKED);
        }
        return problem(DesktopVaultProblem.INVALID_INPUT);
    }

    private static DesktopVaultException problem(DesktopVaultProblem problem) {
        return new DesktopVaultException(problem);
    }

    private static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    public enum IdentityOrigin {
        GENERATED,
        IMPORTED
    }

    public enum ContactVerification {
        UNVERIFIED,
        FINGERPRINT_VERIFIED
    }

    public record Snapshot(
        int autoLockMinutes,
        UUID defaultIdentityId,
        List<IdentityView> identities,
        List<ContactView> contacts
    ) {
        public Snapshot {
            identities = List.copyOf(identities);
            contacts = List.copyOf(contacts);
        }
    }

    public record IdentityView(
        UUID identityId,
        String displayName,
        String note,
        IdentityOrigin origin,
        boolean defaultIdentity,
        String fingerprint
    ) {
    }

    public record ContactView(
        UUID contactId,
        String claimedDisplayName,
        String localDisplayName,
        String note,
        ContactVerification verification,
        String fingerprint
    ) {
        public String displayName() {
            return localDisplayName == null
                ? claimedDisplayName
                : localDisplayName;
        }
    }
}

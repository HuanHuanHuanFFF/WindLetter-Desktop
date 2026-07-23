package com.windletter.desktop.vault;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Enforces the complete Vault chain: file, AEAD, strict payload and core keys.
 */
final class VaultService {

    private static final int MIN_PASSWORD_CODE_POINTS = 12;
    private static final int MAX_PASSWORD_CODE_POINTS = 1_024;

    private final Path vaultPath;
    private final Supplier<VaultKdfCalibration> calibrationSupplier;
    private final SecureRandom secureRandom;
    private final Clock clock;
    private final VaultCipher cipher;
    private final VaultPayloadCodec codec;
    private final VaultKeyMaterialValidator keyValidator;
    private final VaultFileStore fileStore;

    VaultService(Path vaultPath) {
        this(
            vaultPath,
            new VaultKdfCalibrator()::calibrate,
            new SecureRandom(),
            Clock.systemUTC()
        );
    }

    VaultService(
        Path vaultPath,
        Supplier<VaultKdfCalibration> calibrationSupplier,
        SecureRandom secureRandom,
        Clock clock
    ) {
        this.vaultPath = normalized(vaultPath);
        this.calibrationSupplier = Objects.requireNonNull(
            calibrationSupplier,
            "calibrationSupplier"
        );
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cipher = new VaultCipher(secureRandom);
        this.codec = new VaultPayloadCodec();
        this.keyValidator = new VaultKeyMaterialValidator();
        this.fileStore = new VaultFileStore();
    }

    VaultSession create(
        char[] password,
        int autoLockMinutes
    ) throws VaultWriteException {
        validatePassword(password);

        byte[] vaultId = new byte[VaultModelChecks.VAULT_ID_BYTES];
        secureRandom.nextBytes(vaultId);
        byte[] payloadBytes = null;
        byte[] envelope = null;
        VaultPayload payload = null;
        VaultSessionKey sessionKey = null;
        boolean success = false;
        try {
            VaultKdfCalibration calibration = Objects.requireNonNull(
                calibrationSupplier.get(),
                "calibration"
            );
            Instant now = clock.instant();
            payload = new VaultPayload(
                vaultId,
                now,
                now,
                List.of(),
                List.of(),
                new VaultSettings(null, autoLockMinutes)
            );
            keyValidator.validate(payload);
            payloadBytes = codec.encode(payload);
            sessionKey = cipher.deriveSessionKey(
                password,
                calibration.parameters()
            );
            envelope = cipher.seal(payloadBytes, vaultId, sessionKey);
            fileStore.writeNewAtomically(vaultPath, envelope);

            VaultSession session = new VaultSession(payload, sessionKey);
            payload = null;
            sessionKey = null;
            success = true;
            return session;
        } catch (VaultPayloadException | RuntimeException failure) {
            throw new VaultWriteException();
        } finally {
            if (!success) {
                close(payload);
                close(sessionKey);
            }
            clear(envelope);
            clear(payloadBytes);
            clear(vaultId);
        }
    }

    VaultSession open(char[] password) throws VaultOpenException {
        validatePasswordForOpen(password);
        byte[] envelope = null;
        try {
            envelope = fileStore.read(vaultPath);
            return openEnvelope(envelope, password);
        } finally {
            clear(envelope);
        }
    }

    void save(VaultSession session) throws VaultWriteException {
        Objects.requireNonNull(session, "session");
        VaultPayload payload = session.payload();
        VaultSessionKey sessionKey = session.sessionKey();
        byte[] payloadBytes = null;
        byte[] vaultId = null;
        byte[] envelope = null;
        try {
            keyValidator.validate(payload);
            payloadBytes = codec.encode(payload);
            vaultId = payload.vaultId();
            envelope = cipher.seal(payloadBytes, vaultId, sessionKey);
            fileStore.writeAtomically(vaultPath, envelope);
        } catch (VaultPayloadException failure) {
            throw new VaultWriteException();
        } finally {
            clear(envelope);
            clear(vaultId);
            clear(payloadBytes);
        }
    }

    void backup(Path backupPath)
        throws VaultOpenException, VaultWriteException {
        fileStore.copyAtomically(vaultPath, normalized(backupPath));
    }

    VaultSession restore(
        Path backupPath,
        char[] password
    ) throws VaultOpenException, VaultWriteException {
        validatePasswordForOpen(password);
        Path normalizedBackup = normalized(backupPath);
        if (vaultPath.equals(normalizedBackup)) {
            throw new IllegalArgumentException(
                "backupPath must differ from the active Vault"
            );
        }

        byte[] envelope = null;
        VaultSession session = null;
        boolean success = false;
        try {
            envelope = fileStore.read(normalizedBackup);
            session = openEnvelope(envelope, password);
            fileStore.writeAtomically(vaultPath, envelope);
            success = true;
            return session;
        } finally {
            if (!success) {
                close(session);
            }
            clear(envelope);
        }
    }

    private VaultSession openEnvelope(
        byte[] envelope,
        char[] password
    ) throws VaultOpenException {
        OpenedVault opened = null;
        VaultPayload payload = null;
        VaultSessionKey sessionKey = null;
        byte[] plaintext = null;
        byte[] headerVaultId = null;
        boolean success = false;
        try {
            opened = cipher.open(envelope, password);
            plaintext = opened.plaintext();
            headerVaultId = opened.vaultId();
            payload = codec.decode(plaintext, headerVaultId);
            keyValidator.validate(payload);
            sessionKey = opened.takeSessionKey();

            VaultSession session = new VaultSession(payload, sessionKey);
            payload = null;
            sessionKey = null;
            success = true;
            return session;
        } catch (VaultPayloadException | RuntimeException failure) {
            throw new VaultOpenException();
        } finally {
            if (!success) {
                close(payload);
                close(sessionKey);
            }
            close(opened);
            clear(headerVaultId);
            clear(plaintext);
        }
    }

    private static void validatePassword(char[] password) {
        Objects.requireNonNull(password, "password");
        validatePasswordCharacters(password);
        int codePoints = Character.codePointCount(password, 0, password.length);
        if (codePoints < MIN_PASSWORD_CODE_POINTS
            || codePoints > MAX_PASSWORD_CODE_POINTS) {
            throw new IllegalArgumentException(
                "password must contain 12 to 1024 Unicode code points"
            );
        }
    }

    private static void validatePasswordCharacters(char[] password) {
        for (int index = 0; index < password.length; index++) {
            char value = password[index];
            if (value == '\0') {
                throw new IllegalArgumentException("password must not contain NUL");
            }
            if (Character.isHighSurrogate(value)) {
                if (index + 1 >= password.length
                    || !Character.isLowSurrogate(password[index + 1])) {
                    throw new IllegalArgumentException(
                        "password contains an unpaired surrogate"
                    );
                }
                index++;
            } else if (Character.isLowSurrogate(value)) {
                throw new IllegalArgumentException(
                    "password contains an unpaired surrogate"
                );
            }
        }
    }

    private static void validatePasswordForOpen(char[] password)
        throws VaultOpenException {
        try {
            validatePassword(password);
        } catch (RuntimeException failure) {
            throw new VaultOpenException();
        }
    }

    private static Path normalized(Path path) {
        return Objects.requireNonNull(path, "path")
            .toAbsolutePath()
            .normalize();
    }

    private static void close(AutoCloseable value) {
        if (value == null) {
            return;
        }
        try {
            value.close();
        } catch (Exception ignored) {
            // All current Vault close methods are non-throwing and best effort.
        }
    }

    private static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}

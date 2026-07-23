package com.windletter.desktop.selftest;

import com.windletter.api.model.RecipientIdentityRef;
import com.windletter.api.model.RecipientRef;
import com.windletter.api.model.SenderEncryptionIdentityRef;
import com.windletter.api.model.SenderIdentity;
import com.windletter.api.model.SigningIdentityRef;
import com.windletter.api.spi.DecryptionKeyLease;
import com.windletter.api.spi.IdentityService;
import com.windletter.api.spi.RecipientKeyStore;
import com.windletter.api.spi.RecipientPublicKeyMaterial;
import com.windletter.api.spi.RecipientPublicKeyResolver;
import com.windletter.api.spi.SenderEncryptionKeyLease;
import com.windletter.api.spi.SenderEncryptionKeyStore;
import com.windletter.api.spi.SenderPublicKeyResolver;
import com.windletter.api.spi.SigningIdentityLease;
import com.windletter.api.spi.VerificationKeyMaterial;
import com.windletter.api.spi.X25519PublicKeyMaterial;
import com.windletter.crypto.api.Ed25519PrivateKeyHandle;
import com.windletter.crypto.api.X25519PrivateKeyHandle;
import com.windletter.crypto.bc.BouncyCastleEd25519Crypto;
import com.windletter.crypto.bc.BouncyCastleX25519Crypto;
import com.windletter.protocol.key.Ed25519KeyId;
import com.windletter.protocol.key.X25519KeyId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** One-use, in-memory keys for a single phase 1 self-test execution. */
final class EphemeralSelfTestKeys implements
    RecipientPublicKeyResolver,
    SenderEncryptionKeyStore,
    RecipientKeyStore,
    SenderPublicKeyResolver,
    IdentityService,
    AutoCloseable {

    static final String SENDER_ENCRYPTION_ID = "phase-1-sender-encryption";
    static final String SIGNING_ID = "phase-1-signer";
    static final String RECIPIENT_ID = "phase-1-recipient";
    static final String AUTHENTICATED_SENDER = "阶段 1 自检发送者";

    private final AtomicReference<X25519PrivateKeyHandle> senderEncryptionKey;
    private final AtomicReference<X25519PrivateKeyHandle> recipientDecryptionKey;
    private final AtomicReference<Ed25519PrivateKeyHandle> signingKey;
    private final byte[] senderEncryptionPublicKey;
    private final byte[] recipientPublicKey;
    private final byte[] signingPublicKey;
    private final String senderEncryptionKid;
    private final String recipientKid;
    private final String signingKid;

    private EphemeralSelfTestKeys(
        X25519PrivateKeyHandle senderEncryptionKey,
        X25519PrivateKeyHandle recipientDecryptionKey,
        Ed25519PrivateKeyHandle signingKey,
        byte[] senderEncryptionPublicKey,
        byte[] recipientPublicKey,
        byte[] signingPublicKey
    ) {
        this.senderEncryptionKey = new AtomicReference<>(senderEncryptionKey);
        this.recipientDecryptionKey = new AtomicReference<>(recipientDecryptionKey);
        this.signingKey = new AtomicReference<>(signingKey);
        this.senderEncryptionPublicKey = senderEncryptionPublicKey;
        this.recipientPublicKey = recipientPublicKey;
        this.signingPublicKey = signingPublicKey;
        this.senderEncryptionKid = X25519KeyId.derive(senderEncryptionPublicKey);
        this.recipientKid = X25519KeyId.derive(recipientPublicKey);
        this.signingKid = Ed25519KeyId.derive(signingPublicKey);
    }

    static EphemeralSelfTestKeys generate() {
        BouncyCastleX25519Crypto x25519 = new BouncyCastleX25519Crypto();
        BouncyCastleEd25519Crypto ed25519 = new BouncyCastleEd25519Crypto();
        X25519PrivateKeyHandle sender = null;
        X25519PrivateKeyHandle recipient = null;
        Ed25519PrivateKeyHandle signer = null;
        byte[] senderPublic = null;
        byte[] recipientPublic = null;
        byte[] signerPublic = null;
        try {
            sender = x25519.generatePrivateKey();
            recipient = x25519.generatePrivateKey();
            signer = ed25519.generatePrivateKey();
            senderPublic = sender.publicKey();
            recipientPublic = recipient.publicKey();
            signerPublic = signer.publicKey();
            EphemeralSelfTestKeys keys = new EphemeralSelfTestKeys(
                sender,
                recipient,
                signer,
                senderPublic,
                recipientPublic,
                signerPublic
            );
            return keys;
        } catch (RuntimeException | Error failure) {
            Throwable cleanupFailure = closeCapturing(signer, null);
            cleanupFailure = closeCapturing(recipient, cleanupFailure);
            cleanupFailure = closeCapturing(sender, cleanupFailure);
            clear(signerPublic);
            clear(recipientPublic);
            clear(senderPublic);
            if (cleanupFailure != null) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    RecipientRef recipientRef() {
        return new RecipientRef(RECIPIENT_ID, recipientKid, null, Map.of());
    }

    SenderEncryptionIdentityRef senderEncryptionIdentityRef() {
        return new SenderEncryptionIdentityRef(SENDER_ENCRYPTION_ID, null);
    }

    SigningIdentityRef signingIdentityRef() {
        return new SigningIdentityRef(SIGNING_ID, signingKid);
    }

    RecipientIdentityRef recipientIdentityRef() {
        return new RecipientIdentityRef(RECIPIENT_ID, null);
    }

    @Override
    public Optional<RecipientPublicKeyMaterial> resolve(RecipientRef recipient) {
        if (!RECIPIENT_ID.equals(recipient.recipientId())
            || !recipientKid.equals(recipient.x25519Kid())) {
            return Optional.empty();
        }
        return Optional.of(new RecipientPublicKeyMaterial(
            recipientKid,
            recipientPublicKey,
            null,
            null
        ));
    }

    @Override
    public Optional<SenderEncryptionKeyLease> open(SenderEncryptionIdentityRef identity) {
        if (!SENDER_ENCRYPTION_ID.equals(identity.identityId())) {
            return Optional.empty();
        }
        X25519PrivateKeyHandle handle = senderEncryptionKey.getAndSet(null);
        return handle == null
            ? Optional.empty()
            : Optional.of(SenderEncryptionKeyLease.x25519(senderEncryptionKid, handle));
    }

    @Override
    public List<DecryptionKeyLease> openAll(RecipientIdentityRef identity) {
        if (!RECIPIENT_ID.equals(identity.recipientId())) {
            return List.of();
        }
        X25519PrivateKeyHandle handle = recipientDecryptionKey.getAndSet(null);
        return handle == null
            ? List.of()
            : List.of(DecryptionKeyLease.x25519(recipientKid, handle));
    }

    @Override
    public Optional<X25519PublicKeyMaterial> resolveX25519ByKid(String kid) {
        return senderEncryptionKid.equals(kid)
            ? Optional.of(new X25519PublicKeyMaterial(
                senderEncryptionKid,
                senderEncryptionPublicKey
            ))
            : Optional.empty();
    }

    @Override
    public Optional<SigningIdentityLease> openSigningIdentity(SigningIdentityRef identity) {
        if (!SIGNING_ID.equals(identity.identityId())
            || identity.signingKid() != null && !signingKid.equals(identity.signingKid())) {
            return Optional.empty();
        }
        Ed25519PrivateKeyHandle handle = signingKey.getAndSet(null);
        return handle == null
            ? Optional.empty()
            : Optional.of(SigningIdentityLease.ed25519(SIGNING_ID, signingKid, handle));
    }

    @Override
    public Optional<VerificationKeyMaterial> resolveVerificationKeyByKid(String kid) {
        return signingKid.equals(kid)
            ? Optional.of(new VerificationKeyMaterial(signingKid, signingPublicKey, Map.of()))
            : Optional.empty();
    }

    @Override
    public Optional<SenderIdentity> resolveSenderBySigningKid(String kid) {
        return signingKid.equals(kid)
            ? Optional.of(new SenderIdentity(AUTHENTICATED_SENDER, signingKid, Map.of()))
            : Optional.empty();
    }

    @Override
    public void close() {
        Throwable failure = closeCapturing(signingKey.getAndSet(null), null);
        failure = closeCapturing(recipientDecryptionKey.getAndSet(null), failure);
        failure = closeCapturing(senderEncryptionKey.getAndSet(null), failure);
        try {
            rethrow(failure);
        } finally {
            clear(signingPublicKey);
            clear(recipientPublicKey);
            clear(senderEncryptionPublicKey);
        }
    }

    private static Throwable closeCapturing(AutoCloseable handle, Throwable failure) {
        if (handle == null) {
            return failure;
        }
        try {
            handle.close();
        } catch (RuntimeException | Error closeFailure) {
            if (failure == null) {
                return closeFailure;
            }
            failure.addSuppressed(closeFailure);
        } catch (Exception impossible) {
            throw new AssertionError(
                "private-key handle declared an unexpected checked close failure",
                impossible
            );
        }
        return failure;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}

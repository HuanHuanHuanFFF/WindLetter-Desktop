package com.windletter.desktop.vault;

import com.windletter.api.WindLetterReceiver;
import com.windletter.api.WindLetterRuntime;
import com.windletter.api.enums.ArmorFormat;
import com.windletter.api.enums.DecryptStatus;
import com.windletter.api.enums.VerificationPolicy;
import com.windletter.api.enums.VerificationStatus;
import com.windletter.api.model.DecryptRequest;
import com.windletter.api.model.DecryptResult;
import com.windletter.api.model.RecipientIdentityRef;
import com.windletter.api.model.SenderIdentity;
import com.windletter.api.model.SigningIdentityRef;
import com.windletter.api.spi.DecryptionKeyLease;
import com.windletter.api.spi.IdentityService;
import com.windletter.api.spi.RecipientKeyStore;
import com.windletter.api.spi.SenderPublicKeyResolver;
import com.windletter.api.spi.SigningIdentityLease;
import com.windletter.api.spi.VerificationKeyMaterial;
import com.windletter.api.spi.X25519PublicKeyMaterial;
import com.windletter.crypto.api.MLKem768PrivateKeyHandle;
import com.windletter.crypto.api.X25519PrivateKeyHandle;
import com.windletter.crypto.bc.BouncyCastleMLKem768Crypto;
import com.windletter.crypto.bc.BouncyCastleX25519Crypto;
import com.windletter.desktop.receive.ReceiveAuthentication;
import com.windletter.desktop.receive.ReceiveInput;
import com.windletter.desktop.receive.ReceiveResult;
import com.windletter.desktop.receive.ReceiveStatus;
import com.windletter.protocol.ProtocolLimits;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Adapts an unlocked Vault session to the strict real core receiver facade. */
final class VaultReceiveService {

    private static final int MAX_TEXT_INPUT_UTF8_BYTES =
        64 * 1024 * 1024;
    private static final int MAX_BINARY_INPUT_BYTES =
        ProtocolLimits.MAX_WIRE_UTF8_BYTES + 1024;
    private static final Base64.Encoder KID =
        Base64.getUrlEncoder().withoutPadding();

    ReceiveResult receive(VaultSession session, ReceiveInput input) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(input, "input");
        VaultPayload vault = session.payload();
        VaultIdentity recipient = findIdentity(
            vault,
            input.recipientIdentityId()
        );
        validateInput(input);
        VaultReceiverAdapters adapters = new VaultReceiverAdapters(
            recipient,
            vault.contacts()
        );
        WindLetterReceiver receiver = WindLetterRuntime.receiver(
            adapters,
            adapters,
            adapters
        );

        byte[] binary = input.binary();
        try {
            DecryptRequest request = input.isText()
                ? new DecryptRequest(
                    null,
                    input.text(),
                    null,
                    null,
                    new RecipientIdentityRef(
                        recipient.identityId().toString(),
                        null
                    ),
                    VerificationPolicy.ALLOW_UNSIGNED
                )
                : new DecryptRequest(
                    null,
                    null,
                    binary,
                    ArmorFormat.BINARY,
                    new RecipientIdentityRef(
                        recipient.identityId().toString(),
                        null
                    ),
                    VerificationPolicy.ALLOW_UNSIGNED
                );
            return map(receiver.decrypt(request), vault);
        } finally {
            clear(binary);
        }
    }

    private static void validateInput(ReceiveInput input) {
        if (input.isText()) {
            byte[] utf8 = input.text().getBytes(StandardCharsets.UTF_8);
            try {
                if (utf8.length > MAX_TEXT_INPUT_UTF8_BYTES) {
                    throw new IllegalArgumentException(
                        "text Armor exceeds the supported size"
                    );
                }
            } finally {
                clear(utf8);
            }
            return;
        }
        byte[] binary = input.binary();
        try {
            if (binary.length > MAX_BINARY_INPUT_BYTES) {
                throw new IllegalArgumentException(
                    "binary Armor exceeds the supported size"
                );
            }
        } finally {
            clear(binary);
        }
    }

    private static ReceiveResult map(
        DecryptResult coreResult,
        VaultPayload vault
    ) {
        if (coreResult.status() == DecryptStatus.NOT_FOR_ME) {
            return ReceiveResult.failure(ReceiveStatus.NOT_FOR_ME);
        }
        if (coreResult.status() != DecryptStatus.SUCCESS) {
            return ReceiveResult.failure(ReceiveStatus.INVALID_MESSAGE);
        }

        byte[] payload = coreResult.payload().data();
        try {
            if (coreResult.verificationStatus()
                == VerificationStatus.UNSIGNED) {
                return new ReceiveResult(
                    ReceiveStatus.SUCCESS,
                    ReceiveAuthentication.UNSIGNED,
                    payload,
                    coreResult.payload().contentType(),
                    coreResult.payload().originalSize(),
                    null,
                    null,
                    null,
                    false,
                    coreResult.messageId(),
                    coreResult.timestamp()
                );
            }
            if (coreResult.verificationStatus()
                != VerificationStatus.SIGNED_VALID
                || coreResult.senderIdentity() == null) {
                throw new IllegalStateException(
                    "core success has an invalid authentication shape"
                );
            }
            UUID senderContactId = UUID.fromString(
                coreResult.senderIdentity().senderId()
            );
            VaultContact sender = findContact(vault, senderContactId);
            String expectedSigningKid = kid(publicKey(
                sender,
                VaultKeyAlgorithm.ED25519
            ));
            if (!expectedSigningKid.equals(
                coreResult.senderIdentity().signingKid()
            )) {
                throw new IllegalStateException(
                    "authenticated sender does not match the Vault contact"
                );
            }
            return new ReceiveResult(
                ReceiveStatus.SUCCESS,
                ReceiveAuthentication.SIGNED_VALID,
                payload,
                coreResult.payload().contentType(),
                coreResult.payload().originalSize(),
                sender.contactId(),
                sender.claimedDisplayName(),
                expectedSigningKid,
                sender.verificationStatus()
                    == VaultVerificationStatus.FINGERPRINT_VERIFIED,
                coreResult.messageId(),
                coreResult.timestamp()
            );
        } finally {
            clear(payload);
        }
    }

    private static VaultIdentity findIdentity(
        VaultPayload payload,
        UUID identityId
    ) {
        return payload.identities()
            .stream()
            .filter(identity -> identity.identityId().equals(identityId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "recipient identity does not exist"
            ));
    }

    private static VaultContact findContact(
        VaultPayload payload,
        UUID contactId
    ) {
        return payload.contacts()
            .stream()
            .filter(contact -> contact.contactId().equals(contactId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "authenticated sender contact does not exist"
            ));
    }

    private static VaultPrivateKey privateKey(
        VaultIdentity identity,
        VaultKeyAlgorithm algorithm
    ) {
        return identity.keys()
            .stream()
            .filter(key -> key.algorithm() == algorithm)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "recipient identity key is missing"
            ));
    }

    private static VaultPublicKey publicKey(
        VaultContact contact,
        VaultKeyAlgorithm algorithm
    ) {
        return contact.publicKeys()
            .stream()
            .filter(key -> key.algorithm() == algorithm)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "contact public key is missing"
            ));
    }

    private static String kid(VaultPrivateKey key) {
        byte[] value = key.kid();
        try {
            return KID.encodeToString(value);
        } finally {
            clear(value);
        }
    }

    private static String kid(VaultPublicKey key) {
        byte[] value = key.kid();
        try {
            return KID.encodeToString(value);
        } finally {
            clear(value);
        }
    }

    private static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static final class VaultReceiverAdapters
        implements RecipientKeyStore, SenderPublicKeyResolver, IdentityService {

        private final VaultIdentity recipient;
        private final List<VaultContact> contacts;
        private final BouncyCastleX25519Crypto x25519 =
            new BouncyCastleX25519Crypto();
        private final BouncyCastleMLKem768Crypto mlKem768 =
            new BouncyCastleMLKem768Crypto();

        private VaultReceiverAdapters(
            VaultIdentity recipient,
            List<VaultContact> contacts
        ) {
            this.recipient = recipient;
            this.contacts = List.copyOf(contacts);
        }

        @Override
        public List<DecryptionKeyLease> openAll(
            RecipientIdentityRef identity
        ) {
            if (!recipient.identityId().toString().equals(
                identity.recipientId()
            )) {
                return List.of();
            }
            VaultPrivateKey xKey = privateKey(
                recipient,
                VaultKeyAlgorithm.X25519
            );
            VaultPrivateKey kemKey = privateKey(
                recipient,
                VaultKeyAlgorithm.ML_KEM_768
            );
            byte[] xPrivate = xKey.privateKey();
            byte[] kemPrivate = kemKey.privateKey();
            X25519PrivateKeyHandle xHandle = null;
            MLKem768PrivateKeyHandle kemHandle = null;
            try {
                xHandle = x25519.importPrivateKey(xPrivate);
                kemHandle = mlKem768.importPrivateKey(kemPrivate);
                DecryptionKeyLease lease = DecryptionKeyLease.hybrid(
                    kid(xKey),
                    xHandle,
                    kid(kemKey),
                    kemHandle
                );
                xHandle = null;
                kemHandle = null;
                return List.of(lease);
            } finally {
                close(kemHandle);
                close(xHandle);
                clear(kemPrivate);
                clear(xPrivate);
            }
        }

        @Override
        public Optional<X25519PublicKeyMaterial> resolveX25519ByKid(
            String expectedKid
        ) {
            VaultContact contact = uniqueContact(
                expectedKid,
                VaultKeyAlgorithm.X25519
            );
            if (contact == null) {
                return Optional.empty();
            }
            VaultPublicKey key = publicKey(
                contact,
                VaultKeyAlgorithm.X25519
            );
            byte[] value = key.publicKey();
            try {
                return Optional.of(new X25519PublicKeyMaterial(
                    expectedKid,
                    value
                ));
            } finally {
                clear(value);
            }
        }

        @Override
        public Optional<SigningIdentityLease> openSigningIdentity(
            SigningIdentityRef signingIdentityRef
        ) {
            return Optional.empty();
        }

        @Override
        public Optional<VerificationKeyMaterial> resolveVerificationKeyByKid(
            String signingKid
        ) {
            VaultContact contact = uniqueContact(
                signingKid,
                VaultKeyAlgorithm.ED25519
            );
            if (contact == null) {
                return Optional.empty();
            }
            VaultPublicKey key = publicKey(
                contact,
                VaultKeyAlgorithm.ED25519
            );
            byte[] value = key.publicKey();
            try {
                return Optional.of(new VerificationKeyMaterial(
                    signingKid,
                    value,
                    Map.of()
                ));
            } finally {
                clear(value);
            }
        }

        @Override
        public Optional<SenderIdentity> resolveSenderBySigningKid(
            String signingKid
        ) {
            VaultContact contact = uniqueContact(
                signingKid,
                VaultKeyAlgorithm.ED25519
            );
            if (contact == null) {
                return Optional.empty();
            }
            return Optional.of(new SenderIdentity(
                contact.contactId().toString(),
                signingKid,
                Map.of("displayName", contact.claimedDisplayName())
            ));
        }

        private VaultContact uniqueContact(
            String expectedKid,
            VaultKeyAlgorithm algorithm
        ) {
            VaultContact found = null;
            for (VaultContact contact : contacts) {
                VaultPublicKey key = publicKey(contact, algorithm);
                if (!kid(key).equals(expectedKid)) {
                    continue;
                }
                if (found != null) {
                    throw new IllegalStateException(
                        "multiple contacts contain the same key identifier"
                    );
                }
                found = contact;
            }
            return found;
        }

        private static void close(AutoCloseable handle) {
            if (handle == null) {
                return;
            }
            try {
                handle.close();
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception impossible) {
                throw new AssertionError(impossible);
            }
        }
    }
}

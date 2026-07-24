package com.windletter.desktop.vault;

import com.windletter.api.WindLetterRuntime;
import com.windletter.api.WindLetterSender;
import com.windletter.api.enums.ArmorFormat;
import com.windletter.api.enums.KeyAlgProfile;
import com.windletter.api.enums.WindMode;
import com.windletter.api.model.EncryptAndSignRequest;
import com.windletter.api.model.EncryptRequest;
import com.windletter.api.model.EncryptedMessage;
import com.windletter.api.model.Payload;
import com.windletter.api.model.RecipientRef;
import com.windletter.api.model.SenderEncryptionIdentityRef;
import com.windletter.api.model.SenderIdentity;
import com.windletter.api.model.SigningIdentityRef;
import com.windletter.api.spi.IdentityService;
import com.windletter.api.spi.RecipientPublicKeyMaterial;
import com.windletter.api.spi.RecipientPublicKeyResolver;
import com.windletter.api.spi.SenderEncryptionKeyLease;
import com.windletter.api.spi.SenderEncryptionKeyStore;
import com.windletter.api.spi.SigningIdentityLease;
import com.windletter.api.spi.VerificationKeyMaterial;
import com.windletter.crypto.api.Ed25519PrivateKeyHandle;
import com.windletter.crypto.api.X25519PrivateKeyHandle;
import com.windletter.crypto.bc.BouncyCastleEd25519Crypto;
import com.windletter.crypto.bc.BouncyCastleX25519Crypto;
import com.windletter.desktop.send.SendKeyProfile;
import com.windletter.desktop.send.SendMode;
import com.windletter.desktop.send.SendOutputFormat;
import com.windletter.desktop.send.SendRequest;
import com.windletter.desktop.send.SendResult;
import com.windletter.protocol.ProtocolLimits;

import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Adapts one unlocked Vault session to the real core sender facade. */
final class VaultSendService {

    private static final int MAX_RECIPIENTS = 32;
    private static final Base64.Encoder KID =
        Base64.getUrlEncoder().withoutPadding();

    SendResult send(VaultSession session, SendRequest request) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(request, "request");
        validate(request);

        VaultPayload vault = session.payload();
        VaultIdentity senderIdentity = requiresSenderIdentity(request)
            ? findIdentity(vault, request.senderIdentityId())
            : null;
        List<VaultContact> recipients = request.recipientContactIds()
            .stream()
            .map(contactId -> findContact(vault, contactId))
            .toList();
        VaultSenderAdapters adapters = new VaultSenderAdapters(
            senderIdentity,
            recipients
        );
        WindLetterSender sender = WindLetterRuntime.sender(
            adapters,
            adapters,
            adapters
        );

        byte[] payloadBytes = request.payload().data();
        try {
            Payload payload = new Payload(
                request.payload().contentType(),
                payloadBytes,
                payloadBytes.length
            );
            List<RecipientRef> recipientRefs = recipients.stream()
                .map(contact -> recipientRef(contact, request.keyProfile()))
                .toList();
            SenderEncryptionIdentityRef encryptionIdentity =
                request.mode() == SendMode.PUBLIC
                    ? new SenderEncryptionIdentityRef(
                        senderIdentity.identityId().toString(),
                        null
                    )
                    : null;

            EncryptedMessage encrypted;
            if (request.signed()) {
                encrypted = sender.encryptAndSign(new EncryptAndSignRequest(
                    windMode(request.mode()),
                    keyProfile(request.keyProfile()),
                    armorFormat(request.outputFormat()),
                    payload,
                    recipientRefs,
                    Map.of(),
                    encryptionIdentity,
                    new SigningIdentityRef(
                        senderIdentity.identityId().toString(),
                        kid(privateKey(
                            senderIdentity,
                            VaultKeyAlgorithm.ED25519
                        ))
                    )
                ));
            } else {
                encrypted = sender.encrypt(new EncryptRequest(
                    windMode(request.mode()),
                    keyProfile(request.keyProfile()),
                    armorFormat(request.outputFormat()),
                    payload,
                    recipientRefs,
                    Map.of(),
                    encryptionIdentity
                ));
            }
            return result(request, encrypted);
        } finally {
            clear(payloadBytes);
        }
    }

    private static void validate(SendRequest request) {
        int recipientCount = request.recipientContactIds().size();
        if (recipientCount < 1 || recipientCount > MAX_RECIPIENTS) {
            throw new IllegalArgumentException(
                "recipient count must be between 1 and 32"
            );
        }
        Set<UUID> uniqueRecipients =
            new HashSet<>(request.recipientContactIds());
        if (uniqueRecipients.size() != recipientCount
            || uniqueRecipients.contains(null)) {
            throw new IllegalArgumentException(
                "recipient contacts must be unique and non-null"
            );
        }
        String contentType = request.payload().contentType();
        if (contentType.isBlank() || contentType.length() > 255) {
            throw new IllegalArgumentException("content type is invalid");
        }
        if (request.payload().size() > ProtocolLimits.MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("payload is too large");
        }
        if (requiresSenderIdentity(request)
            && request.senderIdentityId() == null) {
            throw new IllegalArgumentException(
                "sender identity is required"
            );
        }
    }

    private static boolean requiresSenderIdentity(SendRequest request) {
        return request.mode() == SendMode.PUBLIC || request.signed();
    }

    private static SendResult result(
        SendRequest request,
        EncryptedMessage encrypted
    ) {
        if (request.outputFormat() == SendOutputFormat.BINARY) {
            return new SendResult(
                request.outputFormat(),
                null,
                encrypted.armorBytes(),
                request.payload().size()
            );
        }
        return new SendResult(
            request.outputFormat(),
            encrypted.armor(),
            null,
            request.payload().size()
        );
    }

    private static RecipientRef recipientRef(
        VaultContact contact,
        SendKeyProfile profile
    ) {
        String mlKemKid = profile == SendKeyProfile.X25519_ML_KEM_768
            ? kid(publicKey(contact, VaultKeyAlgorithm.ML_KEM_768))
            : null;
        return new RecipientRef(
            contact.contactId().toString(),
            kid(publicKey(contact, VaultKeyAlgorithm.X25519)),
            mlKemKid,
            Map.of()
        );
    }

    private static WindMode windMode(SendMode mode) {
        return mode == SendMode.PUBLIC
            ? WindMode.PUBLIC
            : WindMode.OBFUSCATION;
    }

    private static KeyAlgProfile keyProfile(SendKeyProfile profile) {
        return profile == SendKeyProfile.X25519
            ? KeyAlgProfile.X25519
            : KeyAlgProfile.X25519_ML_KEM_768;
    }

    private static ArmorFormat armorFormat(SendOutputFormat format) {
        return switch (format) {
            case BASE64_PEM -> ArmorFormat.BASE64_PEM;
            case WIND_BASE_1024F_V1 ->
                ArmorFormat.WIND_BASE_1024F_V1;
            case BINARY -> ArmorFormat.BINARY;
        };
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
                "sender identity does not exist"
            ));
    }

    private static VaultContact findContact(
        VaultPayload payload,
        UUID contactId
    ) {
        Objects.requireNonNull(contactId, "contactId");
        return payload.contacts()
            .stream()
            .filter(contact -> contact.contactId().equals(contactId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "recipient contact does not exist"
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
            .orElseThrow(() -> new IllegalArgumentException(
                "sender identity key is missing"
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
            .orElseThrow(() -> new IllegalArgumentException(
                "recipient public key is missing"
            ));
    }

    private static String kid(VaultPrivateKey key) {
        byte[] bytes = key.kid();
        try {
            return KID.encodeToString(bytes);
        } finally {
            clear(bytes);
        }
    }

    private static String kid(VaultPublicKey key) {
        byte[] bytes = key.kid();
        try {
            return KID.encodeToString(bytes);
        } finally {
            clear(bytes);
        }
    }

    private static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static final class VaultSenderAdapters
        implements RecipientPublicKeyResolver,
        SenderEncryptionKeyStore,
        IdentityService {

        private final VaultIdentity senderIdentity;
        private final Map<UUID, VaultContact> recipients;
        private final BouncyCastleX25519Crypto x25519 =
            new BouncyCastleX25519Crypto();
        private final BouncyCastleEd25519Crypto ed25519 =
            new BouncyCastleEd25519Crypto();

        private VaultSenderAdapters(
            VaultIdentity senderIdentity,
            List<VaultContact> recipients
        ) {
            this.senderIdentity = senderIdentity;
            this.recipients = recipients.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    VaultContact::contactId,
                    contact -> contact
                )
            );
        }

        @Override
        public Optional<RecipientPublicKeyMaterial> resolve(
            RecipientRef recipient
        ) {
            UUID recipientId = parseId(recipient.recipientId());
            if (recipientId == null) {
                return Optional.empty();
            }
            VaultContact contact = recipients.get(recipientId);
            if (contact == null) {
                return Optional.empty();
            }
            VaultPublicKey xKey = publicKey(
                contact,
                VaultKeyAlgorithm.X25519
            );
            VaultPublicKey kemKey = publicKey(
                contact,
                VaultKeyAlgorithm.ML_KEM_768
            );
            if (!kid(xKey).equals(recipient.x25519Kid())) {
                return Optional.empty();
            }
            if (recipient.mlkem768Kid() != null
                && !kid(kemKey).equals(recipient.mlkem768Kid())) {
                return Optional.empty();
            }
            byte[] xPublic = xKey.publicKey();
            byte[] kemPublic = kemKey.publicKey();
            try {
                return Optional.of(new RecipientPublicKeyMaterial(
                    kid(xKey),
                    xPublic,
                    kid(kemKey),
                    kemPublic
                ));
            } finally {
                clear(kemPublic);
                clear(xPublic);
            }
        }

        @Override
        public Optional<SenderEncryptionKeyLease> open(
            SenderEncryptionIdentityRef identity
        ) {
            if (!matchesSender(identity.identityId())) {
                return Optional.empty();
            }
            VaultPrivateKey key = privateKey(
                senderIdentity,
                VaultKeyAlgorithm.X25519
            );
            byte[] privateBytes = key.privateKey();
            X25519PrivateKeyHandle handle = null;
            try {
                handle = x25519.importPrivateKey(privateBytes);
                SenderEncryptionKeyLease lease =
                    SenderEncryptionKeyLease.x25519(kid(key), handle);
                handle = null;
                return Optional.of(lease);
            } finally {
                close(handle);
                clear(privateBytes);
            }
        }

        @Override
        public Optional<SigningIdentityLease> openSigningIdentity(
            SigningIdentityRef identity
        ) {
            if (!matchesSender(identity.identityId())) {
                return Optional.empty();
            }
            VaultPrivateKey key = privateKey(
                senderIdentity,
                VaultKeyAlgorithm.ED25519
            );
            String signingKid = kid(key);
            if (identity.signingKid() != null
                && !signingKid.equals(identity.signingKid())) {
                return Optional.empty();
            }
            byte[] privateBytes = key.privateKey();
            Ed25519PrivateKeyHandle handle = null;
            try {
                handle = ed25519.importPrivateKey(privateBytes);
                SigningIdentityLease lease =
                    SigningIdentityLease.ed25519(
                        senderIdentity.identityId().toString(),
                        signingKid,
                        handle
                    );
                handle = null;
                return Optional.of(lease);
            } finally {
                close(handle);
                clear(privateBytes);
            }
        }

        @Override
        public Optional<VerificationKeyMaterial> resolveVerificationKeyByKid(
            String signingKid
        ) {
            return Optional.empty();
        }

        @Override
        public Optional<SenderIdentity> resolveSenderBySigningKid(
            String signingKid
        ) {
            return Optional.empty();
        }

        private boolean matchesSender(String identityId) {
            if (senderIdentity == null) {
                return false;
            }
            return senderIdentity.identityId().toString().equals(identityId);
        }

        private static UUID parseId(String value) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException failure) {
                return null;
            }
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

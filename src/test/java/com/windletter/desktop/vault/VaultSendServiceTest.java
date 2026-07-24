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
import com.windletter.desktop.send.SendKeyProfile;
import com.windletter.desktop.send.SendMode;
import com.windletter.desktop.send.SendOutputFormat;
import com.windletter.desktop.send.SendPayload;
import com.windletter.desktop.send.SendRequest;
import com.windletter.desktop.send.SendResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VaultSendServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-24T08:00:00Z");
    private static final char[] PASSWORD =
        "correct horse battery staple".toCharArray();

    @TempDir
    Path directory;

    @Test
    void shouldGenerateEveryRealSendCombinationAndRoundTripThroughCore()
        throws Exception {
        Path recipientPath = directory.resolve("recipient.wlv");
        Path senderPath = directory.resolve("sender.wlv");
        UUID recipientIdentityId;
        String recipientPublicIdentity;
        try (DesktopVault recipient = desktopVault(recipientPath)) {
            recipient.create(password(), 15);
            recipientIdentityId = recipient.createIdentity("收件人", null);
            recipientPublicIdentity = recipient.exportPublicIdentity(
                recipientIdentityId,
                DesktopVault.PublicIdentityArmor.BASE64_PEM
            );
        }

        UUID senderIdentityId;
        UUID contactId;
        String senderPublicIdentity;
        try (DesktopVault sender = desktopVault(senderPath)) {
            sender.create(password(), 15);
            senderIdentityId = sender.createIdentity("发送者", null);
            senderPublicIdentity = sender.exportPublicIdentity(
                senderIdentityId,
                DesktopVault.PublicIdentityArmor.BASE64_PEM
            );
            contactId = sender.importContact(recipientPublicIdentity);

            byte[] payload = "阶段三真实发送 🌬️".getBytes(StandardCharsets.UTF_8);
            for (SendMode mode : SendMode.values()) {
                for (SendKeyProfile profile : SendKeyProfile.values()) {
                    for (boolean signed : List.of(false, true)) {
                        SendOutputFormat outputFormat = outputFormat(
                            mode,
                            profile,
                            signed
                        );
                        SendResult result = sender.send(new SendRequest(
                            senderIdentityId,
                            List.of(contactId),
                            mode,
                            profile,
                            signed,
                            outputFormat,
                            new SendPayload(
                                "text/plain;charset=UTF-8",
                                payload
                            )
                        ));

                        assertEquals(outputFormat, result.outputFormat());
                        assertEquals(payload.length, result.payloadBytes());
                        if (outputFormat == SendOutputFormat.BINARY) {
                            assertNull(result.text());
                            assertNotNull(result.binary());
                        } else {
                            assertNotNull(result.text());
                            assertNull(result.binary());
                        }

                        DecryptResult decrypted = decrypt(
                            recipientPath,
                            recipientIdentityId,
                            senderPublicIdentity,
                            result
                        );
                        assertEquals(DecryptStatus.SUCCESS, decrypted.status());
                        assertEquals(
                            signed
                                ? VerificationStatus.SIGNED_VALID
                                : VerificationStatus.UNSIGNED,
                            decrypted.verificationStatus()
                        );
                        assertArrayEquals(payload, decrypted.payload().data());
                    }
                }
            }
        }
    }

    @Test
    void shouldRejectMissingSelectionsAndOversizedPayloadWithoutOutput()
        throws Exception {
        Path path = directory.resolve("sender.wlv");
        try (DesktopVault sender = desktopVault(path)) {
            sender.create(password(), 15);
            UUID senderId = sender.createIdentity("发送者", null);

            DesktopVaultException noRecipient = assertThrows(
                DesktopVaultException.class,
                () -> sender.send(new SendRequest(
                    senderId,
                    List.of(),
                    SendMode.PUBLIC,
                    SendKeyProfile.X25519,
                    true,
                    SendOutputFormat.BASE64_PEM,
                    new SendPayload("text/plain", new byte[0])
                ))
            );
            assertEquals(
                DesktopVaultProblem.SEND_FAILED,
                noRecipient.problem()
            );
            assertNull(noRecipient.getCause());

            DesktopVaultException oversized = assertThrows(
                DesktopVaultException.class,
                () -> sender.send(new SendRequest(
                    senderId,
                    List.of(UUID.randomUUID()),
                    SendMode.OBFUSCATION,
                    SendKeyProfile.X25519,
                    false,
                    SendOutputFormat.BINARY,
                    new SendPayload(
                        "application/octet-stream",
                        new byte[8 * 1024 * 1024 + 1]
                    )
                ))
            );
            assertEquals(
                DesktopVaultProblem.SEND_FAILED,
                oversized.problem()
            );
            assertNull(oversized.getCause());
        }
    }

    @Test
    void shouldSendOneMessageToMultipleRealRecipients() throws Exception {
        RecipientSetup first = createRecipient(
            directory.resolve("recipient-one.wlv"),
            "收件人一"
        );
        RecipientSetup second = createRecipient(
            directory.resolve("recipient-two.wlv"),
            "收件人二"
        );
        Path senderPath = directory.resolve("multi-sender.wlv");
        byte[] payload = "同一条多收件人消息".getBytes(StandardCharsets.UTF_8);
        try (DesktopVault sender = desktopVault(senderPath)) {
            sender.create(password(), 15);
            UUID senderId = sender.createIdentity("发送者", null);
            String senderPublicIdentity = sender.exportPublicIdentity(
                senderId,
                DesktopVault.PublicIdentityArmor.BASE64_PEM
            );
            UUID firstContact = sender.importContact(first.publicIdentity());
            UUID secondContact = sender.importContact(second.publicIdentity());

            SendResult result = sender.send(new SendRequest(
                senderId,
                List.of(firstContact, secondContact),
                SendMode.OBFUSCATION,
                SendKeyProfile.X25519_ML_KEM_768,
                true,
                SendOutputFormat.BINARY,
                new SendPayload("application/octet-stream", payload)
            ));

            for (RecipientSetup recipient : List.of(first, second)) {
                DecryptResult decrypted = decrypt(
                    recipient.path(),
                    recipient.identityId(),
                    senderPublicIdentity,
                    result
                );
                assertEquals(DecryptStatus.SUCCESS, decrypted.status());
                assertEquals(
                    VerificationStatus.SIGNED_VALID,
                    decrypted.verificationStatus()
                );
                assertArrayEquals(payload, decrypted.payload().data());
            }
        }
    }

    private DecryptResult decrypt(
        Path recipientPath,
        UUID recipientIdentityId,
        String senderPublicIdentity,
        SendResult result
    ) throws Exception {
        VaultService recipientService = service(recipientPath);
        PublicIdentity senderIdentity =
            new PublicIdentityCodec().decodeExchange(senderPublicIdentity);
        try (VaultSession session = recipientService.open(password())) {
            ReceiverKeys receiverKeys = new ReceiverKeys(
                session.payload(),
                recipientIdentityId,
                senderIdentity
            );
            WindLetterReceiver receiver = WindLetterRuntime.receiver(
                receiverKeys,
                receiverKeys,
                receiverKeys
            );
            DecryptRequest request = result.outputFormat()
                == SendOutputFormat.BINARY
                ? new DecryptRequest(
                    null,
                    null,
                    result.binary(),
                    ArmorFormat.BINARY,
                    new RecipientIdentityRef(
                        recipientIdentityId.toString(),
                        null
                    ),
                    VerificationPolicy.ALLOW_UNSIGNED
                )
                : new DecryptRequest(
                    null,
                    result.text(),
                    null,
                    null,
                    new RecipientIdentityRef(
                        recipientIdentityId.toString(),
                        null
                    ),
                    VerificationPolicy.ALLOW_UNSIGNED
                );
            return receiver.decrypt(request);
        }
    }

    private static SendOutputFormat outputFormat(
        SendMode mode,
        SendKeyProfile profile,
        boolean signed
    ) {
        int selector = mode.ordinal() + profile.ordinal() + (signed ? 1 : 0);
        return SendOutputFormat.values()[selector % SendOutputFormat.values().length];
    }

    private RecipientSetup createRecipient(Path path, String displayName)
        throws Exception {
        try (DesktopVault recipient = desktopVault(path)) {
            recipient.create(password(), 15);
            UUID identityId = recipient.createIdentity(displayName, null);
            return new RecipientSetup(
                path,
                identityId,
                recipient.exportPublicIdentity(
                    identityId,
                    DesktopVault.PublicIdentityArmor.BASE64_PEM
                )
            );
        }
    }

    private static DesktopVault desktopVault(Path path) {
        return new DesktopVault(
            path,
            VaultSendServiceTest::calibration,
            new SecureRandom(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            new ScheduledVaultAutoLockScheduler()
        );
    }

    private static VaultService service(Path path) {
        return new VaultService(
            path,
            VaultSendServiceTest::calibration,
            new SecureRandom(),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static VaultKdfCalibration calibration() {
        return new VaultKdfCalibration(
            VaultKdfParameters.minimumSupported(),
            1,
            VaultKdfCalibrator.DEFAULT_TARGET_MILLIS
        );
    }

    private static char[] password() {
        return PASSWORD.clone();
    }

    private static final class ReceiverKeys
        implements RecipientKeyStore, SenderPublicKeyResolver, IdentityService {

        private static final Base64.Encoder KID =
            Base64.getUrlEncoder().withoutPadding();

        private final VaultIdentity recipient;
        private final PublicIdentity sender;
        private final BouncyCastleX25519Crypto x25519 =
            new BouncyCastleX25519Crypto();
        private final BouncyCastleMLKem768Crypto mlKem768 =
            new BouncyCastleMLKem768Crypto();

        private ReceiverKeys(
            VaultPayload payload,
            UUID recipientIdentityId,
            PublicIdentity sender
        ) {
            this.recipient = payload.identities()
                .stream()
                .filter(identity ->
                    identity.identityId().equals(recipientIdentityId))
                .findFirst()
                .orElseThrow();
            this.sender = sender;
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
            VaultPublicKey key = publicKey(
                sender,
                VaultKeyAlgorithm.X25519
            );
            if (!kid(key).equals(expectedKid)) {
                return Optional.empty();
            }
            byte[] bytes = key.publicKey();
            try {
                return Optional.of(new X25519PublicKeyMaterial(
                    expectedKid,
                    bytes
                ));
            } finally {
                clear(bytes);
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
            VaultPublicKey key = publicKey(
                sender,
                VaultKeyAlgorithm.ED25519
            );
            if (!kid(key).equals(signingKid)) {
                return Optional.empty();
            }
            byte[] bytes = key.publicKey();
            try {
                return Optional.of(new VerificationKeyMaterial(
                    signingKid,
                    bytes,
                    Map.of()
                ));
            } finally {
                clear(bytes);
            }
        }

        @Override
        public Optional<SenderIdentity> resolveSenderBySigningKid(
            String signingKid
        ) {
            VaultPublicKey key = publicKey(
                sender,
                VaultKeyAlgorithm.ED25519
            );
            if (!kid(key).equals(signingKid)) {
                return Optional.empty();
            }
            return Optional.of(new SenderIdentity(
                "sender",
                signingKid,
                Map.of("displayName", sender.displayName())
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
                .orElseThrow();
        }

        private static VaultPublicKey publicKey(
            PublicIdentity identity,
            VaultKeyAlgorithm algorithm
        ) {
            return identity.publicKeys()
                .stream()
                .filter(key -> key.algorithm() == algorithm)
                .findFirst()
                .orElseThrow();
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

        private static void close(AutoCloseable value) {
            if (value == null) {
                return;
            }
            try {
                value.close();
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        private static void clear(byte[] value) {
            if (value != null) {
                Arrays.fill(value, (byte) 0);
            }
        }
    }

    private record RecipientSetup(
        Path path,
        UUID identityId,
        String publicIdentity
    ) {
    }
}

package com.windletter.desktop.selftest;

import com.windletter.api.WindLetterReceiver;
import com.windletter.api.WindLetterRuntime;
import com.windletter.api.WindLetterSender;
import com.windletter.api.enums.ArmorFormat;
import com.windletter.api.enums.DecryptStatus;
import com.windletter.api.enums.KeyAlgProfile;
import com.windletter.api.enums.VerificationPolicy;
import com.windletter.api.enums.VerificationStatus;
import com.windletter.api.enums.WindMode;
import com.windletter.api.model.DecryptRequest;
import com.windletter.api.model.DecryptResult;
import com.windletter.api.model.EncryptAndSignRequest;
import com.windletter.api.model.EncryptedMessage;
import com.windletter.api.model.Payload;
import com.windletter.api.model.RecipientIdentityRef;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Executes the phase 1 real-core, in-memory send/receive self-test. */
public final class RoundTripSelfTestService {

    static final String BASE64_PEM_HEADER = "-----BEGIN WIND LETTER-----";

    public SelfTestReport run(String payloadText) {
        Objects.requireNonNull(payloadText, "payloadText");
        byte[] originalPayload = payloadText.getBytes(StandardCharsets.UTF_8);
        byte[] restoredPayload = null;
        long startedAt = System.nanoTime();

        try (EphemeralSelfTestKeys keys = EphemeralSelfTestKeys.generate()) {
            WindLetterSender sender = WindLetterRuntime.sender(keys, keys, keys);
            WindLetterReceiver receiver = WindLetterRuntime.receiver(keys, keys, keys);
            Payload payload = new Payload(
                "text/plain;charset=UTF-8",
                originalPayload,
                originalPayload.length
            );

            EncryptedMessage encrypted = sender.encryptAndSign(new EncryptAndSignRequest(
                WindMode.PUBLIC,
                KeyAlgProfile.X25519,
                ArmorFormat.BASE64_PEM,
                payload,
                List.of(keys.recipientRef()),
                Map.of(),
                keys.senderEncryptionIdentityRef(),
                keys.signingIdentityRef()
            ));

            String armor = encrypted.armor();
            boolean pemHeaderValid = armor != null
                && (armor.startsWith(BASE64_PEM_HEADER + "\n")
                    || armor.startsWith(BASE64_PEM_HEADER + "\r\n"));

            DecryptResult truncated = receiver.decrypt(textRequest(
                truncateBeforeFooter(armor),
                keys.recipientIdentityRef()
            ));
            DecryptResult wrongRecipient = receiver.decrypt(textRequest(
                armor,
                new RecipientIdentityRef("phase-1-wrong-recipient", null)
            ));
            DecryptResult decrypted = receiver.decrypt(textRequest(
                armor,
                keys.recipientIdentityRef()
            ));

            if (decrypted.payload() != null) {
                restoredPayload = decrypted.payload().data();
            }
            boolean payloadMatches = Arrays.equals(originalPayload, restoredPayload);
            String authenticatedSender = decrypted.senderIdentity() == null
                ? null
                : decrypted.senderIdentity().senderId();
            boolean successful = pemHeaderValid
                && truncated.status() == DecryptStatus.INVALID_MESSAGE
                && truncated.payload() == null
                && wrongRecipient.status() == DecryptStatus.NOT_FOR_ME
                && wrongRecipient.payload() == null
                && decrypted.status() == DecryptStatus.SUCCESS
                && decrypted.verificationStatus() == VerificationStatus.SIGNED_VALID
                && decrypted.senderIdentity() != null
                && payloadMatches;

            return new SelfTestReport(
                successful,
                pemHeaderValid,
                decrypted.status(),
                decrypted.verificationStatus(),
                authenticatedSender,
                payloadMatches,
                truncated.status(),
                truncated.payload() == null,
                wrongRecipient.status(),
                wrongRecipient.payload() == null,
                armor == null ? 0 : armor.length(),
                elapsedMillis(startedAt)
            );
        } finally {
            Arrays.fill(originalPayload, (byte) 0);
            if (restoredPayload != null) {
                Arrays.fill(restoredPayload, (byte) 0);
            }
        }
    }

    private static DecryptRequest textRequest(
        String armor,
        RecipientIdentityRef recipientIdentity
    ) {
        return new DecryptRequest(
            null,
            armor,
            null,
            null,
            recipientIdentity,
            VerificationPolicy.REQUIRE_SIGNED_VALID
        );
    }

    private static String truncateBeforeFooter(String armor) {
        if (armor == null) {
            return BASE64_PEM_HEADER + "\nA";
        }
        int minimum = Math.min(armor.length(), BASE64_PEM_HEADER.length() + 2);
        int midpoint = Math.max(minimum, armor.length() / 2);
        return armor.substring(0, midpoint);
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}

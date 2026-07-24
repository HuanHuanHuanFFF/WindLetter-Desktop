package com.windletter.desktop.receive;

import java.util.Objects;
import java.util.UUID;

/** Safe desktop receive result; failure shapes never contain payload or sender data. */
public record ReceiveResult(
    ReceiveStatus status,
    ReceiveAuthentication authentication,
    byte[] payload,
    String contentType,
    long originalSize,
    UUID senderContactId,
    String senderDisplayName,
    String senderSigningKid,
    boolean senderFingerprintVerified,
    String messageId,
    long timestamp
) {

    public ReceiveResult {
        status = Objects.requireNonNull(status, "status");
        authentication = Objects.requireNonNull(
            authentication,
            "authentication"
        );
        payload = payload == null ? null : payload.clone();
        if (status == ReceiveStatus.SUCCESS) {
            if (payload == null
                || contentType == null
                || contentType.isBlank()
                || originalSize != payload.length
                || messageId == null
                || messageId.isBlank()
                || timestamp <= 0) {
                throw new IllegalArgumentException(
                    "successful receive result is incomplete"
                );
            }
            if (authentication == ReceiveAuthentication.SIGNED_VALID) {
                if (senderContactId == null
                    || senderDisplayName == null
                    || senderDisplayName.isBlank()
                    || senderSigningKid == null
                    || senderSigningKid.isBlank()) {
                    throw new IllegalArgumentException(
                        "signed result requires a resolved sender"
                    );
                }
            } else if (authentication == ReceiveAuthentication.UNSIGNED) {
                if (senderContactId != null
                    || senderDisplayName != null
                    || senderSigningKid != null
                    || senderFingerprintVerified) {
                    throw new IllegalArgumentException(
                        "unsigned result must not claim a sender"
                    );
                }
            } else {
                throw new IllegalArgumentException(
                    "successful result has invalid authentication"
                );
            }
        } else if (authentication != ReceiveAuthentication.NOT_APPLICABLE
            || payload != null
            || contentType != null
            || originalSize != 0
            || senderContactId != null
            || senderDisplayName != null
            || senderSigningKid != null
            || senderFingerprintVerified
            || messageId != null
            || timestamp != 0) {
            throw new IllegalArgumentException(
                "failed receive result must not expose protected data"
            );
        }
    }

    @Override
    public byte[] payload() {
        return payload == null ? null : payload.clone();
    }

    public static ReceiveResult failure(ReceiveStatus status) {
        if (status == ReceiveStatus.SUCCESS) {
            throw new IllegalArgumentException(
                "SUCCESS is not a failure status"
            );
        }
        return new ReceiveResult(
            status,
            ReceiveAuthentication.NOT_APPLICABLE,
            null,
            null,
            0,
            null,
            null,
            null,
            false,
            null,
            0
        );
    }
}

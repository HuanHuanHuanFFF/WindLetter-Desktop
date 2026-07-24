package com.windletter.desktop.receive;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Caller-owned desktop receive result.
 *
 * <p>Successful results own plaintext payload bytes and must be closed when
 * the UI replaces or releases them. Failure shapes never contain payload or
 * sender data.</p>
 */
public final class ReceiveResult implements AutoCloseable {

    private final ReceiveStatus status;
    private final ReceiveAuthentication authentication;
    private byte[] payload;
    private final String contentType;
    private final long originalSize;
    private final UUID senderContactId;
    private final String senderDisplayName;
    private final String senderSigningKid;
    private final boolean senderFingerprintVerified;
    private final String messageId;
    private final long timestamp;
    private boolean closed;

    public ReceiveResult(
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
        this.status = Objects.requireNonNull(status, "status");
        this.authentication = Objects.requireNonNull(
            authentication,
            "authentication"
        );
        this.payload = payload == null ? null : payload.clone();
        this.contentType = contentType;
        this.originalSize = originalSize;
        this.senderContactId = senderContactId;
        this.senderDisplayName = senderDisplayName;
        this.senderSigningKid = senderSigningKid;
        this.senderFingerprintVerified = senderFingerprintVerified;
        this.messageId = messageId;
        this.timestamp = timestamp;
        validate();
    }

    public ReceiveStatus status() {
        return status;
    }

    public ReceiveAuthentication authentication() {
        return authentication;
    }

    public synchronized byte[] payload() {
        if (closed && status == ReceiveStatus.SUCCESS) {
            throw new IllegalStateException("receive payload is closed");
        }
        return payload == null ? null : payload.clone();
    }

    public String contentType() {
        return contentType;
    }

    public long originalSize() {
        return originalSize;
    }

    public UUID senderContactId() {
        return senderContactId;
    }

    public String senderDisplayName() {
        return senderDisplayName;
    }

    public String senderSigningKid() {
        return senderSigningKid;
    }

    public boolean senderFingerprintVerified() {
        return senderFingerprintVerified;
    }

    public String messageId() {
        return messageId;
    }

    public long timestamp() {
        return timestamp;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (payload != null) {
            Arrays.fill(payload, (byte) 0);
            payload = null;
        }
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

    private void validate() {
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
            return;
        }
        if (authentication != ReceiveAuthentication.NOT_APPLICABLE
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
}

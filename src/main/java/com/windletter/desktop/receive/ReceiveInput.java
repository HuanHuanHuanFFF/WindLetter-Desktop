package com.windletter.desktop.receive;

import java.util.Objects;
import java.util.UUID;

/** Exactly one text Armor or binary Armor input for a selected local identity. */
public record ReceiveInput(
    UUID recipientIdentityId,
    String text,
    byte[] binary
) {

    public ReceiveInput {
        recipientIdentityId = Objects.requireNonNull(
            recipientIdentityId,
            "recipientIdentityId"
        );
        binary = binary == null ? null : binary.clone();
        boolean hasText = text != null && !text.isBlank();
        boolean hasBinary = binary != null && binary.length > 0;
        if (hasText == hasBinary) {
            throw new IllegalArgumentException(
                "exactly one receive representation is required"
            );
        }
    }

    public static ReceiveInput text(
        UUID recipientIdentityId,
        String text
    ) {
        return new ReceiveInput(recipientIdentityId, text, null);
    }

    public static ReceiveInput binary(
        UUID recipientIdentityId,
        byte[] binary
    ) {
        return new ReceiveInput(recipientIdentityId, null, binary);
    }

    @Override
    public byte[] binary() {
        return binary == null ? null : binary.clone();
    }

    public boolean isText() {
        return text != null;
    }
}

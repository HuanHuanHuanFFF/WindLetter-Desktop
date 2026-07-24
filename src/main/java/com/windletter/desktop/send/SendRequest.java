package com.windletter.desktop.send;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Complete user intent for one Wind Letter send operation. */
public record SendRequest(
    UUID senderIdentityId,
    List<UUID> recipientContactIds,
    SendMode mode,
    SendKeyProfile keyProfile,
    boolean signed,
    SendOutputFormat outputFormat,
    SendPayload payload
) {

    public SendRequest {
        recipientContactIds = List.copyOf(
            Objects.requireNonNull(
                recipientContactIds,
                "recipientContactIds"
            )
        );
        mode = Objects.requireNonNull(mode, "mode");
        keyProfile = Objects.requireNonNull(keyProfile, "keyProfile");
        outputFormat = Objects.requireNonNull(
            outputFormat,
            "outputFormat"
        );
        payload = Objects.requireNonNull(payload, "payload");
    }
}

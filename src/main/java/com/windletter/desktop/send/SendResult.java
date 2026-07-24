package com.windletter.desktop.send;

import java.util.Objects;

/** Successful encrypted output. Text and binary representations are exclusive. */
public record SendResult(
    SendOutputFormat outputFormat,
    String text,
    byte[] binary,
    long payloadBytes
) {

    public SendResult {
        outputFormat = Objects.requireNonNull(
            outputFormat,
            "outputFormat"
        );
        binary = binary == null ? null : binary.clone();
        if (payloadBytes < 0) {
            throw new IllegalArgumentException(
                "payloadBytes must not be negative"
            );
        }
        if (outputFormat == SendOutputFormat.BINARY) {
            if (text != null || binary == null || binary.length == 0) {
                throw new IllegalArgumentException(
                    "binary output must contain only binary data"
                );
            }
        } else if (text == null || text.isBlank() || binary != null) {
            throw new IllegalArgumentException(
                "text output must contain only text data"
            );
        }
    }

    @Override
    public byte[] binary() {
        return binary == null ? null : binary.clone();
    }
}

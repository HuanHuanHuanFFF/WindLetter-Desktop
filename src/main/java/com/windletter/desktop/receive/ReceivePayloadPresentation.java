package com.windletter.desktop.receive;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Strict, MIME-aware payload preview helpers for the receive UI. */
public final class ReceivePayloadPresentation {

    private ReceivePayloadPresentation() {
    }

    public static Optional<String> textPreview(
        String contentType,
        byte[] payload
    ) {
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(payload, "payload");
        if (!isTextual(contentType)) {
            return Optional.empty();
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload))
                .toString();
            return Optional.of(text);
        } catch (CharacterCodingException invalidUtf8) {
            return Optional.empty();
        }
    }

    private static boolean isTextual(String contentType) {
        String mime = contentType
            .split(";", 2)[0]
            .strip()
            .toLowerCase(Locale.ROOT);
        return mime.startsWith("text/")
            || mime.equals("application/json")
            || mime.equals("application/xml")
            || mime.endsWith("+json")
            || mime.endsWith("+xml");
    }
}

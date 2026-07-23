package com.windletter.desktop.vault;

import com.windletter.armor.WindLetterArmor;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * Text envelopes for the canonical public-identity JSON.
 *
 * <p>The PEM form is standard Base64 with fixed 64-character lines. The
 * WindBase form delegates versioning, framing, length checks, alphabet checks
 * and CRC to the core armor implementation, changing only its exact type
 * header and footer.</p>
 */
final class PublicIdentityArmorCodec {

    private static final String PEM_HEADER =
        "-----BEGIN WINDLETTER PUBLIC IDENTITY-----";
    private static final String PEM_FOOTER =
        "-----END WINDLETTER PUBLIC IDENTITY-----";
    private static final String WIND_HEADER = "-----風铭 起-----";
    private static final String WIND_FOOTER = "-----風铭 凪-----";
    private static final String MESSAGE_HEADER = "-----風笺 起-----";
    private static final String MESSAGE_FOOTER = "-----風笺 凪-----";
    private static final int MAX_JSON_BYTES = 256 * 1024;
    private static final int MAX_ARMOR_CHARS = 600_000;
    private static final int BASE64_LINE_CHARS = 64;
    private static final Base64.Encoder BASE64_ENCODER =
        Base64.getEncoder();
    private static final Base64.Decoder BASE64_DECODER =
        Base64.getDecoder();

    String encode(
        String canonicalJson,
        Format format
    ) throws PublicIdentityException {
        Objects.requireNonNull(canonicalJson, "canonicalJson");
        Objects.requireNonNull(format, "format");
        byte[] jsonBytes = canonicalJson.getBytes(StandardCharsets.UTF_8);
        try {
            requireJsonSize(jsonBytes);
            return switch (format) {
                case BASE64_PEM -> encodeBase64Pem(jsonBytes);
                case WIND_BASE_1024F_V1 -> encodeWindBase(jsonBytes);
            };
        } catch (RuntimeException failure) {
            throw new PublicIdentityException();
        } finally {
            Arrays.fill(jsonBytes, (byte) 0);
        }
    }

    String decode(String identityArmor) throws PublicIdentityException {
        Objects.requireNonNull(identityArmor, "identityArmor");
        byte[] jsonBytes = null;
        try {
            if (identityArmor.isEmpty()
                || identityArmor.length() > MAX_ARMOR_CHARS) {
                throw new IllegalArgumentException(
                    "public identity armor has an invalid size"
                );
            }
            if (hasOpeningLine(identityArmor, PEM_HEADER)) {
                jsonBytes = decodeBase64Pem(identityArmor);
            } else if (hasOpeningLine(identityArmor, WIND_HEADER)) {
                jsonBytes = decodeWindBase(identityArmor);
            } else {
                throw new IllegalArgumentException(
                    "public identity armor header is not recognized"
                );
            }
            requireJsonSize(jsonBytes);
            return strictUtf8(jsonBytes);
        } catch (RuntimeException failure) {
            throw new PublicIdentityException();
        } finally {
            if (jsonBytes != null) {
                Arrays.fill(jsonBytes, (byte) 0);
            }
        }
    }

    boolean hasExactHeader(String value) {
        return value != null
            && (hasOpeningLine(value, PEM_HEADER)
                || hasOpeningLine(value, WIND_HEADER));
    }

    private static String encodeBase64Pem(byte[] jsonBytes) {
        String body = BASE64_ENCODER.encodeToString(jsonBytes);
        StringBuilder armor = new StringBuilder(
            PEM_HEADER.length()
                + PEM_FOOTER.length()
                + body.length()
                + body.length() / BASE64_LINE_CHARS
                + 4
        );
        armor.append(PEM_HEADER).append('\n');
        for (int offset = 0; offset < body.length(); offset += BASE64_LINE_CHARS) {
            int end = Math.min(offset + BASE64_LINE_CHARS, body.length());
            armor.append(body, offset, end).append('\n');
        }
        return armor.append(PEM_FOOTER).toString();
    }

    private static byte[] decodeBase64Pem(String armor) {
        Envelope envelope = unwrapEnvelope(armor, PEM_HEADER, PEM_FOOTER);
        StringBuilder body = new StringBuilder();
        for (int index = 0; index < envelope.bodyLines().length; index++) {
            String line = envelope.bodyLines()[index];
            if (line.isEmpty()
                || line.length() > BASE64_LINE_CHARS
                || index < envelope.bodyLines().length - 1
                    && line.length() != BASE64_LINE_CHARS) {
                throw new IllegalArgumentException(
                    "public identity Base64 line length is invalid"
                );
            }
            for (int character = 0; character < line.length(); character++) {
                char value = line.charAt(character);
                boolean allowed = value >= 'A' && value <= 'Z'
                    || value >= 'a' && value <= 'z'
                    || value >= '0' && value <= '9'
                    || value == '+'
                    || value == '/'
                    || value == '=';
                if (!allowed) {
                    throw new IllegalArgumentException(
                        "public identity Base64 contains invalid text"
                    );
                }
            }
            body.append(line);
        }

        byte[] decoded = BASE64_DECODER.decode(body.toString());
        if (!BASE64_ENCODER.encodeToString(decoded).contentEquals(body)) {
            Arrays.fill(decoded, (byte) 0);
            throw new IllegalArgumentException(
                "public identity Base64 is not canonical"
            );
        }
        return decoded;
    }

    private static String encodeWindBase(byte[] jsonBytes) {
        String messageArmor = WindLetterArmor.encodeWindBase1024F(
            jsonBytes
        );
        if (!messageArmor.startsWith(MESSAGE_HEADER + "\n")
            || !messageArmor.endsWith("\n" + MESSAGE_FOOTER)) {
            throw new IllegalStateException(
                "core WindBase armor envelope is unexpected"
            );
        }
        return WIND_HEADER
            + messageArmor.substring(
                MESSAGE_HEADER.length(),
                messageArmor.length() - MESSAGE_FOOTER.length()
            )
            + WIND_FOOTER;
    }

    private static byte[] decodeWindBase(String armor) {
        Envelope envelope = unwrapEnvelope(armor, WIND_HEADER, WIND_FOOTER);
        StringBuilder messageArmor = new StringBuilder(
            armor.length()
                + MESSAGE_HEADER.length()
                + MESSAGE_FOOTER.length()
        );
        messageArmor.append(MESSAGE_HEADER).append('\n');
        for (String line : envelope.bodyLines()) {
            messageArmor.append(line).append('\n');
        }
        messageArmor.append(MESSAGE_FOOTER);
        return WindLetterArmor.decodeWindBase1024F(messageArmor.toString());
    }

    private static Envelope unwrapEnvelope(
        String armor,
        String header,
        String footer
    ) {
        String envelopeText = withoutOptionalFinalLineEnding(armor);
        String[] lines = envelopeText.split("\\r?\\n", -1);
        if (lines.length < 3
            || !header.equals(lines[0])
            || !footer.equals(lines[lines.length - 1])) {
            throw new IllegalArgumentException(
                "public identity armor envelope is invalid"
            );
        }
        for (String line : lines) {
            if (line.indexOf('\r') >= 0) {
                throw new IllegalArgumentException(
                    "public identity armor line ending is invalid"
                );
            }
        }
        String[] bodyLines = Arrays.copyOfRange(
            lines,
            1,
            lines.length - 1
        );
        if (bodyLines.length == 0) {
            throw new IllegalArgumentException(
                "public identity armor body is missing"
            );
        }
        return new Envelope(bodyLines);
    }

    private static String withoutOptionalFinalLineEnding(String value) {
        if (value.endsWith("\r\n")) {
            return value.substring(0, value.length() - 2);
        }
        if (value.endsWith("\n")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String strictUtf8(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value))
                .toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException(
                "public identity armor is not strict UTF-8",
                failure
            );
        }
    }

    private static void requireJsonSize(byte[] jsonBytes) {
        if (jsonBytes.length == 0 || jsonBytes.length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException(
                "public identity JSON has an invalid size"
            );
        }
    }

    private static boolean hasOpeningLine(String value, String header) {
        return value.startsWith(header + "\n")
            || value.startsWith(header + "\r\n");
    }

    enum Format {
        BASE64_PEM,
        WIND_BASE_1024F_V1
    }

    private record Envelope(String[] bodyLines) {
    }
}

package com.windletter.desktop.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.windletter.armor.WindLetterArmor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class PublicIdentityArmorCodecTest {

    private static final String PEM_HEADER =
        "-----BEGIN WINDLETTER PUBLIC IDENTITY-----";
    private static final String PEM_FOOTER =
        "-----END WINDLETTER PUBLIC IDENTITY-----";
    private static final String WIND_HEADER = "-----風铭 起-----";
    private static final String WIND_FOOTER = "-----風铭 凪-----";
    private static final String MESSAGE_HEADER = "-----風笺 起-----";

    private final PublicIdentityArmorCodec codec =
        new PublicIdentityArmorCodec();

    @Test
    void shouldWrapCanonicalJsonBytesIn64CharacterBase64Pem()
        throws Exception {
        String json =
            "{\"format\":\"windletter.public-identity\","
                + "\"version\":1,\"displayName\":\"風笺 · 𠮷 · "
                + "abcdefghijklmnopqrstuvwxyz0123456789\"}";

        String armor = codec.encode(
            json,
            PublicIdentityArmorCodec.Format.BASE64_PEM
        );

        assertTrue(armor.startsWith(PEM_HEADER + "\n"));
        assertTrue(armor.endsWith("\n" + PEM_FOOTER));
        String[] lines = armor.split("\n");
        for (int index = 1; index < lines.length - 2; index++) {
            assertEquals(64, lines[index].length());
        }
        assertTrue(lines[lines.length - 2].length() <= 64);
        String base64Body = String.join(
            "",
            java.util.Arrays.copyOfRange(lines, 1, lines.length - 1)
        );
        assertEquals(
            json,
            new String(
                Base64.getDecoder().decode(base64Body),
                StandardCharsets.UTF_8
            )
        );
        assertEquals(json, codec.decode(armor));
        assertEquals(json, codec.decode(armor.replace("\n", "\r\n")));
        assertEquals(json, codec.decode(armor + "\n"));
    }

    @Test
    void shouldRoundTripStrictUtf8ThroughCoreWindBaseWithFengMingHeader()
        throws Exception {
        String json =
            "{\"format\":\"windletter.public-identity\","
                + "\"version\":1,\"displayName\":\"風铭 · 𠮷\"}";

        String armor = codec.encode(
            json,
            PublicIdentityArmorCodec.Format.WIND_BASE_1024F_V1
        );

        assertTrue(armor.startsWith(WIND_HEADER + "\n"));
        assertTrue(armor.endsWith("\n" + WIND_FOOTER));
        assertFalse(armor.contains(MESSAGE_HEADER));
        assertEquals(json, codec.decode(armor));
        assertEquals(json, codec.decode(armor.replace("\n", "\r\n")));
        assertEquals(json, codec.decode(armor + "\r\n"));
    }

    @Test
    void shouldRejectMessageArmorWrongHeadersAndCorruptedBodies()
        throws Exception {
        String json =
            "{\"format\":\"windletter.public-identity\",\"version\":1}";
        String messageArmor = WindLetterArmor.encodeWindBase1024F(
            json.getBytes(StandardCharsets.UTF_8)
        );
        String windArmor = codec.encode(
            json,
            PublicIdentityArmorCodec.Format.WIND_BASE_1024F_V1
        );
        int bodyOffset = windArmor.indexOf('\n') + 1;
        int codePoint = windArmor.codePointAt(bodyOffset);
        String corruptedWind = windArmor.substring(0, bodyOffset)
            + new String(Character.toChars(codePoint == '风' ? '信' : '风'))
            + windArmor.substring(
                bodyOffset + Character.charCount(codePoint)
            );
        String pemArmor = codec.encode(
            json,
            PublicIdentityArmorCodec.Format.BASE64_PEM
        );
        String corruptedPem = pemArmor.replaceFirst(
            "\\n[A-Za-z0-9+/]",
            "\n!"
        );

        assertThrows(
            PublicIdentityException.class,
            () -> codec.decode(messageArmor)
        );
        assertThrows(
            PublicIdentityException.class,
            () -> codec.decode(windArmor.replace(
                WIND_FOOTER,
                "-----風铭 终-----"
            ))
        );
        assertThrows(
            PublicIdentityException.class,
            () -> codec.decode(corruptedWind)
        );
        assertThrows(
            PublicIdentityException.class,
            () -> codec.decode(corruptedPem)
        );
    }
}

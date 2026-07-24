package com.windletter.desktop.receive;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceivePayloadPresentationTest {

    @Test
    void shouldPreviewOnlyStrictUtf8PayloadsWithTextualMime() {
        assertEquals(
            "風笺\n𠮷 🌬️",
            ReceivePayloadPresentation.textPreview(
                "text/plain;charset=UTF-8",
                "風笺\n𠮷 🌬️".getBytes(StandardCharsets.UTF_8)
            ).orElseThrow()
        );
        assertEquals(
            "{\"ok\":true}",
            ReceivePayloadPresentation.textPreview(
                "application/json",
                "{\"ok\":true}".getBytes(StandardCharsets.UTF_8)
            ).orElseThrow()
        );
        assertTrue(ReceivePayloadPresentation.textPreview(
            "text/plain",
            new byte[] {(byte) 0xC3, 0x28}
        ).isEmpty());
        assertTrue(ReceivePayloadPresentation.textPreview(
            "application/octet-stream",
            "looks like text".getBytes(StandardCharsets.UTF_8)
        ).isEmpty());
    }
}

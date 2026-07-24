package com.windletter.desktop.ui;

import com.windletter.desktop.send.SendPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SendDesktopPanePayloadTest {

    @TempDir
    Path directory;

    @Test
    void shouldPreserveTextAndFilePayloadBytesExactly() throws Exception {
        String text = "風笺文本\n𠮷 🌬️";
        SendPayload textPayload = SendDesktopPane.loadPayload(null, text);
        assertEquals(
            "text/plain;charset=UTF-8",
            textPayload.contentType()
        );
        assertArrayEquals(
            text.getBytes(StandardCharsets.UTF_8),
            textPayload.data()
        );

        byte[] fileBytes = new byte[] {0, 1, 2, 13, 10, -1};
        Path file = directory.resolve("payload.bin");
        Files.write(file, fileBytes);
        SendPayload filePayload = SendDesktopPane.loadPayload(file, null);
        assertArrayEquals(fileBytes, filePayload.data());
        assertFalse(filePayload.contentType().isBlank());
    }
}

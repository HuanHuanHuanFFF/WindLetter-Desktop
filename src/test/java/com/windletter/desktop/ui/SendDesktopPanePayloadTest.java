package com.windletter.desktop.ui;

import com.windletter.desktop.send.SendPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.windletter.protocol.ProtocolLimits;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void shouldRejectAnOversizedFileBeforeReadingIt() throws Exception {
        Path oversized = directory.resolve("oversized.bin");
        try (SeekableByteChannel channel = Files.newByteChannel(
            oversized,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        )) {
            channel.position(ProtocolLimits.MAX_PAYLOAD_BYTES);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {1}));
        }

        assertThrows(
            java.io.IOException.class,
            () -> SendDesktopPane.loadPayload(oversized, null)
        );
    }
}

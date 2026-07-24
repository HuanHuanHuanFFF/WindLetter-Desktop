package com.windletter.desktop.receive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReceivePayloadWriterTest {

    @TempDir
    Path directory;

    @Test
    void shouldReplaceTheTargetWithExactRecoveredBytes() throws Exception {
        ReceivePayloadWriter writer = new ReceivePayloadWriter();
        Path target = directory.resolve("recovered.bin");
        Files.write(target, new byte[] {9, 9, 9});
        byte[] payload = new byte[] {0, 1, 2, 13, 10, -1};

        writer.write(target, payload);

        assertArrayEquals(payload, Files.readAllBytes(target));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }
}

package com.windletter.desktop.send;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SendOutputWriterTest {

    @TempDir
    Path directory;

    @Test
    void shouldAtomicallyReplaceTargetsWithExactTextAndBinaryBytes()
        throws Exception {
        SendOutputWriter writer = new SendOutputWriter();
        Path textTarget = directory.resolve("message.txt");
        Files.writeString(textTarget, "旧内容", StandardCharsets.UTF_8);
        writer.write(
            textTarget,
            new SendResult(
                SendOutputFormat.WIND_BASE_1024F_V1,
                "-----風笺 起-----\n正文\n-----風笺 凪-----",
                null,
                6
            )
        );
        assertEquals(
            "-----風笺 起-----\n正文\n-----風笺 凪-----",
            Files.readString(textTarget, StandardCharsets.UTF_8)
        );

        Path binaryTarget = directory.resolve("message.wlb");
        byte[] expected = new byte[] {0, 1, 2, -1};
        writer.write(
            binaryTarget,
            new SendResult(
                SendOutputFormat.BINARY,
                null,
                expected,
                1
            )
        );
        assertArrayEquals(expected, Files.readAllBytes(binaryTarget));
        try (var files = Files.list(directory)) {
            assertEquals(
                2,
                files.count(),
                "successful writes must not leave temporary files"
            );
        }
    }
}

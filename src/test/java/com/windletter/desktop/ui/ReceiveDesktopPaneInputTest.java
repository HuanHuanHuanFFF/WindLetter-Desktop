package com.windletter.desktop.ui;

import com.windletter.desktop.receive.ReceiveInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReceiveDesktopPaneInputTest {

    @TempDir
    Path directory;

    @Test
    void shouldPreserveExactTextAndBinaryRepresentations() throws Exception {
        UUID identityId = UUID.randomUUID();
        String armor = "-----BEGIN WIND LETTER-----\nabc\n"
            + "-----END WIND LETTER-----\n";
        ReceiveInput text = ReceiveDesktopPane.loadInput(
            identityId,
            null,
            armor
        );
        assertEquals(armor, text.text());

        byte[] encoded = new byte[] {0, 1, 2, 3, (byte) 255};
        Path binary = directory.resolve("message.wlb");
        Files.write(binary, encoded);
        ReceiveInput loaded = ReceiveDesktopPane.loadInput(
            identityId,
            binary,
            null
        );
        assertArrayEquals(encoded, loaded.binary());
    }

    @Test
    void shouldRejectAnEmptyBinaryMessage() throws Exception {
        Path empty = directory.resolve("empty.wlb");
        Files.write(empty, new byte[0]);
        assertThrows(
            IOException.class,
            () -> ReceiveDesktopPane.loadInput(
                UUID.randomUUID(),
                empty,
                null
            )
        );
    }
}

package com.windletter.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WindLetterApplicationContractTest {

    @Test
    void windowUsesProductDisplayName() {
        assertEquals("風笺 · WindLetter", WindLetterApplication.WINDOW_TITLE);
    }
}

package com.windletter.desktop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApplicationInfoTest {

    @Test
    void shouldExposeTheReleaseAndPinnedCoreVersions() {
        assertEquals("0.1.0", ApplicationInfo.version());
        assertEquals(
            "4a5e9a747148fd05940e7571ff4cc80f014a4127",
            ApplicationInfo.coreCommit()
        );
        assertEquals("4a5e9a7", ApplicationInfo.coreShortCommit());
        assertEquals(
            "版本 0.1.0 · 核心 4a5e9a7",
            ApplicationInfo.compactLabel()
        );
    }
}

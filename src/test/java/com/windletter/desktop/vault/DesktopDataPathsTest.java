package com.windletter.desktop.vault;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesktopDataPathsTest {

    @Test
    void shouldUseWindowsRoamingApplicationDataWhenAvailable() {
        Path path = DesktopDataPaths.defaultVaultPath(
            "Windows 11",
            Map.of("APPDATA", "C:\\Users\\Test\\AppData\\Roaming"),
            "C:\\Users\\Test"
        );

        assertEquals(
            Path.of(
                "C:\\Users\\Test\\AppData\\Roaming",
                "WindLetter",
                "vault.wlv"
            ).toAbsolutePath().normalize(),
            path
        );
    }

    @Test
    void shouldUsePrivateHomeFallbackOutsideWindowsOrWithoutAppData() {
        Path path = DesktopDataPaths.defaultVaultPath(
            "Linux",
            Map.of(),
            "/home/test"
        );

        assertEquals(
            Path.of("/home/test", ".windletter", "vault.wlv")
                .toAbsolutePath()
                .normalize(),
            path
        );
    }
}

package com.windletter.desktop.vault;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class DesktopDataPaths {

    private static final String APPLICATION_DIRECTORY = "WindLetter";
    private static final String VAULT_FILE_NAME = "vault.wlv";

    private DesktopDataPaths() {
    }

    static Path defaultVaultPath() {
        return defaultVaultPath(
                System.getProperty("os.name", ""),
                System.getenv(),
                System.getProperty("user.home"));
    }

    static Path defaultVaultPath(String osName, Map<String, String> environment, String userHome) {
        Objects.requireNonNull(osName, "osName");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(userHome, "userHome");

        String appData = environment.get("APPDATA");
        if (isWindows(osName) && appData != null && !appData.isBlank()) {
            return normalize(Path.of(appData, APPLICATION_DIRECTORY, VAULT_FILE_NAME));
        }

        if (userHome.isBlank()) {
            throw new IllegalStateException("User home is unavailable");
        }
        return normalize(Path.of(userHome, ".windletter", VAULT_FILE_NAME));
    }

    private static boolean isWindows(String osName) {
        return osName.toLowerCase(Locale.ROOT).contains("win");
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}

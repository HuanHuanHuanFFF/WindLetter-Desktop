package com.windletter.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

/** Immutable build identity shown to users and included in release evidence. */
public final class ApplicationInfo {

    private static final String RESOURCE =
        "/com/windletter/desktop/build.properties";
    private static final Properties BUILD = load();

    private ApplicationInfo() {
    }

    public static String version() {
        return required("app.version");
    }

    public static String coreCommit() {
        return required("core.commit");
    }

    public static String coreVersion() {
        return required("core.version");
    }

    public static String coreShortCommit() {
        String commit = coreCommit();
        return commit.substring(0, Math.min(7, commit.length()));
    }

    public static String compactLabel() {
        return "版本 " + version() + " · 核心 " + coreShortCommit();
    }

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream input =
                 ApplicationInfo.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                    "build identity resource is missing"
                );
            }
            properties.load(input);
            return properties;
        } catch (IOException failure) {
            throw new IllegalStateException(
                "build identity resource cannot be read",
                failure
            );
        }
    }

    private static String required(String name) {
        String value = Objects.requireNonNull(
            BUILD.getProperty(name),
            name
        ).trim();
        if (value.isEmpty()) {
            throw new IllegalStateException(
                "build identity property is empty: " + name
            );
        }
        return value;
    }
}

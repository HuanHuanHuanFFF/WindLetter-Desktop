package com.windletter.desktop;

import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.InputStream;
import java.util.Objects;

/** Shared application icon loaded from the user-provided WindLetter logo. */
public final class ApplicationBranding {

    private static final String WINDOW_ICON =
        "/com/windletter/desktop/windletter-logo.png";
    private static final Image ICON = loadIcon();

    private ApplicationBranding() {
    }

    public static void applyTo(Stage stage) {
        Objects.requireNonNull(stage, "stage");
        stage.getIcons().setAll(ICON);
    }

    private static Image loadIcon() {
        try (InputStream input =
                 ApplicationBranding.class.getResourceAsStream(WINDOW_ICON)) {
            if (input == null) {
                throw new IllegalStateException(
                    "WindLetter window icon is missing"
                );
            }
            Image image = new Image(input);
            if (image.isError()) {
                throw new IllegalStateException(
                    "WindLetter window icon cannot be decoded",
                    image.getException()
                );
            }
            return image;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(
                "WindLetter window icon cannot be read",
                failure
            );
        }
    }
}

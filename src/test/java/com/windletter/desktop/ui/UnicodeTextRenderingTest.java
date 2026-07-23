package com.windletter.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.javafx.font.CharToGlyphMapper;
import com.sun.javafx.font.PGFont;
import com.windletter.armor.WindLetterArmor;
import com.windletter.desktop.WindLetterApplication;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class UnicodeTextRenderingTest {

    @BeforeAll
    static void startJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        Platform.setImplicitExit(false);
    }

    @Test
    void userTextControlsShouldCoverEveryFrozenWindBaseGlyph()
        throws Exception {
        String alphabet = windBaseAlphabet();
        assertEquals(1024, alphabet.codePointCount(0, alphabet.length()));

        runOnFxThread(() -> {
            TextField input = new TextField(alphabet);
            PasswordField password = new PasswordField();
            password.setText(alphabet);
            TextArea output = new TextArea(alphabet);
            VBox root = new VBox(input, password, output);
            root.getStyleClass().add("app-root");
            Scene scene = new Scene(root);
            scene.getStylesheets().add(
                Objects.requireNonNull(
                    WindLetterApplication.class.getResource(
                        "windletter.css"
                    )
                ).toExternalForm()
            );
            root.applyCss();

            for (TextInputControl control : List.of(
                input,
                password,
                output
            )) {
                assertFullCoverage(control.getFont(), alphabet);
            }
        });
    }

    private static void assertFullCoverage(Font font, String alphabet)
        throws Exception {
        Method nativeFont = Font.class.getDeclaredMethod("getNativeFont");
        nativeFont.setAccessible(true);
        PGFont peer = (PGFont) nativeFont.invoke(font);
        CharToGlyphMapper mapper = peer.getFontResource().getGlyphMapper();
        int missingGlyph = mapper.getMissingGlyphCode();
        List<Integer> missing = alphabet.codePoints()
            .filter(codePoint -> mapper.getGlyphCode(codePoint) == missingGlyph)
            .boxed()
            .toList();
        assertTrue(
            missing.isEmpty(),
            () -> font.getFamily()
                + " lacks "
                + missing.size()
                + " WindBase glyphs; first missing code point is U+"
                + Integer.toHexString(missing.get(0)).toUpperCase()
        );
    }

    private static String windBaseAlphabet() throws Exception {
        try (InputStream input = WindLetterArmor.class.getResourceAsStream(
            "/com/windletter/armor/wind-base-1024f-v1-alphabet.txt"
        )) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void runOnFxThread(ThrowingRunnable runnable)
        throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                runnable.run();
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                finished.countDown();
            }
        });
        assertTrue(finished.await(10, TimeUnit.SECONDS));
        Throwable thrown = failure.get();
        if (thrown instanceof Exception exception) {
            throw exception;
        }
        if (thrown instanceof Error error) {
            throw error;
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}

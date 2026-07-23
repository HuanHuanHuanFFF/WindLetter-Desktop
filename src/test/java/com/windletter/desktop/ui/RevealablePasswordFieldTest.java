package com.windletter.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RevealablePasswordFieldTest {

    @BeforeAll
    static void startJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @Test
    void shouldStartHiddenAndToggleWithoutChangingThePassword() throws Exception {
        runOnFxThread(() -> {
            RevealablePasswordField field =
                new RevealablePasswordField("保险库密码");
            field.textProperty().set("風笺 passphrase");

            ToggleButton revealButton = (ToggleButton) field.lookup(
                ".password-reveal-button"
            );
            assertNotNull(revealButton);
            assertFalse(field.isPasswordVisible());
            assertEquals("显示密码", revealButton.getAccessibleText());

            revealButton.fire();

            assertTrue(field.isPasswordVisible());
            assertEquals("風笺 passphrase", field.textProperty().get());
            assertEquals("隐藏密码", revealButton.getAccessibleText());

            revealButton.fire();

            assertFalse(field.isPasswordVisible());
            assertEquals("風笺 passphrase", field.textProperty().get());
        });
    }

    @Test
    void shouldReturnAnOwnedArrayAndClearBothVisualStates() throws Exception {
        runOnFxThread(() -> {
            RevealablePasswordField field =
                new RevealablePasswordField("保险库密码");
            field.textProperty().set("八位安全密码");
            field.setPasswordVisible(true);

            char[] password = field.takePassword();
            try {
                assertArrayEquals("八位安全密码".toCharArray(), password);
                assertTrue(field.textProperty().isEmpty().get());
                TextField visibleField = (TextField) field.lookup(
                    ".revealed-password-field"
                );
                PasswordField maskedField = (PasswordField) field.lookup(
                    ".password-field"
                );
                assertNotNull(visibleField);
                assertNotNull(maskedField);
                assertEquals("", visibleField.getText());
                assertEquals("", maskedField.getText());
            } finally {
                Arrays.fill(password, '\0');
            }
        });
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

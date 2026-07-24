package com.windletter.desktop.ui;

import javafx.animation.PauseTransition;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.util.Duration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/** Clears copied plaintext only while the application still owns it. */
final class SensitiveClipboard implements AutoCloseable {

    private final Access access;
    private final Delay delay;
    private byte[] ownedDigest;

    SensitiveClipboard(Access access, Delay delay) {
        this.access = Objects.requireNonNull(access, "access");
        this.delay = Objects.requireNonNull(delay, "delay");
    }

    static SensitiveClipboard system(Duration retention) {
        Objects.requireNonNull(retention, "retention");
        PauseTransition transition = new PauseTransition(retention);
        return new SensitiveClipboard(
            new Access() {
                @Override
                public boolean setText(String value) {
                    ClipboardContent content = new ClipboardContent();
                    content.putString(value);
                    return Clipboard.getSystemClipboard()
                        .setContent(content);
                }

                @Override
                public String text() {
                    Clipboard clipboard = Clipboard.getSystemClipboard();
                    return clipboard.hasString()
                        ? clipboard.getString()
                        : null;
                }

                @Override
                public void clear() {
                    Clipboard.getSystemClipboard().clear();
                }
            },
            new Delay() {
                @Override
                public void schedule(Runnable action) {
                    transition.setOnFinished(event -> action.run());
                    transition.playFromStart();
                }

                @Override
                public void cancel() {
                    transition.stop();
                    transition.setOnFinished(null);
                }
            }
        );
    }

    boolean copy(String plaintext) {
        Objects.requireNonNull(plaintext, "plaintext");
        releaseOwnership();
        if (!access.setText(plaintext)) {
            return false;
        }
        ownedDigest = digest(plaintext);
        delay.schedule(this::clearIfOwned);
        return true;
    }

    void clearIfOwned() {
        byte[] expected = ownedDigest;
        ownedDigest = null;
        delay.cancel();
        if (expected == null) {
            return;
        }
        byte[] actual = null;
        try {
            String current = access.text();
            if (current != null) {
                actual = digest(current);
                if (MessageDigest.isEqual(expected, actual)) {
                    access.clear();
                }
            }
        } finally {
            Arrays.fill(expected, (byte) 0);
            if (actual != null) {
                Arrays.fill(actual, (byte) 0);
            }
        }
    }

    @Override
    public void close() {
        clearIfOwned();
    }

    private void releaseOwnership() {
        delay.cancel();
        if (ownedDigest != null) {
            Arrays.fill(ownedDigest, (byte) 0);
            ownedDigest = null;
        }
    }

    private static byte[] digest(String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        try {
            return MessageDigest.getInstance("SHA-256").digest(encoded);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    interface Access {
        boolean setText(String value);

        String text();

        void clear();
    }

    interface Delay {
        void schedule(Runnable action);

        void cancel();
    }
}

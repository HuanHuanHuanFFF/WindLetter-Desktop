package com.windletter.desktop.receive;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Delivers recovered payload bytes through a sibling temporary file. */
public final class ReceivePayloadWriter {

    public void write(Path target, byte[] payload) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(payload, "payload");
        Path absoluteTarget = target.toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("target parent is unavailable");
        }
        Path temporary = parent.resolve(
            "." + absoluteTarget.getFileName()
                + "." + UUID.randomUUID() + ".tmp"
        );
        byte[] copy = payload.clone();
        boolean moved = false;
        try {
            Files.write(
                temporary,
                copy,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            );
            moveReplacing(temporary, absoluteTarget);
            moved = true;
        } finally {
            Arrays.fill(copy, (byte) 0);
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void moveReplacing(Path source, Path target)
        throws IOException {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING
            );
        }
    }
}

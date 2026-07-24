package com.windletter.desktop.send;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

/** Writes encrypted output through a sibling temporary file before replacement. */
public final class SendOutputWriter {

    public void write(Path target, SendResult result) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(result, "result");
        Path absoluteTarget = target.toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("target parent is unavailable");
        }
        Path temporary = parent.resolve(
            "." + absoluteTarget.getFileName()
                + "." + UUID.randomUUID() + ".tmp"
        );
        boolean moved = false;
        try {
            byte[] bytes = result.outputFormat() == SendOutputFormat.BINARY
                ? result.binary()
                : result.text().getBytes(StandardCharsets.UTF_8);
            try {
                Files.write(
                    temporary,
                    bytes,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
                );
            } finally {
                java.util.Arrays.fill(bytes, (byte) 0);
            }
            moveReplacing(temporary, absoluteTarget);
            moved = true;
        } finally {
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

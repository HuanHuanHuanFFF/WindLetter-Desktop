package com.windletter.desktop.vault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;

/** Reads and atomically replaces encrypted Vault envelope files only. */
final class VaultFileStore {

    static final int MAX_ENVELOPE_BYTES = 8 * 1024 * 1024 + 8 * 1024;

    private final AtomicReplacer atomicReplacer;

    VaultFileStore() {
        this((source, target) -> Files.move(
            source,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        ));
    }

    VaultFileStore(AtomicReplacer atomicReplacer) {
        this.atomicReplacer = Objects.requireNonNull(
            atomicReplacer,
            "atomicReplacer"
        );
    }

    byte[] read(Path source) throws VaultOpenException {
        Objects.requireNonNull(source, "source");
        byte[] envelope = null;
        boolean success = false;
        try (FileChannel channel = FileChannel.open(
            normalized(source),
            StandardOpenOption.READ
        )) {
            long size = channel.size();
            if (size <= 0 || size > MAX_ENVELOPE_BYTES) {
                throw new IOException("Vault envelope size is invalid");
            }

            envelope = new byte[Math.toIntExact(size)];
            ByteBuffer output = ByteBuffer.wrap(envelope);
            while (output.hasRemaining()) {
                if (channel.read(output) < 0) {
                    throw new IOException("Vault envelope was truncated");
                }
            }
            ByteBuffer extra = ByteBuffer.allocate(1);
            if (channel.read(extra) >= 0) {
                throw new IOException("Vault envelope grew while reading");
            }
            success = true;
            return envelope;
        } catch (IOException | RuntimeException failure) {
            throw new VaultOpenException();
        } finally {
            if (!success) {
                clear(envelope);
            }
        }
    }

    void writeAtomically(
        Path target,
        byte[] envelope
    ) throws VaultWriteException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(envelope, "envelope");
        if (envelope.length == 0 || envelope.length > MAX_ENVELOPE_BYTES) {
            throw new IllegalArgumentException(
                "envelope is outside the supported size"
            );
        }

        Path normalizedTarget = normalized(target);
        Path parent = normalizedTarget.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("target must have a parent directory");
        }

        byte[] ownedEnvelope = envelope.clone();
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(
                parent,
                "." + normalizedTarget.getFileName() + ".",
                ".tmp"
            );
            writeAndFlush(temporary, ownedEnvelope);
            atomicReplacer.replace(temporary, normalizedTarget);
            temporary = null;
        } catch (IOException | RuntimeException failure) {
            throw new VaultWriteException();
        } finally {
            clear(ownedEnvelope);
            deleteTemporary(temporary);
        }
    }

    void copyAtomically(
        Path source,
        Path target
    ) throws VaultOpenException, VaultWriteException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (normalized(source).equals(normalized(target))) {
            throw new IllegalArgumentException("source and target must differ");
        }

        byte[] envelope = null;
        try {
            envelope = read(source);
            writeAtomically(target, envelope);
        } finally {
            clear(envelope);
        }
    }

    private static void writeAndFlush(Path target, byte[] envelope)
        throws IOException {
        try (FileChannel channel = FileChannel.open(
            target,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING
        )) {
            ByteBuffer input = ByteBuffer.wrap(envelope);
            while (input.hasRemaining()) {
                channel.write(input);
            }
            channel.force(true);
        }
    }

    private static Path normalized(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static void deleteTemporary(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
            // The temporary file contains only an encrypted envelope.
        }
    }

    private static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    @FunctionalInterface
    interface AtomicReplacer {
        void replace(Path source, Path target) throws IOException;
    }
}

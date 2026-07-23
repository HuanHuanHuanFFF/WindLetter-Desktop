package com.windletter.desktop.vault;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class VaultModelChecks {

    static final int VAULT_ID_BYTES = 16;
    static final int KID_BYTES = 32;
    static final int MAX_IDENTITIES = 64;
    static final int MAX_CONTACTS = 1_024;

    private VaultModelChecks() {
    }

    static byte[] copyExact(byte[] value, int length, String fieldName) {
        requireExact(value, length, fieldName);
        return value.clone();
    }

    static void requireExact(byte[] value, int length, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.length != length) {
            throw new IllegalArgumentException(fieldName + " has an invalid length");
        }
    }

    static String displayName(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        String normalized = value.strip();
        requireCodePoints(normalized, 1, 64, fieldName);
        normalized.codePoints().forEach(codePoint -> {
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint)
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE) {
                throw new IllegalArgumentException(fieldName + " contains unsupported characters");
            }
        });
        return normalized;
    }

    static String optionalDisplayName(String value, String fieldName) {
        return value == null ? null : displayName(value, fieldName);
    }

    static String note(String value, String fieldName) {
        if (value == null) {
            return null;
        }
        requireCodePoints(value, 0, 256, fieldName);
        value.codePoints().forEach(codePoint -> {
            if (codePoint == 0
                || Character.getType(codePoint) == Character.SURROGATE
                || (Character.isISOControl(codePoint)
                    && codePoint != '\n'
                    && codePoint != '\t')) {
                throw new IllegalArgumentException(fieldName + " contains unsupported characters");
            }
        });
        return value;
    }

    static void timestamps(Instant createdAt, Instant updatedAt) {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
    }

    static <T> List<T> boundedList(
        List<T> value,
        int maximum,
        String fieldName
    ) {
        Objects.requireNonNull(value, fieldName);
        if (value.size() > maximum || value.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(fieldName + " is invalid");
        }
        return List.copyOf(value);
    }

    static void requireUniqueIds(
        List<? extends Identified> values,
        String fieldName
    ) {
        Set<UUID> ids = new HashSet<>();
        for (Identified value : values) {
            if (!ids.add(value.id())) {
                throw new IllegalArgumentException(fieldName + " contains duplicate IDs");
            }
        }
    }

    static void requireAlgorithmOrder(
        List<? extends AlgorithmKey> keys,
        String fieldName
    ) {
        VaultKeyAlgorithm[] algorithms = VaultKeyAlgorithm.values();
        if (keys.size() != algorithms.length) {
            throw new IllegalArgumentException(fieldName + " must contain exactly three keys");
        }
        for (int index = 0; index < algorithms.length; index++) {
            if (keys.get(index).algorithm() != algorithms[index]) {
                throw new IllegalArgumentException(
                    fieldName + " must contain X25519, ML-KEM-768 and Ed25519 in order"
                );
            }
        }
    }

    private static void requireCodePoints(
        String value,
        int minimum,
        int maximum,
        String fieldName
    ) {
        int count = value.codePointCount(0, value.length());
        if (count < minimum || count > maximum) {
            throw new IllegalArgumentException(fieldName + " has an invalid length");
        }
    }

    interface Identified {
        UUID id();
    }

    interface AlgorithmKey {
        VaultKeyAlgorithm algorithm();
    }
}

package com.windletter.desktop.vault;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Measures Argon2id on the target JVM before creating a new Vault. */
final class VaultKdfCalibrator {

    static final long DEFAULT_TARGET_MILLIS = 500;
    private static final int[] MEMORY_CANDIDATES_KIB = {
        65_536,
        131_072,
        262_144
    };

    private final long targetMillis;
    private final int maxCandidateMemoryKiB;
    private final Probe probe;

    VaultKdfCalibrator() {
        this(
            DEFAULT_TARGET_MILLIS,
            defaultMaxCandidateMemoryKiB(),
            new LiveProbe(new SecureRandom())
        );
    }

    VaultKdfCalibrator(
        long targetMillis,
        int maxCandidateMemoryKiB,
        Probe probe
    ) {
        if (targetMillis <= 0) {
            throw new IllegalArgumentException("targetMillis must be positive");
        }
        if (maxCandidateMemoryKiB < VaultKdfParameters.MIN_MEMORY_KIB
            || maxCandidateMemoryKiB > VaultKdfParameters.MAX_MEMORY_KIB) {
            throw new IllegalArgumentException(
                "maxCandidateMemoryKiB is outside the supported range"
            );
        }
        this.targetMillis = targetMillis;
        this.maxCandidateMemoryKiB = maxCandidateMemoryKiB;
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    VaultKdfCalibration calibrate() {
        VaultKdfParameters selected = VaultKdfParameters.minimumSupported();
        long selectedBaselineMillis = measure(selected);

        if (selectedBaselineMillis <= targetMillis) {
            for (int memoryKiB : MEMORY_CANDIDATES_KIB) {
                if (memoryKiB <= selected.memoryKiB()) {
                    continue;
                }
                if (memoryKiB > maxCandidateMemoryKiB) {
                    break;
                }
                VaultKdfParameters candidate = new VaultKdfParameters(
                    memoryKiB,
                    VaultKdfParameters.MIN_ITERATIONS,
                    VaultKdfParameters.MIN_PARALLELISM
                );
                long candidateMillis = measure(candidate);
                if (candidateMillis > targetMillis) {
                    break;
                }
                selected = candidate;
                selectedBaselineMillis = candidateMillis;
            }
        }

        long perIterationMillis = Math.max(
            1,
            selectedBaselineMillis / selected.iterations()
        );
        int iterations = (int) Math.max(
            VaultKdfParameters.MIN_ITERATIONS,
            Math.min(
                VaultKdfParameters.MAX_ITERATIONS,
                targetMillis / perIterationMillis
            )
        );
        VaultKdfParameters calibrated = new VaultKdfParameters(
            selected.memoryKiB(),
            iterations,
            selected.parallelism()
        );
        long measuredMillis = measure(calibrated);
        return new VaultKdfCalibration(calibrated, measuredMillis, targetMillis);
    }

    private long measure(VaultKdfParameters parameters) {
        long millis = probe.measure(parameters);
        if (millis <= 0) {
            throw new IllegalStateException("Argon2id probe returned an invalid duration");
        }
        return millis;
    }

    private static int defaultMaxCandidateMemoryKiB() {
        long maxHeapKiB = Runtime.getRuntime().maxMemory() / 1024;
        long heapBound = Math.max(
            VaultKdfParameters.MIN_MEMORY_KIB,
            maxHeapKiB / 4
        );
        return (int) Math.min(262_144, heapBound);
    }

    @FunctionalInterface
    interface Probe {
        long measure(VaultKdfParameters parameters);
    }

    private static final class LiveProbe implements Probe {

        private final SecureRandom secureRandom;

        private LiveProbe(SecureRandom secureRandom) {
            this.secureRandom = secureRandom;
        }

        @Override
        public long measure(VaultKdfParameters parameters) {
            byte[] password = new byte[32];
            byte[] salt = new byte[VaultKeyDerivation.SALT_BYTES];
            byte[] key = null;
            secureRandom.nextBytes(password);
            secureRandom.nextBytes(salt);
            long startedAt = System.nanoTime();
            try {
                key = VaultKeyDerivation.derive(password, salt, parameters);
                return Math.max(
                    1,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                );
            } finally {
                clear(key);
                clear(salt);
                clear(password);
            }
        }

        private static void clear(byte[] value) {
            if (value != null) {
                Arrays.fill(value, (byte) 0);
            }
        }
    }
}

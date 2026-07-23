package com.windletter.desktop.vault;

/** Bounded Argon2id cost parameters stored in the authenticated Vault header. */
record VaultKdfParameters(int memoryKiB, int iterations, int parallelism) {

    public static final int MIN_MEMORY_KIB = 65_536;
    public static final int MAX_MEMORY_KIB = 524_288;
    public static final int MIN_ITERATIONS = 2;
    public static final int MAX_ITERATIONS = 10;
    public static final int MIN_PARALLELISM = 1;
    public static final int MAX_PARALLELISM = 4;

    public VaultKdfParameters {
        if (memoryKiB < MIN_MEMORY_KIB || memoryKiB > MAX_MEMORY_KIB) {
            throw new IllegalArgumentException("memoryKiB is outside the supported range");
        }
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException("iterations is outside the supported range");
        }
        if (parallelism < MIN_PARALLELISM || parallelism > MAX_PARALLELISM) {
            throw new IllegalArgumentException("parallelism is outside the supported range");
        }
    }

    /** Security floor for tests and imported Vaults, not a creation-time default. */
    static VaultKdfParameters minimumSupported() {
        return new VaultKdfParameters(MIN_MEMORY_KIB, MIN_ITERATIONS, MIN_PARALLELISM);
    }
}

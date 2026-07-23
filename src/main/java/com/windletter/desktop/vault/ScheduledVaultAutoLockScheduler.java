package com.windletter.desktop.vault;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

final class ScheduledVaultAutoLockScheduler implements VaultAutoLockScheduler {

    private final ScheduledExecutorService executor;

    ScheduledVaultAutoLockScheduler() {
        ThreadFactory threadFactory = action -> {
            Thread thread = new Thread(action, "windletter-vault-auto-lock");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    @Override
    public Cancellable schedule(Runnable action, Duration delay) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(delay, "delay");
        if (delay.isZero() || delay.isNegative()) {
            throw new IllegalArgumentException("Auto-lock delay must be positive");
        }

        var future = executor.schedule(action, delay.toMillis(), TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}

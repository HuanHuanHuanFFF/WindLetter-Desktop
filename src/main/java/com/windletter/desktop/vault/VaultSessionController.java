package com.windletter.desktop.vault;

import java.time.Duration;
import java.util.Objects;

final class VaultSessionController implements AutoCloseable {

    private final VaultAutoLockScheduler scheduler;

    private VaultSession session;
    private VaultAutoLockScheduler.Cancellable autoLockTask;
    private long timerGeneration;
    private boolean closed;

    VaultSessionController() {
        this(new ScheduledVaultAutoLockScheduler());
    }

    VaultSessionController(VaultAutoLockScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    synchronized void unlock(VaultSession newSession) {
        ensureOpen();
        Objects.requireNonNull(newSession, "newSession");

        cancelAutoLockTask();
        timerGeneration++;

        VaultSession previousSession = session;
        session = newSession;
        try {
            scheduleAutoLock();
        } catch (RuntimeException failure) {
            session = null;
            newSession.close();
            if (previousSession != null && previousSession != newSession) {
                previousSession.close();
            }
            throw failure;
        }

        if (previousSession != null && previousSession != newSession) {
            previousSession.close();
        }
    }

    synchronized void activity() {
        ensureOpen();
        requireSession();
        cancelAutoLockTask();
        timerGeneration++;
        scheduleAutoLockOrLock();
    }

    synchronized VaultSession session() {
        ensureOpen();
        return requireSession();
    }

    synchronized <T> T use(SessionOperation<T> operation) throws Exception {
        ensureOpen();
        Objects.requireNonNull(operation, "operation");
        VaultSession currentSession = requireSession();
        cancelAutoLockTask();
        timerGeneration++;
        try {
            return operation.apply(currentSession);
        } finally {
            if (!closed && session != null) {
                scheduleAutoLockOrLock();
            }
        }
    }

    synchronized boolean isUnlocked() {
        return !closed && session != null;
    }

    synchronized void lock() {
        ensureOpen();
        lockInternal();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        lockInternal();
        closed = true;
        scheduler.close();
    }

    private void scheduleAutoLock() {
        long expectedGeneration = timerGeneration;
        Duration delay = Duration.ofMinutes(session.payload().settings().autoLockMinutes());
        autoLockTask = scheduler.schedule(() -> expire(expectedGeneration), delay);
    }

    private void scheduleAutoLockOrLock() {
        try {
            scheduleAutoLock();
        } catch (RuntimeException failure) {
            lockInternal();
            throw failure;
        }
    }

    private synchronized void expire(long expectedGeneration) {
        if (closed || session == null || expectedGeneration != timerGeneration) {
            return;
        }
        lockInternal();
    }

    private void lockInternal() {
        cancelAutoLockTask();
        timerGeneration++;
        VaultSession expiredSession = session;
        session = null;
        if (expiredSession != null) {
            expiredSession.close();
        }
    }

    private void cancelAutoLockTask() {
        if (autoLockTask != null) {
            autoLockTask.cancel();
            autoLockTask = null;
        }
    }

    private VaultSession requireSession() {
        if (session == null) {
            throw new IllegalStateException("Vault is locked");
        }
        return session;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Vault session controller is closed");
        }
    }

    @FunctionalInterface
    interface SessionOperation<T> {
        T apply(VaultSession session) throws Exception;
    }
}

package com.windletter.desktop.vault;

import java.time.Duration;

interface VaultAutoLockScheduler extends AutoCloseable {

    Cancellable schedule(Runnable action, Duration delay);

    @Override
    void close();

    interface Cancellable {
        void cancel();
    }
}

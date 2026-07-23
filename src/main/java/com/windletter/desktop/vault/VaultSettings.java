package com.windletter.desktop.vault;

import java.util.UUID;

record VaultSettings(UUID defaultIdentityId, int autoLockMinutes) {

    static final int MIN_AUTO_LOCK_MINUTES = 1;
    static final int MAX_AUTO_LOCK_MINUTES = 1_440;

    VaultSettings {
        if (autoLockMinutes < MIN_AUTO_LOCK_MINUTES
            || autoLockMinutes > MAX_AUTO_LOCK_MINUTES) {
            throw new IllegalArgumentException(
                "autoLockMinutes is outside the supported range"
            );
        }
    }
}

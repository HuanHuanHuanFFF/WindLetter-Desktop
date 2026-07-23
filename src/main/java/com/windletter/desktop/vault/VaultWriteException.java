package com.windletter.desktop.vault;

/** Stable, non-diagnostic failure returned when a Vault cannot be encrypted. */
final class VaultWriteException extends Exception {

    static final String USER_MESSAGE = "无法保存密钥库。";

    VaultWriteException() {
        super(USER_MESSAGE);
    }
}

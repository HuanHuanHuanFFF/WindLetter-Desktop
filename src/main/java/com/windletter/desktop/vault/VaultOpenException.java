package com.windletter.desktop.vault;

/** Stable, non-diagnostic failure returned for every untrusted Vault open error. */
final class VaultOpenException extends Exception {

    static final String USER_MESSAGE = "无法解锁密钥库，请检查密码或文件。";

    VaultOpenException() {
        super(USER_MESSAGE);
    }
}

package com.windletter.desktop.vault;

final class VaultPayloadException extends Exception {

    static final String USER_MESSAGE = "保险库数据无效或已损坏，无法继续。";

    VaultPayloadException() {
        super(USER_MESSAGE);
    }
}

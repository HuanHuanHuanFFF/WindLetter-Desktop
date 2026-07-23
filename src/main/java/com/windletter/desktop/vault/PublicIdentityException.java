package com.windletter.desktop.vault;

final class PublicIdentityException extends Exception {

    static final String USER_MESSAGE = "公开身份文件无效或已损坏。";

    PublicIdentityException() {
        super(USER_MESSAGE);
    }
}

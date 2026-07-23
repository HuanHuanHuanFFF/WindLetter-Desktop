package com.windletter.desktop.vault;

public enum DesktopVaultProblem {
    OPEN_FAILED("无法解锁保险库。请检查密码或备份文件。"),
    SAVE_FAILED("无法保存更改。请确认位置可写并重试。"),
    INVALID_PASSWORD("密码须包含 8–256 个 Unicode 字符。"),
    INVALID_PUBLIC_IDENTITY("无法导入该公开身份。文件无效或已损坏。"),
    INVALID_INPUT("输入无效、条目不存在或已重复。"),
    LOCKED("保险库已锁定，请重新解锁。"),
    LOCK_BEFORE_RESTORE("恢复备份前请先锁定当前保险库。");

    private final String userMessage;

    DesktopVaultProblem(String userMessage) {
        this.userMessage = userMessage;
    }

    public String userMessage() {
        return userMessage;
    }
}

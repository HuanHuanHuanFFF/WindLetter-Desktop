package com.windletter.desktop.ui;

import com.windletter.desktop.vault.DesktopVault;

public final class DesktopVaultPresenter {

    private DesktopVaultPresenter() {
    }

    public static String identityListLabel(DesktopVault.IdentityView identity) {
        return identity.displayName()
            + (identity.defaultIdentity() ? " · 默认发送身份" : "");
    }

    public static String originLabel(DesktopVault.IdentityView identity) {
        return identity.origin() == DesktopVault.IdentityOrigin.GENERATED
            ? "在本机生成"
            : "从加密备份导入";
    }

    public static String contactListLabel(DesktopVault.ContactView contact) {
        return contact.displayName();
    }

    public static String verificationLabel(DesktopVault.ContactView contact) {
        if (contact.verification()
            == DesktopVault.ContactVerification.FINGERPRINT_VERIFIED) {
            return "已核对三组公钥指纹（不代表实名，也不代表消息签名有效）";
        }
        return "未核对公钥指纹，请先通过可信渠道比对";
    }

    public static String noteLabel(String note) {
        return note == null || note.isEmpty() ? "未填写" : note;
    }
}

package com.windletter.desktop.ui;

import com.windletter.desktop.vault.DesktopVault;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopVaultPresenterTest {

    @Test
    void shouldDistinguishIdentityOriginDefaultAndLocalNote() {
        DesktopVault.IdentityView identity = new DesktopVault.IdentityView(
            UUID.randomUUID(),
            "小岚",
            "只在本机保存",
            DesktopVault.IdentityOrigin.IMPORTED,
            true,
            "X25519  one\nML-KEM-768  two\nEd25519  three"
        );

        assertEquals(
            "小岚 · 默认发送身份",
            DesktopVaultPresenter.identityListLabel(identity)
        );
        assertEquals("从加密备份导入", DesktopVaultPresenter.originLabel(identity));
        assertEquals("只在本机保存", DesktopVaultPresenter.noteLabel(identity.note()));
    }

    @Test
    void shouldUseImmutableClaimedNameWithoutTreatingFingerprintAsIdentityOrSignature() {
        DesktopVault.ContactView verified = new DesktopVault.ContactView(
            UUID.randomUUID(),
            "对方声明名称",
            null,
            DesktopVault.ContactVerification.FINGERPRINT_VERIFIED,
            "X25519  one\nML-KEM-768  two\nEd25519  three"
        );

        assertEquals(
            "对方声明名称",
            DesktopVaultPresenter.contactListLabel(verified)
        );
        String verification = DesktopVaultPresenter.verificationLabel(verified);
        assertTrue(verification.contains("已核对三组公钥指纹"));
        assertTrue(verification.contains("不代表实名"));
        assertTrue(verification.contains("不代表消息签名有效"));
        assertEquals("未填写", DesktopVaultPresenter.noteLabel(null));
    }

    @Test
    void shouldKeepUnverifiedContactWarningExplicit() {
        DesktopVault.ContactView unverified = new DesktopVault.ContactView(
            UUID.randomUUID(),
            "陌生联系人",
            null,
            DesktopVault.ContactVerification.UNVERIFIED,
            "X25519  one\nML-KEM-768  two\nEd25519  three"
        );

        assertEquals(
            "未核对公钥指纹，请先通过可信渠道比对",
            DesktopVaultPresenter.verificationLabel(unverified)
        );
    }
}

package com.windletter.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.windletter.api.enums.DecryptStatus;
import com.windletter.api.enums.VerificationStatus;
import com.windletter.desktop.selftest.SelfTestReport;
import org.junit.jupiter.api.Test;

class SelfTestPresenterTest {

    @Test
    void successfulReportBecomesPlainChineseWithoutSensitiveMessageContent() {
        SelfTestReport report = new SelfTestReport(
            true,
            true,
            DecryptStatus.SUCCESS,
            VerificationStatus.SIGNED_VALID,
            "阶段 1 自检发送者",
            true,
            DecryptStatus.INVALID_MESSAGE,
            true,
            DecryptStatus.NOT_FOR_ME,
            true,
            812,
            37
        );

        SelfTestViewState state = SelfTestPresenter.completed(report);

        assertEquals("真实收发成功", state.title());
        assertEquals("签名有效 · 阶段 1 自检发送者", state.authentication());
        assertEquals("标准 Base64 PEM · 812 字符", state.transport());
        assertEquals("原始内容已完整恢复", state.payload());
        assertEquals("篡改消息与错误收件人均已安全拒绝", state.negativeChecks());
        assertTrue(state.successful());
        assertFalse(state.running());
        assertFalse(state.toString().contains("BEGIN WIND LETTER"));
    }

    @Test
    void failedTaskUsesGenericUserMessageInsteadOfExceptionDetails() {
        SelfTestViewState state = SelfTestPresenter.failed();

        assertEquals("真实收发未完成", state.title());
        assertEquals("未能完成安全自检，请重试。", state.summary());
        assertFalse(state.successful());
        assertFalse(state.running());
    }
}

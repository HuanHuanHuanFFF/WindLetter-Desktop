package com.windletter.desktop.ui;

import com.windletter.desktop.selftest.SelfTestReport;

/** Maps core result enums to stable, plain Chinese UI wording. */
public final class SelfTestPresenter {

    private SelfTestPresenter() {
    }

    public static SelfTestViewState idle() {
        return new SelfTestViewState(
            "等待真实收发自检",
            "点击按钮后，将在内存中生成临时密钥并完成一次真实 WindLetter 收发。",
            "尚未验证",
            "PUBLIC · X25519 · 已签名 · Base64 PEM",
            "尚未恢复",
            "尚未检查",
            "—",
            false,
            false
        );
    }

    public static SelfTestViewState running() {
        return new SelfTestViewState(
            "正在执行真实收发",
            "正在生成临时密钥、加密、Armor 传递、解密并验签…",
            "验证中",
            "PUBLIC · X25519 · 已签名 · Base64 PEM",
            "恢复中",
            "检查中",
            "计时中",
            false,
            true
        );
    }

    public static SelfTestViewState completed(SelfTestReport report) {
        if (!report.successful()) {
            return new SelfTestViewState(
                "真实收发未通过",
                "安全自检返回了未通过结果，请重试。",
                authentication(report),
                transport(report),
                report.payloadMatches() ? "原始内容已完整恢复" : "原始内容未能确认",
                "失败输入已被拒绝，但完整检查未通过",
                report.elapsedMillis() + " ms",
                false,
                false
            );
        }
        return new SelfTestViewState(
            "真实收发成功",
            "消息已通过 WindLetter 核心库完成加密、传递、解密与验签。",
            authentication(report),
            transport(report),
            "原始内容已完整恢复",
            "篡改消息与错误收件人均已安全拒绝",
            report.elapsedMillis() + " ms",
            true,
            false
        );
    }

    public static SelfTestViewState failed() {
        return new SelfTestViewState(
            "真实收发未完成",
            "未能完成安全自检，请重试。",
            "未确认",
            "未确认",
            "未恢复",
            "未完成",
            "—",
            false,
            false
        );
    }

    private static String authentication(SelfTestReport report) {
        return report.authenticatedSender() == null
            ? "未确认发送者"
            : "签名有效 · " + report.authenticatedSender();
    }

    private static String transport(SelfTestReport report) {
        return report.pemHeaderValid()
            ? "标准 Base64 PEM · " + report.armorCharacters() + " 字符"
            : "文本传输格式未确认";
    }
}

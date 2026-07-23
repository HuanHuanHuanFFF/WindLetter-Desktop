package com.windletter.desktop.selftest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.windletter.api.enums.DecryptStatus;
import com.windletter.api.enums.VerificationStatus;
import org.junit.jupiter.api.Test;

class RoundTripSelfTestServiceTest {

    @Test
    void signedPublicPemRoundTripUsesRealCoreAndRestoresUnicodePayload() {
        SelfTestReport report = new RoundTripSelfTestService().run(
            "風笺桌面端真实收发 · 𠮷 · 🌬️"
        );

        assertTrue(report.successful());
        assertTrue(report.pemHeaderValid());
        assertEquals(DecryptStatus.SUCCESS, report.decryptStatus());
        assertEquals(VerificationStatus.SIGNED_VALID, report.verificationStatus());
        assertEquals("阶段 1 自检发送者", report.authenticatedSender());
        assertTrue(report.payloadMatches());
        assertTrue(report.armorCharacters() > 100);
    }

    @Test
    void malformedAndWrongRecipientInputsStayFailuresWithoutPayloadExposure() {
        String sensitivePayload = "不应出现在报告中的 payload-marker-7d22";

        SelfTestReport report = new RoundTripSelfTestService().run(sensitivePayload);

        assertEquals(DecryptStatus.INVALID_MESSAGE, report.truncatedMessageStatus());
        assertTrue(report.truncatedPayloadAbsent());
        assertEquals(DecryptStatus.NOT_FOR_ME, report.wrongRecipientStatus());
        assertTrue(report.wrongRecipientPayloadAbsent());
        assertFalse(report.toString().contains(sensitivePayload));
    }
}

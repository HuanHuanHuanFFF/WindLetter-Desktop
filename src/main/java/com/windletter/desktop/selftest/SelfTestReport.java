package com.windletter.desktop.selftest;

import com.windletter.api.enums.DecryptStatus;
import com.windletter.api.enums.VerificationStatus;

/** Non-sensitive observable results from the phase 1 real-core self-test. */
public record SelfTestReport(
    boolean successful,
    boolean pemHeaderValid,
    DecryptStatus decryptStatus,
    VerificationStatus verificationStatus,
    String authenticatedSender,
    boolean payloadMatches,
    DecryptStatus truncatedMessageStatus,
    boolean truncatedPayloadAbsent,
    DecryptStatus wrongRecipientStatus,
    boolean wrongRecipientPayloadAbsent,
    int armorCharacters,
    long elapsedMillis
) {
}

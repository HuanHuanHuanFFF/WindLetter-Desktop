package com.windletter.desktop.receive;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReceiveResultTest {

    @Test
    void shouldClearOwnedPayloadAndRejectAccessAfterClose() {
        byte[] callerPayload = new byte[] {1, 2, 3};
        ReceiveResult result = new ReceiveResult(
            ReceiveStatus.SUCCESS,
            ReceiveAuthentication.SIGNED_VALID,
            callerPayload,
            "application/octet-stream",
            callerPayload.length,
            UUID.randomUUID(),
            "发送者",
            "signing-kid",
            true,
            "message-id",
            1
        );
        callerPayload[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, result.payload());

        result.close();

        assertThrows(IllegalStateException.class, result::payload);
    }
}

package com.windletter.desktop.vault;

import com.windletter.desktop.receive.ReceiveInput;
import com.windletter.desktop.receive.ReceiveResult;
import com.windletter.desktop.receive.ReceiveStatus;
import com.windletter.desktop.send.SendKeyProfile;
import com.windletter.desktop.send.SendMode;
import com.windletter.desktop.send.SendOutputFormat;
import com.windletter.desktop.send.SendPayload;
import com.windletter.desktop.send.SendRequest;
import com.windletter.desktop.send.SendResult;
import com.windletter.protocol.ProtocolLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PayloadBoundaryRoundTripTest {

    @TempDir
    Path directory;

    @Test
    void shouldRoundTripEmptyAndMaximumPayloadsThroughTheRealCore()
        throws Exception {
        try (DesktopVault vault = new DesktopVault(
            directory.resolve("payload-boundaries.wlv")
        )) {
            vault.create("八位安全测试密码".toCharArray(), 15);
            UUID identityId = vault.createIdentity("边界测试身份", null);
            UUID contactId = vault.importContact(vault.exportPublicIdentity(
                identityId,
                DesktopVault.PublicIdentityArmor.BASE64_PEM
            ));

            roundTrip(vault, identityId, contactId, new byte[0]);

            byte[] maximum = new byte[ProtocolLimits.MAX_PAYLOAD_BYTES];
            for (int index = 0; index < maximum.length; index++) {
                maximum[index] = (byte) (index * 31 + 7);
            }
            try {
                roundTrip(vault, identityId, contactId, maximum);
            } finally {
                Arrays.fill(maximum, (byte) 0);
            }
        }
    }

    private static void roundTrip(
        DesktopVault vault,
        UUID identityId,
        UUID contactId,
        byte[] payload
    ) throws Exception {
        SendResult encrypted = vault.send(new SendRequest(
            identityId,
            List.of(contactId),
            SendMode.PUBLIC,
            SendKeyProfile.X25519,
            false,
            SendOutputFormat.BINARY,
            new SendPayload("application/octet-stream", payload)
        ));
        try (ReceiveResult result = vault.receive(ReceiveInput.binary(
            identityId,
            encrypted.binary()
        ))) {
            assertEquals(ReceiveStatus.SUCCESS, result.status());
            assertEquals(payload.length, result.originalSize());
            assertArrayEquals(payload, result.payload());
        }
    }
}

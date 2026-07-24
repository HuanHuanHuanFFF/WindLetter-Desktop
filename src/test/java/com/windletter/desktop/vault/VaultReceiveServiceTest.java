package com.windletter.desktop.vault;

import com.windletter.desktop.receive.ReceiveAuthentication;
import com.windletter.desktop.receive.ReceiveInput;
import com.windletter.desktop.receive.ReceiveResult;
import com.windletter.desktop.receive.ReceiveStatus;
import com.windletter.desktop.send.SendKeyProfile;
import com.windletter.desktop.send.SendMode;
import com.windletter.desktop.send.SendOutputFormat;
import com.windletter.desktop.send.SendPayload;
import com.windletter.desktop.send.SendRequest;
import com.windletter.desktop.send.SendResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultReceiveServiceTest {

    @TempDir
    Path directory;

    @Test
    void shouldReceiveEveryRealCombinationWithAccurateAuthentication()
        throws Exception {
        try (
            DesktopVault sender = new DesktopVault(
                directory.resolve("sender.wlv")
            );
            DesktopVault recipient = new DesktopVault(
                directory.resolve("recipient.wlv")
            )
        ) {
            sender.create("发送方安全测试密码".toCharArray(), 15);
            recipient.create("接收方安全测试密码".toCharArray(), 15);
            UUID senderIdentityId = sender.createIdentity("可信发送者", null);
            UUID recipientIdentityId = recipient.createIdentity(
                "当前收件身份",
                null
            );
            String senderPublicIdentity = sender.exportPublicIdentity(
                senderIdentityId,
                DesktopVault.PublicIdentityArmor.BASE64_PEM
            );
            String recipientPublicIdentity = recipient.exportPublicIdentity(
                recipientIdentityId,
                DesktopVault.PublicIdentityArmor.WIND_BASE_1024F_V1
            );
            UUID recipientContactId = sender.importContact(
                recipientPublicIdentity
            );
            UUID senderContactId = recipient.importContact(
                senderPublicIdentity
            );
            recipient.updateContactNoteAndVerification(
                senderContactId,
                "已线下核对",
                true
            );

            byte[] payload = "阶段四真实接收 𠮷 🌬️"
                .getBytes(StandardCharsets.UTF_8);
            for (SendMode mode : SendMode.values()) {
                for (SendKeyProfile profile : SendKeyProfile.values()) {
                    for (boolean signed : List.of(false, true)) {
                        SendOutputFormat format = outputFormat(
                            mode,
                            profile,
                            signed
                        );
                        SendResult encrypted = sender.send(new SendRequest(
                            senderIdentityId,
                            List.of(recipientContactId),
                            mode,
                            profile,
                            signed,
                            format,
                            new SendPayload(
                                "text/plain;charset=UTF-8",
                                payload
                            )
                        ));

                        ReceiveResult result = recipient.receive(input(
                            recipientIdentityId,
                            encrypted
                        ));
                        assertEquals(ReceiveStatus.SUCCESS, result.status());
                        assertArrayEquals(payload, result.payload());
                        assertEquals(
                            "text/plain;charset=UTF-8",
                            result.contentType()
                        );
                        assertEquals(payload.length, result.originalSize());
                        assertFalse(result.messageId().isBlank());
                        assertTrue(result.timestamp() > 0);
                        if (signed) {
                            assertEquals(
                                ReceiveAuthentication.SIGNED_VALID,
                                result.authentication()
                            );
                            assertEquals(
                                senderContactId,
                                result.senderContactId()
                            );
                            assertEquals(
                                "可信发送者",
                                result.senderDisplayName()
                            );
                            assertTrue(result.senderFingerprintVerified());
                        } else {
                            assertEquals(
                                ReceiveAuthentication.UNSIGNED,
                                result.authentication()
                            );
                            assertNull(result.senderContactId());
                            assertNull(result.senderDisplayName());
                            assertFalse(result.senderFingerprintVerified());
                        }
                    }
                }
            }
        }
    }

    @Test
    void shouldKeepWrongRecipientAndInvalidMessageResultsPayloadFree()
        throws Exception {
        try (
            DesktopVault sender = new DesktopVault(
                directory.resolve("negative-sender.wlv")
            );
            DesktopVault recipient = new DesktopVault(
                directory.resolve("negative-recipient.wlv")
            )
        ) {
            sender.create("发送方安全测试密码".toCharArray(), 15);
            recipient.create("接收方安全测试密码".toCharArray(), 15);
            UUID senderId = sender.createIdentity("发送者", null);
            UUID intendedRecipient = recipient.createIdentity(
                "正确收件身份",
                null
            );
            UUID wrongRecipient = recipient.createIdentity(
                "错误收件身份",
                null
            );
            UUID contactId = sender.importContact(
                recipient.exportPublicIdentity(
                    intendedRecipient,
                    DesktopVault.PublicIdentityArmor.BASE64_PEM
                )
            );
            recipient.importContact(sender.exportPublicIdentity(
                senderId,
                DesktopVault.PublicIdentityArmor.BASE64_PEM
            ));
            SendResult encrypted = sender.send(new SendRequest(
                senderId,
                List.of(contactId),
                SendMode.PUBLIC,
                SendKeyProfile.X25519,
                true,
                SendOutputFormat.BASE64_PEM,
                new SendPayload(
                    "application/octet-stream",
                    new byte[] {0, 1, 2, 3}
                )
            ));

            ReceiveResult notForMe = recipient.receive(ReceiveInput.text(
                wrongRecipient,
                encrypted.text()
            ));
            assertFailure(ReceiveStatus.NOT_FOR_ME, notForMe);

            String truncated = encrypted.text().substring(
                0,
                encrypted.text().lastIndexOf('\n')
            );
            ReceiveResult invalid = recipient.receive(ReceiveInput.text(
                intendedRecipient,
                truncated
            ));
            assertFailure(ReceiveStatus.INVALID_MESSAGE, invalid);

            ReceiveResult malformedBinary = recipient.receive(
                ReceiveInput.binary(
                    intendedRecipient,
                    new byte[] {1, 2, 3, 4}
                )
            );
            assertFailure(
                ReceiveStatus.INVALID_MESSAGE,
                malformedBinary
            );
        }
    }

    @Test
    void shouldRequireKnownSenderOnlyWhenTheMessageNeedsSenderTrust()
        throws Exception {
        try (
            DesktopVault sender = new DesktopVault(
                directory.resolve("trust-sender.wlv")
            );
            DesktopVault recipient = new DesktopVault(
                directory.resolve("trust-recipient.wlv")
            )
        ) {
            sender.create("发送方安全测试密码".toCharArray(), 15);
            recipient.create("接收方安全测试密码".toCharArray(), 15);
            UUID senderId = sender.createIdentity("将被删除的联系人", null);
            UUID recipientId = recipient.createIdentity("接收身份", null);
            UUID recipientContact = sender.importContact(
                recipient.exportPublicIdentity(
                    recipientId,
                    DesktopVault.PublicIdentityArmor.BASE64_PEM
                )
            );
            UUID senderContact = recipient.importContact(
                sender.exportPublicIdentity(
                    senderId,
                    DesktopVault.PublicIdentityArmor.BASE64_PEM
                )
            );

            SendResult signed = sender.send(new SendRequest(
                senderId,
                List.of(recipientContact),
                SendMode.OBFUSCATION,
                SendKeyProfile.X25519_ML_KEM_768,
                true,
                SendOutputFormat.WIND_BASE_1024F_V1,
                new SendPayload("text/plain", new byte[] {1})
            ));
            SendResult unsigned = sender.send(new SendRequest(
                senderId,
                List.of(recipientContact),
                SendMode.OBFUSCATION,
                SendKeyProfile.X25519,
                false,
                SendOutputFormat.BASE64_PEM,
                new SendPayload("text/plain", new byte[] {2})
            ));

            recipient.deleteContact(senderContact);

            assertFailure(
                ReceiveStatus.INVALID_MESSAGE,
                recipient.receive(ReceiveInput.text(
                    recipientId,
                    signed.text()
                ))
            );
            ReceiveResult unsignedResult = recipient.receive(
                ReceiveInput.text(recipientId, unsigned.text())
            );
            assertEquals(
                ReceiveStatus.SUCCESS,
                unsignedResult.status()
            );
            assertEquals(
                ReceiveAuthentication.UNSIGNED,
                unsignedResult.authentication()
            );
            assertArrayEquals(
                new byte[] {2},
                unsignedResult.payload()
            );
        }
    }

    @Test
    void shouldRejectTamperedBinaryWithoutExposingPlaintext()
        throws Exception {
        try (
            DesktopVault sender = new DesktopVault(
                directory.resolve("tamper-sender.wlv")
            );
            DesktopVault recipient = new DesktopVault(
                directory.resolve("tamper-recipient.wlv")
            )
        ) {
            sender.create("发送方安全测试密码".toCharArray(), 15);
            recipient.create("接收方安全测试密码".toCharArray(), 15);
            UUID senderId = sender.createIdentity("篡改测试发送者", null);
            UUID recipientId = recipient.createIdentity(
                "篡改测试接收者",
                null
            );
            UUID recipientContact = sender.importContact(
                recipient.exportPublicIdentity(
                    recipientId,
                    DesktopVault.PublicIdentityArmor.BASE64_PEM
                )
            );
            recipient.importContact(sender.exportPublicIdentity(
                senderId,
                DesktopVault.PublicIdentityArmor.BASE64_PEM
            ));
            SendResult encrypted = sender.send(new SendRequest(
                senderId,
                List.of(recipientContact),
                SendMode.PUBLIC,
                SendKeyProfile.X25519_ML_KEM_768,
                true,
                SendOutputFormat.BINARY,
                new SendPayload(
                    "text/plain",
                    "绝不能从被篡改消息恢复".getBytes(
                        StandardCharsets.UTF_8
                    )
                )
            ));

            byte[] tampered = encrypted.binary();
            tampered[tampered.length - 1] ^= 1;
            ReceiveResult result = recipient.receive(ReceiveInput.binary(
                recipientId,
                tampered
            ));

            assertFailure(ReceiveStatus.INVALID_MESSAGE, result);
        }
    }

    @Test
    void shouldKeepLocalReceiveFailuresGenericAndDistinguishLockedVault()
        throws Exception {
        try (DesktopVault vault = new DesktopVault(
            directory.resolve("receive-errors.wlv")
        )) {
            vault.create("接收方安全测试密码".toCharArray(), 15);
            UUID identityId = vault.createIdentity("接收身份", null);

            DesktopVaultException missingIdentity = assertThrows(
                DesktopVaultException.class,
                () -> vault.receive(ReceiveInput.binary(
                    UUID.randomUUID(),
                    new byte[] {1}
                ))
            );
            assertEquals(
                DesktopVaultProblem.RECEIVE_FAILED,
                missingIdentity.problem()
            );
            assertNull(missingIdentity.getCause());

            vault.lock();
            DesktopVaultException locked = assertThrows(
                DesktopVaultException.class,
                () -> vault.receive(ReceiveInput.binary(
                    identityId,
                    new byte[] {1}
                ))
            );
            assertEquals(DesktopVaultProblem.LOCKED, locked.problem());
            assertNull(locked.getCause());
        }
    }

    private static ReceiveInput input(
        UUID recipientIdentityId,
        SendResult encrypted
    ) {
        return encrypted.outputFormat() == SendOutputFormat.BINARY
            ? ReceiveInput.binary(recipientIdentityId, encrypted.binary())
            : ReceiveInput.text(recipientIdentityId, encrypted.text());
    }

    private static SendOutputFormat outputFormat(
        SendMode mode,
        SendKeyProfile profile,
        boolean signed
    ) {
        int selector = mode.ordinal() + profile.ordinal() + (signed ? 1 : 0);
        return SendOutputFormat.values()[
            selector % SendOutputFormat.values().length
        ];
    }

    private static void assertFailure(
        ReceiveStatus expectedStatus,
        ReceiveResult result
    ) {
        assertEquals(expectedStatus, result.status());
        assertEquals(
            ReceiveAuthentication.NOT_APPLICABLE,
            result.authentication()
        );
        assertNull(result.payload());
        assertNull(result.contentType());
        assertEquals(0, result.originalSize());
        assertNull(result.senderContactId());
        assertNull(result.senderDisplayName());
        assertFalse(result.senderFingerprintVerified());
        assertNull(result.messageId());
        assertEquals(0, result.timestamp());
    }
}

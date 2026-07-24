package com.windletter.desktop.ui;

import com.windletter.desktop.send.SendKeyProfile;
import com.windletter.desktop.send.SendMode;
import com.windletter.desktop.send.SendOutputFormat;
import com.windletter.desktop.send.SendPayload;
import com.windletter.desktop.send.SendRequest;
import com.windletter.desktop.send.SendResult;
import com.windletter.desktop.vault.DesktopVault;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiveDesktopPaneFlowTest {

    @TempDir
    Path directory;

    @BeforeAll
    static void startJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        Platform.setImplicitExit(false);
    }

    @Test
    void shouldDecryptAndAuthenticateARealWindBaseMessage() throws Exception {
        try (DesktopVault vault = preparedVault("receive-page.wlv")) {
            SendResult encrypted = signedMessage(vault, "接收页真实消息 𠮷 🌬️");

            runOnFxThread(() -> {
                Stage stage = new Stage();
                AtomicReference<String> status = new AtomicReference<>();
                ReceiveDesktopPane pane = new ReceiveDesktopPane(
                    stage,
                    vault,
                    vault.snapshot(),
                    (operation, success, failure) -> {
                        try {
                            success.accept(operation.call());
                        } catch (Throwable thrown) {
                            failure.accept(thrown);
                        }
                    },
                    (message, error) -> status.set(message)
                );
                Node root = pane.build();
                stage.setScene(new Scene((Parent) root, 1000, 760));
                stage.show();

                TextArea armor = (TextArea) root.lookup(
                    "#receive-armor-text"
                );
                Button process = (Button) root.lookup(
                    "#receive-process-button"
                );
                Label title = (Label) root.lookup(
                    "#receive-result-title"
                );
                Label authentication = (Label) root.lookup(
                    "#receive-authentication"
                );
                Label sender = (Label) root.lookup("#receive-sender");
                TextArea preview = (TextArea) root.lookup(
                    "#receive-payload-preview"
                );

                assertNotNull(armor);
                assertNotNull(process);
                assertNotNull(title);
                assertNotNull(authentication);
                assertNotNull(sender);
                assertNotNull(preview);
                assertTrue(process.isDisabled());

                armor.setText(encrypted.text());
                assertFalse(process.isDisabled());
                process.fire();

                assertEquals("解密成功", title.getText());
                assertTrue(authentication.getText().contains("签名有效"));
                assertTrue(authentication.getText().contains("指纹已核对"));
                assertTrue(sender.getText().contains("接收测试身份"));
                assertEquals("接收页真实消息 𠮷 🌬️", preview.getText());
                assertEquals("消息已安全解密。", status.get());

                pane.close();
                stage.close();
            });
        }
    }

    @Test
    void shouldClearPlaintextAndShowStableFailureForInvalidInput()
        throws Exception {
        try (DesktopVault vault = preparedVault("receive-failure.wlv")) {
            SendResult encrypted = signedMessage(vault, "不得残留的明文");

            runOnFxThread(() -> {
                Stage stage = new Stage();
                ReceiveDesktopPane pane = new ReceiveDesktopPane(
                    stage,
                    vault,
                    vault.snapshot(),
                    (operation, success, failure) -> {
                        try {
                            success.accept(operation.call());
                        } catch (Throwable thrown) {
                            failure.accept(thrown);
                        }
                    },
                    (message, error) -> {
                    }
                );
                Node root = pane.build();
                stage.setScene(new Scene((Parent) root, 1000, 760));
                stage.show();

                TextArea armor = (TextArea) root.lookup(
                    "#receive-armor-text"
                );
                Button process = (Button) root.lookup(
                    "#receive-process-button"
                );
                Label title = (Label) root.lookup(
                    "#receive-result-title"
                );
                TextArea preview = (TextArea) root.lookup(
                    "#receive-payload-preview"
                );

                armor.setText(encrypted.text());
                process.fire();
                assertEquals("不得残留的明文", preview.getText());

                armor.setText(encrypted.text().substring(
                    0,
                    encrypted.text().lastIndexOf('\n')
                ));
                process.fire();

                assertEquals("消息无法安全处理", title.getText());
                assertTrue(preview.getText().isEmpty());
                pane.close();
                assertTrue(preview.getText().isEmpty());
                stage.close();
            });
        }
    }

    private DesktopVault preparedVault(String fileName) throws Exception {
        DesktopVault vault = new DesktopVault(directory.resolve(fileName));
        vault.create("八位安全测试密码".toCharArray(), 15);
        UUID identityId = vault.createIdentity("接收测试身份", null);
        UUID contactId = vault.importContact(vault.exportPublicIdentity(
            identityId,
            DesktopVault.PublicIdentityArmor.BASE64_PEM
        ));
        vault.updateContactNoteAndVerification(contactId, "已线下核对", true);
        return vault;
    }

    private static SendResult signedMessage(
        DesktopVault vault,
        String text
    ) throws Exception {
        DesktopVault.Snapshot snapshot = vault.snapshot();
        UUID identityId = snapshot.identities().get(0).identityId();
        UUID contactId = snapshot.contacts().get(0).contactId();
        return vault.send(new SendRequest(
            identityId,
            List.of(contactId),
            SendMode.OBFUSCATION,
            SendKeyProfile.X25519_ML_KEM_768,
            true,
            SendOutputFormat.WIND_BASE_1024F_V1,
            new SendPayload(
                "text/plain;charset=UTF-8",
                text.getBytes(StandardCharsets.UTF_8)
            )
        ));
    }

    private static void runOnFxThread(ThrowingRunnable runnable)
        throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                runnable.run();
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                finished.countDown();
            }
        });
        assertTrue(finished.await(60, TimeUnit.SECONDS));
        Throwable thrown = failure.get();
        if (thrown instanceof Exception exception) {
            throw exception;
        }
        if (thrown instanceof Error error) {
            throw error;
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}

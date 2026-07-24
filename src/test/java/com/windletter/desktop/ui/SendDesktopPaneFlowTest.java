package com.windletter.desktop.ui;

import com.windletter.desktop.vault.DesktopVault;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SendDesktopPaneFlowTest {

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
    void shouldGenerateRealWindBaseTextFromTheSendPage() throws Exception {
        try (DesktopVault vault = new DesktopVault(
            directory.resolve("send-page.wlv")
        )) {
            vault.create("八位安全测试密码".toCharArray(), 15);
            UUID identityId = vault.createIdentity("发送测试身份", null);
            String publicIdentity = vault.exportPublicIdentity(
                identityId,
                DesktopVault.PublicIdentityArmor.BASE64_PEM
            );
            vault.importContact(publicIdentity);

            runOnFxThread(() -> {
                Stage stage = new Stage();
                AtomicReference<String> status = new AtomicReference<>();
                SendDesktopPane pane = new SendDesktopPane(
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
                TextArea payload = (TextArea) root.lookup(
                    "#send-text-payload"
                );
                CheckBox recipient = (CheckBox) root.lookup(
                    ".send-recipient-check"
                );
                Button generate = (Button) root.lookup(
                    "#send-generate-button"
                );
                TextArea output = (TextArea) root.lookup(
                    "#send-output-text"
                );

                assertNotNull(payload);
                assertNotNull(recipient);
                assertNotNull(generate);
                assertNotNull(output);
                assertTrue(generate.isDisabled());

                payload.setText("来自发送页的真实消息 🌬️");
                recipient.setSelected(true);
                assertFalse(generate.isDisabled());
                generate.fire();

                assertTrue(output.getText().startsWith(
                    "-----風笺 起-----\n"
                ));
                assertEquals(
                    "WindLetter 消息已成功生成。",
                    status.get()
                );
                stage.close();
            });
        }
    }

    @Test
    void shouldClearOldOutputAndShowAStableFailureState() throws Exception {
        try (DesktopVault vault = new DesktopVault(
            directory.resolve("send-page-failure.wlv")
        )) {
            vault.create("八位安全测试密码".toCharArray(), 15);
            UUID identityId = vault.createIdentity("失败测试身份", null);
            vault.importContact(vault.exportPublicIdentity(
                identityId,
                DesktopVault.PublicIdentityArmor.BASE64_PEM
            ));

            runOnFxThread(() -> {
                Stage stage = new Stage();
                SendDesktopPane pane = new SendDesktopPane(
                    stage,
                    vault,
                    vault.snapshot(),
                    (operation, success, failure) ->
                        failure.accept(new IllegalStateException()),
                    (message, error) -> {
                    }
                );
                Node root = pane.build();
                stage.setScene(new Scene((Parent) root, 1000, 760));
                stage.show();
                CheckBox recipient = (CheckBox) root.lookup(
                    ".send-recipient-check"
                );
                Button generate = (Button) root.lookup(
                    "#send-generate-button"
                );
                TextArea output = (TextArea) root.lookup(
                    "#send-output-text"
                );
                Label summary = (Label) root.lookup(
                    "#send-output-summary"
                );

                recipient.setSelected(true);
                generate.fire();

                assertNotNull(summary);
                assertTrue(output.getText().isEmpty());
                assertEquals(
                    "生成失败，未保留任何输出。",
                    summary.getText()
                );
                stage.close();
            });
        }
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

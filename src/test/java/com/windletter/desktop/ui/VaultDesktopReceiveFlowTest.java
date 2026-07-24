package com.windletter.desktop.ui;

import com.windletter.desktop.send.SendKeyProfile;
import com.windletter.desktop.send.SendMode;
import com.windletter.desktop.send.SendOutputFormat;
import com.windletter.desktop.send.SendPayload;
import com.windletter.desktop.send.SendRequest;
import com.windletter.desktop.send.SendResult;
import com.windletter.desktop.vault.DesktopVault;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.TabPane;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultDesktopReceiveFlowTest {

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
    void shouldRunTheProductionReceivePageOffTheJavaFxThread()
        throws Exception {
        DesktopVault vault = new DesktopVault(
            directory.resolve("production-receive-page.wlv")
        );
        vault.create("八位安全测试密码".toCharArray(), 15);
        UUID identityId = vault.createIdentity("生产接收身份", null);
        UUID contactId = vault.importContact(vault.exportPublicIdentity(
            identityId,
            DesktopVault.PublicIdentityArmor.BASE64_PEM
        ));
        SendResult encrypted = vault.send(new SendRequest(
            identityId,
            List.of(contactId),
            SendMode.PUBLIC,
            SendKeyProfile.X25519,
            true,
            SendOutputFormat.BASE64_PEM,
            new SendPayload(
                "text/plain;charset=UTF-8",
                "生产接收页后台任务 🌬️".getBytes(StandardCharsets.UTF_8)
            )
        ));

        AtomicReference<Stage> stageRef = new AtomicReference<>();
        AtomicReference<VaultDesktopView> viewRef = new AtomicReference<>();
        try {
            runOnFxThread(() -> {
                Stage stage = new Stage();
                VaultDesktopView view = new VaultDesktopView(stage, vault);
                stageRef.set(stage);
                viewRef.set(view);
                view.show();

                TabPane tabs = (TabPane) stage.getScene().lookup(
                    ".tab-pane"
                );
                assertNotNull(tabs);
                tabs.getSelectionModel().select(3);
                assertEquals(
                    "接收",
                    tabs.getSelectionModel().getSelectedItem().getText()
                );
                TextArea armor = (TextArea) stage.getScene().lookup(
                    "#receive-armor-text"
                );
                Button process = (Button) stage.getScene().lookup(
                    "#receive-process-button"
                );
                assertNotNull(armor);
                assertNotNull(process);
                armor.setText(encrypted.text());
                process.fire();
            });

            long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(30);
            String preview = "";
            while (System.nanoTime() < deadline) {
                preview = onFxThread(() -> {
                    TextArea area = (TextArea) stageRef.get()
                        .getScene()
                        .lookup("#receive-payload-preview");
                    return area == null ? "" : area.getText();
                });
                if ("生产接收页后台任务 🌬️".equals(preview)) {
                    break;
                }
                Thread.sleep(100);
            }
            assertEquals("生产接收页后台任务 🌬️", preview);
        } finally {
            runOnFxThread(() -> {
                VaultDesktopView view = viewRef.get();
                if (view != null) {
                    view.close();
                } else {
                    vault.close();
                }
                Stage stage = stageRef.get();
                if (stage != null) {
                    stage.close();
                }
            });
        }
    }

    private static <T> T onFxThread(ThrowingSupplier<T> supplier)
        throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        runOnFxThread(() -> result.set(supplier.get()));
        return result.get();
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

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}

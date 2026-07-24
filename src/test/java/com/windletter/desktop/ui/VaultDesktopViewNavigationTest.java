package com.windletter.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.windletter.desktop.vault.DesktopVault;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VaultDesktopViewNavigationTest {

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
    void shouldKeepContactsTabSelectedWhenWorkspaceRefreshes()
        throws Exception {
        DesktopVault vault = new DesktopVault(
            directory.resolve("navigation-test.wlv")
        );
        vault.create("八位安全测试密码".toCharArray(), 15);

        runOnFxThread(() -> {
            Stage stage = new Stage();
            VaultDesktopView view = new VaultDesktopView(stage, vault);
            try {
                view.show();
                TabPane originalTabs = tabPane(stage);
                assertEquals(
                    java.util.List.of(
                        "我的身份",
                        "联系人",
                        "发送",
                        "接收",
                        "备份与恢复",
                        "协议自检"
                    ),
                    originalTabs.getTabs().stream()
                        .map(tab -> tab.getText())
                        .toList()
                );
                originalTabs.getSelectionModel().select(2);
                Button generate = (Button) originalTabs
                    .getSelectionModel()
                    .getSelectedItem()
                    .getContent()
                    .lookup("#send-generate-button");
                assertNotNull(generate);
                assertTrue(generate.isDisabled());

                originalTabs.getSelectionModel().select(3);
                Button receive = (Button) originalTabs
                    .getSelectionModel()
                    .getSelectedItem()
                    .getContent()
                    .lookup("#receive-process-button");
                assertNotNull(receive);
                assertTrue(receive.isDisabled());

                originalTabs.getSelectionModel().select(1);
                assertEquals(
                    "联系人",
                    originalTabs.getSelectionModel()
                        .getSelectedItem()
                        .getText()
                );

                Method refresh = VaultDesktopView.class.getDeclaredMethod(
                    "refreshWorkspace",
                    String.class
                );
                refresh.setAccessible(true);
                refresh.invoke(view, "联系人已刷新。");

                TabPane refreshedTabs = tabPane(stage);
                assertEquals(
                    "联系人",
                    refreshedTabs.getSelectionModel()
                        .getSelectedItem()
                        .getText()
                );
            } finally {
                view.close();
                stage.close();
            }
        });
    }

    private static TabPane tabPane(Stage stage) {
        TabPane tabs = (TabPane) stage.getScene().lookup(".tab-pane");
        assertNotNull(tabs);
        return tabs;
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
        assertTrue(finished.await(20, TimeUnit.SECONDS));
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

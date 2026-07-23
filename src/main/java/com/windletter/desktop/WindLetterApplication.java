package com.windletter.desktop;

import com.windletter.desktop.selftest.RoundTripSelfTestService;
import com.windletter.desktop.selftest.SelfTestReport;
import com.windletter.desktop.ui.SelfTestPresenter;
import com.windletter.desktop.ui.SelfTestViewState;
import java.net.URL;
import java.util.Objects;
import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Phase 1 JavaFX shell backed by the real WindLetter core self-test. */
public final class WindLetterApplication extends Application {

    public static final String WINDOW_TITLE = "風笺 · WindLetter";
    private static final String SELF_TEST_PAYLOAD = "風笺桌面端真实收发 · 𠮷 · 🌬️";

    private final RoundTripSelfTestService selfTestService = new RoundTripSelfTestService();
    private final Label resultTitle = new Label();
    private final Label resultSummary = new Label();
    private final Label authenticationValue = new Label();
    private final Label transportValue = new Label();
    private final Label payloadValue = new Label();
    private final Label negativeChecksValue = new Label();
    private final Label timingValue = new Label();
    private final VBox resultCard = new VBox(14);
    private final Button runButton = new Button("运行真实收发自检");
    private final ProgressIndicator progress = new ProgressIndicator();
    private Task<SelfTestReport> currentTask;

    @Override
    public void start(Stage stage) {
        Label eyebrow = new Label("WINDLETTER DESKTOP · 阶段 1");
        eyebrow.getStyleClass().add("eyebrow");

        Label heading = new Label("先确认真实协议链路，再扩展完整桌面功能");
        heading.getStyleClass().add("heading");
        heading.setWrapText(true);

        Label introduction = new Label(
            "本页使用短生命周期内存密钥，通过真实核心库完成一次已签名消息收发。"
                + "不会保存私钥，也不会展示完整消息或测试内容。"
        );
        introduction.getStyleClass().add("introduction");
        introduction.setWrapText(true);

        resultTitle.getStyleClass().add("result-title");
        resultSummary.getStyleClass().add("result-summary");
        resultSummary.setWrapText(true);
        resultCard.getStyleClass().add("result-card");
        resultCard.getChildren().addAll(
            resultTitle,
            resultSummary,
            detailRow("发送者认证", authenticationValue),
            detailRow("传输格式", transportValue),
            detailRow("内容恢复", payloadValue),
            detailRow("安全失败检查", negativeChecksValue),
            detailRow("本次耗时", timingValue)
        );

        progress.setPrefSize(20, 20);
        progress.setMaxSize(20, 20);
        progress.setVisible(false);
        progress.setManaged(false);

        runButton.getStyleClass().add("primary-button");
        runButton.setDefaultButton(true);
        runButton.setOnAction(event -> runSelfTest());

        HBox actionBar = new HBox(12, runButton, progress);
        actionBar.setAlignment(Pos.CENTER_LEFT);

        Label boundary = new Label(
            "当前边界：仅验证 PUBLIC · X25519 · signed · Base64 PEM；身份与密钥保存将在阶段 2 实现。"
        );
        boundary.getStyleClass().add("boundary-note");
        boundary.setWrapText(true);

        VBox root = new VBox(22, eyebrow, heading, introduction, resultCard, actionBar, boundary);
        root.getStyleClass().add("app-root");
        root.setPadding(new Insets(36));
        VBox.setVgrow(resultCard, Priority.ALWAYS);

        Scene scene = new Scene(root, 760, 610);
        URL stylesheet = Objects.requireNonNull(
            WindLetterApplication.class.getResource("windletter.css"),
            "windletter.css"
        );
        scene.getStylesheets().add(stylesheet.toExternalForm());

        applyState(SelfTestPresenter.idle());
        stage.setTitle(WINDOW_TITLE);
        stage.setMinWidth(680);
        stage.setMinHeight(560);
        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() {
        Task<SelfTestReport> task = currentTask;
        if (task != null) {
            task.cancel(true);
        }
    }

    private void runSelfTest() {
        if (currentTask != null) {
            return;
        }
        applyState(SelfTestPresenter.running());

        Task<SelfTestReport> task = new Task<>() {
            @Override
            protected SelfTestReport call() {
                return selfTestService.run(SELF_TEST_PAYLOAD);
            }
        };
        currentTask = task;
        task.setOnSucceeded(event -> {
            currentTask = null;
            applyState(SelfTestPresenter.completed(task.getValue()));
        });
        task.setOnFailed(event -> {
            currentTask = null;
            applyState(SelfTestPresenter.failed());
        });
        task.setOnCancelled(event -> currentTask = null);

        Thread worker = new Thread(task, "windletter-real-self-test");
        worker.setDaemon(true);
        worker.start();
    }

    private void applyState(SelfTestViewState state) {
        resultTitle.setText(state.title());
        resultSummary.setText(state.summary());
        authenticationValue.setText(state.authentication());
        transportValue.setText(state.transport());
        payloadValue.setText(state.payload());
        negativeChecksValue.setText(state.negativeChecks());
        timingValue.setText(state.timing());

        resultCard.getStyleClass().removeAll("result-success", "result-error", "result-running");
        if (state.running()) {
            resultCard.getStyleClass().add("result-running");
        } else if (state.successful()) {
            resultCard.getStyleClass().add("result-success");
        } else {
            resultCard.getStyleClass().add("result-error");
        }
        runButton.setDisable(state.running());
        runButton.setText(state.running() ? "自检进行中…" : "运行真实收发自检");
        progress.setVisible(state.running());
        progress.setManaged(state.running());
    }

    private static HBox detailRow(String labelText, Label value) {
        Label label = new Label(labelText);
        label.getStyleClass().add("detail-label");
        label.setMinWidth(112);

        value.getStyleClass().add("detail-value");
        value.setWrapText(true);
        value.setMaxWidth(Double.MAX_VALUE);

        Region spacer = new Region();
        HBox.setHgrow(value, Priority.ALWAYS);
        HBox.setHgrow(spacer, Priority.NEVER);
        HBox row = new HBox(18, label, value, spacer);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }
}

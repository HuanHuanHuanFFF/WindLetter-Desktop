package com.windletter.desktop.ui;

import com.windletter.desktop.send.SendKeyProfile;
import com.windletter.desktop.send.SendMode;
import com.windletter.desktop.send.SendOutputFormat;
import com.windletter.desktop.send.SendOutputWriter;
import com.windletter.desktop.send.SendPayload;
import com.windletter.desktop.send.SendRequest;
import com.windletter.desktop.send.SendResult;
import com.windletter.desktop.vault.DesktopVault;
import com.windletter.desktop.vault.DesktopVaultException;
import com.windletter.protocol.ProtocolLimits;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.FileChooser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Independent Stage 3 send workspace over the safe DesktopVault facade. */
final class SendDesktopPane {

    private static final String TEXT_CONTENT_TYPE =
        "text/plain;charset=UTF-8";

    private final Stage stage;
    private final DesktopVault vault;
    private final DesktopVault.Snapshot snapshot;
    private final SendRunner runner;
    private final StatusSink status;
    private final SendOutputWriter writer = new SendOutputWriter();
    private final ObjectProperty<Path> selectedFile =
        new SimpleObjectProperty<>();
    private final List<CheckBox> recipientChecks = new ArrayList<>();

    private final ComboBox<DesktopVault.IdentityView> identity =
        new ComboBox<>();
    private final ComboBox<SendMode> mode = new ComboBox<>();
    private final ComboBox<SendKeyProfile> keyProfile = new ComboBox<>();
    private final ComboBox<SendOutputFormat> outputFormat = new ComboBox<>();
    private final CheckBox signed = new CheckBox("签名并让收件人验证身份");
    private final RadioButton textSource = new RadioButton("输入文本");
    private final RadioButton fileSource = new RadioButton("选择文件");
    private final TextArea textPayload = new TextArea();
    private final TextField filePath = new TextField();
    private final Label selectionSummary = new Label();
    private final TextArea outputText = new TextArea();
    private final Label outputSummary = new Label();
    private final Button copyOutput = secondaryButton("复制文本");
    private final Button saveOutput = secondaryButton("保存消息");

    private SendResult latestResult;

    SendDesktopPane(
        Stage stage,
        DesktopVault vault,
        DesktopVault.Snapshot snapshot,
        SendRunner runner,
        StatusSink status
    ) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.vault = Objects.requireNonNull(vault, "vault");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.status = Objects.requireNonNull(status, "status");
    }

    Node build() {
        VBox content = new VBox(
            18,
            heading(),
            payloadCard(),
            recipientsCard(),
            optionsCard(),
            outputCard()
        );
        content.setPadding(new Insets(24));
        content.setMaxWidth(920);

        VBox centered = new VBox(content);
        centered.setAlignment(Pos.TOP_CENTER);
        ScrollPane scroll = new ScrollPane(centered);
        scroll.setFitToWidth(true);
        scroll.setPannable(true);
        return scroll;
    }

    private Node heading() {
        Label title = sectionHeading("生成 WindLetter 消息");
        Label description = new Label(
            "选择原始内容、收件人和传输方式。消息由真实 WindLetter 核心库生成。"
        );
        description.getStyleClass().add("introduction");
        description.setWrapText(true);
        return new VBox(6, title, description);
    }

    private Node payloadCard() {
        ToggleGroup sources = new ToggleGroup();
        textSource.setToggleGroup(sources);
        fileSource.setToggleGroup(sources);
        textSource.setId("send-text-source");
        fileSource.setId("send-file-source");
        textSource.setSelected(true);

        textPayload.setPromptText("输入要发送的文本，可包含中文、Emoji 和换行。");
        textPayload.setId("send-text-payload");
        textPayload.setPrefRowCount(8);
        textPayload.setWrapText(true);

        filePath.setEditable(false);
        filePath.setPromptText("尚未选择文件");
        filePath.textProperty().bind(Bindings.createStringBinding(
            () -> selectedFile.get() == null
                ? ""
                : selectedFile.get().toString(),
            selectedFile
        ));
        Button chooseFile = secondaryButton("选择文件");
        chooseFile.setOnAction(event -> choosePayloadFile());
        HBox fileRow = new HBox(10, filePath, chooseFile);
        HBox.setHgrow(filePath, Priority.ALWAYS);

        textPayload.disableProperty().bind(fileSource.selectedProperty());
        filePath.disableProperty().bind(textSource.selectedProperty());
        chooseFile.disableProperty().bind(textSource.selectedProperty());

        VBox card = card(
            fieldLabel("1. 原始内容"),
            new HBox(18, textSource, fileSource),
            textPayload,
            fileRow,
            boundaryNote(
                "单条 payload 最大 8 MiB。文件按原始字节发送；当前协议只携带 MIME，不携带文件名。"
            )
        );
        return card;
    }

    private Node recipientsCard() {
        VBox choices = new VBox(8);
        if (snapshot.contacts().isEmpty()) {
            Label empty = new Label(
                "还没有联系人。请先在“联系人”页导入公开身份。"
            );
            empty.getStyleClass().add("boundary-note");
            empty.setWrapText(true);
            choices.getChildren().add(empty);
        } else {
            for (DesktopVault.ContactView contact : snapshot.contacts()) {
                CheckBox check = new CheckBox(contactLabel(contact));
                check.getStyleClass().add("send-recipient-check");
                check.setUserData(contact.contactId());
                check.setWrapText(true);
                check.selectedProperty().addListener(
                    (observable, oldValue, newValue) ->
                        updateSelectionSummary()
                );
                recipientChecks.add(check);
                choices.getChildren().add(check);
            }
        }
        ScrollPane recipientScroll = new ScrollPane(choices);
        recipientScroll.setFitToWidth(true);
        recipientScroll.setPrefViewportHeight(150);
        recipientScroll.setMaxHeight(190);

        selectionSummary.getStyleClass().add("workspace-state");
        updateSelectionSummary();
        return card(
            fieldLabel("2. 收件人（可多选，最多 32 人）"),
            recipientScroll,
            selectionSummary
        );
    }

    private Node optionsCard() {
        identity.getItems().setAll(snapshot.identities());
        identity.setMaxWidth(Double.MAX_VALUE);
        identity.setCellFactory(list -> identityCell());
        identity.setButtonCell(identityCell());
        snapshot.identities().stream()
            .filter(DesktopVault.IdentityView::defaultIdentity)
            .findFirst()
            .or(() -> snapshot.identities().stream().findFirst())
            .ifPresent(identity::setValue);

        mode.getItems().setAll(SendMode.values());
        mode.setValue(SendMode.PUBLIC);
        mode.setCellFactory(list -> enumCell(SendDesktopPane::modeLabel));
        mode.setButtonCell(enumCell(SendDesktopPane::modeLabel));
        mode.setMaxWidth(Double.MAX_VALUE);

        keyProfile.getItems().setAll(SendKeyProfile.values());
        keyProfile.setValue(SendKeyProfile.X25519);
        keyProfile.setCellFactory(
            list -> enumCell(SendDesktopPane::keyProfileLabel)
        );
        keyProfile.setButtonCell(
            enumCell(SendDesktopPane::keyProfileLabel)
        );
        keyProfile.setMaxWidth(Double.MAX_VALUE);

        outputFormat.getItems().setAll(SendOutputFormat.values());
        outputFormat.setValue(SendOutputFormat.WIND_BASE_1024F_V1);
        outputFormat.setCellFactory(
            list -> enumCell(SendDesktopPane::formatLabel)
        );
        outputFormat.setButtonCell(
            enumCell(SendDesktopPane::formatLabel)
        );
        outputFormat.setMaxWidth(Double.MAX_VALUE);

        signed.setSelected(true);

        GridPane options = new GridPane();
        options.setHgap(16);
        options.setVgap(12);
        addOption(options, 0, "发送身份", identity);
        addOption(options, 1, "隐私模式", mode);
        addOption(options, 2, "收件人密钥", keyProfile);
        addOption(options, 3, "传输格式", outputFormat);
        options.add(signed, 1, 4);

        Button generate = primaryButton("生成真实消息");
        generate.setId("send-generate-button");
        List<javafx.beans.Observable> dependencies = new ArrayList<>();
        dependencies.add(identity.valueProperty());
        dependencies.add(mode.valueProperty());
        dependencies.add(signed.selectedProperty());
        dependencies.add(fileSource.selectedProperty());
        dependencies.add(selectedFile);
        recipientChecks.stream()
            .map(CheckBox::selectedProperty)
            .forEach(dependencies::add);
        BooleanBinding invalid = Bindings.createBooleanBinding(
            this::isInvalid,
            dependencies.toArray(javafx.beans.Observable[]::new)
        );
        generate.disableProperty().bind(invalid);
        generate.setOnAction(event -> generate());

        Label modeBoundary = boundaryNote(
            "public 会使用所选身份的 X25519 私钥；obfuscation 不发送该加密身份。开启签名时只额外使用同一身份的 Ed25519 私钥。"
        );
        return card(
            fieldLabel("3. 消息设置"),
            options,
            generate,
            modeBoundary
        );
    }

    private Node outputCard() {
        outputText.setEditable(false);
        outputText.setId("send-output-text");
        outputText.setWrapText(true);
        outputText.setPrefRowCount(12);
        outputText.setPromptText(
            "文本 Armor 会显示在这里；二进制消息只提供保存。"
        );
        outputSummary.getStyleClass().add("workspace-state");
        outputSummary.setId("send-output-summary");
        outputSummary.setText("尚未生成消息。");
        copyOutput.setDisable(true);
        saveOutput.setDisable(true);
        copyOutput.setOnAction(event -> copyLatestText());
        saveOutput.setOnAction(event -> saveLatest());
        FlowPane actions = new FlowPane(10, 10, copyOutput, saveOutput);
        return card(
            fieldLabel("4. 加密结果"),
            outputSummary,
            outputText,
            actions,
            boundaryNote(
                "只有生成成功后才会启用复制或保存。重新生成前会先清除上一次输出，避免把失败误当成成功。"
            )
        );
    }

    private void generate() {
        clearOutput();
        UUID senderId = identity.getValue() == null
            ? null
            : identity.getValue().identityId();
        List<UUID> contactIds = selectedContactIds();
        SendMode selectedMode = mode.getValue();
        SendKeyProfile selectedProfile = keyProfile.getValue();
        SendOutputFormat selectedFormat = outputFormat.getValue();
        boolean shouldSign = signed.isSelected();
        Path file = fileSource.isSelected() ? selectedFile.get() : null;
        String text = textSource.isSelected() ? textPayload.getText() : null;

        runner.run(
            () -> vault.send(new SendRequest(
                senderId,
                contactIds,
                selectedMode,
                selectedProfile,
                shouldSign,
                selectedFormat,
                loadPayload(file, text)
            )),
            this::showResult,
            this::showFailure
        );
    }

    static SendPayload loadPayload(Path file, String text)
        throws IOException {
        if (file == null) {
            byte[] bytes = Objects.requireNonNull(text, "text")
                .getBytes(StandardCharsets.UTF_8);
            try {
                return new SendPayload(TEXT_CONTENT_TYPE, bytes);
            } finally {
                java.util.Arrays.fill(bytes, (byte) 0);
            }
        }
        long size = Files.size(file);
        if (size > ProtocolLimits.MAX_PAYLOAD_BYTES) {
            throw new IOException("payload exceeds the supported size");
        }
        byte[] bytes = Files.readAllBytes(file);
        try {
            if (bytes.length > ProtocolLimits.MAX_PAYLOAD_BYTES) {
                throw new IOException("payload exceeds the supported size");
            }
            String contentType = Files.probeContentType(file);
            if (contentType == null || contentType.isBlank()) {
                contentType = "application/octet-stream";
            }
            return new SendPayload(contentType, bytes);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private void showResult(SendResult result) {
        latestResult = Objects.requireNonNull(result, "result");
        boolean binary = result.outputFormat() == SendOutputFormat.BINARY;
        outputText.setText(binary ? "" : result.text());
        outputSummary.setText(
            binary
                ? "二进制消息已生成（"
                    + result.binary().length
                    + " 字节），请选择保存位置。"
                : formatLabel(result.outputFormat())
                    + " 已生成，可复制或保存。"
        );
        copyOutput.setDisable(binary);
        saveOutput.setDisable(false);
        status.show("WindLetter 消息已成功生成。", false);
    }

    private void clearOutput() {
        latestResult = null;
        outputText.clear();
        outputSummary.setText("正在生成新消息…");
        copyOutput.setDisable(true);
        saveOutput.setDisable(true);
    }

    private void showFailure(Throwable failure) {
        latestResult = null;
        outputText.clear();
        outputSummary.setText("生成失败，未保留任何输出。");
        copyOutput.setDisable(true);
        saveOutput.setDisable(true);
        status.show(userMessage(failure), true);
    }

    private void copyLatestText() {
        SendResult result = latestResult;
        if (result == null || result.outputFormat() == SendOutputFormat.BINARY) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(result.text());
        Clipboard.getSystemClipboard().setContent(content);
        status.show("加密消息文本已复制到剪贴板。", false);
    }

    private void saveLatest() {
        SendResult result = latestResult;
        if (result == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("保存 WindLetter 消息");
        chooser.setInitialFileName(initialFileName(result.outputFormat()));
        chooser.getExtensionFilters().add(extension(result.outputFormat()));
        java.io.File selected = chooser.showSaveDialog(stage);
        if (selected == null) {
            return;
        }
        Path target = selected.toPath();
        runner.run(
            () -> {
                writer.write(target, result);
                return result;
            },
            ignored -> status.show("消息已安全保存。", false),
            failure -> status.show(
                "无法保存消息。请检查目标位置后重试。",
                true
            )
        );
    }

    private void choosePayloadFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择要发送的文件");
        java.io.File selected = chooser.showOpenDialog(stage);
        if (selected != null) {
            selectedFile.set(selected.toPath().toAbsolutePath().normalize());
        }
    }

    private boolean isInvalid() {
        int recipientCount = selectedContactIds().size();
        if (recipientCount < 1 || recipientCount > 32) {
            return true;
        }
        if ((mode.getValue() == SendMode.PUBLIC || signed.isSelected())
            && identity.getValue() == null) {
            return true;
        }
        return fileSource.isSelected() && selectedFile.get() == null;
    }

    private List<UUID> selectedContactIds() {
        return recipientChecks.stream()
            .filter(CheckBox::isSelected)
            .map(check -> (UUID) check.getUserData())
            .toList();
    }

    private void updateSelectionSummary() {
        int selected = selectedContactIds().size();
        selectionSummary.setText(
            selected == 0
                ? "尚未选择收件人。"
                : selected > 32
                    ? "已选择 " + selected
                        + " 位收件人，超过 32 人上限。"
                    : "已选择 " + selected + " 位收件人。"
        );
    }

    private static String contactLabel(DesktopVault.ContactView contact) {
        String verification = contact.verification()
            == DesktopVault.ContactVerification.FINGERPRINT_VERIFIED
                ? "已核对指纹"
                : "未核对指纹";
        return contact.displayName() + " · " + verification;
    }

    private static ListCell<DesktopVault.IdentityView> identityCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(
                DesktopVault.IdentityView item,
                boolean empty
            ) {
                super.updateItem(item, empty);
                setText(empty || item == null
                    ? null
                    : item.displayName()
                        + (item.defaultIdentity() ? " · 默认" : ""));
            }
        };
    }

    private static <T> ListCell<T> enumCell(
        java.util.function.Function<T, String> label
    ) {
        return new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : label.apply(item));
            }
        };
    }

    private static void addOption(
        GridPane grid,
        int row,
        String label,
        Node value
    ) {
        Label field = fieldLabel(label);
        grid.add(field, 0, row);
        grid.add(value, 1, row);
        GridPane.setHgrow(value, Priority.ALWAYS);
    }

    private static String modeLabel(SendMode value) {
        return value == SendMode.PUBLIC
            ? "公开模式（可见收件人标识）"
            : "混淆模式（减少关系特征暴露）";
    }

    private static String keyProfileLabel(SendKeyProfile value) {
        return value == SendKeyProfile.X25519
            ? "X25519"
            : "X25519 + ML-KEM-768";
    }

    private static String formatLabel(SendOutputFormat value) {
        return switch (value) {
            case BASE64_PEM -> "标准 Base64 PEM";
            case WIND_BASE_1024F_V1 -> "風笺文本（WindBase）";
            case BINARY -> "二进制文件";
        };
    }

    private static String initialFileName(SendOutputFormat format) {
        return switch (format) {
            case BASE64_PEM -> "windletter.pem";
            case WIND_BASE_1024F_V1 -> "windletter.txt";
            case BINARY -> "windletter.wlb";
        };
    }

    private static FileChooser.ExtensionFilter extension(
        SendOutputFormat format
    ) {
        return switch (format) {
            case BASE64_PEM -> new FileChooser.ExtensionFilter(
                "WindLetter Base64 PEM (*.pem)",
                "*.pem"
            );
            case WIND_BASE_1024F_V1 -> new FileChooser.ExtensionFilter(
                "風笺文本 (*.txt)",
                "*.txt"
            );
            case BINARY -> new FileChooser.ExtensionFilter(
                "WindLetter 二进制消息 (*.wlb)",
                "*.wlb"
            );
        };
    }

    private static String userMessage(Throwable failure) {
        if (failure instanceof DesktopVaultException vaultFailure) {
            return vaultFailure.getMessage();
        }
        return "无法生成消息。请检查 payload 文件和发送设置。";
    }

    private static VBox card(Node... children) {
        VBox card = new VBox(12, children);
        card.getStyleClass().add("content-card");
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private static Label fieldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        return label;
    }

    private static Label sectionHeading(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-heading");
        return label;
    }

    private static Label boundaryNote(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("boundary-note");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static Button primaryButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("primary-button");
        return button;
    }

    private static Button secondaryButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("secondary-button");
        return button;
    }

    @FunctionalInterface
    interface StatusSink {
        void show(String message, boolean error);
    }

    @FunctionalInterface
    interface SendRunner {
        void run(
            Callable<SendResult> operation,
            Consumer<SendResult> success,
            Consumer<Throwable> failure
        );
    }
}

package com.windletter.desktop.ui;

import com.windletter.desktop.receive.ReceiveAuthentication;
import com.windletter.desktop.receive.ReceiveInput;
import com.windletter.desktop.receive.ReceivePayloadFileName;
import com.windletter.desktop.receive.ReceivePayloadPresentation;
import com.windletter.desktop.receive.ReceivePayloadWriter;
import com.windletter.desktop.receive.ReceiveResult;
import com.windletter.desktop.receive.ReceiveStatus;
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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Independent Stage 4 receive workspace over the safe DesktopVault facade. */
final class ReceiveDesktopPane implements AutoCloseable {

    private static final long MAX_BINARY_ARMOR_BYTES =
        ProtocolLimits.MAX_WIRE_UTF8_BYTES + 1024L;
    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    private final Stage stage;
    private final DesktopVault vault;
    private final DesktopVault.Snapshot snapshot;
    private final ReceiveRunner runner;
    private final StatusSink status;
    private final ReceivePayloadWriter writer = new ReceivePayloadWriter();
    private final SensitiveClipboard clipboard =
        SensitiveClipboard.system(Duration.seconds(60));
    private final ObjectProperty<Path> selectedFile =
        new SimpleObjectProperty<>();

    private final ComboBox<DesktopVault.IdentityView> identity =
        new ComboBox<>();
    private final RadioButton textSource = new RadioButton("粘贴文本");
    private final RadioButton binarySource = new RadioButton("导入二进制");
    private final TextArea armorText = new TextArea();
    private final TextField filePath = new TextField();
    private final Label resultTitle = new Label();
    private final Label authentication = detailValue();
    private final Label sender = detailValue();
    private final Label message = detailValue();
    private final Label payloadInfo = detailValue();
    private final TextArea payloadPreview = new TextArea();
    private final Button copyPayload = secondaryButton("复制恢复文本");
    private final Button savePayload = secondaryButton("保存原始 payload");

    private ReceiveResult latestResult;
    private String latestTextPreview;
    private boolean closed;

    ReceiveDesktopPane(
        Stage stage,
        DesktopVault vault,
        DesktopVault.Snapshot snapshot,
        ReceiveRunner runner,
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
            inputCard(),
            resultCard()
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

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        clearResult("接收页已关闭。");
        clipboard.close();
        armorText.clear();
        selectedFile.set(null);
    }

    private Node heading() {
        Label title = sectionHeading("接收并解密 WindLetter");
        Label description = new Label(
            "粘贴完整文本 Armor 或导入二进制消息，选择本地身份后由真实核心执行解析、路由、解密、binding 和验签。"
        );
        description.getStyleClass().add("introduction");
        description.setWrapText(true);
        return new VBox(6, title, description);
    }

    private Node inputCard() {
        identity.getItems().setAll(snapshot.identities());
        identity.setCellFactory(list -> identityCell());
        identity.setButtonCell(identityCell());
        identity.setMaxWidth(Double.MAX_VALUE);
        snapshot.identities().stream()
            .filter(DesktopVault.IdentityView::defaultIdentity)
            .findFirst()
            .or(() -> snapshot.identities().stream().findFirst())
            .ifPresent(identity::setValue);

        ToggleGroup sourceGroup = new ToggleGroup();
        textSource.setToggleGroup(sourceGroup);
        binarySource.setToggleGroup(sourceGroup);
        textSource.setSelected(true);
        textSource.setId("receive-text-source");
        binarySource.setId("receive-binary-source");

        armorText.setId("receive-armor-text");
        armorText.setPromptText(
            "-----BEGIN WIND LETTER-----\n"
                + "或\n"
                + "-----風笺 起-----"
        );
        armorText.setPrefRowCount(11);
        armorText.setWrapText(true);

        filePath.setEditable(false);
        filePath.setPromptText("尚未选择二进制消息");
        filePath.textProperty().bind(Bindings.createStringBinding(
            () -> selectedFile.get() == null
                ? ""
                : selectedFile.get().toString(),
            selectedFile
        ));
        Button choose = secondaryButton("选择 binary 消息");
        choose.setOnAction(event -> chooseBinaryFile());
        HBox fileRow = new HBox(10, filePath, choose);
        HBox.setHgrow(filePath, Priority.ALWAYS);

        armorText.disableProperty().bind(binarySource.selectedProperty());
        filePath.disableProperty().bind(textSource.selectedProperty());
        choose.disableProperty().bind(textSource.selectedProperty());

        Button process = primaryButton("安全解析并解密");
        process.setId("receive-process-button");
        BooleanBinding invalid = Bindings.createBooleanBinding(
            this::isInputInvalid,
            identity.valueProperty(),
            textSource.selectedProperty(),
            armorText.textProperty(),
            selectedFile
        );
        process.disableProperty().bind(invalid);
        process.setOnAction(event -> process());

        GridPane fields = new GridPane();
        fields.setHgap(16);
        fields.setVgap(12);
        fields.add(fieldLabel("本地收件身份"), 0, 0);
        fields.add(identity, 1, 0);
        GridPane.setHgrow(identity, Priority.ALWAYS);

        return card(
            fieldLabel("1. 消息输入"),
            fields,
            new HBox(18, textSource, binarySource),
            armorText,
            fileRow,
            process,
            boundaryNote(
                "文本必须保留精确 Header 和 Footer，应用不会修剪或猜测格式。binary 文件最大约 20 MiB。"
            )
        );
    }

    private Node resultCard() {
        resultTitle.setId("receive-result-title");
        resultTitle.getStyleClass().add("result-title");
        resultTitle.setText("尚未处理消息");

        authentication.setId("receive-authentication");
        sender.setId("receive-sender");
        payloadPreview.setId("receive-payload-preview");
        payloadPreview.setEditable(false);
        payloadPreview.setWrapText(true);
        payloadPreview.setPrefRowCount(12);
        payloadPreview.setPromptText(
            "只有成功解密且 MIME 为文本、内容是严格 UTF-8 时才会预览。"
        );
        copyPayload.setDisable(true);
        savePayload.setDisable(true);
        copyPayload.setOnAction(event -> copyTextPayload());
        savePayload.setOnAction(event -> saveRecoveredPayload());

        FlowPane actions = new FlowPane(
            10,
            10,
            copyPayload,
            savePayload
        );
        return card(
            fieldLabel("2. 安全结果"),
            resultTitle,
            detailsGrid(
                "认证状态", authentication,
                "发送者", sender,
                "消息", message,
                "Payload", payloadInfo
            ),
            payloadPreview,
            actions,
            boundaryNote(
                "“签名有效”只证明消息由对应联系人公钥持有者签发；是否核对过该公钥指纹会单独显示。“未签名”绝不代表发送者可信。"
            )
        );
    }

    private void process() {
        if (closed) {
            return;
        }
        clearResult("正在安全处理新消息…");
        UUID identityId = identity.getValue().identityId();
        Path binaryFile = binarySource.isSelected()
            ? selectedFile.get()
            : null;
        String text = textSource.isSelected() ? armorText.getText() : null;
        runner.run(
            () -> {
                ReceiveResult result = vault.receive(loadInput(
                    identityId,
                    binaryFile,
                    text
                ));
                if (Thread.currentThread().isInterrupted()) {
                    result.close();
                    throw new CancellationException(
                        "receive operation was cancelled"
                    );
                }
                return result;
            },
            this::showResult,
            this::showLocalFailure
        );
    }

    static ReceiveInput loadInput(
        UUID identityId,
        Path binaryFile,
        String text
    ) throws IOException {
        if (binaryFile == null) {
            return ReceiveInput.text(identityId, text);
        }
        long size = Files.size(binaryFile);
        if (size <= 0 || size > MAX_BINARY_ARMOR_BYTES) {
            throw new IOException("binary Armor has an invalid size");
        }
        byte[] bytes = Files.readAllBytes(binaryFile);
        try {
            if (bytes.length > MAX_BINARY_ARMOR_BYTES) {
                throw new IOException("binary Armor exceeds the limit");
            }
            return ReceiveInput.binary(identityId, bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private void showResult(ReceiveResult result) {
        Objects.requireNonNull(result, "result");
        if (closed) {
            result.close();
            return;
        }
        if (result.status() != ReceiveStatus.SUCCESS) {
            result.close();
            latestResult = null;
            latestTextPreview = null;
            payloadPreview.clear();
            copyPayload.setDisable(true);
            savePayload.setDisable(true);
            authentication.setText("不可用");
            sender.setText("未展示");
            message.setText("未展示");
            payloadInfo.setText("未展示");
            if (result.status() == ReceiveStatus.NOT_FOR_ME) {
                resultTitle.setText("不是发给当前身份的消息");
                status.show(
                    "当前身份没有匹配的收件密钥。请确认选择了正确身份。",
                    true
                );
            } else {
                resultTitle.setText("消息无法安全处理");
                status.show(
                    "消息无效、已损坏、被篡改、发送者未知或不受当前版本支持。",
                    true
                );
            }
            return;
        }

        closeLatestResult();
        latestResult = result;
        resultTitle.setText("解密成功");
        message.setText(
            "ID " + result.messageId()
                + " · "
                + TIME_FORMAT.format(
                    Instant.ofEpochSecond(result.timestamp())
                        .atZone(ZoneId.systemDefault())
                )
        );
        payloadInfo.setText(
            result.contentType()
                + " · "
                + result.originalSize()
                + " 字节"
        );
        if (result.authentication()
            == ReceiveAuthentication.SIGNED_VALID) {
            authentication.setText(
                result.senderFingerprintVerified()
                    ? "签名有效 · 联系人公钥指纹已核对"
                    : "签名有效 · 联系人公钥指纹尚未核对"
            );
            sender.setText(
                result.senderDisplayName()
                    + " · signing KID "
                    + result.senderSigningKid()
            );
        } else {
            authentication.setText(
                "未签名 · 无法确认发送者身份"
            );
            sender.setText("未知；协议未提供可认证发送者");
        }

        byte[] payload = result.payload();
        try {
            latestTextPreview = ReceivePayloadPresentation.textPreview(
                result.contentType(),
                payload
            ).orElse(null);
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
        payloadPreview.setText(
            latestTextPreview == null ? "" : latestTextPreview
        );
        copyPayload.setDisable(latestTextPreview == null);
        savePayload.setDisable(false);
        status.show("消息已安全解密。", false);
    }

    private void showLocalFailure(Throwable failure) {
        clearResult("接收操作未完成。");
        resultTitle.setText("接收操作未完成");
        status.show(userMessage(failure), true);
    }

    private void clearResult(String title) {
        clipboard.clearIfOwned();
        closeLatestResult();
        latestTextPreview = null;
        resultTitle.setText(title);
        authentication.setText("");
        sender.setText("");
        message.setText("");
        payloadInfo.setText("");
        payloadPreview.clear();
        copyPayload.setDisable(true);
        savePayload.setDisable(true);
    }

    private void closeLatestResult() {
        ReceiveResult result = latestResult;
        latestResult = null;
        if (result != null) {
            result.close();
        }
    }

    private void copyTextPayload() {
        if (latestTextPreview == null) {
            return;
        }
        if (clipboard.copy(latestTextPreview)) {
            status.show(
                "恢复后的明文已复制；若未被替换，将在 60 秒后自动清除。",
                false
            );
        } else {
            status.show("无法写入系统剪贴板。", true);
        }
    }

    private void saveRecoveredPayload() {
        ReceiveResult result = latestResult;
        if (result == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("保存恢复后的原始 payload");
        String extension = ReceivePayloadFileName.suggestedExtension(
            result.contentType()
        );
        chooser.setInitialFileName(
            ReceivePayloadFileName.suggestedName(result.contentType())
        );
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter(
                "根据消息类型推断 (*." + extension + ")",
                "*." + extension
            ),
            new FileChooser.ExtensionFilter("所有文件", "*.*")
        );
        java.io.File selected = chooser.showSaveDialog(stage);
        if (selected == null) {
            return;
        }
        byte[] payload = result.payload();
        Path target = selected.toPath();
        runner.run(
            () -> {
                try {
                    writer.write(target, payload);
                } finally {
                    Arrays.fill(payload, (byte) 0);
                }
                return result;
            },
            ignored -> status.show(
                "恢复后的原始 payload 已保存。",
                false
            ),
            failure -> {
                Arrays.fill(payload, (byte) 0);
                status.show(
                    "无法保存 payload。请检查目标位置后重试。",
                    true
                );
            }
        );
    }

    private void chooseBinaryFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 WindLetter 二进制消息");
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter(
                "WindLetter 二进制消息 (*.wlb)",
                "*.wlb"
            ),
            new FileChooser.ExtensionFilter("所有文件", "*.*")
        );
        java.io.File selected = chooser.showOpenDialog(stage);
        if (selected != null) {
            selectedFile.set(selected.toPath().toAbsolutePath().normalize());
        }
    }

    private boolean isInputInvalid() {
        if (identity.getValue() == null) {
            return true;
        }
        if (textSource.isSelected()) {
            return armorText.getText() == null
                || armorText.getText().isBlank();
        }
        return selectedFile.get() == null;
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

    private static String userMessage(Throwable failure) {
        if (failure instanceof DesktopVaultException vaultFailure) {
            return vaultFailure.getMessage();
        }
        return "无法处理该消息。请检查输入文件和所选身份。";
    }

    private static VBox card(Node... children) {
        VBox card = new VBox(12, children);
        card.getStyleClass().add("content-card");
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private static GridPane detailsGrid(Object... labelValuePairs) {
        GridPane grid = new GridPane();
        grid.setHgap(18);
        grid.setVgap(14);
        for (int index = 0; index < labelValuePairs.length; index += 2) {
            Label label = new Label((String) labelValuePairs[index]);
            label.getStyleClass().add("detail-label");
            Node value = (Node) labelValuePairs[index + 1];
            grid.add(label, 0, index / 2);
            grid.add(value, 1, index / 2);
            GridPane.setHgrow(value, Priority.ALWAYS);
        }
        return grid;
    }

    private static Label detailValue() {
        Label label = new Label();
        label.getStyleClass().add("detail-value");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
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
    interface ReceiveRunner {
        void run(
            Callable<ReceiveResult> operation,
            Consumer<ReceiveResult> success,
            Consumer<Throwable> failure
        );
    }
}

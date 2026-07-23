package com.windletter.desktop.ui;

import com.windletter.desktop.WindLetterApplication;
import com.windletter.desktop.selftest.RoundTripSelfTestService;
import com.windletter.desktop.vault.DesktopVault;
import com.windletter.desktop.vault.DesktopVaultException;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Stage 2 Chinese JavaFX workflow over the safe {@link DesktopVault} boundary. */
public final class VaultDesktopView implements AutoCloseable {

    private static final String SELF_TEST_PAYLOAD =
        "風笺桌面端真实收发 · 𠮷 · 🌬️";
    private static final long MAX_PUBLIC_IDENTITY_BYTES = 256L * 1024L;

    private final Stage stage;
    private final DesktopVault vault;
    private final RoundTripSelfTestService selfTestService;
    private final Timeline lockMonitor;

    private Label statusLabel;
    private ProgressIndicator busyIndicator;
    private Task<?> currentTask;
    private char[] pendingPassword;
    private boolean workspaceVisible;
    private DesktopVault.Snapshot snapshot;

    private ListView<DesktopVault.IdentityView> identityList;
    private Label identityName;
    private Label identityOrigin;
    private Label identityNote;
    private Label identityFingerprint;
    private Label identityDefault;

    private ListView<DesktopVault.ContactView> contactList;
    private Label contactName;
    private Label contactClaimedName;
    private Label contactVerification;
    private Label contactNote;
    private Label contactFingerprint;

    public VaultDesktopView(Stage stage, DesktopVault vault) {
        this(stage, vault, new RoundTripSelfTestService());
    }

    VaultDesktopView(
        Stage stage,
        DesktopVault vault,
        RoundTripSelfTestService selfTestService
    ) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.vault = Objects.requireNonNull(vault, "vault");
        this.selfTestService = Objects.requireNonNull(
            selfTestService,
            "selfTestService"
        );
        this.lockMonitor = new Timeline(new KeyFrame(
            Duration.seconds(1),
            event -> checkAutoLock()
        ));
        this.lockMonitor.setCycleCount(Timeline.INDEFINITE);
    }

    public void show() {
        stage.setTitle(WindLetterApplication.WINDOW_TITLE);
        stage.setMinWidth(820);
        stage.setMinHeight(650);
        if (vault.isUnlocked()) {
            showWorkspace("保险库已解锁。");
        } else {
            showAuthentication(null, false);
        }
        stage.show();
    }

    @Override
    public void close() {
        lockMonitor.stop();
        Task<?> task = currentTask;
        if (task != null) {
            task.cancel(true);
        }
        clearPendingPassword();
        vault.close();
    }

    private void showAuthentication(String message, boolean error) {
        workspaceVisible = false;
        lockMonitor.stop();
        snapshot = null;
        identityList = null;
        contactList = null;

        VBox card = new VBox(18);
        card.getStyleClass().add("auth-card");
        card.setMaxWidth(540);

        Label eyebrow = new Label("WINDLETTER DESKTOP · 安全保险库");
        eyebrow.getStyleClass().add("eyebrow");
        Label heading = new Label(
            vault.exists() ? "解锁你的風笺" : "创建你的風笺保险库"
        );
        heading.getStyleClass().add("heading");
        heading.setWrapText(true);
        Label introduction = new Label(
            vault.exists()
                ? "私钥保持加密保存。输入保险库密码后，才会在本次会话中解锁。"
                : "保险库会使用 Argon2id 与 AES-256-GCM 加密。密码遗失后无法恢复，请妥善备份。"
        );
        introduction.getStyleClass().add("introduction");
        introduction.setWrapText(true);
        introduction.setMaxWidth(Double.MAX_VALUE);
        introduction.setMinHeight(Region.USE_PREF_SIZE);

        card.getChildren().addAll(eyebrow, heading, introduction);
        if (vault.exists()) {
            buildUnlockForm(card);
        } else {
            buildCreateForm(card);
        }

        Label path = new Label("保存位置：" + vault.vaultPath());
        path.getStyleClass().add("path-note");
        path.setWrapText(true);
        path.setMaxWidth(Double.MAX_VALUE);
        path.setMinHeight(Region.USE_PREF_SIZE);
        card.getChildren().add(path);

        BorderPane page = page(centered(card));
        showScene(page, 900, 700);
        if (message != null) {
            showStatus(message, error);
        }
    }

    private void buildUnlockForm(VBox card) {
        RevealablePasswordField password = passwordField("保险库密码");
        Button unlock = primaryButton("解锁保险库");
        unlock.setDefaultButton(true);
        unlock.setOnAction(event -> {
            char[] passwordChars = takePassword(password);
            runPasswordOperation(
                passwordChars,
                value -> {
                    vault.unlock(value);
                    return null;
                },
                ignored -> showWorkspace("解锁成功。")
            );
        });
        password.setOnAction(event -> unlock.fire());

        Button restore = secondaryButton("从完整备份恢复");
        restore.setOnAction(event -> {
            Path backup = chooseOpenVaultFile("选择完整保险库备份");
            if (backup == null) {
                return;
            }
            char[] passwordChars = takePassword(password);
            runPasswordOperation(
                passwordChars,
                value -> {
                    vault.restore(backup, value);
                    return null;
                },
                ignored -> showWorkspace("备份已验证并恢复。")
            );
        });

        FlowPane actions = actions(unlock, restore);
        card.getChildren().addAll(
            fieldLabel("密码"),
            password,
            actions,
            boundaryNote("连续无操作达到设定时间后，应用会自动锁定并清除当前解锁材料。")
        );
    }

    private void buildCreateForm(VBox card) {
        RevealablePasswordField password = passwordField("8–256 个字符");
        RevealablePasswordField confirmation = passwordField("再次输入密码");
        ComboBox<Integer> autoLock = new ComboBox<>(
            FXCollections.observableArrayList(5, 15, 30, 60)
        );
        autoLock.setValue(15);
        autoLock.setMaxWidth(Double.MAX_VALUE);
        Label minutes = new Label("分钟");
        HBox autoLockRow = new HBox(10, autoLock, minutes);
        autoLockRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(autoLock, Priority.ALWAYS);

        Button create = primaryButton("创建并解锁");
        create.setDefaultButton(true);
        create.setOnAction(event -> {
            char[] first = takePassword(password);
            char[] second = takePassword(confirmation);
            int autoLockMinutes = autoLock.getValue();
            if (!Arrays.equals(first, second)) {
                clear(first);
                clear(second);
                showStatus("两次输入的密码不一致。", true);
                return;
            }
            clear(second);
            runPasswordOperation(
                first,
                value -> {
                    vault.create(value, autoLockMinutes);
                    return null;
                },
                ignored -> showWorkspace("保险库已创建。现在可以生成第一个身份。")
            );
        });
        confirmation.setOnAction(event -> create.fire());

        card.getChildren().addAll(
            fieldLabel("新密码"),
            password,
            fieldLabel("确认密码"),
            confirmation,
            fieldLabel("无操作自动锁定"),
            autoLockRow,
            create,
            boundaryNote(
                "密码须包含 8–256 个 Unicode 字符。应用不保存密码；忘记密码后，只能使用仍记得密码的完整备份恢复。"
            )
        );
    }

    private void showWorkspace(String message) {
        try {
            snapshot = vault.snapshot();
        } catch (DesktopVaultException failure) {
            showAuthentication(failure.getMessage(), true);
            return;
        }

        workspaceVisible = true;
        BorderPane workspace = page(null);
        workspace.setTop(workspaceHeader());

        TabPane tabs = new TabPane(
            tab("我的身份", identityPane()),
            tab("联系人", contactPane()),
            tab("备份与恢复", backupPane()),
            tab("协议自检", selfTestPane())
        );
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        workspace.setCenter(tabs);
        BorderPane.setMargin(tabs, new Insets(0, 26, 20, 26));

        showScene(workspace, 1080, 760);
        lockMonitor.playFromStart();
        showStatus(message, false);
    }

    private Node workspaceHeader() {
        Label product = new Label(WindLetterApplication.WINDOW_TITLE);
        product.getStyleClass().add("product-title");
        Label state = new Label(
            "保险库已解锁 · 无操作 "
                + snapshot.autoLockMinutes()
                + " 分钟后自动锁定"
        );
        state.getStyleClass().add("workspace-state");

        VBox titles = new VBox(3, product, state);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button lock = secondaryButton("立即锁定");
        lock.setOnAction(event -> {
            vault.lock();
            showAuthentication("保险库已锁定。", false);
        });

        HBox header = new HBox(18, titles, spacer, lock);
        header.getStyleClass().add("workspace-header");
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    private Node identityPane() {
        identityList = new ListView<>(
            FXCollections.observableArrayList(snapshot.identities())
        );
        identityList.setPlaceholder(new Label("还没有身份"));
        identityList.setCellFactory(ignored -> new ListCell<>() {
            @Override
            protected void updateItem(
                DesktopVault.IdentityView item,
                boolean empty
            ) {
                super.updateItem(item, empty);
                setText(empty || item == null
                    ? null
                    : DesktopVaultPresenter.identityListLabel(item));
            }
        });

        identityName = detailValue();
        identityOrigin = detailValue();
        identityNote = detailValue();
        identityFingerprint = fingerprintValue();
        identityDefault = detailValue();

        GridPane details = detailsGrid(
            "显示名称", identityName,
            "来源", identityOrigin,
            "发送身份", identityDefault,
            "本地备注", identityNote,
            "三组 KID 指纹", identityFingerprint
        );

        Button create = primaryButton("生成新身份");
        create.setOnAction(event -> createIdentity());
        Button edit = secondaryButton("编辑名称与备注");
        edit.setOnAction(event -> editIdentity());
        Button select = secondaryButton("设为发送身份");
        select.setOnAction(event -> selectIdentity());
        Button export = secondaryButton("导出公开身份");
        export.setOnAction(event -> exportIdentity());
        Button importBackup = secondaryButton("从加密备份导入");
        importBackup.setOnAction(event -> inspectIdentityBackup());
        Button delete = dangerButton("删除身份");
        delete.setOnAction(event -> deleteIdentity());

        FlowPane actionBar = actions(
            create,
            edit,
            select,
            export,
            importBackup,
            delete
        );
        VBox right = new VBox(
            18,
            sectionHeading("身份详情"),
            details,
            actionBar,
            boundaryNote(
                "显示名称会随公开身份文件分享给别人；备注只保存在本地加密保险库中。删除身份前请确认已有可解锁的完整备份。"
            )
        );
        right.setPadding(new Insets(22));

        identityList.getSelectionModel()
            .selectedItemProperty()
            .addListener((observable, oldValue, selected) ->
                showIdentityDetails(selected));
        if (!snapshot.identities().isEmpty()) {
            identityList.getSelectionModel().selectFirst();
        } else {
            showIdentityDetails(null);
        }
        return split(identityList, right);
    }

    private void showIdentityDetails(DesktopVault.IdentityView identity) {
        boolean empty = identity == null;
        identityName.setText(empty ? "请选择身份" : identity.displayName());
        identityOrigin.setText(
            empty ? "—" : DesktopVaultPresenter.originLabel(identity)
        );
        identityDefault.setText(
            empty ? "—" : identity.defaultIdentity() ? "当前默认" : "非默认"
        );
        identityNote.setText(
            empty ? "—" : DesktopVaultPresenter.noteLabel(identity.note())
        );
        identityFingerprint.setText(empty ? "—" : identity.fingerprint());
    }

    private void createIdentity() {
        Optional<MetadataInput> input = metadataDialog(
            "生成新身份",
            "对外显示名称",
            "",
            "",
            false
        );
        input.ifPresent(value -> runBusy(
            () -> vault.createIdentity(value.displayName(), value.note()),
            identityId -> {
                refreshWorkspace("新身份已生成并加密保存。");
                selectIdentityInList(identityId);
            }
        ));
    }

    private void editIdentity() {
        DesktopVault.IdentityView selected = selectedIdentity();
        if (selected == null) {
            showStatus("请先选择身份。", true);
            return;
        }
        Optional<MetadataInput> input = metadataDialog(
            "编辑身份",
            "对外显示名称",
            selected.displayName(),
            selected.note(),
            false
        );
        input.ifPresent(value -> runBusy(
            () -> {
                vault.updateIdentity(
                    selected.identityId(),
                    value.displayName(),
                    value.note()
                );
                return null;
            },
            ignored -> {
                refreshWorkspace("身份名称与备注已保存。");
                selectIdentityInList(selected.identityId());
            }
        ));
    }

    private void selectIdentity() {
        DesktopVault.IdentityView selected = selectedIdentity();
        if (selected == null) {
            showStatus("请先选择身份。", true);
            return;
        }
        runBusy(
            () -> {
                vault.selectDefaultIdentity(selected.identityId());
                return null;
            },
            ignored -> {
                refreshWorkspace("默认发送身份已更新。");
                selectIdentityInList(selected.identityId());
            }
        );
    }

    private void exportIdentity() {
        DesktopVault.IdentityView selected = selectedIdentity();
        if (selected == null) {
            showStatus("请先选择身份。", true);
            return;
        }
        Path target = chooseSavePublicIdentity(selected.displayName());
        if (target == null) {
            return;
        }
        runBusy(
            () -> {
                String encoded = vault.exportPublicIdentity(selected.identityId());
                Files.writeString(
                    target,
                    encoded,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
                );
                return null;
            },
            ignored -> showStatus("公开身份已导出；文件中不含私钥和本地备注。", false)
        );
    }

    private void inspectIdentityBackup() {
        Path backup = chooseOpenVaultFile("选择包含身份的完整备份");
        if (backup == null) {
            return;
        }
        char[] password = promptPassword(
            "查看备份身份",
            "输入该备份的保险库密码"
        );
        if (password == null) {
            return;
        }
        runPasswordOperation(
            password,
            value -> vault.inspectBackup(backup, value),
            identities -> chooseBackupIdentity(backup, identities)
        );
    }

    private void chooseBackupIdentity(
        Path backup,
        List<DesktopVault.IdentityView> identities
    ) {
        if (identities.isEmpty()) {
            showStatus("该备份中没有可导入的身份。", true);
            return;
        }
        List<IdentityChoice> choices = identities.stream()
            .map(IdentityChoice::new)
            .toList();
        ChoiceDialog<IdentityChoice> dialog = new ChoiceDialog<>(
            choices.get(0),
            choices
        );
        dialog.initOwner(stage);
        dialog.setTitle("选择要导入的身份");
        dialog.setHeaderText("只会导入选中的身份，不会导入联系人或设置。");
        Optional<IdentityChoice> selected = dialog.showAndWait();
        if (selected.isEmpty()) {
            return;
        }

        char[] password = promptPassword(
            "确认导入",
            "再次输入备份密码以导入私钥身份"
        );
        if (password == null) {
            return;
        }
        UUID sourceId = selected.get().identity().identityId();
        runPasswordOperation(
            password,
            value -> vault.importIdentityFromBackup(
                backup,
                value,
                sourceId
            ),
            importedId -> {
                refreshWorkspace("身份已从加密备份导入。");
                selectIdentityInList(importedId);
            }
        );
    }

    private void deleteIdentity() {
        DesktopVault.IdentityView selected = selectedIdentity();
        if (selected == null) {
            showStatus("请先选择身份。", true);
            return;
        }
        if (!confirm(
            "删除身份",
            "删除“" + selected.displayName() + "”及其三套私钥？",
            "如果没有可解锁的完整备份，此操作无法恢复。"
        )) {
            return;
        }
        runBusy(
            () -> {
                vault.deleteIdentity(selected.identityId());
                return null;
            },
            ignored -> refreshWorkspace("身份已删除。")
        );
    }

    private Node contactPane() {
        contactList = new ListView<>(
            FXCollections.observableArrayList(snapshot.contacts())
        );
        contactList.setPlaceholder(new Label("还没有联系人"));
        contactList.setCellFactory(ignored -> new ListCell<>() {
            @Override
            protected void updateItem(
                DesktopVault.ContactView item,
                boolean empty
            ) {
                super.updateItem(item, empty);
                setText(empty || item == null
                    ? null
                    : DesktopVaultPresenter.contactListLabel(item));
            }
        });

        contactName = detailValue();
        contactClaimedName = detailValue();
        contactVerification = detailValue();
        contactNote = detailValue();
        contactFingerprint = fingerprintValue();
        GridPane details = detailsGrid(
            "显示名称", contactName,
            "对方声明名称", contactClaimedName,
            "核验状态", contactVerification,
            "本地备注", contactNote,
            "三组 KID 指纹", contactFingerprint
        );

        Button paste = primaryButton("粘贴公开身份");
        paste.setOnAction(event -> pasteContact());
        Button importFile = secondaryButton("导入公开身份文件");
        importFile.setOnAction(event -> importContactFile());
        Button edit = secondaryButton("编辑本地信息");
        edit.setOnAction(event -> editContact());
        Button verification = secondaryButton("切换指纹核验状态");
        verification.setOnAction(event -> toggleContactVerification());
        Button delete = dangerButton("删除联系人");
        delete.setOnAction(event -> deleteContact());

        VBox right = new VBox(
            18,
            sectionHeading("联系人详情"),
            details,
            actions(paste, importFile, edit, verification, delete),
            boundaryNote(
                "“已核对”只表示你通过可信渠道比对了这里的三组完整 KID；它不代表实名，也不能替代接收消息时的签名验证。"
            )
        );
        right.setPadding(new Insets(22));
        contactList.getSelectionModel()
            .selectedItemProperty()
            .addListener((observable, oldValue, selected) ->
                showContactDetails(selected));
        if (!snapshot.contacts().isEmpty()) {
            contactList.getSelectionModel().selectFirst();
        } else {
            showContactDetails(null);
        }
        return split(contactList, right);
    }

    private void showContactDetails(DesktopVault.ContactView contact) {
        boolean empty = contact == null;
        contactName.setText(empty ? "请选择联系人" : contact.displayName());
        contactClaimedName.setText(
            empty ? "—" : contact.claimedDisplayName()
        );
        contactVerification.setText(
            empty ? "—" : DesktopVaultPresenter.verificationLabel(contact)
        );
        contactNote.setText(
            empty ? "—" : DesktopVaultPresenter.noteLabel(contact.note())
        );
        contactFingerprint.setText(empty ? "—" : contact.fingerprint());
    }

    private void pasteContact() {
        Optional<String> encoded = publicIdentityDialog();
        encoded.ifPresent(value -> importContact(value, "联系人已导入，当前为未核对状态。"));
    }

    private void importContactFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导入公开身份文件");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("風笺公开身份 (*.json)", "*.json")
        );
        java.io.File selected = chooser.showOpenDialog(stage);
        if (selected == null) {
            return;
        }
        Path source = selected.toPath();
        runBusy(
            () -> {
                if (Files.size(source) > MAX_PUBLIC_IDENTITY_BYTES) {
                    throw new IOException("public identity is too large");
                }
                return Files.readString(source, StandardCharsets.UTF_8);
            },
            encoded -> importContact(
                encoded,
                "联系人已从文件导入，当前为未核对状态。"
            )
        );
    }

    private void importContact(String encoded, String successMessage) {
        runBusy(
            () -> vault.importContact(encoded),
            contactId -> {
                refreshWorkspace(successMessage);
                selectContactInList(contactId);
            }
        );
    }

    private void editContact() {
        DesktopVault.ContactView selected = selectedContact();
        if (selected == null) {
            showStatus("请先选择联系人。", true);
            return;
        }
        Optional<MetadataInput> input = metadataDialog(
            "编辑联系人本地信息",
            "本地显示名称（可留空）",
            selected.localDisplayName(),
            selected.note(),
            true
        );
        input.ifPresent(value -> runBusy(
            () -> {
                vault.updateContact(
                    selected.contactId(),
                    value.displayName(),
                    value.note(),
                    selected.verification()
                        == DesktopVault.ContactVerification.FINGERPRINT_VERIFIED
                );
                return null;
            },
            ignored -> {
                refreshWorkspace("联系人本地信息已保存。");
                selectContactInList(selected.contactId());
            }
        ));
    }

    private void toggleContactVerification() {
        DesktopVault.ContactView selected = selectedContact();
        if (selected == null) {
            showStatus("请先选择联系人。", true);
            return;
        }
        boolean markVerified = selected.verification()
            == DesktopVault.ContactVerification.UNVERIFIED;
        String action = markVerified ? "标记为已核对" : "取消已核对标记";
        if (markVerified && !confirm(
            action,
            "你是否已经通过可信渠道逐一比对三组完整 KID？",
            selected.fingerprint()
        )) {
            return;
        }
        runBusy(
            () -> {
                vault.updateContact(
                    selected.contactId(),
                    selected.localDisplayName(),
                    selected.note(),
                    markVerified
                );
                return null;
            },
            ignored -> {
                refreshWorkspace(markVerified
                    ? "已记录指纹核对状态。"
                    : "已取消指纹核对状态。");
                selectContactInList(selected.contactId());
            }
        );
    }

    private void deleteContact() {
        DesktopVault.ContactView selected = selectedContact();
        if (selected == null) {
            showStatus("请先选择联系人。", true);
            return;
        }
        if (!confirm(
            "删除联系人",
            "删除“" + selected.displayName() + "”？",
            "只会删除本地联系人记录，不影响对方的身份。"
        )) {
            return;
        }
        runBusy(
            () -> {
                vault.deleteContact(selected.contactId());
                return null;
            },
            ignored -> refreshWorkspace("联系人已删除。")
        );
    }

    private Node backupPane() {
        Label heading = sectionHeading("完整加密备份");
        Label explanation = new Label(
            "备份包含全部身份私钥、联系人和设置，但文件本身仍受当前保险库密码保护。"
                + "恢复时会先完成密码、认证加密、严格结构和密钥一致性校验，再替换当前保险库。"
        );
        explanation.getStyleClass().add("introduction");
        explanation.setWrapText(true);

        Button backup = primaryButton("创建完整备份");
        backup.setOnAction(event -> backupVault());
        Button restore = dangerButton("锁定并恢复备份");
        restore.setOnAction(event -> restoreVaultFromWorkspace());

        Label path = new Label("当前保险库：" + vault.vaultPath());
        path.getStyleClass().add("path-note");
        path.setWrapText(true);

        VBox card = new VBox(
            18,
            heading,
            explanation,
            actions(backup, restore),
            boundaryNote(
                "V1 不提供独立明文或第二种私钥导出格式。迁移单个身份时，从完整加密备份中选择导入。"
            ),
            path
        );
        card.getStyleClass().add("content-card");
        card.setMaxWidth(760);
        return centered(card);
    }

    private void backupVault() {
        FileChooser chooser = vaultFileChooser("保存完整保险库备份");
        chooser.setInitialFileName("WindLetter-backup.wlv");
        java.io.File selected = chooser.showSaveDialog(stage);
        if (selected == null) {
            return;
        }
        runBusy(
            () -> {
                vault.backup(selected.toPath());
                return null;
            },
            ignored -> showStatus("完整加密备份已保存。", false)
        );
    }

    private void restoreVaultFromWorkspace() {
        Path backup = chooseOpenVaultFile("选择要恢复的完整备份");
        if (backup == null) {
            return;
        }
        if (!confirm(
            "恢复完整备份",
            "这会替换当前保险库中的全部身份、联系人和设置。",
            "备份会先完整验证；验证失败时不会替换当前文件。当前会话将立即锁定。"
        )) {
            return;
        }
        char[] password = promptPassword(
            "恢复完整备份",
            "输入该备份的保险库密码"
        );
        if (password == null) {
            return;
        }
        vault.lock();
        showAuthentication("正在验证备份…", false);
        runPasswordOperation(
            password,
            value -> {
                vault.restore(backup, value);
                return null;
            },
            ignored -> showWorkspace("备份已验证并恢复。"),
            failure -> showAuthentication(userMessage(failure), true)
        );
    }

    private Node selfTestPane() {
        Label title = sectionHeading("真实协议收发自检");
        Label summary = new Label();
        Label authentication = detailValue();
        Label transport = detailValue();
        Label payload = detailValue();
        Label negativeChecks = detailValue();
        Label timing = detailValue();
        GridPane details = detailsGrid(
            "结果", summary,
            "发送者认证", authentication,
            "传输格式", transport,
            "内容恢复", payload,
            "安全失败检查", negativeChecks,
            "本次耗时", timing
        );

        Runnable idle = () -> {
            SelfTestViewState state = SelfTestPresenter.idle();
            summary.setText(state.summary());
            authentication.setText(state.authentication());
            transport.setText(state.transport());
            payload.setText(state.payload());
            negativeChecks.setText(state.negativeChecks());
            timing.setText(state.timing());
        };
        idle.run();

        Button run = primaryButton("运行真实收发自检");
        run.setOnAction(event -> {
            SelfTestViewState state = SelfTestPresenter.running();
            summary.setText(state.summary());
            runBusy(
                () -> selfTestService.run(SELF_TEST_PAYLOAD),
                report -> applySelfTest(
                    SelfTestPresenter.completed(report),
                    summary,
                    authentication,
                    transport,
                    payload,
                    negativeChecks,
                    timing
                ),
                failure -> applySelfTest(
                    SelfTestPresenter.failed(),
                    summary,
                    authentication,
                    transport,
                    payload,
                    negativeChecks,
                    timing
                )
            );
        });

        VBox card = new VBox(
            18,
            title,
            new Label("使用短生命周期内存密钥，通过真实 WindLetter 核心库完成已签名消息收发。"),
            details,
            run,
            boundaryNote(
                "自检不会保存测试私钥，也不会展示完整消息。它与本地身份保险库相互独立。"
            )
        );
        card.getStyleClass().add("content-card");
        card.setMaxWidth(800);
        return centered(card);
    }

    private void applySelfTest(
        SelfTestViewState state,
        Label summary,
        Label authentication,
        Label transport,
        Label payload,
        Label negativeChecks,
        Label timing
    ) {
        summary.setText(state.summary());
        authentication.setText(state.authentication());
        transport.setText(state.transport());
        payload.setText(state.payload());
        negativeChecks.setText(state.negativeChecks());
        timing.setText(state.timing());
        showStatus(
            state.successful() ? "真实协议自检通过。" : "真实协议自检未通过。",
            !state.successful()
        );
    }

    private void refreshWorkspace(String message) {
        showWorkspace(message);
    }

    private void checkAutoLock() {
        if (workspaceVisible && !vault.isUnlocked()) {
            showAuthentication("因长时间无操作，保险库已自动锁定。", false);
        }
    }

    private <T> void runBusy(
        Callable<T> operation,
        Consumer<T> success
    ) {
        runBusy(operation, success, failure ->
            showStatus(userMessage(failure), true));
    }

    private <T> void runBusy(
        Callable<T> operation,
        Consumer<T> success,
        Consumer<Throwable> failure
    ) {
        if (currentTask != null) {
            showStatus("已有操作正在进行，请稍候。", true);
            return;
        }
        setBusy(true);
        showStatus("正在安全处理…", false);
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return operation.call();
            }
        };
        currentTask = task;
        task.setOnSucceeded(event -> {
            currentTask = null;
            setBusy(false);
            success.accept(task.getValue());
        });
        task.setOnFailed(event -> {
            currentTask = null;
            setBusy(false);
            failure.accept(task.getException());
        });
        task.setOnCancelled(event -> {
            currentTask = null;
            setBusy(false);
        });

        Thread worker = new Thread(task, "windletter-desktop-operation");
        worker.setDaemon(true);
        worker.start();
    }

    private <T> void runPasswordOperation(
        char[] password,
        PasswordOperation<T> operation,
        Consumer<T> success
    ) {
        runPasswordOperation(
            password,
            operation,
            success,
            failure -> showStatus(userMessage(failure), true)
        );
    }

    private <T> void runPasswordOperation(
        char[] password,
        PasswordOperation<T> operation,
        Consumer<T> success,
        Consumer<Throwable> failure
    ) {
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(operation, "operation");
        if (currentTask != null) {
            clear(password);
            showStatus("已有操作正在进行，请稍候。", true);
            return;
        }
        pendingPassword = password;
        runBusy(
            () -> {
                try {
                    return operation.apply(password);
                } finally {
                    clear(password);
                }
            },
            result -> {
                clearPendingPassword();
                success.accept(result);
            },
            cause -> {
                clearPendingPassword();
                failure.accept(cause);
            }
        );
    }

    private void clearPendingPassword() {
        clear(pendingPassword);
        pendingPassword = null;
    }

    private BorderPane page(Node center) {
        BorderPane page = new BorderPane();
        page.getStyleClass().add("app-root");
        if (center != null) {
            page.setCenter(center);
        }

        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.getStyleClass().add("status-label");
        busyIndicator = new ProgressIndicator();
        busyIndicator.setPrefSize(18, 18);
        busyIndicator.setMaxSize(18, 18);
        busyIndicator.setVisible(false);
        busyIndicator.setManaged(false);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox statusBar = new HBox(12, statusLabel, spacer, busyIndicator);
        statusBar.getStyleClass().add("status-bar");
        statusBar.setAlignment(Pos.CENTER_LEFT);
        page.setBottom(statusBar);
        return page;
    }

    private void showScene(Parent root, double width, double height) {
        Scene scene = new Scene(root, width, height);
        URL stylesheet = Objects.requireNonNull(
            WindLetterApplication.class.getResource("windletter.css"),
            "windletter.css"
        );
        scene.getStylesheets().add(stylesheet.toExternalForm());
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> recordActivity());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> recordActivity());
        stage.setScene(scene);
    }

    private void recordActivity() {
        if (!workspaceVisible
            || currentTask != null
            || !vault.isUnlocked()) {
            return;
        }
        try {
            vault.recordActivity();
        } catch (RuntimeException failure) {
            Platform.runLater(this::checkAutoLock);
        }
    }

    private void setBusy(boolean busy) {
        if (busyIndicator != null) {
            busyIndicator.setVisible(busy);
            busyIndicator.setManaged(busy);
        }
    }

    private void showStatus(String message, boolean error) {
        if (statusLabel == null) {
            return;
        }
        statusLabel.setText(message == null ? "" : message);
        statusLabel.getStyleClass().removeAll("status-error", "status-success");
        statusLabel.getStyleClass().add(
            error ? "status-error" : "status-success"
        );
    }

    private DesktopVault.IdentityView selectedIdentity() {
        return identityList == null
            ? null
            : identityList.getSelectionModel().getSelectedItem();
    }

    private DesktopVault.ContactView selectedContact() {
        return contactList == null
            ? null
            : contactList.getSelectionModel().getSelectedItem();
    }

    private void selectIdentityInList(UUID identityId) {
        if (identityList == null) {
            return;
        }
        snapshot.identities()
            .stream()
            .filter(identity -> identity.identityId().equals(identityId))
            .findFirst()
            .ifPresent(identityList.getSelectionModel()::select);
    }

    private void selectContactInList(UUID contactId) {
        if (contactList == null) {
            return;
        }
        snapshot.contacts()
            .stream()
            .filter(contact -> contact.contactId().equals(contactId))
            .findFirst()
            .ifPresent(contactList.getSelectionModel()::select);
    }

    private Optional<MetadataInput> metadataDialog(
        String title,
        String displayNameLabel,
        String currentDisplayName,
        String currentNote,
        boolean displayNameOptional
    ) {
        Dialog<MetadataInput> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        ButtonType save = new ButtonType(
            "保存",
            ButtonBar.ButtonData.OK_DONE
        );
        dialog.getDialogPane().getButtonTypes().addAll(
            save,
            ButtonType.CANCEL
        );

        TextField displayName = new TextField(
            currentDisplayName == null ? "" : currentDisplayName
        );
        TextArea note = new TextArea(currentNote == null ? "" : currentNote);
        note.setPrefRowCount(4);
        note.setWrapText(true);
        VBox content = new VBox(
            10,
            fieldLabel(displayNameLabel),
            displayName,
            fieldLabel("备注（仅本地加密保存）"),
            note
        );
        content.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(content);
        if (!displayNameOptional) {
            Node saveButton = dialog.getDialogPane().lookupButton(save);
            saveButton.disableProperty().bind(
                displayName.textProperty().isEmpty()
            );
        }
        dialog.setResultConverter(button -> button == save
            ? new MetadataInput(displayName.getText(), note.getText())
            : null);
        return dialog.showAndWait();
    }

    private Optional<String> publicIdentityDialog() {
        Dialog<String> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("粘贴公开身份");
        dialog.setHeaderText(
            "粘贴完整的 WindLetter Desktop 公开身份 JSON。"
        );
        ButtonType importButton = new ButtonType(
            "导入",
            ButtonBar.ButtonData.OK_DONE
        );
        dialog.getDialogPane().getButtonTypes().addAll(
            importButton,
            ButtonType.CANCEL
        );
        TextArea text = new TextArea();
        text.setPromptText("{\"format\":\"windletter.public-identity\", ...}");
        text.setPrefRowCount(14);
        text.setWrapText(true);
        dialog.getDialogPane().setContent(text);
        Node button = dialog.getDialogPane().lookupButton(importButton);
        button.disableProperty().bind(text.textProperty().isEmpty());
        dialog.setResultConverter(selected ->
            selected == importButton ? text.getText() : null);
        return dialog.showAndWait();
    }

    private char[] promptPassword(String title, String header) {
        Dialog<char[]> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        ButtonType continueButton = new ButtonType(
            "继续",
            ButtonBar.ButtonData.OK_DONE
        );
        dialog.getDialogPane().getButtonTypes().addAll(
            continueButton,
            ButtonType.CANCEL
        );
        RevealablePasswordField password = passwordField("保险库密码");
        dialog.getDialogPane().setContent(password);
        Node button = dialog.getDialogPane().lookupButton(continueButton);
        button.disableProperty().bind(password.textProperty().isEmpty());
        dialog.setResultConverter(selected ->
            selected == continueButton
                ? password.takePassword()
                : null);
        Optional<char[]> result = dialog.showAndWait();
        password.clear();
        return result.orElse(null);
    }

    private boolean confirm(
        String title,
        String header,
        String content
    ) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        return alert.showAndWait().filter(
            button -> button == ButtonType.OK
        ).isPresent();
    }

    private Path chooseOpenVaultFile(String title) {
        java.io.File selected = vaultFileChooser(title).showOpenDialog(stage);
        return selected == null ? null : selected.toPath();
    }

    private Path chooseSavePublicIdentity(String displayName) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出公开身份");
        chooser.setInitialFileName(safeFileName(displayName) + ".json");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("風笺公开身份 (*.json)", "*.json")
        );
        java.io.File selected = chooser.showSaveDialog(stage);
        return selected == null ? null : selected.toPath();
    }

    private static FileChooser vaultFileChooser(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("風笺加密保险库 (*.wlv)", "*.wlv")
        );
        return chooser;
    }

    private static String safeFileName(String displayName) {
        String safe = displayName.replaceAll("[\\\\/:*?\"<>|]", "_").strip();
        return safe.isEmpty() ? "WindLetter-public-identity" : safe;
    }

    private static RevealablePasswordField passwordField(String prompt) {
        return new RevealablePasswordField(prompt);
    }

    private static char[] takePassword(RevealablePasswordField field) {
        return field.takePassword();
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

    private static Button dangerButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("danger-button");
        return button;
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

    private static Label detailValue() {
        Label label = new Label();
        label.getStyleClass().add("detail-value");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static Label fingerprintValue() {
        Label label = detailValue();
        label.getStyleClass().add("fingerprint-value");
        label.setMinHeight(82);
        return label;
    }

    private static FlowPane actions(Node... nodes) {
        FlowPane pane = new FlowPane(Orientation.HORIZONTAL, 10, 10);
        pane.getChildren().addAll(nodes);
        pane.setAlignment(Pos.CENTER_LEFT);
        return pane;
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

    private static Node split(ListView<?> list, VBox details) {
        list.setMinWidth(260);
        list.setPrefWidth(320);
        VBox left = new VBox(10, sectionHeading("列表"), list);
        left.setPadding(new Insets(22));
        VBox.setVgrow(list, Priority.ALWAYS);
        javafx.scene.control.SplitPane split =
            new javafx.scene.control.SplitPane(left, details);
        split.setDividerPositions(0.34);
        return split;
    }

    private static Node centered(Node node) {
        VBox wrapper = new VBox(node);
        wrapper.setAlignment(Pos.TOP_CENTER);
        wrapper.setPadding(new Insets(30));
        ScrollPane scroll = new ScrollPane(wrapper);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setPannable(true);
        return scroll;
    }

    private static Tab tab(String title, Node content) {
        return new Tab(title, content);
    }

    private static String userMessage(Throwable failure) {
        if (failure instanceof DesktopVaultException vaultFailure) {
            return vaultFailure.getMessage();
        }
        return "操作未完成。请检查所选文件和输入后重试。";
    }

    private static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    private record MetadataInput(String displayName, String note) {
    }

    private record IdentityChoice(DesktopVault.IdentityView identity) {
        @Override
        public String toString() {
            return identity.displayName()
                + (identity.defaultIdentity() ? " · 原默认身份" : "");
        }
    }

    @FunctionalInterface
    private interface PasswordOperation<T> {
        T apply(char[] password) throws Exception;
    }
}

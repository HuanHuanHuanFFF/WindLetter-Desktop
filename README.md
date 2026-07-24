# 風笺 · WindLetter Desktop

風笺 · WindLetter 是一个基于 JavaFX 的 Windows 桌面应用，帮助普通用户生成和管理身份、管理联系人，并通过真实 WindLetter 核心安全发送、接收、解密和验签消息。

本项目采用 [Apache License 2.0](LICENSE)。

WindLetter 的 JavaFX 桌面客户端。阶段 1–4 已完成；当前可通过加密
Vault 管理身份和联系人，并使用真实 WindLetter 核心完成完整发送、
接收、解密和验签。

## 开发环境

- Java 17
- Maven 3.9.9（仓库也提供 Maven Wrapper）
- JavaFX 21.0.10
- WindLetter 核心库固定到 `core-baseline.properties` 中的提交

## 准备核心依赖

核心库尚未发布为不可变 Maven 制品。本项目不复制核心源码或 JAR，而是校验相邻核心仓库的来源、完整提交、干净状态和 Maven 坐标，再运行核心全量测试并安装到项目隔离的本地 Maven 仓库：

```powershell
.\scripts\prepare-core.ps1 -JdkHome 'C:\Users\幻\.jdks\ms-17.0.16'
```

校验失败时脚本会停止，不会使用漂移的 `SNAPSHOT`。

## 构建与运行

```powershell
.\mvnw.cmd verify
.\mvnw.cmd javafx:run
```

在可交互的 Windows 桌面会话中，可运行控件级 smoke；脚本会启动窗口、点击真实收发自检、确认成功状态并关闭窗口：

```powershell
.\scripts\ui-smoke.ps1
```

非交互或隔离桌面会话无法发现顶层窗口，此时脚本会明确失败，不会把后台 Java 进程存在误报为 UI 验证成功。

## Windows 自包含应用

使用 Java 17 的 `jpackage` 生成包含私有运行时的 app-image：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\build-windows-package.ps1 `
  -Type app-image `
  -JdkHome 'C:\Users\幻\.jdks\ms-17.0.16'
```

默认会重新核对并测试固定核心基线，再执行桌面端完整验证。开发期间核心
已经准备完毕时可以显式使用 `-SkipCorePreparation`。输出位于
`dist\app-image\WindLetter`，普通用户运行其中的 `WindLetter.exe`
不需要另行安装 Java。

在可交互 Windows 会话中验证打包结果：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-packaged-app.ps1
```

该 smoke 使用隔离的临时 `APPDATA`，创建测试 Vault、运行真实协议收发、
确认加密文件持久化、完全退出后重新解锁，最后删除测试数据；它不读取
默认用户 Vault。

生成 `.msi` / `.exe` 还需要 WiX 3.x。构建脚本会优先使用被 Git
忽略的 `.local-tools\wix314`，也可通过 `-WixBin` 指定其他目录。当前
验证基线为 WiX `3.14.1.8722`，官方便携包
`wix314-binaries.zip` 的 SHA-256 为
`6AC824E1642D6F7277D0ED7EA09411A508F6116BA6FAE0AA5F2C7DAA2FF43D31`。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\build-windows-package.ps1 `
  -Type msi `
  -JdkHome 'C:\Users\幻\.jdks\ms-17.0.16'

powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\build-windows-package.ps1 `
  -Type exe `
  -JdkHome 'C:\Users\幻\.jdks\ms-17.0.16'
```

制品分别输出到 `dist\msi` 和 `dist\exe`。MSI 全流程验证会拒绝覆盖已有
WindLetter 安装，使用隔离数据完成安装、真实收发、重启解锁和卸载，并
确认卸载不会删除加密 Vault：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-windows-installer.ps1
```

普通用户说明见 [docs/USER_GUIDE.md](docs/USER_GUIDE.md)，发布门禁见
[docs/dev/10-phase-5-release-checklist.md](docs/dev/10-phase-5-release-checklist.md)。

接收页复制恢复明文后，如果剪贴板内容未被用户替换，应用会在 60 秒后
自动清除；锁定、关闭接收页或开始处理下一条消息时也会提前清除。

身份私钥只保存在 Argon2id + AES-256-GCM 保护的加密保险库中；应用不会明文持久化私钥。新保险库密码须包含 8–256 个 Unicode 字符，密码框默认隐藏并可使用右侧小眼睛临时显示。

公开身份可在只读文本框中切换 `風铭（WindBase）` 或标准 Base64 PEM 并复制；联系人粘贴导入会按精确头部自动识别这两种格式，同时兼容阶段 2 早期的原始 JSON。

“接收”页支持粘贴标准 Base64 PEM 或風笺 WindBase 消息，也支持导入
binary Armor。只有核心返回成功时才会展示或保存 payload；已签名消息
会显示联系人和签名验证状态，未签名消息会明确标为无法认证发送者。
保存时会根据消息携带的 MIME 类型建议 `.pdf`、`.png`、`.json` 等
常见后缀；无法可靠识别时才使用 `.bin`。

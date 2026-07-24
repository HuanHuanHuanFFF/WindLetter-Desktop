# 風笺 · WindLetter Desktop

WindLetter 的 JavaFX 桌面客户端。阶段 1–3 已完成：真实核心收发基线、加密保险库、身份和联系人管理，以及完整真实发送界面。

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

身份私钥只保存在 Argon2id + AES-256-GCM 保护的加密保险库中；应用不会明文持久化私钥。新保险库密码须包含 8–256 个 Unicode 字符，密码框默认隐藏并可使用右侧小眼睛临时显示。

公开身份可在只读文本框中切换 `風铭（WindBase）` 或标准 Base64 PEM 并复制；联系人粘贴导入会按精确头部自动识别这两种格式，同时兼容阶段 2 早期的原始 JSON。

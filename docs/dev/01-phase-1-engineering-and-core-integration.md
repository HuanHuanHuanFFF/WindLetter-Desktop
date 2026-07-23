# 阶段 1 开发记录：工程骨架与真实核心闭环

- 日期：2026-07-23（Asia/Shanghai）
- 桌面分支：`spike/desktop-v0`
- 桌面起始提交：`0071019fe67df084afc3809cf6c4cd72c678c9f1`
- 核心库：`D:\CodingProject\WindLetter`
- 核心分支：`spike/demo-v0`
- 核心固定提交：`15677e77f53cc2b6b8b765d124cd3f5cb1023594`
- 当前状态：阶段 1 已完成；自动化验证通过，交互式桌面 UI smoke 已由用户确认成功

## 1. 本阶段实际增加的能力

应用已经具备一个最小 JavaFX 窗口，并能通过真实 WindLetter 核心库执行以下内存闭环：

1. 使用 Bouncy Castle 核心 provider 生成短生命周期 X25519 发送密钥、X25519 收件密钥和 Ed25519 签名密钥；
2. 通过 `WindLetterRuntime.sender(...)` 创建真实发送端；
3. 固定使用 `PUBLIC + X25519 + signed + BASE64_PEM` 生成消息；
4. 只把标准 PEM Armor 文本交给 `DecryptRequest`，并把 `armorFormat` 设为 `null`，由核心根据精确 Header 自动路由；
5. 通过 `WindLetterRuntime.receiver(...)` 完成解析、收件人路由、解密、binding 和验签；
6. 校验结果为 `SUCCESS + SIGNED_VALID`、发送者身份非空、恢复 payload bytes 与原始 bytes 完全一致；
7. 校验截断 Armor 返回 `INVALID_MESSAGE` 且没有 payload；
8. 校验错误收件人返回 `NOT_FOR_ME` 且没有 payload。

JavaFX 界面在后台线程执行该流程，界面只展示非敏感的成功状态、认证结果、传输格式、失败输入检查和耗时，不展示完整 Armor、wire JSON 或 payload。

## 2. 可复现核心依赖方案

核心库当前没有不可变发布制品，仍使用 `0.1.0-SNAPSHOT`。阶段 1 采用以下过渡门禁：

- `core-baseline.properties` 固定核心仓库 URL、完整提交 SHA、Maven 坐标和版本；
- `scripts/prepare-core.ps1` 校验 origin、HEAD、干净工作区、根 POM、API artifact 和 Java 17；
- 校验通过后运行核心完整 `clean install`，测试与产物写入桌面项目忽略的 `.mvn/repository`；
- 桌面构建通过 `.mvn/maven.config` 强制使用该隔离仓库；
- Maven Wrapper 固定 3.9.9，并固定官方二进制分发包 SHA-256；
- 不复制核心源码、JAR，不使用 `systemPath`，也不依赖未固定提交的在线 `SNAPSHOT`。

端到端执行以下命令已经成功：

```powershell
.\scripts\prepare-core.ps1 -JdkHome 'C:\Users\幻\.jdks\ms-17.0.16'
```

输出确认准备的是完整提交 `15677e77f53cc2b6b8b765d124cd3f5cb1023594`。

## 3. 安全边界

### 已实现

- 私钥只存在于核心 provider 的内存 handle，不导出、不序列化、不写入磁盘；
- handle 只使用一次，交给核心 lease 后由核心关闭；未转移的 handle 在 `close()` 中全部尝试关闭；
- 清理失败会在尝试清理其他 handle 后继续抛出，不静默吞掉；
- 应用可控的 payload byte buffer、恢复副本和公开材料 buffer 在 `finally` / `close()` 中尽力清零；
- UI 失败只展示通用中文提示，不显示异常原因或密码学堆栈；
- 项目没有添加记录 payload、Armor、密钥、CEK、KEK 或 shared secret 的日志。

### 明确限制

- Java `String`、核心对象内部复制和 JVM/Provider 内部内存不能由应用保证立即清零；
- 本阶段不持久化身份，不实现 Vault，不提供私钥备份或恢复；
- 阶段 2 在核心补齐受控私钥序列化 API 前仍被阻塞，桌面端不会用反射或复制 provider 实现绕过。

## 4. 测试先行证据

真实收发测试先在缺少 `RoundTripSelfTestService` / `SelfTestReport` 时出现预期编译失败，随后最小实现使同一聚焦测试通过。UI 状态测试同样先因缺少 `SelfTestPresenter` / `SelfTestViewState` 失败，再由最小状态映射实现转绿。

当前桌面自动化结果：

- 测试套件：3
- 测试：5
- failures：0
- errors：0
- skipped：0
- 完整命令：`.\mvnw.cmd -q verify`

当前核心库自动化结果：

- 测试套件：95
- 测试：928
- failures：0
- errors：0
- skipped：0
- 已由 `prepare-core.ps1` 在固定提交上重新执行完整 `clean install`

## 5. 运行与 UI 验证边界

本会话已经实际启动 `com.windletter.desktop.Launcher`，进程命令行使用 JavaFX 21.0.10、桌面 `target/classes` 和项目隔离仓库中的 WindLetter 核心模块，Java 进程保持响应。这证明运行入口与运行时依赖已经接入。

Codex 工具进程位于隔离的非交互桌面会话。两次 Windows UI Automation 尝试都无法发现顶层窗口，因此本记录不把后台 Java 进程存在冒充为“按钮已点击成功”。交互式 UI smoke 可在用户桌面会话执行：

```powershell
.\scripts\ui-smoke.ps1
```

该脚本会启动窗口、查找标题 `風笺 · WindLetter`、点击“运行真实收发自检”、等待“真实收发成功”，然后关闭窗口。也可以运行 `.\mvnw.cmd javafx:run` 后人工点击按钮。

随后，用户在交互式 Windows 桌面完成 UI smoke，并明确反馈“已成功”。这属于用户运行验收证据，与本会话自动化测试和进程启动证据分开记录。至此阶段 1 的窗口启动、按钮流程和真实收发成功状态均已验收。

## 6. 阶段提交

- `da9e642` `build: bootstrap reproducible desktop project`
- `30bc5a2` `feat: add real core round-trip self-test`
- `18e1697` `feat: add JavaFX real self-test window`
- `c2bdebf` `build: pin Maven distribution checksum`

以上提交均已推送到 `origin/spike/desktop-v0`。

## 7. 剩余 P2 与影响

| 项目 | 影响 | 后续处理 |
|---|---|---|
| 核心仍是本地固定提交的 `SNAPSHOT` | 新环境必须先取得核心仓库并执行准备脚本 | 发布前改为带不可变版本号的正式制品 |
| UI 控件自动化依赖交互式 Windows 桌面 | 隔离 Codex 会话不能独立完成控件级验收；本阶段已由用户桌面验收补齐 | 阶段 5 接入可交互 Windows CI |
| 阶段 1 UI 只暴露一个固定协议组合 | 不能替代完整发送与接收产品流程 | 阶段 3、4 增加全部选项与输入输出流程 |
| 当前桌面测试为最小闭环 5 项 | 尚未覆盖完整配置矩阵、文件、大消息和更多 Unicode 边界 | 随阶段 2—5 按能力增量补齐 |
| 尚无可分发 Windows 安装包 | 普通用户仍需要 JDK/Maven 才能启动当前开发版本 | 阶段 5 使用 `jpackage` 并做 clean-machine smoke |

这些 P2 不改变当前协议、密码学、认证正确性或用户交互 smoke 证据，不阻塞阶段 1 完成。

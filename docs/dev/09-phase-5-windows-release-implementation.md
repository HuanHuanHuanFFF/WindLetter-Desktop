# 阶段 5：桌面完整性与 Windows 发布实现记录

日期：2026-07-24
分支：`spike/desktop-v0`

## 1. 进入阶段 5 的实时基线

- 桌面起始提交：`c2584cb32b670bf31cf4934bfb14fa77e61dfa36`
- 桌面分支：`spike/desktop-v0`，起始工作区干净并与远端同步。
- 核心分支：`spike/demo-v0`
- 核心提交：`4a5e9a747148fd05940e7571ff4cc80f014a4127`
- 核心工作区：干净并与远端同步。
- JDK：Microsoft OpenJDK `17.0.16`
- `jpackage`：`17.0.16`，可生成 Windows app-image。
- WiX：本机未安装；JDK 17 生成 `.exe` / `.msi` 的当前外部阻塞。

Oracle JDK 17 文档确认：app-image 和原生包均由 `jpackage` 在目标平台
生成；非模块化应用会获得 classpath 应用所需的默认 JDK 模块集合；
Windows `.exe` / `.msi` 需要 WiX 3.0 或更高版本。

参考：

- [Oracle JDK 17 Packaging Overview](https://docs.oracle.com/en/java/javase/17/jpackage/packaging-overview.html)
- [Oracle JDK 17 jpackage 命令](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jpackage.html)

## 2. 阶段 5 范围

1. 固化版本、核心基线和品牌资产；
2. 生成包含私有 Java 运行时的 app-image；
3. 在隔离用户数据目录执行真实 packaged UI smoke；
4. 加固大消息、Unicode、剪贴板、临时文件和异常恢复；
5. 安装 WiX 后生成 per-user `.exe` / `.msi`；
6. 验证安装、启动、真实收发、重启解锁、升级和卸载；
7. 补齐用户说明、备份/升级警告、发布检查单和剩余 P2。

阶段 5 不修改 WindLetter 协议、密码算法或 Vault schema。发现 P0/P1
时停止发布；普通 P2 必须记录影响和后续建议。

## 3. 闭环 1：品牌、版本和自包含 app-image

状态：已实现，待提交推送。

实现：

- 应用版本固定为 `0.1.0`；
- 界面显示版本与核心短提交 `4a5e9a7`；
- 构建元数据与 `core-baseline.properties` 不一致时拒绝打包；
- 用户提供的 `E:\Download\windletter.svg` 已作为
  `assets/windletter.svg` 品牌源文件；
- 同源生成 256×256 JavaFX PNG 和多尺寸 Windows ICO；
- JavaFX Stage 左上角图标和 `jpackage --icon` 使用同一 Logo；
- Maven 固定 JAR / dependency 插件，收集完整 runtime classpath；
- `build-windows-package.ps1` 支持 `app-image` / `exe` / `msi`；
- 构建默认重新准备并测试固定核心，也支持明确跳过已完成的核心准备；
- 发布脚本保持纯 ASCII 源文件，在运行时从 UTF-8 Base64 恢复中文，
  避免 Windows PowerShell 5 无 BOM 代码页破坏元数据和 UIA 名称；
- `smoke-packaged-app.ps1` 使用随机隔离 `APPDATA`，不会接触真实 Vault。

真实验证：

1. Maven 完整 `verify` 通过；
2. `jpackage --type app-image` 成功；
3. app-image 约 148 MiB，包含 `runtime\bin\server\jvm.dll`；
4. app-image 不包含也不依赖外部 `java.exe`；
5. packaged ICO 与仓库品牌 ICO 的 SHA-256 完全一致；
6. Windows UI Automation 真实启动 `WindLetter.exe`；
7. 首次启动显示创建 Vault 页面和新 Logo；
8. 自动化使用“小眼睛”切换后的安全输入边界创建测试 Vault；
9. 进入工作区后选择“协议自检”，真实收发成功；
10. 隔离 `%APPDATA%\WindLetter\vault.wlv` 已加密持久化；
11. 窗口关闭后隔离 smoke 数据被安全清除。

## 4. 可访问性修复

阶段 5 packaged smoke 发现：认证页进入工作区时替换整个 JavaFX
`Scene` 会使 Windows UI Automation 保留空的旧提供者。

已改为保留同一个 `Scene`、只替换根节点，并增加回归测试。发送页、
接收页和标签刷新测试均通过。这个修复同时改善 Windows 辅助技术和
端到端自动化的稳定性，不改变业务或密码学行为。

## 5. 当前阻塞与风险

| 等级 | 项目 | 影响 | 处理 |
| --- | --- | --- | --- |
| P1（安装器闭环） | WiX 未安装 | 只能生成 app-image，不能生成 `.exe` / `.msi` | 下一闭环请求安装 WiX 3.x |
| P2 | 安装包尚未代码签名 | Windows 可能显示未知发布者或 SmartScreen 提示 | Demo 可记录接受；公开发布前需要可信代码签名证书 |
| P2 | 当前使用默认/用户提供品牌图标但没有完整视觉规范 | 不影响运行，安装器细节仍可精修 | 阶段 5 发布检查时复核 |
| P2 | 核心 Maven 版本仍为 `0.1.0-SNAPSHOT` | 制品坐标本身不可证明不可变 | 继续依靠完整提交校验和隔离仓库，正式发布前发布不可变核心制品 |

## 6. 下一闭环

1. 补齐大消息、空文件、Unicode、剪贴板和临时文件自动化边界；
2. 安装 WiX 3.x 并生成 per-user Windows 安装器；
3. 使用隔离安装目录和数据目录验证安装、重启、真实收发和卸载；
4. 完成用户文档与发布检查。

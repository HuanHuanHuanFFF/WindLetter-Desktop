# 阶段 5 Windows 发布检查单

日期：2026-07-24  
应用版本：`0.1.0`  
核心提交：`4a5e9a747148fd05940e7571ff4cc80f014a4127`

## P0 / P1 发布门禁

| 检查项 | 状态 | 证据 |
| --- | --- | --- |
| 私钥不明文持久化 | 通过 | Vault 为 Argon2id + AES-256-GCM；既有负向与持久化测试通过 |
| 真实核心发送/接收 | 通过 | packaged UI smoke 运行真实协议自检 |
| 严格解析、路由、binding、验签 | 通过 | 阶段 4 真实正负向矩阵与阶段 5 packaged smoke |
| 失败不误报成功 | 通过 | 只有核心 `SUCCESS` 展示 payload |
| Java 17 自包含运行时 | 通过 | app-image 内含私有 runtime，无需外部 Java |
| 应用和安装器 Logo | 通过 | 用户 SVG 同源生成 JavaFX PNG 与 Windows ICO |
| MSI 生成与 WiX ICE | 通过 | WiX `3.14.1.8722`，`light.exe` 返回 0 |
| 安装与启动 | 通过 | per-user MSI 静默安装后启动已安装 `WindLetter.exe` |
| 真实收发与重启解锁 | 通过 | 隔离 APPDATA 创建 Vault、真实收发、退出、重启、解锁 |
| 卸载程序文件 | 通过 | MSI 卸载返回 0，安装目录和产品注册信息消失 |
| 卸载保留用户 Vault | 通过 | 加密测试 Vault 在卸载后仍存在，再由 smoke 安全清理 |
| 桌面完整自动化测试 | 通过 | 36 suites / 83 tests，0 failure / error / skipped |

## 当前制品

制品目录被 Git 忽略，每次正式发布必须重新构建、重新记录校验值：

| 制品 | 大小 | SHA-256 |
| --- | ---: | --- |
| `dist/exe/WindLetter-0.1.0.exe` | 68,716,544 bytes | `7D129FF047120227060F9B5FF568487C8CE6E995688B7ED90566E0ABCDED0781` |
| `dist/msi/WindLetter-0.1.0.msi` | 68,120,836 bytes | `CF7FA598B03ACE1F0F1FD01CD2884147F005095709C869218F9AC8301734050A` |
| `dist/app-image/WindLetter` | 155,203,188 bytes / 371 files | 目录制品，不使用单文件哈希 |

当前 EXE 和 MSI 均为 `NotSigned`，不能把以上哈希用于未来重建制品。

## 可重复构建门禁

1. Java 必须是 17；
2. POM 核心提交必须与 `core-baseline.properties` 一致；
3. 默认重新验证固定核心仓库、完整测试并安装到隔离 Maven 仓库；
4. 桌面 Maven `clean verify` 必须通过；
5. 安装器使用 WiX 3.x 的 `candle.exe` 和 `light.exe`；
6. jpackage 临时目录固定到项目 `target` 下的 ASCII 路径；
7. 最终制品写入根目录 `dist/<type>`，不会被下一次 Maven `clean` 删除；
8. MSI 发布前必须运行 `smoke-windows-installer.ps1`。

WiX 便携基线：

- 官方发布：WiX Toolset v3.14.1；
- 版本：`3.14.1.8722`；
- 文件：`wix314-binaries.zip`；
- SHA-256：
  `6AC824E1642D6F7277D0ED7EA09411A508F6116BA6FAE0AA5F2C7DAA2FF43D31`；
- 本地目录：`.local-tools/wix314`，不提交二进制工具。

## 未完成 P2

| 项目 | 影响 | 发布前建议 |
| --- | --- | --- |
| 安装包未代码签名 | Windows 显示未知发布者，可能触发 SmartScreen | 取得可信代码签名证书并签名 EXE/MSI |
| 未在独立干净 Windows 环境验收 | 当前验证机可能已有隐含系统组件 | 在 Windows Sandbox/全新 VM 重跑安装 smoke 与人工流程 |
| 尚无 0.1.1 制品 | 不能真实证明跨版本升级保留 Vault 和快捷方式 | 发布第二版本前构建 0.1.1，执行 0.1.0 → 0.1.1 → rollback 验证 |
| WiX 3 已停止社区支持 | 构建工具无后续社区修复 | Java 17 当前 jpackage 要求 WiX 3；后续评估新 JDK + 受支持 WiX |
| 核心仍是 `0.1.0-SNAPSHOT` | Maven 坐标本身不可证明内容不可变 | 发布不可变核心制品，桌面改用 release 坐标 |
| 没有公开发行许可证与隐私/支持信息 | 不影响本地 Demo，阻塞正式公共分发合规 | 发布前补齐 LICENSE、支持渠道和隐私说明 |

这些 P2 不改变当前 Demo 的协议正确性、私钥保护或本机真实可运行性，
但在面向不受控普通用户公开分发前必须重新评估。

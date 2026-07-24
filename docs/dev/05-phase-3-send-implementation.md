# 阶段 3：完整发送界面实现记录

日期：2026-07-24
分支：`spike/desktop-v0`

## 1. 进入阶段 3 的实时基线

- 桌面端 `HEAD`：`d8ef928f96b52c7f8797c8c1f44006788ea7e930`
- 桌面工作区：干净，本地与 `origin/spike/desktop-v0` 同步。
- 核心仓库：`D:\CodingProject\WindLetter`
- 核心分支：`spike/demo-v0`
- 核心 `HEAD`：`4a5e9a747148fd05940e7571ff4cc80f014a4127`
- 核心工作区：干净，本地与 `origin/spike/demo-v0` 同步。
- 核心公开发送入口仍为
  `WindLetterRuntime.sender(...)` →
  `WindLetterSender.encrypt(...)` /
  `encryptAndSign(...)`。

本阶段不复制 wire、AAD、收件人构造、KEM、密钥包装、签名或
Armor 实现；桌面端只提供 Vault 密钥租约、联系人公钥解析、用户输入
与输出处理。

## 2. 阶段范围与完成门禁

阶段 3 只增加发送能力：

1. 文本或文件 payload，保持原始字节及明确 MIME；
2. 1–32 个联系人，多选且不重复；
3. public / obfuscation；
4. X25519 / X25519ML-KEM-768；
5. signed / unsigned；
6. 标准 Base64 PEM / 風笺 WindBase / binary；
7. 文本复制、文本或二进制保存、清晰成功/失败反馈；
8. 加密和文件读写在后台执行，不阻塞 JavaFX 线程。

完成门禁：

- 8 种模式 × 密钥配置 × 签名组合全部通过真实核心收发；
- 三种用户输出格式全部进入核心 Armor；
- public 只从选中本地身份打开 X25519 租约；
- obfuscation 不向核心传发送者加密身份；
- signed 只从选中本地身份打开 Ed25519 租约；
- 不兼容、缺失、重复或超过上限的选择在生成前拒绝；
- 文本输出可复制/保存，binary 不进入文本框且只能保存；
- 失败不保留旧的伪成功输出，不创建半成品目标文件；
- 阶段自动化验证和 Windows UI smoke 通过。

## 3. 安全边界

- payload 上限跟随核心 `ProtocolLimits.MAX_PAYLOAD_BYTES`，当前为
  8 MiB；不在桌面端放宽核心限制。
- Vault 私钥只在一次核心调用前导入短生命周期 handle；私钥编码副本
  在 `finally` 中清零，handle 所有权交给核心 lease 并由核心关闭。
- 应用不会记录 payload、完整密文、私钥、CEK、KEK、shared secret
  或底层密码学异常。
- 发送失败统一映射为不含 cause 的用户错误；详细内部错误不展示。
- 生成的 WindLetter 密文可被复制或保存，本身不是私钥；明文 payload
  仍可能留在 JavaFX 控件、文件缓存或 JVM 内部副本中，阶段 5 继续处理
  剪贴板和进程内残留边界。
- 本阶段不改变 Vault schema 和私钥持久化格式。

## 4. 闭环 1：真实 Vault 发送业务层

状态：已实现，待本闭环提交推送。

新增：

- 独立的发送请求、payload、输出格式和结果模型；
- `DesktopVault.send(...)` 安全门面；
- Vault 联系人到 `RecipientPublicKeyResolver` 的严格适配；
- Vault X25519 私钥到 `SenderEncryptionKeyStore` 的短租约适配；
- Vault Ed25519 私钥到 `IdentityService` 的短租约适配；
- 三种核心 Armor 输出映射；
- 1–32 收件人、重复选择、MIME、8 MiB payload 和身份要求校验；
- 通用且无内部 cause 的发送错误。

TDD 证据：

1. RED：`VaultSendServiceTest` 首次编译因发送模型、发送门面和
   `SEND_FAILED` 尚不存在而失败，符合预期缺失行为。
2. GREEN：聚焦测试成功。
3. 测试使用两个真实加密 Vault，把收件人的公开身份导入发送方，再把
   生成结果交给真实 `WindLetterRuntime.receiver(...)`；8 种协议组合
   均恢复原始 UTF-8 payload，并分别得到 `SIGNED_VALID` 或 `UNSIGNED`。
4. PEM、風笺和 binary 三种输出均在该矩阵中覆盖。
5. 空收件人和超过核心上限的 payload 返回无 cause 的通用发送失败。

## 5. 后续闭环

1. 独立 JavaFX 发送页：文本/文件来源、身份、联系人多选和配置联动；
2. 输出页：文本复制与保存、binary 原子保存、成功/失败状态；
3. 多收件人真实收发、文件字节保持、错误恢复和 JavaFX 导航回归；
4. 阶段 3 Windows UI smoke、完整报告和剩余 P2。

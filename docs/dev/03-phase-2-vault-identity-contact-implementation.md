# 阶段 2 实施记录：Vault、身份与联系人

- 状态：进行中
- 日期：2026-07-23（Asia/Shanghai）
- 桌面起始提交：`1d5f2d4`
- 固定核心提交：`4a5e9a747148fd05940e7571ff4cc80f014a4127`

## 1. 已完成闭环：Vault 内存认证加密

当前新增的是低层、包内可见的 Vault Cipher，不是用户可用的持久化身份库。

已实现：

- 使用 Bouncy Castle Argon2id 从 `char[]` 密码的 UTF-8 bytes 派生 32-byte KEK；
- KDF 参数设定硬性上下限，解析不可信 Header 时先校验参数，再执行 Argon2id；
- 使用 JDK `AES/GCM/NoPadding` 完成 AES-256-GCM 整体 payload 认证加密；
- 每次 seal 生成新的 16-byte Vault ID、16-byte salt 和 12-byte nonce；
- 物理 Envelope 使用固定 magic、big-endian Header 长度、CBOR Header 和 `ciphertext || tag`；
- AAD 是 magic、Header 长度和原始 CBOR Header 的完整 bytes；
- Header parser 开启重复字段检测、未知字段拒绝、尾随 token 拒绝和 primitive null 拒绝；
- 解锁时重新序列化 Header，并与原始 bytes 做常量时间比较，从而拒绝非规范 CBOR Header；
- 错误密码、tag 错误、篡改和损坏输入统一映射到无 cause 的通用中文失败；
- 调用方拥有解密返回值；派生密码 bytes、KEK、Cipher 临时数组和失败 plaintext 在 finally 中尽力清零；
- low-level Cipher 和 KDF 参数类型保持 package-private，避免界面或其它包绕过后续 Vault 服务。

未实现：

- 尚未选择创建 Vault 时的 Argon2id 校准参数；当前 `minimumSupported()` 只是安全下限和测试参数，不是产品默认值；
- 尚未实现 Vault payload schema 编解码；
- 尚未实现原子文件保存、备份、恢复或用户数据目录；
- 尚未生成、导出或落盘任何真实身份私钥；
- 尚未实现锁定状态机、身份、联系人或 JavaFX 界面。

## 2. 测试先行证据

RED：

- 先新增 `VaultCipherTest`；
- 聚焦测试正常进入 test compilation，只因 `VaultCipher`、`VaultKdfParameters` 和通用异常类型尚不存在而失败。

GREEN：

- `seal → open` 恢复包含中文、补充平面 Unicode 和 NUL 的原始 binary payload；
- 同一密码和 payload 的两次 seal 产生不同 Envelope；
- 错误密码、密文篡改和损坏输入返回相同用户消息且无 cause；
- 低于/高于允许范围的 memory、iterations 和 parallelism 全部拒绝；
- `.\mvnw.cmd -q -Dtest=VaultCipherTest test` 通过；
- `.\mvnw.cmd -q verify` 通过：4 个测试套件、9 个测试，0 failure、0 error、0 skipped。

## 3. 当前安全边界

- 本闭环证明“内存 payload 可以通过版本化 Header、Argon2id 与 AES-256-GCM 认证加密并安全失败”；
- 它不证明文件写入原子性、备份可恢复、私钥 schema 正确、解锁生命周期或完整阶段 2 可用；
- 未经后续 payload、文件和状态机测试，不得把实际身份私钥写入用户数据目录；
- Java/JCA/Jackson/Bouncy Castle 内部复制无法由应用保证清零，阶段报告只能声明可控 byte buffer 的 best-effort 清理。

## 4. 下一闭环

1. 在目标 JDK/Windows 环境实现并测试 Argon2id 参数校准；
2. 实现严格、带上限的 CBOR Vault payload schema；
3. 使用测试临时目录实现安全文件创建、flush、原子替换和恢复失败保留旧文件；
4. 上述闭环通过后，才接入真实三算法身份密钥。

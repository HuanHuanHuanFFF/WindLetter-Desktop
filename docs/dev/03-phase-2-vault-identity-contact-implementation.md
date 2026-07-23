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

- 尚未实现 Vault payload schema 编解码；
- 尚未实现原子文件保存、备份、恢复或用户数据目录；
- 尚未生成、导出或落盘任何真实身份私钥；
- 尚未实现锁定状态机、身份、联系人或 JavaFX 界面。

## 2. 已完成闭环：Argon2id 目标机校准

新增 package-private `VaultKdfCalibrator`：

- 创建 Vault 前通过真实 Argon2id probe 测量当前 JVM；
- 从 64 MiB、128 MiB、256 MiB 候选中选择目标时延内最大的内存档位；
- 基于实测每次迭代成本选择 2—10 次迭代，并再次测量最终组合；
- 默认目标时延为 500 ms，最大候选内存同时受产品上限和 JVM 最大堆四分之一约束；
- 校准用随机 password bytes、salt 和派生 key 均在 finally 中尽力清零；
- Cipher 与校准器共用唯一 `VaultKeyDerivation`，没有复制 Argon2id 参数或实现。

校准参数不是全局常量。后续创建服务会把本机校准结果写入每个 Vault 的认证 Header；解锁已有 Vault 时使用文件中经过边界检查和 AEAD 认证的参数。

测试使用可控计时 probe，稳定覆盖：

- 在目标时延内选择最大内存，再增加迭代；
- 最低安全成本已超过目标时延时不降低安全下限；
- 遵守目标机器内存 cap 和最大迭代次数；
- `VaultCipherTest,VaultKdfCalibratorTest` 聚焦测试通过；
- 完整 `verify` 通过：5 个测试套件、12 个测试，0 failure、0 error、0 skipped。

测试先行证据：

- RED：先新增 `VaultKdfCalibratorTest`，聚焦测试只因校准器和校准结果类型尚不存在而在 test compilation 失败；
- GREEN：实现真实 probe 与可注入计时 seam 后，3 项校准行为测试、Cipher 联合聚焦测试和完整 `verify` 全部通过。

## 3. 已完成闭环：严格 Vault Payload 与 Vault ID 绑定

新增完整 V1 内存模型与严格 CBOR codec：

- Payload 包含 `vaultId`、时间、身份、联系人和自动锁定设置；
- 每个身份必须恰好按 X25519、ML-KEM-768、Ed25519 顺序包含三条私钥记录；
- 私钥编码和长度固定为 `RAW-32`、`FIPS203-DK-2400`、`SEED-32`；
- 联系人只持有三套公钥，核验状态与 `verifiedAt` 必须一致；
- 身份/联系人 ID 不得重复，默认身份必须引用当前已有身份；
- display name 按 1—64 个 Unicode 码点校验并拒绝不适合界面显示的控制字符；
- note 最多 256 个 Unicode 码点，允许缺失、换行和 tab，但拒绝 NUL、代理码点及其它控制字符；
- 身份最多 64 个、联系人最多 1024 个、Payload 最多 8 MiB；
- CBOR 私钥、KID 和公钥必须是 byte string，文本/Base64 形式直接拒绝；
- parser 拒绝重复 map key、未知字段、尾随 token、primitive null、非规范 CBOR 和越界集合；
- Cipher seal 现在由上层传入唯一 `vaultId`；open 返回可关闭的 Header `vaultId` 与 plaintext；
- Payload decode 必须以常量时间比较认证 Header 和明文 Payload 的 `vaultId`；
- `OpenedVault.close()` 和 `VaultPayload.close()` 会清零各自拥有的明文与私钥数组，关闭后拒绝再次导出。

测试先行证据：

- RED：先修改 Cipher 契约并新增 `VaultPayloadCodecTest`，聚焦测试只因 Payload 模型、codec、`OpenedVault` 以及新 seal 签名尚不存在而在 test compilation 失败；
- GREEN：真实二进制 CBOR 往返、Header/Payload ID 不一致、重复算法、未知字段、尾随数据、文本私钥、错误长度、集合超限、明文/私钥关闭清零均通过；
- 完整 `verify` 通过：6 个测试套件、18 个测试，0 failure、0 error、0 skipped。

本闭环只验证结构和内存所有权。当前尚未通过核心 provider 重新导入私钥并核对派生公钥/KID，因此仍禁止把真实身份私钥写入磁盘。

## 4. 已完成闭环：核心密钥材料一致性校验

新增 package-private `VaultKeyMaterialValidator`：

- 对每条身份私钥调用固定核心基线的 `importPrivateKey(...)`；
- 从核心 handle 重新取得公钥，并与 Vault 中持久化公钥做常量时间比较；
- 对身份和联系人公钥调用核心 `X25519KeyId`、`MLKem768KeyId`、`Ed25519KeyId` 重新派生 KID；
- 将核心返回的 Base64URL KID 解码为 Vault 的 32-byte KID 后做常量时间比较；
- 不复制私钥导入、ML-KEM 结构、公钥派生或 KID 算法；
- 每次校验创建短生命周期 handle，并在 try-with-resources 中关闭；
- 私钥、公钥与 KID 临时数组均在 finally 中尽力清零；
- provider 拒绝、私钥/公钥不匹配和 KID 不匹配统一为无 cause 的 Payload 通用失败。

测试先行证据：

- RED：先新增真实核心 provider 测试，聚焦测试只因 `VaultKeyMaterialValidator` 尚不存在而在 test compilation 失败；
- GREEN：真实生成并导出的 X25519、ML-KEM-768、Ed25519 私钥连续校验两次通过；
- 负向：篡改身份公钥、篡改联系人 KID 均稳定返回通用失败；
- 完整 `verify` 通过：7 个测试套件、21 个测试，0 failure、0 error、0 skipped。

该校验器已经实现并验证，但尚未接入后续文件解锁服务；在接线完成前不能宣称磁盘 Vault 的私钥一致性已被强制执行。

## 5. 闭环 1 测试先行证据

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

## 6. 当前安全边界

- 本闭环证明“内存 payload 可以通过版本化 Header、Argon2id 与 AES-256-GCM 认证加密并安全失败”；
- 它不证明文件写入原子性、备份可恢复、私钥 schema 正确、解锁生命周期或完整阶段 2 可用；
- 未经后续 payload、文件和状态机测试，不得把实际身份私钥写入用户数据目录；
- Java/JCA/Jackson/Bouncy Castle 内部复制无法由应用保证清零，阶段报告只能声明可控 byte buffer 的 best-effort 清理。

## 7. 下一闭环

1. 使用测试临时目录实现安全文件创建、flush、原子替换和恢复失败保留旧文件；
2. 建立 create/open/save/lock 服务，强制串联 Cipher、Payload codec 与核心密钥校验；
3. 上述闭环通过后，才生成并持久化真实三算法身份密钥。

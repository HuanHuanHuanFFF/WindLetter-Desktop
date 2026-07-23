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
- 创建时生成新的 16-byte Vault ID 和 16-byte salt；每次 seal 生成新的 12-byte nonce；
- 物理 Envelope 使用固定 magic、big-endian Header 长度、CBOR Header 和 `ciphertext || tag`；
- AAD 是 magic、Header 长度和原始 CBOR Header 的完整 bytes；
- Header parser 开启重复字段检测、未知字段拒绝、尾随 token 拒绝和 primitive null 拒绝；
- 解锁时重新序列化 Header，并与原始 bytes 做常量时间比较，从而拒绝非规范 CBOR Header；
- 错误密码、tag 错误、篡改和损坏输入统一映射到无 cause 的通用中文失败；
- 调用方拥有解密返回值；派生密码 bytes、KEK、Cipher 临时数组和失败 plaintext 在 finally 中尽力清零；
- low-level Cipher 和 KDF 参数类型保持 package-private，避免界面或其它包绕过后续 Vault 服务。

该初始闭环当时未实现、后续闭环已补齐：

- Vault payload schema 编解码见第 3 节；
- 原子文件保存与备份见第 5 节；
- 创建、解锁、保存、锁定与恢复服务见第 6 节。

该初始闭环当时仍未实现、后续已有进展：

- 真实身份生成与加密落盘见第 7 节；
- 公开身份与联系人见第 8 节；
- 最终用户数据目录、自动锁定计时器和 JavaFX 界面仍未实现。

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

该闭环完成时校验器尚未接入文件解锁服务；第 6 节已完成接线，现在所有 `VaultService.open/restore/save` 都强制执行该校验。

## 5. 已完成闭环：加密 Envelope 原子文件与备份

新增 package-private `VaultFileStore`，只接受和返回加密 Envelope：

- 文件读写上限为 8 MiB Payload 加固定 Envelope 余量；
- 保存时先把调用方 Envelope 防御性复制到受控数组；
- 在目标目录创建临时文件，完整写入后调用 `FileChannel.force(true)`；
- 只允许 `ATOMIC_MOVE + REPLACE_EXISTING` 替换目标；
- 平台不支持原子移动或移动失败时不退化为普通覆盖，旧 Vault 保持原样；
- 失败后尽力删除临时文件；临时文件只包含认证加密后的 Envelope；
- 备份通过“读取现有加密 Envelope → 原子写入另一个目标”完成，不解锁、不重新加密；
- 读取缺失、空、截断、读取期间变化或超大文件时返回无 cause 的统一解锁失败；
- 读取和写入使用的受控 Envelope 数组在失败或完成后尽力清零。

测试先行证据：

- RED：先新增 `VaultFileStoreTest`，聚焦测试只因 `VaultFileStore` 尚不存在而在 test compilation 失败；
- GREEN：Windows 测试临时目录上的真实创建、替换、读取和 `ATOMIC_MOVE` 通过；
- 故障注入：模拟原子移动失败后旧文件 bytes 不变，且目录中无遗留临时文件；
- 备份：源文件和备份文件同时保留，备份 bytes 与源 Envelope 完全一致；
- 负向：缺失、空和超过上限的文件返回相同通用失败且无 cause；
- 完整 `verify` 通过：8 个测试套件、25 个测试，0 failure、0 error、0 skipped。

当前尚未实现目录 fsync 的跨平台保证；Windows 原子移动在目标测试环境已真实通过。断电后的目录项持久性仍作为发布前 P2 记录，不得表述为绝对耐久。

## 6. 已完成闭环：Vault 创建、解锁、保存、锁定与恢复服务

新增 `VaultService`、`VaultSession` 与 `VaultSessionKey`，强制串联此前的全部安全边界：

```text
加密文件
  → Header/KDF 边界检查
  → Argon2id + AES-GCM
  → Header/Payload vaultId 比对
  → 严格 CBOR schema
  → 核心私钥导入、公钥与 KID 一致性
  → 解锁会话
```

实现行为：

- 创建新 Vault 时先执行本机 Argon2id 校准，生成随机 Vault ID 和空 Payload；
- 创建采用目标空文件占位后原子替换，已有 Vault 不得被静默覆盖；
- 密码只在创建/解锁时用于 Argon2id，不保存在服务或会话中；
- 解锁态保存 `VaultSessionKey`：32-byte KEK、16-byte salt 和边界内 KDF 参数；
- 保存时复用当前 salt/KEK，但每次生成新的 AES-GCM nonce 和新 Envelope；
- `VaultSession.close()` 先清 Payload 私钥，并在 finally 中保证继续清除 KEK 与 salt；
- 创建密码要求 12—1024 个 Unicode 码点，拒绝 NUL 和未配对 surrogate；
- 创建时密码策略错误是本地输入错误；解锁时短密码、错误密码、损坏和结构/密钥失败仍统一为无 cause 的通用解锁失败；
- 备份只复制认证加密 Envelope；
- 恢复先完成 AEAD、严格 schema、Vault ID 和核心密钥一致性全链验证，成功后才原子替换当前 Vault；
- 失败恢复保持当前 Vault bytes 不变。

测试先行证据：

- RED：先新增 `VaultServiceTest`，聚焦测试只因 `VaultService` 和 `VaultSession` 尚不存在而在 test compilation 失败；
- 创建后清除调用方 password 数组，仍可使用解锁态 KEK 保存；保存前后 Envelope 不同；
- 锁定后反射检查受控 KEK 数组全部归零，且会话拒绝继续访问；
- 重启式重新 open 恢复相同 Vault ID 与设置；
- 错误密码和重复创建均失败，已有 Vault bytes 不变；
- 损坏备份验证失败不替换当前文件；有效备份可恢复被破坏的当前文件；
- 完整 `verify` 通过：9 个测试套件、30 个测试，0 failure、0 error、0 skipped。

该服务当前创建的是空 Vault，尚未提供真实身份生成/导入操作；因此本闭环没有把真实私钥写入磁盘。

## 7. 已完成闭环：真实三算法身份生成、选择与删除

新增 `VaultIdentityManager` 与候选 Payload 事务：

- 每个新身份分别调用核心 Bouncy Castle provider 生成 X25519、ML-KEM-768、Ed25519 handle；
- 只通过固定核心基线的受控 `exportPrivateKey(...)` 取得规范私钥编码；
- 公钥从对应核心 handle 取得，KID 通过核心协议 API 派生；
- handle 使用 try-with-resources 关闭，导出私钥、公钥和 KID 临时数组在 finally 中尽力清零；
- 新身份默认 `origin=GENERATED`，display name 和 note 先经过 Vault 文本边界校验；
- 修改身份列表时深拷贝现有私钥数组，使当前 Payload 与候选 Payload 拥有独立清理责任；
- 候选 Payload 先执行完整核心密钥校验和原子保存，成功后才替换会话内 Payload；
- 保存失败时关闭候选 Payload 并清除其中私钥，当前会话不接纳未提交身份；
- 第一个身份自动成为默认身份；
- 选择默认身份必须引用现有身份；
- 删除默认身份后选择剩余第一项；删除最后一个身份后默认身份为空；
- Payload 替换时再次常量时间核对 Vault ID，成功后清除旧 Payload 私钥。

测试先行证据：

- RED：先新增 `VaultIdentityManagerTest`，聚焦测试只因 `VaultIdentityManager` 尚不存在而在 test compilation 失败；
- GREEN：生成真实三算法身份、加密落盘、关闭会话、重新 open 后恢复相同 identity ID、名称、note 和三套非零私钥；
- 重新 open 会强制经过核心私钥导入、公钥与 KID 一致性校验；
- 生成第二身份、切换默认身份、删除默认身份并再次重启后状态正确；
- 删除最后一个身份后身份列表和默认身份均为空，重启后保持；
- 完整 `verify` 通过：10 个测试套件、32 个测试，0 failure、0 error、0 skipped。

这是阶段 2 首次真实私钥持久化证据：测试只在 JUnit 临时目录落盘，物理文件始终是 Argon2id + AES-256-GCM 认证加密 Envelope。公开导出和名称/备注编辑已在第 8 节补齐；本地私钥身份导入仍未实现。

## 8. 已完成闭环：公开身份、联系人与本地元数据

新增 `PublicIdentityCodec`、`PublicIdentity` 与 `VaultContactManager`：

- 公开身份是 WindLetter Desktop 产品 JSON，不是 WindLetter 消息、Armor 或协议 wire；
- 导出字段严格限定为 format、version、displayName 和三套公钥记录；
- 不导出 note、identityId、origin、时间戳或任何 privateKey；
- JSON parser 开启重复字段检测，拒绝未知字段、尾随数据和 primitive null；
- 输入 UTF-8 JSON 最大 256 KiB；
- 三套算法必须按 X25519、ML-KEM-768、Ed25519 完整出现，重复、缺失和换序均拒绝；
- encoding 和公钥长度必须与算法精确匹配；
- KID、公钥必须是无 padding 的规范 Base64URL；
- 导入时通过核心 KID API重新派生并做常量时间比较，不能信任文件中的 KID；
- 新联系人保存对方 `claimedDisplayName`，本地名称与 note 初始为空，核验状态固定为 `UNVERIFIED`；
- 相同三套公钥的重新导入会明确拒绝，不覆盖本地名称、note 或核验状态；
- 本地可以设置/清除联系人显示名称和 note，并显式切换 `UNVERIFIED` / `FINGERPRINT_VERIFIED`；
- 首次核验记录 `verifiedAt`；只编辑本地元数据时保留既有核验时间；
- 联系人修改和删除使用与身份相同的候选 Payload 原子提交；
- 身份 displayName 与 note 也已支持原子编辑，私钥、identityId、origin 和创建时间保持不变。

测试先行证据：

- RED：先新增 `PublicIdentityContactManagerTest`，聚焦测试只因公开身份 codec、domain 和联系人 manager 尚不存在而在 test compilation 失败；
- 公开导出包含用户确认的 displayName，明确不包含本地 note、identityId、origin 和 privateKey；
- 公开 JSON 解码后恢复三套公钥，导入联系人默认为未核验；
- 本地名称、note 和指纹核验状态保存后可重启恢复；
- 重复导入相同公钥明确失败，既有本地元数据保持不变；
- 未知 privateKey 字段、重复算法、错误 KID、错误长度、带 padding Base64URL 和重复 JSON 字段全部通用失败且无 cause；
- 身份 displayName/note 编辑可跨重启恢复，三套私钥保持有效；
- 完整 `verify` 通过：11 个测试套件、34 个测试，0 failure、0 error、0 skipped。

联系人 `FINGERPRINT_VERIFIED` 只表示本地用户确认了完整公钥组合；它不等于实名，也不等于未来 WindLetter 消息的签名有效。两种状态必须在后续 UI 中分开显示。

## 9. 已完成闭环：从加密 Vault 备份导入私钥身份

V1 不新增第二套单身份私钥文件或第二套密码学格式：

- 私钥导出边界是完整的认证加密 Vault 备份；
- 私钥身份导入从该备份中解锁并选择一个身份合并到当前 Vault；
- 源备份强制经过 Header/KDF 边界、AEAD、严格 CBOR、Vault ID 和核心密钥一致性全链校验；
- 只复制用户选择的身份，不复制源联系人、默认设置或其它身份；
- 导入身份分配新的本地 identityId，设置 `origin=IMPORTED`；
- displayName 和本地 note 作为用户自己的私有备份数据一并迁移；
- 目标已有相同三套 KID 时明确拒绝，不创建重复身份；
- 导入采用候选 Payload 原子提交，错误源密码、缺失身份或重复身份不会改变目标 Vault；
- 源会话在导入结束后立即关闭，清除源私钥副本与源 KEK。

测试先行证据：

- RED：先新增 `VaultIdentityImportTest`，聚焦测试只因 `importFromVaultAndSave(...)` 尚不存在而在 test compilation 失败；
- 源身份生成后导出完整加密备份，备份 bytes 中不出现 displayName 或 note 明文；
- 从备份只导入指定身份，新 identityId 与源不同，origin 为 `IMPORTED`；
- 目标重启后导入身份和三套私钥完整恢复并再次通过核心校验；
- 第二次导入相同三套密钥明确失败，目标只保留一份；
- 错误源密码返回通用解锁失败，目标身份列表保持不变；
- 完整 `verify` 通过：12 个测试套件、36 个测试，0 failure、0 error、0 skipped。

V1 的权衡是：为了迁移单个私钥身份，用户需要持有完整加密 Vault 备份及其密码；应用只合并选中身份。独立单身份加密包可作为未来 P2，但不得复制 Vault 密码学实现。

## 10. 闭环 1 测试先行证据

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

## 11. 当前安全边界

- 当前已证明空 Vault 的创建、认证加密、严格解析、Windows 原子保存、锁定、重新解锁、加密备份和验证后恢复；
- 核心私钥一致性校验已接入所有含 Payload 的保存、解锁和恢复路径，真实三算法身份已在测试临时目录完成加密落盘与重启恢复；
- 自动锁定计时器、最终用户数据目录和 JavaFX 流程尚未完成，阶段 2 仍不可宣称完成；
- Windows `ATOMIC_MOVE` 已实测，跨平台目录 fsync 和进程崩溃恰好发生在创建占位后的恢复体验仍是 P2；
- Java/JCA/Jackson/Bouncy Castle 内部复制无法由应用保证清零，阶段报告只能声明可控 byte buffer 的 best-effort 清理。

## 12. 下一闭环

1. 实现最终用户数据目录与自动锁定计时器；
2. 把 Vault 生命周期、身份与联系人能力接入 JavaFX 阶段 2 界面；
3. 完成真实 Windows 交互式 smoke 与阶段 2 报告。

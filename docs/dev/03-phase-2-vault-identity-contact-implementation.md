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
- 创建及打开密码要求 8—256 个 Unicode 码点，拒绝 NUL 和未配对 surrogate；
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

这是阶段 2 首次真实私钥持久化证据：测试只在 JUnit 临时目录落盘，物理文件始终是 Argon2id + AES-256-GCM 认证加密 Envelope。公开导出和备注编辑已在第 8 节补齐；本地私钥身份导入仍未实现。

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
- 新联系人保存对方 `claimedDisplayName`，note 初始为空，核验状态固定为 `UNVERIFIED`；
- 相同三套公钥的重新导入会明确拒绝，不覆盖名称、note 或核验状态；
- `claimedDisplayName` 导入后冻结；本地只能设置/清除 note，并显式切换 `UNVERIFIED` / `FINGERPRINT_VERIFIED`；
- 首次核验记录 `verifiedAt`；只编辑本地元数据时保留既有核验时间；
- 联系人修改和删除使用与身份相同的候选 Payload 原子提交；
- 身份 displayName 与三套密钥一同冻结；只有 note 支持原子编辑，私钥、identityId、origin 和创建时间保持不变。

测试先行证据：

- RED：先新增 `PublicIdentityContactManagerTest`，聚焦测试只因公开身份 codec、domain 和联系人 manager 尚不存在而在 test compilation 失败；
- 公开导出包含用户确认的 displayName，明确不包含本地 note、identityId、origin 和 privateKey；
- 公开 JSON 解码后恢复三套公钥，导入联系人默认为未核验；
- 稳定名称、note 和指纹核验状态保存后可重启恢复；
- 重复导入相同公钥明确失败，既有本地元数据保持不变；
- 未知 privateKey 字段、重复算法、错误 KID、错误长度、带 padding Base64URL 和重复 JSON 字段全部通用失败且无 cause；
- 身份 note 编辑可跨重启恢复，displayName 和三套私钥保持不变；
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

## 10. 已完成闭环：最终用户数据路径与自动锁定

最终用户 Vault 不写入工程目录：

- Windows 默认使用 `%APPDATA%\WindLetter\vault.wlv`；
- 非 Windows 或 Windows 缺少 `APPDATA` 时，回退到 `${user.home}/.windletter/vault.wlv`；
- 路径转换为绝对规范路径，父目录在首次原子保存时创建；
- 文件与目录权限当前继承操作系统用户目录的访问控制，V1 不声称额外加固 ACL。

`VaultSessionController` 统一持有解锁会话和自动锁定任务：

- 延迟值取自已通过严格 Payload 校验的 `autoLockMinutes`；
- 每次受保护操作记录活动时取消旧任务并重新计时；
- 计时任务携带 generation token，即使底层已取消任务仍并发触发，也不能锁定更新后的会话；
- 超时、用户主动锁定、会话替换和控制器关闭都会关闭 `VaultSession`，从而清除当前 Payload 私钥副本与 KEK；
- 计时线程为 daemon，不阻止应用退出；
- 调度失败时不保留半解锁会话。

测试先行证据：

- RED：先新增 `DesktopDataPathsTest` 与 `VaultSessionControllerTest`，聚焦测试只因路径、调度器和会话控制器尚不存在而在 test compilation 失败；
- Windows 与回退路径均得到精确验证；
- 活动会取消旧任务并按 Payload 设置重新调度；
- 已取消的旧任务即使被强制触发，也不会锁定当前会话；
- 最新任务到期后会话进入锁定状态，KEK 缓冲区已清零；
- 替换会话、主动锁定与控制器关闭均清理对应会话；
- 完整 `verify` 通过：14 个测试套件、40 个测试，0 failure、0 error、0 skipped。

## 11. 已完成闭环：JavaFX 安全调用门面

新增公开 `DesktopVault` 作为 JavaFX 与敏感 Vault 实现之间的唯一业务边界：

- JavaFX 只能取得 identity/contact ID、稳定显示名称、备注、来源、默认状态、核验状态和三组完整 KID 指纹；
- 门面不返回私钥、公钥、KEK、Vault ID、底层 Payload 或 Session；
- 公开身份导出仍由严格 `PublicIdentityCodec` 完成，note 和本地 ID 不进入公开文件；
- 所有接收密码的方法消费调用方的 `char[]`，无论成功或失败都在返回前清零；
- 创建、解锁、恢复、身份导入均调用已有 Vault/核心库实现，不增加第二套加密格式；
- 备份检查在临时源会话中完成，取出安全视图后立即关闭并清除源私钥与 KEK；
- 受保护操作通过 `VaultSessionController.use(...)` 串行执行，操作期间暂停计时；结束后重新开始完整自动锁定周期；
- 如果重新调度自动锁定失败，会立即锁定并清除当前会话，不能保留无定时器的解锁状态；
- 整库恢复要求当前 Vault 先锁定，避免替换文件后仍持有旧 Vault 的解锁会话。

测试先行证据：

- RED：先新增 `DesktopVaultTest`，聚焦测试只因 `DesktopVault`、安全视图和通用问题类型尚不存在而在 test compilation 失败；
- 真实生成三算法身份后，安全视图只包含产品元数据和三组 KID，公开导出不含 note、identity ID 或 `privateKey`；
- 公开身份导入联系人、设置 note、指纹核验与删除均可通过门面完成；门面不提供修改身份或联系人 display name 的方法；
- 完整加密备份可检查身份列表、选择导入一个私钥身份，并可在锁定后恢复整库；
- create、unlock、inspect backup、import identity 和 restore 的密码数组均在方法返回前清零；
- 错误密码只返回通用解锁失败且无 cause，未锁定时恢复返回明确的安全前置条件；
- 完整 `verify` 通过：15 个测试套件、43 个测试，0 failure、0 error、0 skipped。

## 12. 已完成闭环：阶段 2 JavaFX 用户流程

阶段 1 单页壳已升级为完整中文阶段 2 工作区，同时保留真实协议自检：

- 首次启动显示创建保险库页：密码确认、5/15/30/60 分钟自动锁定选项和密码不可恢复提示；
- 已有保险库显示解锁页，并提供从完整加密备份恢复的入口；
- 我的身份页支持真实三算法身份生成、note 编辑、默认身份选择、公开身份文本分享、从完整加密备份选择导入和删除；display name 创建后只读；
- 联系人页支持粘贴或从文件导入 PEM、風铭及兼容 JSON 公开身份、note 编辑、三组 KID 指纹展示、核验状态切换和删除；对方声明 display name 只读；
- 备份页支持创建完整加密备份，以及“先锁定、验证成功后替换”的整库恢复；
- 协议自检页保留阶段 1 真实 `PUBLIC · X25519 · signed · Base64 PEM` 收发与负面检查；
- 所有 KDF、密钥生成、磁盘和协议操作都在单一后台任务线程执行，JavaFX 线程不直接执行耗时密码学操作；
- 所有密码入口使用统一的可显示密码控件：默认隐藏，右侧小眼睛可切换显示/隐藏且不改变已输入内容；
- 密码从控件取得后立即清空隐藏和显示状态，转换的 `char[]` 由门面消费并清零；若后台任务尚未开始就关闭窗口，待处理密码数组也会主动清零；
- 受保护工作区的鼠标按下与键盘按下事件会续期自动锁定；后台受保护操作执行时不让 UI 活动回调争抢 Session 锁；
- 每秒检查自动锁定状态，超时后清除工作区引用并返回解锁页；
- UI 只展示门面安全视图，不直接引用私钥、Payload、Session 或 KEK；
- 所有未预期错误只显示通用操作失败，不把异常 cause、文件内部结构或密码学细节展示给用户。

测试先行与自动化证据：

- RED：先新增 `DesktopVaultPresenterTest`，聚焦测试只因阶段 2 展示语义 presenter 尚不存在而在 test compilation 失败；
- 明确区分“生成/导入来源”“默认发送身份”“联系人稳定声明名称/本地备注”；
- “已核对指纹”文案明确声明不代表实名，也不代表消息签名有效；
- 完整 `verify` 通过：17 个测试套件、50 个测试，0 failure、0 error、0 skipped。

Windows 真实 smoke（全部使用 `target/` 临时加密数据，不触碰真实 `%APPDATA%`）：

- 正式 Maven JavaFX 入口启动成功，窗口标题为 `風笺 · WindLetter`，进程持续响应；
- 创建页与锁定后的解锁页完成实际渲染检查，长说明、路径和安全边界文案可完整显示；
- 使用 JavaFX 官方 `Application` 生命周期打开真实解锁工作区，身份、联系人、备份与恢复、协议自检四页完成实际渲染检查；
- 临时 Vault 中的真实 X25519、ML-KEM-768、Ed25519 身份和三组 KID 在身份页正确显示；
- 从 JavaFX“生成新身份”对话框创建第二个真实三算法身份成功，列表、note 和三组新 KID 刷新，状态提示为已加密保存；
- 联系人页正确显示 claimed display name、note、三组 KID 和“已核对但不代表实名/签名”的边界；
- 从 JavaFX 协议自检页实际运行真实收发成功：签名有效、原文完整恢复、篡改消息与错误收件人均被拒绝；
- 点击“立即锁定”后工作区被清除并返回解锁页；输入临时 smoke 密码后重新进入工作区成功。

## 13. 闭环 1 测试先行证据

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

## 14. 当前安全边界

- 当前已证明空 Vault 的创建、认证加密、严格解析、Windows 原子保存、锁定、重新解锁、加密备份和验证后恢复；
- 核心私钥一致性校验已接入所有含 Payload 的保存、解锁和恢复路径，真实三算法身份已在测试临时目录完成加密落盘与重启恢复；
- 最终用户数据目录、自动锁定、活动续期、安全视图和阶段 2 JavaFX 流程均已完成；
- Windows `ATOMIC_MOVE` 已实测，跨平台目录 fsync 和进程崩溃恰好发生在创建占位后的恢复体验仍是 P2；
- Windows 用户数据目录当前继承 `%APPDATA%` ACL，未额外收紧访问控制是阶段 2 的待评估 P2；
- JavaFX `PasswordField`/`TextField` 内部以不可清零的 `String` 保存当前文本；小眼睛显示状态不会持久化密码，应用会尽快同时清空两个控件状态并清零自己创建的 `char[]`，但不能声称清除了 JavaFX/JVM 内部副本；
- Java/JCA/Jackson/Bouncy Castle 内部复制无法由应用保证清零，阶段报告只能声明可控 byte buffer 的 best-effort 清理。

## 15. 阶段状态与下一步

阶段 2 的实现、自动化验证和 Windows 交互式 smoke 已完成。按项目推进约定，等待用户确认阶段 2 报告后再进入阶段 3 完整发送界面。

## 16. 用户 UI 测试反馈闭环：密码长度与显示切换

- 新保险库密码边界由 12—1024 调整为 8—256 个 Unicode 码点；补充平面字符按 Unicode 码点计数，不按 UTF-16 `char` 数量误算；
- 7 个和 257 个码点均拒绝，8 个和 256 个码点均接受；NUL 与未配对 surrogate 继续拒绝；
- 创建、解锁、恢复备份和从备份导入私钥身份的全部密码框均增加右侧小眼睛；
- 密码默认隐藏，显示/隐藏切换保留同一输入内容，并同步更新“显示密码/隐藏密码”无障碍说明；
- 提交密码后同时清空隐藏和显示控件，调用方 `char[]` 的既有清零边界不变。
- 规则不满足时创建页显示明确的 8–256 字符提示；解锁、恢复和备份身份导入仍保留通用失败，避免泄漏认证细节；
- 完整 `verify` 通过：17 个测试套件、50 个测试，0 failure、0 error、0 skipped。

## 17. 用户 UI 测试反馈闭环：公开身份文本装甲

- 公开身份的规范内层数据仍是既有严格 JSON schema，不修改字段、不引入私钥，也不把 display name 写入协议消息；
- 新增标准 Base64 PEM：精确使用 `BEGIN/END WINDLETTER PUBLIC IDENTITY`，正文是 JSON UTF-8 bytes 的标准 Base64，并严格按 64 字符换行；
- 新增風铭 WindBase：精确使用 `-----風铭 起-----` / `-----風铭 凪-----`；正文的版本、长度、字母表和 CRC 全部复用固定核心库 `windletter-armor`，桌面端未复制算法；
- 导入通过精确 Header 自动路由，两种装甲恢复后继续执行原有严格 JSON、算法顺序、公钥长度和 KID 重派生校验；普通 WindLetter 消息装甲、错误头尾和损坏正文统一拒绝；
- 原始 JSON 导入保留为旧数据兼容路径；
- “导出公开身份”改为“分享公开身份”：弹窗使用只读多行文本框，默认显示風铭，可切换标准 Base64 PEM，并可一键复制；不再强制先选择输出文件；
- 从文件导入仍作为辅助兼容入口，扩展名过滤覆盖 `.pem`、`.txt`、`.json` 和 `.wlpub`；
- 公开身份装甲不增加认证含义，界面继续提示接收者通过可信渠道核对三组 KID。
- RED：新增格式与交换测试后，测试编译只因双格式装甲 API 和交换解码入口尚不存在而失败；
- GREEN：装甲、身份交换和安全门面聚焦测试通过；完整 `verify` 通过：18 个测试套件、53 个测试，0 failure、0 error、0 skipped。

## 18. 用户 UI 测试反馈闭环：刷新后保留当前功能页

- 原因确认：身份或联系人变更成功后会重建整个工作区；新 `TabPane` 默认选择第一项“我的身份”，旧实现只恢复列表项，没有恢复标签页；
- 当前工作区记录已选标签索引，刷新重建后恢复同一功能页；联系人导入、备注更新、核验状态切换和删除后继续停留在“联系人”页；
- 进入创建、解锁或自动锁定页面时清除标签状态，新的解锁会话仍从“我的身份”开始；
- RED：真实 JavaFX 工作区选中“联系人”后执行同一刷新路径，断言得到“期望联系人，实际我的身份”；
- GREEN：同一回归测试与全部 UI 测试通过。

## 19. 用户 UI 测试反馈闭环：WindBase 扩展汉字字形

- 问题不是 UTF-8 解码损坏，而是界面优先使用的 `Microsoft YaHei UI` 缺少部分扩展汉字字形；JavaFX 自带回退后，冻结的 1024 个 WindBase 字符仍有 23 个映射到 missing glyph；
- 所有 `TextInputControl` 使用 `SimSun-ExtB` 优先、微软雅黑与系统 sans-serif 后备的字体链；标签和按钮继续保持原有微软雅黑界面风格；
- 该范围覆盖普通文本框、密码框、备注框、公开身份粘贴框和装甲输出框；
- RED：真实加载 JavaFX 样式并读取核心库冻结字符表，`TextField`/`TextArea` 的实际字体映射报告缺少 23 个字形，首个为 `U+2CC76`；
- 曾验证 JavaFX `SansSerif` 仍缺同样 23 个字形，因此未采用无法解决问题的通用字体替换；
- GREEN：最终字体链在当前 Windows/JDK/JavaFX 基线上覆盖 1024/1024 个冻结字符；同一聚焦测试和全部 UI 测试通过；
- 当前不随应用打包字体；其它 Windows 安装若缺少 `SimSun-ExtB`，仍需在阶段 5 打包验证中检测并给出 PEM 回退提示或引入具备明确再分发许可的字体资源。

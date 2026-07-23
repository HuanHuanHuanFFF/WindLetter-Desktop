# 阶段 2 设计：身份、联系人与加密 Vault

- 状态：设计基线已获用户确认；核心私钥导出前置闭环已实现、推送并成为桌面固定基线，Vault 代码尚未实现
- 日期：2026-07-23（Asia/Shanghai）
- 桌面起始基线：`spike/desktop-v0` / `3e90b245f0ff8384a7eaa34c199aa5f4066f016c`
- 核心起始基线：`spike/demo-v0` / `15677e77f53cc2b6b8b765d124cd3f5cb1023594`
- 核心导出实现：`spike/demo-v0` / `da0414cf97cf171a2df00ca56b493eed77dcae5a`
- 核心候选固定基线：`spike/demo-v0` / `4a5e9a747148fd05940e7571ff4cc80f014a4127`
- 桌面采用核心基线：`core-baseline.properties` / `4a5e9a747148fd05940e7571ff4cc80f014a4127`

## 1. 本阶段新增的真实能力

阶段 2 完成后，用户能够：

- 创建受密码保护的本地 Vault，并在启动后解锁、手动锁定或空闲自动锁定；
- 生成、导入、选择、公开导出和删除本地身份；
- 管理联系人公钥，保存本地名称、备注和指纹核验状态；
- 备份和恢复完整加密 Vault；
- 在后续发送、接收流程中按需取得新的短生命周期核心私钥 handle。

完整发送、接收配置仍属于阶段 3、4；阶段 2 只建立可被真实主链使用的身份与联系人基础。

## 2. 威胁模型与恢复边界

### 2.1 防护目标

- 应用数据目录、备份或磁盘被离线复制时，攻击者不能直接得到私钥；
- Vault 被静态篡改时不得返回部分身份、错误密码细节或可区分的认证失败；
- 普通日志、崩溃提示和界面不得包含密码、私钥、KEK、CEK、shared secret 或完整解密缓冲；
- 联系人公钥与本地核验状态也必须获得完整性保护，避免离线替换联系人密钥。

### 2.2 不承诺防护

- Vault 已解锁时的恶意管理员、同会话恶意软件、键盘记录器、进程内存读取或屏幕捕获；
- 被攻陷的 JVM、密码学 provider、核心库或操作系统；
- Windows SSD 上删除或原子替换旧文件后的物理不可恢复。

### 2.3 恢复边界

- V1 不提供后门、服务器托管、密码找回或恢复密钥；
- 密码与可用备份同时丢失时，私钥永久无法恢复；
- 公开身份和公钥不能恢复解密或签名能力；
- 恢复时先在临时内存完成结构、AEAD、私钥/公钥/KID 一致性校验，成功后才原子替换现有 Vault；
- 任一失败必须保留旧 Vault。

## 3. 身份语义

一个本地身份由三把独立随机生成的私钥组成，不从单个主种子派生：

| 用途 | 算法 | V1 私钥编码 | 公钥长度 |
|---|---|---:|---:|
| 静态 ECDH、收发 | X25519 | `RAW-32`，32 bytes | 32 bytes |
| Hybrid 解封装 | ML-KEM-768 | `FIPS203-DK-2400`，2400 bytes | 1184 bytes |
| 签名、验签 | Ed25519 | `SEED-32`，32 bytes | 32 bytes |

本地身份记录：

```text
IdentityRecordV1 {
  identityId: UUID
  displayName: UTF-8 text
  note: UTF-8 text | absent
  origin: GENERATED | IMPORTED
  createdAt: UTC timestamp
  updatedAt: UTC timestamp
  keys: [
    PrivateKeyRecordV1(X25519),
    PrivateKeyRecordV1(ML-KEM-768),
    PrivateKeyRecordV1(Ed25519)
  ]
}
```

- `identityId` 是本地不可变标识，不由名称或 KID 派生，也不默认对外导出；
- `displayName` 表示身份名称，既用于本地展示，也包含在公开身份导出中；
- `note` 表示本地备注，永不进入公开身份导出；
- `origin` 只表示本机生成或导入，不保存来源文件路径，也不表示可信；
- `displayName` 为 trim 后 1—64 个 Unicode 码点，拒绝 NUL 与不适合界面展示的控制字符；
- `note` 最多 256 个 Unicode 码点，允许缺失。

每个私钥记录的逻辑结构：

```text
PrivateKeyRecordV1 {
  algorithm: X25519 | ML-KEM-768 | Ed25519
  encoding: RAW-32 | FIPS203-DK-2400 | SEED-32
  kid: bytes[32]
  publicKey: bytes[32 | 1184]
  privateKey: bytes[32 | 2400]
}
```

KID 规则完全复用核心协议现有规则：

- X25519：RFC 7638 OKP JWK Thumbprint；
- Ed25519：RFC 7638 / RFC 8037 OKP JWK Thumbprint；
- ML-KEM-768：`SHA-256(raw 1184-byte public key)`。

解锁或导入时不得信任持久化的公钥与 KID：必须将私钥重新导入核心 provider，从 handle 取得公钥，再重新派生 KID 并比较全部字段。V1 每个身份恰好包含上述三种算法各一条记录；缺失、重复或多余算法均拒绝。

## 4. Vault 文件格式

### 4.1 两层结构

V1 使用二进制、版本化 Vault。公开 Header 与 AES-256-GCM ciphertext 分离；解密 payload 使用确定长度、严格解析的 CBOR byte string 保存密钥，不把明文私钥转换为 Base64 或 Java `String`。

逻辑 Envelope：

```text
VaultEnvelopeV1 {
  format: "windletter.desktop.vault"
  formatVersion: 1
  vaultId: bytes[16]
  kdf: {
    algorithm: "ARGON2ID"
    salt: bytes[16]
    memoryKiB: uint
    iterations: uint
    parallelism: uint
    outputBytes: 32
  }
  aead: {
    algorithm: "AES-256-GCM"
    nonce: bytes[12]
  }
  ciphertextAndTag: bytes
}
```

AAD 是不含 `ciphertextAndTag` 的规范化 Envelope Header 原始字节，必须绑定：

- format 与 formatVersion；
- vaultId；
- 完整 KDF 算法、salt 和参数；
- AEAD 算法与 nonce。

在执行 Argon2id 前必须先对文件长度、版本、salt/nonce 长度和 KDF 参数做硬上限检查，避免被篡改文件诱导不受限的 CPU 或内存消耗。具体默认 Argon2id 成本在目标 Windows/JDK 17 环境测量后固定；目标是普通用户可接受的解锁延迟，而不是现在猜测参数。

### 4.2 加密 Payload

```text
VaultPayloadV1 {
  schemaVersion: 1
  vaultId: bytes[16]
  createdAt: UTC timestamp
  updatedAt: UTC timestamp
  identities: IdentityRecordV1[]
  contacts: ContactRecordV1[]
  settings: {
    defaultIdentityId: UUID | absent
    autoLockMinutes: uint
  }
}
```

- payload 中的 `vaultId` 必须与认证 Header 相同；
- V1 禁止重复 map key、未知必需算法、无限长度 CBOR item 和越界数组；
- 身份、联系人、文本和总文件大小均设置产品级上限并覆盖负向测试；
- 整个 payload 一次性认证加密，避免每把私钥独立 nonce、独立 AAD 和局部更新产生的复杂状态；
- 每次保存生成新的 12-byte CSPRNG nonce，并通过临时文件、flush、原子替换完成；
- 旧文件删除后的物理残留风险必须在用户文档说明。

## 5. 公开身份交换格式

公开身份是 WindLetter Desktop 产品数据，不是 WindLetter 消息、Armor 或协议 wire。

V1 使用严格 UTF-8 JSON：

```json
{
  "format": "windletter.public-identity",
  "version": 1,
  "displayName": "幻",
  "keys": [
    {
      "algorithm": "X25519",
      "encoding": "RAW-32",
      "kid": "BASE64URL_NO_PADDING",
      "publicKey": "BASE64URL_NO_PADDING"
    },
    {
      "algorithm": "ML-KEM-768",
      "encoding": "RAW-1184",
      "kid": "BASE64URL_NO_PADDING",
      "publicKey": "BASE64URL_NO_PADDING"
    },
    {
      "algorithm": "Ed25519",
      "encoding": "RAW-32",
      "kid": "BASE64URL_NO_PADDING",
      "publicKey": "BASE64URL_NO_PADDING"
    }
  ]
}
```

规则：

- 只导出 `displayName` 和三把公钥，不导出 note、identityId、origin、时间戳或任何私钥；
- 导入时重新派生三个 KID，不能信任文件中的 KID；
- `displayName` 是对方自述名称，不等于实名或指纹已核验；
- 导入后的联系人可设置本地名称；重新导入不得静默覆盖本地名称、备注或核验状态；
- V1 不支持静默换钥。任一公钥变化都必须作为新联系人或明确的人工替换操作处理；
- 用户界面必须把“签名有效”和“联系人指纹已核验”分开显示。

## 6. 联系人记录

```text
ContactRecordV1 {
  contactId: UUID
  claimedDisplayName: UTF-8 text
  localDisplayName: UTF-8 text | absent
  note: UTF-8 text | absent
  verificationStatus: UNVERIFIED | FINGERPRINT_VERIFIED
  verifiedAt: UTC timestamp | absent
  addedAt: UTC timestamp
  updatedAt: UTC timestamp
  publicKeys: [
    PublicKeyRecordV1(X25519),
    PublicKeyRecordV1(ML-KEM-768),
    PublicKeyRecordV1(Ed25519)
  ]
}
```

展示名称优先使用 `localDisplayName`，否则使用对方公开身份中的 `claimedDisplayName`。核验状态是本地用户对完整公钥组合的判断，不从公开身份文件继承。

## 7. 核心私钥序列化边界

阶段 2 起始核心接口能够生成和导入私钥 handle，但 handle 不可导出。阶段 2 的第一个代码闭环已在三个核心 capability 上增加算法专用导出方法：

```java
byte[] exportPrivateKey(X25519PrivateKeyHandle privateKey);
byte[] exportPrivateKey(MLKem768PrivateKeyHandle privateKey);
byte[] exportPrivateKey(Ed25519PrivateKeyHandle privateKey);
```

契约：

- 只接受由同一 provider 实现创建且尚未关闭的 handle；
- null、其它 provider 的 handle、已关闭 handle 必须稳定拒绝；
- 返回调用方拥有的防御性 byte[] 副本，调用方负责在 finally 中清零；
- 导出不得关闭、修改或使原 handle 失效；
- 生成后导出再导入，必须得到相同公钥并能完成各算法真实操作；
- 导入已知编码再导出，必须得到相同规范编码；
- 不增加 `toString()`、日志或异常内容中的密钥材料；
- 核心 API Javadoc 必须写明精确编码、长度、所有权和清理责任。

桌面端只能通过这条公开边界取得新生成的私钥编码，不得反射 provider handle、复制核心实现或自行生成 ML-KEM 编码。

## 8. 解锁与内存生命周期

- 应用启动默认锁定；
- 密码使用 `char[]`；UTF-8 派生输入、KEK、解密 payload 和解析出的私钥 byte[] 均在 finally 中尽力清零；
- Vault 解锁态可以保留经过受控管理的解密密钥记录，但不得长期保留核心 handle；
- 每次发送、接收或导出公开身份时，从 Vault byte[] 导入新的 provider handle；
- handle 交给核心 lease 后由核心关闭；未转移所有权的 handle 由桌面应用关闭；
- 手动锁定、自动锁定、应用退出和解锁失败都必须清除解密态、关闭未转移 handle；
- 错误密码、AEAD tag 错误、格式损坏和私钥一致性错误向用户返回同一个通用解锁失败结果。

Java/JVM 无法保证清除所有内部复制；阶段报告必须区分“可控 buffer 已清理”和“进程内存绝对不可恢复”。

## 9. 测试与完成条件

### 9.1 核心前置闭环

- 三种算法分别先观察缺少导出 API 的预期 RED；
- 生成 → 导出 → 关闭 → 导入 → 相同公钥和真实密码操作；
- 已知编码导入 → 导出完全一致；
- 返回数组修改不影响 handle；
- null、foreign handle、closed handle 稳定失败；
- focused crypto tests 与核心完整 reactor tests 全绿。

已取得的证据：

- RED：`BouncyCastlePrivateKeyExportTest` 正常编译到调用点，只因三种 `exportPrivateKey(...)` 不存在而失败；
- GREEN：同一聚焦测试在最小实现后通过；
- 兼容性：接口默认实现明确抛出 `UnsupportedOperationException`，只有支持持久化边界的 provider 显式覆盖，未强迫协议测试替身导出私钥；
- 编码稳定性：公开 RFC 7748、RFC 8032 与 CCTV ML-KEM 向量证明三种已知私钥编码导入后可原样导出；
- 完整回归：JDK 17 下执行核心 `mvn -q test`，96 个测试套件、933 个测试，0 failure、0 error、0 skipped；
- 提交与推送：实现 `da0414cf97cf171a2df00ca56b493eed77dcae5a`、编码测试 `4a5e9a747148fd05940e7571ff4cc80f014a4127` 均已推送到 `origin/spike/demo-v0`。
- 基线接入：桌面准备脚本已按完整 SHA 校验核心仓库、重新运行上述 933 项测试并安装到项目隔离 Maven 仓库；随后桌面 3 个测试套件、5 个测试及 `verify` 全部通过。

### 9.2 Vault

- 创建、保存、重启、正确密码解锁并恢复三种密钥；
- 错误密码、Header/ciphertext/tag 篡改、截断、重复字段、越界 KDF 参数均通用失败；
- nonce 不复用，旧文件在写入失败时保留；
- 锁定后可观察的解密态和 handle 被清理；
- 备份恢复成功；错误备份不替换当前 Vault。

### 9.3 身份与联系人

- 生成、公开导出、重新导入、选择、删除；
- note 不进入公开身份，displayName 进入公开身份；
- 公开身份私钥字段、未知字段、重复算法、错误长度和错误 KID 均拒绝；
- 联系人本地名称和核验状态不被重新导入静默覆盖；
- 日志、异常和 UI 文案经过敏感内容扫描。

只有核心边界、Vault、身份/联系人服务、JavaFX 用户流程、自动化测试和交互式 Windows smoke 都通过后，才能报告阶段 2 完成。

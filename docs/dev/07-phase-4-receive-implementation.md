# 阶段 4：完整接收界面实现记录

日期：2026-07-24
分支：`spike/desktop-v0`

## 1. 进入阶段 4 的实时基线

- 桌面端 `HEAD`：`646cbcd179814a382e3604f074aac4aa584f2170`
- 桌面工作区：干净，本地与 `origin/spike/desktop-v0` 同步。
- 核心仓库：`D:\CodingProject\WindLetter`
- 核心分支：`spike/demo-v0`
- 核心 `HEAD`：`4a5e9a747148fd05940e7571ff4cc80f014a4127`
- 核心工作区：干净，本地与远端同步。
- 核心公开接收入口仍为
  `WindLetterRuntime.receiver(...)` →
  `WindLetterReceiver.decrypt(...)`。

本阶段不会在桌面端解析 wire、推导 AAD、路由 RID、解包 CEK、
解密 ciphertext、执行 binding 或验证签名；上述行为全部由固定核心
门面完成。

## 2. 阶段范围与完成门禁

阶段 4 只增加接收能力：

1. 粘贴标准 Base64 PEM 或風笺 WindBase 文本；
2. 导入 binary Armor 文件；
3. 选择本地收件身份；
4. 文本通过核心精确 Header 自动路由，binary 显式使用 `BINARY`；
5. 真实解析、收件人路由、解密、binding 和验签；
6. 只在 `SUCCESS` 时展示或保存原始 payload；
7. signed valid 展示稳定联系人名称和指纹核对状态；
8. unsigned 明确说明“未签名”，不得暗示发送者可信；
9. `NOT_FOR_ME` 与 `INVALID_MESSAGE` 使用稳定、无 oracle 的中文结果；
10. 文本 payload 可预览，任意 payload 均可按原始字节保存。

完成门禁：

- 阶段 3 的 8 种协议组合和三种 Armor 均能通过 Vault 接收门面恢复；
- public 和 signed 所需发送者公钥必须来自本地联系人；
- obfuscation unsigned 不错误要求发送者联系人；
- 错误身份、未知签名人、截断、篡改和 malformed binary 无 payload；
- JavaFX 后台执行，失败不保留上一次成功 payload；
- 文件读取和 payload 保存有大小限制与临时文件边界；
- 完成 Windows JavaFX `Stage` smoke 和阶段报告。

## 3. 安全与错误边界

- 文本输入原样交给核心自动检测，不 `trim`、不模糊识别 Header。
- binary 输入显式使用 `ArmorFormat.BINARY`。
- 接收策略固定允许 unsigned，但 signed 消息仍必须验签成功。
- 只有核心 `DecryptStatus.SUCCESS` 映射为桌面成功结果。
- `NOT_FOR_ME` 与 `INVALID_MESSAGE` 的桌面结果不含 payload、发送者、
  message ID 或时间戳。
- 用户界面不会展示 parser、RID、binding、unwrap、AEAD、签名失败阶段
  或底层异常，从而避免形成可利用的详细 oracle。
- 私钥只从选中的 Vault 身份导入一次性 X25519 / ML-KEM handle，
  私钥编码副本在 `finally` 中清零。
- 发送者 X25519 和 Ed25519 公钥只能从 Vault 联系人按 KID 唯一解析；
  重复 KID 作为本地信任数据错误拒绝，不任意选择第一条。

## 4. 闭环 1：真实 Vault 接收业务层

状态：已实现，待本闭环提交推送。

新增：

- 独立接收输入、状态、认证状态和安全结果模型；
- `DesktopVault.receive(...)` 安全门面；
- 选中本地身份到 `RecipientKeyStore` 的短生命周期私钥适配；
- 联系人 X25519 公钥到 `SenderPublicKeyResolver` 的唯一 KID 解析；
- 联系人 Ed25519 公钥和稳定身份到 `IdentityService` 的唯一 KID 解析；
- 核心 `SUCCESS` / `NOT_FOR_ME` / `INVALID_MESSAGE` 的严格结果映射；
- signed valid 返回稳定联系人显示名、signing KID 和本地指纹核对状态；
- unsigned 成功不返回发送者身份；
- 文本和 binary 输入资源上限。

TDD 证据：

1. RED：`VaultReceiveServiceTest` 首次编译因接收模型、接收门面和
   `receive(...)` 尚不存在而失败，符合预期缺失行为。
2. GREEN：聚焦测试成功。
3. 两个真实加密 Vault 双向交换公开身份；阶段 3 的
   2 mode × 2 key profile × signed/unsigned 与三种 Armor
   均通过真实核心接收端恢复原始 Unicode payload。
4. signed 结果为 `SIGNED_VALID`，准确映射已核对联系人；
   unsigned 结果不声明发送者。
5. 错误收件身份返回 `NOT_FOR_ME`；截断文本和 malformed binary
   返回 `INVALID_MESSAGE`，三者均无 payload 或身份数据。
6. 删除发送者联系人后，signed obfuscation 消息变为
   `INVALID_MESSAGE`；不依赖发送者身份的 unsigned obfuscation
   仍能成功，证明没有把联系人信任错误扩张到 unsigned。

## 5. 后续闭环

1. 独立 JavaFX 接收页与后台任务接线；
2. 严格 UTF-8 文本预览、二进制提示和原始 payload 保存；
3. 生产 View 正负流程、导航与错误恢复测试；
4. 阶段 4 完成报告和剩余 P2。

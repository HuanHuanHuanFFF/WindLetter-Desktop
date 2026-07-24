# 阶段 3 完成报告：完整发送界面

日期：2026-07-24
分支：`spike/desktop-v0`

## 1. 阶段结论

阶段 3 已形成真实可运行的完整发送闭环：

- 用户可以输入 UTF-8 文本或选择文件作为原始 payload；
- 用户可以选择 1–32 位联系人；
- 用户可以选择 public / obfuscation；
- 用户可以选择 X25519 / X25519 + ML-KEM-768；
- 用户可以选择 signed / unsigned；
- 用户可以生成标准 Base64 PEM、風笺 WindBase 或 binary；
- 文本输出可以复制或保存，binary 只提供保存；
- 所有消息由固定 WindLetter 核心发送门面真实生成；
- Vault 私钥通过短生命周期 lease 进入核心，桌面端不实现协议或密码学；
- 文件读取、核心加密和保存通过后台任务执行，不阻塞 JavaFX 线程；
- 失败会清除旧结果并禁用复制/保存，不会把失败误报为成功。

阶段 3 可以结束；进入阶段 4 前等待用户确认。

## 2. 固定依赖与提交

- WindLetter 核心基线：
  `4a5e9a747148fd05940e7571ff4cc80f014a4127`
- 核心分支：`spike/demo-v0`
- 阶段结束时核心工作区干净，与远端同步。
- 桌面发送业务层提交：`ec1206f`
- JavaFX 完整发送页提交：`f8decd3`
- 阶段结束时桌面分支与 `origin/spike/desktop-v0` 的
  ahead/behind 为 `0/0`。

## 3. 能力与证据

| 能力 | 合同/代码 | 运行接线 | 真实验证 |
| --- | --- | --- | --- |
| 文本 payload | UTF-8 + 明确 MIME | 已接入发送页 | Unicode 文本经真实核心收发后字节一致 |
| 文件 payload | 原始字节 + 探测 MIME | 已接入文件选择 | 二进制文件字节保持测试通过 |
| 多收件人 | 1–32、唯一联系人 | 已接入复选列表 | 两套独立 Vault 均解密同一 signed Hybrid binary |
| public / obfuscation | 映射核心 `WindMode` | 已接入下拉选择 | 两种模式均在 8 组合矩阵真实解密 |
| X25519 / Hybrid | 映射核心 `KeyAlgProfile` | 已接入下拉选择 | 两种配置均在 8 组合矩阵真实解密 |
| signed / unsigned | 分别调用 `encryptAndSign` / `encrypt` | 已接入签名复选框 | `SIGNED_VALID` / `UNSIGNED` 状态准确 |
| 三种输出 | 核心 PEM / WindBase / binary Armor | 已接入输出区 | 三种格式均由核心接收端恢复 |
| 复制与保存 | 文本复制；同目录临时文件后替换 | 已接入按钮 | 精确文本/二进制写入及无临时残留测试通过 |
| 后台运行 | 复用主 View JavaFX `Task` | 已接入生产工作区 | 真实 `Stage` 从发送页异步收到核心 WindBase 输出 |
| 失败恢复 | 清除旧结果、禁用后续操作 | 已接入输出状态 | 失败回归测试通过 |

## 4. 自动化验证

最终命令：

```text
.\mvnw.cmd -q verify
```

结果：

- 25 个测试套件
- 63 个测试
- 0 failure
- 0 error
- 0 skipped

关键测试不是 mock 协议主链：

1. 两个真实加密 Vault 交换公开身份，覆盖
   2 mode × 2 key profile × signed/unsigned；
2. 每个生成结果都交给真实 `WindLetterRuntime.receiver(...)`；
3. PEM、風笺和 binary 均恢复原始 payload；
4. 多收件人 signed + obfuscation + Hybrid + binary 被两套独立 Vault
   分别解密和验签；
5. 真实 JavaFX `Stage` 通过生产 `VaultDesktopView` 后台任务入口生成
   WindBase 文本。

JavaFX 仍会输出既有的 unnamed module 警告；测试结果不受影响。

## 5. 安全边界

- 发送页不接触 Vault 私钥字节。
- Vault 适配器只为一次核心调用复制私钥编码并立即导入 handle；
  私钥编码副本在 `finally` 中清零，handle 由核心 lease 关闭。
- public 模式才向核心提供发送者 X25519 身份；
  obfuscation 不提供发送者加密身份。
- signed 才打开 Ed25519 signing lease。
- 核心 payload 上限 8 MiB 在文件读取前和读取后均检查。
- 不记录 payload、完整密文、文件内容、私钥、CEK、KEK、
  shared secret 或底层密码异常。
- 发送失败对用户只展示通用错误，异常 cause 不透出界面。
- 保存先写目标同目录临时文件，再替换目标；失败尽力删除临时文件。
- 本阶段没有改变 Vault schema、密码规则或私钥持久化格式。

## 6. Windows JavaFX 验证边界

已自动化验证真实 Windows JavaFX `Stage`：

1. 生产工作区出现“发送”标签；
2. 无收件人时生成按钮禁用；
3. 选择联系人并输入 Unicode 文本后可生成；
4. 生产后台 `Task` 返回精确 `-----風笺 起-----` 输出；
5. 联系人页刷新仍保持原标签，不受新增标签影响。

操作系统原生文件选择器、保存对话框和系统剪贴板的人工点击体验尚未
在本轮自动操作；底层文件读取、精确保存、按钮状态和剪贴板写入代码已
分别接线，其中文件读写有自动化测试。用户可以在当前提交上进行最终
手动 UI 体验确认。

## 7. 推迟处理的 P2

1. 当前 WindLetter payload schema 只携带 MIME，不携带原始文件名。
   影响：收件端能恢复原始字节，但不能从协议消息自动还原文件名；
   后续需由协议扩展或受约束的应用层元数据方案解决，不能擅自使用
   当前核心拒绝的 `customHeaders`。
2. `Files.probeContentType` 结果依赖操作系统，未知类型会回退为
   `application/octet-stream`。影响：字节不变，但接收端可能需要用户
   手动选择打开方式。
3. 目标文件系统不支持 `ATOMIC_MOVE` 时会回退为同目录替换。
   影响：仍避免直接写半成品目标，但不能对所有文件系统承诺原子替换。
4. 发送页不会自动清除系统剪贴板。影响：发送页只复制加密 Armor，
   不复制私钥或明文；统一剪贴板生命周期策略留到阶段 5。
5. 文本输入和 JVM/JavaFX 内部 payload 副本不能保证清零。
   影响：锁定或页面销毁后应用不再持有业务引用，但不能承诺进程内
   绝对清零；威胁模型不防护已解锁时的恶意进程内读取。
6. 8 MiB 加密有忙碌提示但没有百分比进度和显式取消按钮。
   影响：JavaFX 线程不会冻结，但低性能机器上等待体验仍可改善。
7. 发送页已从主 View 独立，但单个组件仍较长；大联系人列表使用普通
   复选容器而非虚拟化列表。影响：当前普通联系人规模可用，阶段 5
   应在真实规模下评估拆分和性能。
8. 其它平台的 JavaFX 字体、文件替换和文件类型探测仍未验证；
   当前阶段只声明 Windows 基线。

以上均不影响阶段 3 的真实 Windows 发送主流程，不属于协议、
密码学、认证或私钥保护阻塞。

## 8. 下一阶段

阶段 4 将增加完整接收能力：

- 粘贴 PEM / 風笺文本或导入 binary；
- 精确 Header 自动路由；
- 选择本地收件身份；
- 真实解析、路由、解密、binding 和验签；
- 只在 `SUCCESS` 时展示 payload；
- 清晰区分已认证发送者、未签名、非本收件人和无 oracle 的无效消息。

开始前等待用户确认。

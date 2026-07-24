# 阶段 5 完成报告

日期：2026-07-24  
分支：`spike/desktop-v0`  
应用版本：`0.1.0`  
核心基线：`4a5e9a747148fd05940e7571ff4cc80f014a4127`

## 1. 阶段结论

阶段 5 的本机 Windows Demo 闭环已完成：

- 普通用户可通过 MSI 安装，无需预装 Java；
- JavaFX 窗口和 Windows 安装制品使用用户提供的同一 SVG Logo；
- 已安装程序能创建加密 Vault、运行真实 WindLetter 收发、持久化、
  完全退出、重新启动并解锁；
- 卸载会移除程序和产品注册信息，但保留用户加密 Vault；
- Payload 上限、Unicode、文件后缀、敏感剪贴板和临时数据边界均有
  自动化证据；
- 使用说明、构建方式、发布检查单与剩余 P2 已记录。

当前状态可称为“Windows 本机可安装、真实主流程可运行的 Demo”。
由于未签名、未做独立 clean-machine 验收和跨版本升级测试，不能称为
“面向公众无条件发布完毕”。

## 2. 交付能力

### 品牌与版本

- 品牌源文件：`assets/windletter.svg`；
- JavaFX Stage：同源 256×256 PNG；
- Windows launcher / installer：同源多尺寸 ICO；
- 界面展示应用版本 `0.1.0` 与核心短提交 `4a5e9a7`。

### 自包含发布

- `app-image`、MSI、EXE 三种输出；
- 私有 Java 17 运行时；
- WiX 使用官方便携 `3.14.1.8722`，不修改系统 PATH 或安装系统组件；
- 构建验证核心基线、Java 版本、完整桌面测试和品牌资源；
- 制品保存到 `dist/<type>`，各格式可以同时保留。

### 发布 smoke

`smoke-packaged-app.ps1`：

1. 使用随机或显式的隔离 `APPDATA`；
2. 启动真实打包程序；
3. 使用密码显示控件创建加密 Vault；
4. 运行真实核心收发自检；
5. 确认 `vault.wlv` 已持久化；
6. 关闭进程并再次启动；
7. 确认出现已有 Vault 解锁页；
8. 解锁并恢复工作区；
9. 安全清理测试数据。

`smoke-windows-installer.ps1` 额外：

1. 拒绝覆盖已有 WindLetter 安装；
2. 安装最终 MSI；
3. 对已安装程序运行上述 packaged smoke；
4. 卸载；
5. 确认安装目录和注册信息已删除；
6. 确认加密用户 Vault 未被卸载器删除；
7. 最后只清理本次隔离测试数据。

## 3. 验证结果

- Maven：36 suites / 83 tests；
- failure：0；
- error：0；
- skipped：0；
- app-image packaged UI smoke：通过；
- 最终 MSI install / real send-receive / restart / unlock / uninstall：通过；
- 卸载保留加密 Vault：通过；
- app-image：155,203,188 bytes / 371 files；
- MSI：68,120,836 bytes；
- EXE：68,716,544 bytes。

自动化 Payload 证据包括：

- 空 payload 真实核心收发；
- 精确 8 MiB 最大 payload 真实核心收发并逐字节比对；
- 超过 8 MiB 的稀疏文件在读取前拒绝；
- UTF-8 中文、emoji、补充平面 Unicode、WindBase 字形与原始文件字节；
- MIME 到保存后缀映射与未知类型 `.bin` 回退；
- 剪贴板 60 秒条件清除、锁定/换消息提前清除、用户新内容不被覆盖。

## 4. 安全边界

- 发布流程没有写入或读取默认用户 Vault；
- 所有 UI smoke 使用项目 `target` 内的隔离 APPDATA；
- 测试密码仅用于随机隔离 Vault，测试结束后清理；
- 安装器 per-user 安装，不要求管理员；
- 应用私钥仍只存在于 Argon2id + AES-256-GCM 加密 Vault；
- 卸载不等于销毁用户密钥，用户必须明确管理 Vault 与备份；
- 安装包未签名的风险已在用户说明和发布检查单中醒目标记。

## 5. 剩余 P2

1. 可信代码签名；
2. 独立全新 Windows VM / Sandbox 的 clean-machine 验收；
3. 真实 `0.1.0 → 0.1.1` 升级与回退；
4. WiX 3 停止社区支持后的构建迁移；
5. 核心不可变 Maven release；
6. 正式公开发行所需的许可证、隐私和支持信息；
7. macOS / Linux 打包；
8. 自动更新、崩溃上报、Windows Hello / DPAPI 便利解锁。

## 6. 阶段门禁判断

未发现会把解析失败误报成功、绕过核心严格主链、明文持久化私钥或泄露
密码学秘密的 P0/P1。剩余项目均是发布成熟度或外部环境验证 P2。

因此阶段 5 可结束，并按用户要求等待下一阶段/发布方向确认。

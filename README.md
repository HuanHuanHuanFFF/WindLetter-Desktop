# 風笺 · WindLetter Desktop

WindLetter 的 JavaFX 桌面客户端。当前处于阶段 1：工程骨架与真实核心库收发闭环。

## 开发环境

- Java 17
- Maven 3.9.9（仓库也提供 Maven Wrapper）
- JavaFX 21.0.10
- WindLetter 核心库固定到 `core-baseline.properties` 中的提交

## 准备核心依赖

核心库尚未发布为不可变 Maven 制品。本项目不复制核心源码或 JAR，而是校验相邻核心仓库的来源、完整提交、干净状态和 Maven 坐标，再运行核心全量测试并安装到项目隔离的本地 Maven 仓库：

```powershell
.\scripts\prepare-core.ps1 -JdkHome 'C:\Users\幻\.jdks\ms-17.0.16'
```

校验失败时脚本会停止，不会使用漂移的 `SNAPSHOT`。

## 构建与运行

```powershell
.\mvnw.cmd verify
.\mvnw.cmd javafx:run
```

阶段 1 只使用短生命周期内存密钥，不会把私钥写入磁盘。

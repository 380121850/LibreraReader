# desktop/ — 桌面平台（预留，暂缓开发）

目标：Windows + Linux 桌面阅读器。两条候选技术路线（决策时二选一，见 MULTI_PLATFORM.md）：

1. **JVM/Compose Multiplatform**：复用仓库里大量"零 Android 依赖"的纯 Java
   逻辑（格式解析 `com/foobnix/ext`、`model/dao2`、WebDAV 传输核心、
   `AiClient` 等），MuPDF 走本地 C 库（JNI 或 Panama）；UI 全新。
2. **C++/Qt**：直接复用 L0 引擎源码树，包体小、性能好，但业务逻辑全部重写。

发布渠道（远期）：Windows 上架 Microsoft Store / 官网安装包；Linux 走
flatpak / AppImage / 发行版打包。本目录当前为空，只作为将来平台代码的落点。

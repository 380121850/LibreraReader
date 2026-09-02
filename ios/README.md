# ios/ — 苹果 iOS 平台（预留，未立项开发）

按 MULTI_PLATFORM.md 的方案，iOS 版属于 **L2+1 原生壳 + L0 共享 C/C++ 引擎**：

- UI/业务：Swift / SwiftUI 原生编写（苹果 App Store 唯一渠道，发布物见 `store/ios/apple/`）；
- 引擎复用：`Builder/mupdf-1.23.7` 同一源码树交叉编译成
  `MuPDF.xcframework`（arm64-simulator / arm64-device），UI 侧用 C API 或薄 ObjC++ 封装；
  与 Android 的 JNI（`Builder/jni`）、鸿蒙的 NAPI（`harmony/entry/src/main/cpp`）并列。
- 广告：如做免费版再评估 AdMob iOS SDK（与 Android 的 `ads-admob` 互不共享）。

本目录当前为空：仓库内无任何 iOS 构建产物/证书。

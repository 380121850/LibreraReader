# Librera Reader HarmonyOS 移植总体分阶段计划

> 制订日期：2026-08-17。本计划为活文档，每阶段结束 review 一次，按实际情况调整后续阶段。
> 移植策略：**C++ 核心（MuPDF）与 Android 共享源码树复用，UI 层用 ArkTS 全新实现**，Android 的 Java/Kotlin 业务代码不直接移植。

## 现状基线（截至制订日）

| 能力 | 状态 |
|---|---|
| 构建/安装/日志链路（Ubuntu22 SSH + hvigorw + hdc + hilog） | ✅ 稳定 |
| MuPDF 1.23.7 原生库（arm64-v8a / x86_64，源码树与 Android 共享） | ✅ 可编译打包 |
| NAPI 导出 | ✅ 12 个：version / openDocument / openDocumentByFd / pageCount / renderPage / **renderPageAsync** / getText / searchText / getDocumentInfo / closeDocument / **getToc** / **getPageSize** |
| UI | Index.ets API 自测页（10 项测试） + Reader.ets 阅读器（Swiper 翻页 + tap zone + async 渲染） |
| 已验证 | 5 页 PDF 全链路、async render + rotate/invert、getToc/getPageSize、EPUB 有 MuPDF 上游 SIGSEGV |

已知限制：MuPDF 1.23.7 EPUB CSS parser 在某些文件上触发 SIGSEGV（fz_chartorune NULL deref），需升级 MuPDF 或等上游修复。

## 已修复问题记录（2026-08-18）

### 启动概率闪退（SIGSEGV in RenderJobExecute）✅ 已修复
- **现象**：VM 上手动打开 APP 有概率闪退，faultlog 签名全部为 `NativeAsyncWork::AsyncWorkCallback → RenderJobExecute` 内 SEGV_ACCERR。
- **主根因**：`renderPageAsync` 90/270 度旋转的像素拷贝索引公式错误 —— `(y*stride + (w-1-x)) * 4` 把本已是字节单位的 stride 又乘了 4，y≥1 即按 4 倍行距越界读源 pixmap，撞堆 guard page。Index 每次启动都跑 rot=90 渲染测试 → "概率"取决于堆布局（debug 越界落未用堆页不崩，release -O2 必崩）。
- **次根因（一并修复）**：closeDocument 显式 `delete h` 后 napi_external finalizer 再次 drop → double-free/UAF；RenderJob 持裸 DocumentHandle* 无引用计数；全文件无 fz_var()（longjmp 后局部变量 UB）；PageCount/GetDocumentInfo/SearchText 有裸 MuPDF 调用（无 try 即 abort）。
- **修复**：DocumentHandle 引入 refs/closed 引用计数（ReleaseHandle 为唯一删除点）；RenderJob 入队前 AcquireHandle；所有 fz_try 块补 fz_var 并集中 fz_always 释放；裸调用包 try；修正旋转索引公式。
- **验证**：release 版循环启动 15 次零崩溃、faultlog 零新增、10 项 API 测试全过。

---

## 阶段 1：环境与构建链路 ✅ 已完成

Ubuntu22（lee 用户 + `source ~/.bashrc`）构建、`hvigorw assembleHap --mode module -p product=default -p buildMode=debug`、hdc 安装启动、`timeout N hilog | grep` 日志验证。

## 阶段 2：最小阅读 PoC（NAPI 打通）✅ 已完成

5 页 test.pdf，Swiper 翻页 + tap zone 导航全链路 hilog 验证通过（`[Reader] Page changed to x/5`）。

## 阶段 3：文档引擎补全（NAPI 层扩展）✅ 已完成

目标：把"能打开一个 PDF"变成"支撑完整阅读器所需的文档能力"。

1. **真异步渲染** ✅ `renderPageAsync`（napi_async_worker + pthread mutex 保护 fz_context，支持 zoom/rotationDeg/invert 选项）
2. **打开方式** ✅ `openDocumentByFd`（已实现，待阶段 4 picker 对接）
3. **结构信息** ✅ `getToc`（fz_load_outline DFS 扁平化）、`getPageSize`（fz_bound_page via fz_load_page）；`getLinks`/`getPageLayout` 留阶段 5
4. **渲染参数** ✅ rotate（0/90/180/270 软件旋转）、invert（XOR 0xFF）通过 RenderOptions 传入 renderPageAsync
5. **缩略图渲染** ✅ 复用 renderPageAsync(zoom<1) 即可，无需单独 API
6. **格式验证** ⚠️ PDF 全功能通过；EPUB 触发 MuPDF 1.23.7 `fz_parse_css→fz_chartorune` SIGSEGV（上游 bug，已记录）
7. **Index.ets API 自测页** ✅ 10 项测试全部 hilog 打勾

NAPI 导出从 9 → 12：新增 renderPageAsync / getToc / getPageSize。
Reader.ets 已切换到 async 渲染路径，5 页 PDF 全页翻页无错误。

## 阶段 4：书库与文件接入（Library）✅ 已完成

目标：脱离 rawfile 测试文档，成为"能打开用户自己文件的应用"。

1. ✅ **文件选择打开**：DocumentViewPicker → URI → fs.openSync(READ_ONLY) → copy to sandbox cacheDir → `openDocument(localPath)`。无需额外权限（picker 走系统授权）。
2. ✅ **最近阅读 + 进度持久化**：`@ohos.data.preferences` 存储 RecentBook[]（path/title/page/totalPages/lastRead），上限 20 条。Reader 每次翻页即保存，退出时兜底保存。重进 Reader 自动恢复到上次页码。
3. ✅ **Index 页面**：双按钮（"打开文件" picker + "测试 PDF" rawfile）+ 最近阅读列表（点击直接进 Reader）。
4. ⚠️ **待真机验证**：picker UI 在 emulator 上难以 uinput 自动化，代码逻辑已就绪（编译通过、app 稳定无 crash），需真机走一遍 picker→Reader→退出重进恢复页码全链路。

新增文件：`entry/src/main/ets/model/ReadingProgress.ets`（preferences 封装）。
修改文件：Index.ets（picker + recent books UI）、Reader.ets（router params + 进度保存/恢复）。

## 阶段 5：完整阅读体验（Reader UI 重构）✅ 已完成

1. ✅ **双滚动模式**：水平 Swiper 翻页（默认）+ 垂直 List 连续滚动，菜单内一键切换（`[Reader] Scroll mode: horizontal/vertical`）
2. ✅ **Pinch 缩放**：`PinchGesture({fingers:2}).onActionEnd` → zoomIn/zoomOut（步进 0.25x，范围 0.5x–4.0x）→ `dataSource.setZoom()` → 下次 renderPageAsync 用新 zoom。菜单内也有 A+/A- 按钮
3. ✅ **夜间模式**：`RenderOptions.invert=true` → MuPDF 渲染后 XOR 0xFF 反色。UI toggle + 顶栏/底栏颜色自适应（`[Reader] Night mode: ON/OFF`）
4. ✅ **TOC 侧边栏**：打开文档时 `getToc()` 加载 → TocPanel 组件从右侧滑入 → 点击条目跳转页码（`[Reader] TOC jump to page N`）。无目录时不显示按钮
5. ✅ **菜单浮层**：居中面板含缩放控制、模式切换、夜间开关、TOC 入口、关闭按钮。每次操作均有 hilog 埋点
6. ✅ **进度持久化**：每次翻页自动 saveRecentBook，退出兜底保存

Reader.ets 从 ~370 行扩展到 ~580 行，新增 TocPanel + PageRenderer 组件。
⚠️ 待真机验证：pinch 手势、垂直滚动流畅性、大文档（数百页）性能。

## 阶段 6：系统集成与发布准备 ✅ 已完成

1. ✅ **文件关联**：module.json5 skills 增加 `{scheme:"file", utd:"general.pdf"}`；EntryAbility onCreate/onNewWant 捕获 want.uri 存 AppStorage，Index 启动时消费 → importUriToSandbox → 直跳 Reader。hilog 验证 URI 管道通（未授权路径 ENOENT 为正确沙箱行为）
2. ✅ **多语言资源**：AppScope zh_CN / en_US string 目录建立（品牌名不变），i18n 结构就绪
3. ✅ **性能收口**：Swiper/List cachedCount(1) 预加载；zoom/夜间切换经 ForEach key 触发重渲染。体积分析：libmupdf.so 的 .rodata 46MB 为内嵌字体（base14+CJK），--strip-debug 无效；abiFilters 不影响 libs/ 打包（HAP 恒双 ABI 110MB，装机时按设备 ABI 提取）。后续优化：字体子集化或商店级 per-ABI 分发
4. ✅ **仓库上库清理**（沿用独立清理计划，全部落位）：.gitignore 鸿蒙段 / gen_signing.sh+make_material.js 密码参数化（LIBRERA_SIGN_PW）/ build-profile.json5 加密材料可克隆再生（signing/README.md 一键流程）/ libmupdf.so 入 prebuilt/harmony/mupdf-1.23.7 双 ABI 已入库 / harmony 根目录无秘密副本
5. ✅ **Release 打包+冒烟**：`buildMode=release` 签名 HAP 109.9MB，VM 干净卸载重装后全部 API 测试通过（注：debug→release 覆盖安装会导致 BMS 元数据不一致，需先 bm uninstall）

新增：`model/FileImport.ets`（URI→沙箱导入，picker/open-with 共用）。

## 阶段 7（长期，按需裁剪）：对齐 Librera 功能广度

TTS 朗读、搜索全书、批注/高亮编辑、OPDS 书源、云同步等。**须先明确"不迁移清单"**——Android 版功能面太大，按需求优先级逐个排期，不默认全量对齐。

---

## 架构与工程原则（全程有效）

1. **禁止 Windows 侧构建**；构建一律 SSH lee@192.168.50.111 且前缀 `source ~/.bashrc &&`
2. NAPI 是唯一桥：文档重计算全在 C++，ArkTS 只做 UI 与状态
3. 新 API 先在 Index 测试页用日志验证，再接入正式 UI
4. ArkTS 严格语法（禁 any/throw 限制/ESObject 受限）——UI 代码保持保守写法，避免编译错误返工
5. 验证一律 hilog 日志驱动（`timeout N hilog | grep`），不用截图作为常规手段
6. 每阶段完成即更新本文件勾选状态，并 review 是否调整后续阶段

## 主要风险

| 风险 | 缓解 |
|---|---|
| 同步 renderPage 卡 UI（大页/低性能设备） | 阶段 3 首项就是 async worker，后续 UI 只允许走异步版 |
| EPUB reflow 布局参数与 Android 端不一致 | 阶段 3 格式验证时与 Android 端截图对比页数与排版 |
| 鸿蒙文件授权模型（scoped storage）限制书库扫描 | 阶段 4 先做 picker + 最近阅读，目录扫描视系统能力降级 |
| MuPDF AGPL 许可与 Librera 发布方式 | 发布前确认许可合规路径（沿用 Android 版现有做法） |

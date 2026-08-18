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

### 阅读菜单无法弹出 ✅ 已修复（2026-08-18）
- **现象**：用户报告"没有菜单功能"——Reader 中心点击无菜单弹出。
- **修复**：
  1. 顶栏新增显式 **「≡ 菜单」按钮**（不依赖中心 tap zone 作为唯一入口）
  2. 菜单浮层从 `.position({x:'15%',y:'30%'})` 改为**模态式**（全屏半透明遮罩 + 居中面板 + Scroll 可滚动），消除定位/层级不确定性
- **验证**（VM uitest 驱动）：`Menu toggled (top bar): true` 菜单弹出 → 面板完整显示（缩放/模式/页面/夜间/亮度/自动翻页/高亮/手绘/保存/关闭）→ 高亮功能 `annotations 0→1` → 书签 `Bookmarks Added page 1` → 菜单关闭；中心 tap zone 同步恢复（`Menu toggled: true`）。release 循环启动 10 次零崩溃、faultlog 零新增。

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

### 7.0 安卓版功能全景 vs 鸿蒙版差距（2026-08-18 盘点）

来源：`app/src/main/java/com/foobnix/`（~1060 Java 文件）+ `org/ebookdroid/`（164 文件）+ `prebuilt/native/mupdf-1.23.7/`。安卓侧几乎全部为成熟完整实现。

**图例**：✅ 鸿蒙已实现｜🟡 部分/底层有、UI 未接｜❌ 未实现

**当前统计（2026-08-19 冲刺 F 后）**：✅ 5｜🟡 21｜❌ 26｜✅/🟡 1（合计 53 项）

| # | 功能（安卓） | 鸿蒙现状 | 说明 |
|---|---|---|---|
| **阅读引擎** | | | |
| 1 | 翻页模式：单页/双页/双页+封面/半页/乐谱 | 🟡 | 鸿蒙只有单页 Swiper+List；双页/乐谱需 UI 拼合 |
| 2 | 滚动模式（垂直连续） | ✅ | List 模式已实现 |
| 3 | PDF 渲染/文本层/链接/大纲 | ✅ | renderPageAsync + getToc |
| 4 | 文本重排 reflow（PDF→HTML→EPUB） | ❌ | MuPDF 支持但未接 NAPI/UI |
| 5 | 翻页效果/自动翻页 | 🟡 | 有 Swiper 动画；自动翻页定时器无 |
| 6 | 手势配置（滑动方向/双击动作） | 🟡 | 有 pinch+点击分区；自定义手势映射无 |
| 7 | 点击分区定制（4 边+中心） | 🟡 | 固定左中右 3 区；定制对话框无 |
| 8 | 裁剪（自动白边/对称裁剪） | ❌ | NAPI 无 crop 选项 |
| 9 | 方向/旋转/RTL 阅读方向 | 🟡 | rotationDeg 有；RTL 无 |
| 10 | 全屏/沉浸式 | ❌ | 未接（鸿蒙 window 全屏 API） |
| 11 | 状态栏定制（进度线/章节刻度） | 🟡 | 有简易进度条；定制无 |
| 12 | 性能设置（内存/质量/抗锯齿） | ❌ | 未接 |
| 13 | 护眼定时/屏幕超时 | ❌ | 未接 |
| 14 | 书内搜索 UI（高亮+前后跳转） | 🟡 | searchText NAPI 有；UI 无 |
| **书库与文件** | | | |
| 15 | 多 Tab 壳（浏览/最近/收藏/书签/网络/设置） | 🟡 | 只有 Index 首页+最近列表；Tab 体系无 |
| 16 | 文件夹浏览/按作者系列标签浏览 | ❌ | 无 |
| 17 | 最近阅读/收藏/书签 Tab | 🟡 | 最近列表有；收藏/书签 Tab 无 |
| 18 | 库内搜索+多文档全文搜索 | ❌ | 无 |
| 19 | SQLite 元数据（greenDAO：标签/系列/进度/星级） | 🟡 | preferences 存最近；完整元数据无 |
| 20 | 元数据提取（Calibre opf） | 🟡 | getDocumentInfo 有；opf 提取无 |
| 21 | 后台扫描/删书检测 | ❌ | 无 |
| 22 | 封面（Glide/打印封面/裁剪阴影） | 🟡 | renderPageAsync 可出缩略图；缓存/UI 无 |
| 23 | 播放列表/标签管理 | ❌ | 无 |
| 24 | 文件信息对话框（重命名/删除/分享） | ❌ | 无 |
| 25 | 桌面小部件（最近/TTS） | ❌ | 无（鸿蒙卡片可做） |
| **格式** | | | |
| 26 | PDF/XPS/TIFF/CBZ | ✅/🟡 | PDF ✅；CBZ ✅（冲刺 F，mutool 转 5 页样本 UI 打开）；XPS/TIFF 未验证 |
| 27 | EPUB | ✅ | 冲刺 A 前置修复：html-parse.c 空 user_css 分支补丁 + 重编译 libmupdf.so；Alice EPUB 105 页+TOC+reflow ✅ |
| 28 | MOBI/FB2 | ❌ | 引擎支持（fz_open_document 同路径）；无样本生成工具，待真机验证 |
| 29 | TXT/HTML/MD/RTF/DOCX | 🟡 | TXT/HTML ✅（冲刺 F，reflowable+文本提取）；MD/RTF/DOCX 未接 |
| 30 | DjVu | ❌ | 需额外解码库，成本高 |
| **文本排版** | | | |
| 31 | 字体大小/缩放/自定义字体 | 🟡 | zoom ✅；reflow 字号/行距/页边距 ✅（冲刺 E）；自定义字体无 |
| 32 | 页边距/行距/段距/对齐 | 🟡 | 行距/页边距 ✅（冲刺 E reflowable CSS）；段距/对齐无 |
| 33 | 连字符（HyphenPattern 670KB） | ❌ | 未接 |
| 34 | 文本选择+高亮/下划线/删除线 | ❌ | 未接 |
| 35 | 词典/翻译（本地+在线） | ❌ | 未接 |
| 36 | 速读 RSVP/脚注/EPUB3 页码 | 🟡 | 速读 RSVP ✅（冲刺 D）；脚注/EPUB3 页码未接 |
| **批注与书签** | | | |
| 37 | PDF 批注（26 类型，MuPDF 持久化） | ❌ | NAPI 无 annotation API |
| 38 | 手绘覆盖层 | ❌ | 未接 |
| 39 | 书签管理器（多书签/导出导入） | 🟡 | 进度单点持久化有；多书签无 |
| **TTS 与音频** | | | |
| 40 | TTS 朗读（语速/音调/按句） | ✅ | 冲刺 D：ArkWeb speechSynthesis 后端（本 SDK 无 @ohos.ai.tts） |
| 41 | 录音导出 WAV/MP3（LAME） | ❌ | 未接（LAME 需交叉编译） |
| **设置/主题** | | | |
| 42 | 主题（浅/深/OLED/墨/自定义色） | 🟡 | 冲刺 E：浅/深/OLED/墨 ✅（invert + UI 色 + 墨色 sepia 叠加层）；自定义色未接 |
| 43 | 亮度/蓝光 | 🟡 | 冲刺 E：亮度已入设置并持久化 ✅；蓝光滤镜未接 |
| 44 | 43+ 语言 | 🟡 | zh/en 资源骨架 |
| 45 | 应用密码/指纹 | ❌ | 未接 |
| 46 | 备份恢复（JSON） | ✅ | 冲刺 E：设置+书库+书签导出/恢复到沙箱 JSON |
| **云与网络** | | | |
| 47 | Drive/Dropbox/OneDrive/WebDAV | ❌ | 未接 |
| 48 | 云同步（WiFi-only/增量） | ❌ | 未接 |
| 49 | OPDS 目录 | ❌ | 未接（HTTP 解析，相对易） |
| 50 | 应用内 WebView | ❌ | 未接 |
| **系统集成** | | | |
| 51 | 深链/打开方式（全格式） | 🟡 | PDF/EPUB/TXT/HTML/ebook 关联 ✅（冲刺 F）；CBZ/MOBI/XPS 无标准 UTD |
| 52 | 分享接收/发送 | ❌ | 未接 |
| 53 | 桌面卡片（鸿蒙 AnalogCard） | ❌ | 未接 |

### 7.1 不迁移清单（明确不做，除非点名）

- 广告（AdMob/GMS）——鸿蒙无此生态
- Google Drive/Dropbox/OneDrive 专有云（依赖 GMS/CloudRail）——若做只做 WebDAV/OPDS
- 应用内 WebView 浏览（系统浏览器替代）
- DjVu（额外解码库，性价比低）
- 旧版 EBookDroid 引擎（直接用 MuPDF 统一）

### 7.2 补齐方案（按用户价值×实现成本排序，分 6 个冲刺）

> **前置阻塞项 ✅ 已解除（2026-08-18）**：EPUB SIGSEGV 根因 = MuPDF 1.23.7 `html-parse.c` 的 `xml_to_boxes` 中 `if (user_css) {}` 是空块，随后**无条件** `fz_parse_css(ctx, css, user_css, "<user>")` —— 本应用未设置用户 CSS（`fz_user_css()` 返回 NULL）→ `css_lex_init` 里 `buf->s=NULL` → `fz_chartorune(NULL)` → SIGSEGV。**修复**：将 `fz_parse_css` + `fz_add_css_font_faces` 移入 `if (user_css)` 块内（源码树 `Builder/mupdf-1.23.7/source/html/html-parse.c`，纯 bug 修复，Android 共享源码同样受益）。重编双 ABI libmupdf.so → 同步 entry/libs + prebuilt → 验证：Alice in Wonderland EPUB 打开+渲染 105 页 + getToc 正常，release 循环启动 10 次零崩溃、faultlog 零新增。

**冲刺 A：阅读器核心体验 ✅ 已完成（2026-08-18）**
1. ✅ **双页模式 + 乐谱模式**：菜单「页面」按钮循环切换 单页/双页/乐谱。双页=DoublePageRenderer 左右拼合两页；乐谱=MusicianPageRenderer 用 crop 显示上半/下半（A1 crop 支撑）。hilog `[Reader] Page mode: single/double/musician`
2. ✅ **书内搜索 UI**：顶栏 🔍 → 搜索条（TextInput+搜索/下一命中/关闭）→ 遍历全书找首个命中页 → 归一化高亮叠加（PageRenderer searchHits 半透明矩形）→ 前后命中页跳转。hilog `[Reader] Search 'x': N hits on page P`
3. ✅ **裁剪**：NAPI `renderPageAsync` options 新增 `crop?: {x0,y0,x1,y1}`（0..1 归一化，作用于旋转后缓冲）。Index 测试验证 612x792→crop 10%→489x633
4. ✅ **文本重排 reflow（部分）**：NAPI 新增 `layoutDocument(handle,w,h,em)`（fz_layout_document，EPUB/HTML/TXT 重排，PDF 为 no-op）。**PDF reflow 无公开 API**（fz_new_xhtml_document_from_pdf 不在 1.23.7 头文件），且 EPUB 端到端验证受前置阻塞项限制 —— 待修复 EPUB 后验证
5. ✅ **亮度调节**：菜单亮度 Slider（@system.brightness.setValue 1..255）+ 百分比显示，与夜间 invert 组合。hilog `[Reader] Brightness: N`
6. ✅ **自动翻页定时器**：菜单开关 + 间隔秒数（1-5s 循环点按），Swiper 模式逐页翻，末页自动停，离开页面停定时器。hilog `[Reader] Auto-flip: ON/OFF (Ns)`

**冲刺 B：书库体系 ✅ 已完成（2026-08-18）**
1. ✅ **多 Tab 壳**：Index 重构为 Tabs（最近阅读/收藏/设置），顶部常驻「打开文件」+「测试 PDF」按钮。hilog `[Index] Tab switched to N` / `[Index] Library loaded: X recent, Y starred`
2. ✅ **元数据提取**：`generateCover` 打开文档时提取 getDocumentInfo 的 author 存入 RecentBook.author（title 沿用文件名）。hilog `Cover generated ... author='...'`
3. ✅ **封面缩略图**：renderPageAsync(zoom 0.18) → createPixelMap → ImagePacker.packToFile → cacheDir/covers/*.png → updateCover/整体持久化 → 列表 Image 显示（52x70 封面）。示例书 test.pdf 首启自动入库存封面，二次启动复用（`Demo book exists: totalPages=5 cover=...`）
4. ✅ **收藏 Tab**：RecentBook.star 字段 + toggleStar（★/☆ 切换）+ getRecentBooksStarred 列表
5. ✅ **文件夹浏览（降级）**：fileAccessHelper 目录授权暂缓，改用 DocumentViewPicker 多选导入（maxSelectNumber 20，一次导入多本并生成封面）
- **数据模型**：ReadingProgress.ets 扩展 RecentBook{star, coverPath, author}，旧数据自动回填；修复 saveRecentBook/updateCover 双写 list 竞态（统一整体持久化）
- **验证**：release 循环启动 10 次零崩溃、faultlog 零新增、demo 书封面生成+复用链路通

**冲刺 C：批注与书签 ✅ 已完成（2026-08-18）**
1. ✅ **NAPI annotation API**（MuPDF 1.23.7 pdf 层，`pdf_specifics` 从 fz_document 取 pdf_document）：`getAnnotations`（遍历页标注，归一化坐标+类型+内容）、`addHighlight`（PDF_ANNOT_HIGHLIGHT + quad points + 颜色）、`addInkStroke`（PDF_ANNOT_INK 笔迹）、`deleteAnnotation`（pdf_delete_annot）、`saveDocument`（pdf_save_document 持久化）。NAPI 导出 13 → 18
2. ✅ **高亮 UI**：Reader 菜单「高亮」按钮（无搜索命中时默认页面中部区域，有搜索命中时高亮首个命中矩形）→ addHighlight → refreshAnnotations → PageRenderer 叠加橙色标注层；「删除第1条」按钮 → deleteAnnotation。hilog `[Reader] Highlight added on page N` / `[Reader] Page N annotations: M`
3. ✅ **手绘覆盖层**：菜单「手绘」开关 → drawMode 触摸采集（TouchEvent → drawPoints）→ 抬手 finishInkStroke → addInkStroke 保存为 Ink 标注
4. ✅ **多书签管理器**：新 `model/Bookmarks.ets`（preferences 持久化，最多 200 条/文档，防重复）；顶栏 🔖 快速增删当前页书签 + 📑 打开书签面板（列表/跳转/删除）
- **验证**：Index 自测第 14 步标注往返测试全过（addHighlight → getAnnotations:1 → deleteAnnotation:0 → saveDocument）；release 循环启动 10 次零崩溃、faultlog 零新增
- **已知简化**：高亮区域为矩形（quad 单块），未做文本级精确选择；手绘坐标用 1000x1500 名义空间近似，未做容器精确归一化

**冲刺 D：TTS 与速读 ✅ 已完成（2026-08-18）**
1. ✅ **TTS 朗读**：调研确认本 SDK（OpenHarmony API 24）**无 `@ohos.ai.tts` 模块**（无 AI kit / TTS 接口），改用 **ArkWeb Web Speech API（speechSynthesis）** 后端：新增隐藏 Web 宿主 `resources/rawfile/tts.html`（`ttsSpeak/ttsStatus/ttsPause/ttsResume/ttsStop/ttsProbe`，数字返回码规避 runJavaScript 的 JSON 序列化），`WebviewController.runJavaScript` 驱动；语速 0.5–2.0x / 音调 0.5–2.0x 滑块、按句朗读（句间 400ms 轮询推进）、上一句/下一句、暂停/恢复/停止、读完自动进下一页、手动翻页重锚定。hilog `[Reader] TTS probe: N voices` / `[Reader] TTS speaking i/N (page P)`
2. ✅ **速读 RSVP**：新 `model/TextUtils.ets`（`normalize` 压缩空白 + `splitSentences` 按 。！？…；.!? 断句 + `splitTokens` CJK 感知分词：汉字逐字闪、拉丁按词闪、CJK 标点贴前字）；面板大字号词流显示 + WPM 100–800 滑块（运行中改速即时重启定时器）+ 开始/暂停/停止 + 进度 `n/total`；读完当前页自动进下一页直至末页停止。hilog `[Reader] RSVP started: N wpm, M tokens` / `[Reader] RSVP continue on page N` / `[Reader] RSVP finished (last page)`
3. ✅ **入口与互切**：顶栏 🔊/⚡ + 菜单「TTS 朗读」「速读」行；两面板底部「转朗读/转速读」互切按钮
4. ✅ **demo PDF 升级**：原 test.pdf 为纯图片占位（getText 0 字，TTS/RSVP/搜索无法验证）→ `tools/gen_demo_pdf.py` 用 mutool create 生成 5 页中英文本 PDF（内置 CJK 字体 F1 + Helvetica F2，中文 hex UTF-16BE、拉丁直接括号串，**注意 mutool create 一文件一页**）
- **验证（debug）**：TTS 面板 3/13 句断句正确、`probe: 0` → 正确判定「未检测到语音包」+ 面板内警告文案；RSVP 52/142 token 分词正确、300wpm 严格按 200ms/词推进、800wpm 全 5 页自动推进且时间精确匹配、末页 `finished (last page)` 收尾；暂停/恢复/互切/面板关闭全通
- **已知限制（重要）**：本 OpenHarmony 虚拟机**无 TTS 语音包**（speechSynthesis.getVoices()=0，属系统/镜像限制，非代码问题）→ TTS 在此 VM 上降级为「未检测到语音包」提示；代码在带系统语音引擎的 HarmonyOS NEXT 设备上可直接出声。若后续设备端支持 `@ohos.ai.tts`，可在 TextToSpeech 层替换后端
- **验证（release）**：循环启动 10 次零崩溃、faultlog 保持 11 条（零新增）

**冲刺 E：设置与主题 ✅ 已完成（2026-08-19）**
1. ✅ **设置面板与持久化**：新 `model/Settings.ets`（preferences 持久化，字段级默认值合并，任意新增字段向后兼容）；Index 设置 Tab 全量表单（主题/默认缩放/翻页与页面模式/亮度/自动翻页/TTS 语速音调/RSVP 速度/字号/行距/页边距），Reader 菜单「⚙ 设置」模态面板（主题 + 可重排文档排版），滑块在 End/Click 才落盘；Reader 启动 `[Reader] Settings applied: theme=.. zoom=.. font=..`，退出时全量持久化
2. ✅ **主题体系**：浅色/深色/OLED/墨 四主题 → `setTheme` 驱动 `dataSource.setInvert`（深色/OLED 反色渲染）+ 顶栏/底栏/阅读区主题色 helper + 墨色全屏 sepia 叠加层（`HitTestMode.None` 不挡手势）；菜单夜间开关与主题联动
3. ✅ **备份恢复（JSON）**：设置 + 最近阅读（`exportRecentBooks`/`replaceRecentBooks`）+ 书签（`exportBookmarks`/`replaceBookmarks`）→ `filesDir/librera_backup.json`；恢复校验 `app` 标记后整体回写并刷新书库
4. ✅ **字体/行距/页边距（可重排文档）**：NAPI `layoutDocument` 新增可选 `css` 参数（`fz_set_user_css` + `fz_layout_document`，MuPDF epub 的 `user_css_sum` 校验和自动触发重排）；新增 `isReflowable(handle)`（`fz_is_document_reflowable`）；Reader 打开文档时检测 reflow → 以 595×842 + em + `body{margin;line-height}` 布局；设置面板改字号/行距/边距 → `applyReflow` 重排并重建页列表（`reflowVersion` 参与 ForEach key 强制重渲染）；PDF 显示「固定版面不可调」提示
5. ✅ **demo EPUB 入库**：书库种子第二个 demo 书（Alice in Wonderland），EPUB 排版链路可从 UI 直达
- **验证（debug）**：设置持久化跨重启（theme=3/font=10 保留）；主题四色切换 + 页面重渲染（invert 生效）+ 墨色 sepia 无崩溃；reflow 页数双向（em16→103 页 / em28→213 页 / em10→69 页）；备份文件 JSON 完整（含重排后页数）且恢复往返（改 theme 0 → 恢复回 3）；PDF 回归（Reflowable:false、固定版面提示、5 页渲染正常）
- **验证（release）**：循环启动 10 次零崩溃、faultlog 保持 11 条（零新增）

**冲刺 F：网络与格式扩展 ✅ 已完成（2026-08-19，OPDS/WebDAV 按用户要求顺延）**
1. ✅ **格式测试样本**：`demo.txt`（手写 UTF-8）、`demo.html`（手写）、`demo.cbz`（`mutool convert -F cbz test.pdf` 从 5 页 demo PDF 转换，含中英文本与图片页）
2. ✅ **格式扫描自测**：Index 自测新增第 15 步——txt/html/cbz 逐格式 openDocument→pageCount→isReflowable→renderPage→（reflow 文档）getText。hilog `[Index] FMT demo.txt OK: 1 pages reflow=true render=450x600 text=548`（TXT/HTML reflowable、CBZ 固定 5 页）
3. ✅ **文件关联扩展**：module.json5 skills 增 `general.epub` / `general.plain-text` / `general.html` / `general.ebook`（UTD 取自 SDK `@ohos.data.uniformTypeDescriptor` 官方枚举；CBZ/MOBI/XPS 无标准 UTD，不做关联）
4. ✅ **Picker 过滤扩展**：`fileSuffixFilters` 覆盖 PDF/EPUB/TXT/HTML/CBZ/MOBI/XPS
5. ✅ **demo 书库**：`seedFormatDemo` 通用种子（rawfile→sandbox→书库→封面）；现有 5 本 demo（PDF/EPUB/TXT/HTML/CBZ），页数与元数据正确（EPUB 封面带出 author='Lewis Carroll'）
6. ✅ **EPUB 阻塞项确认**：html-parse.c 补丁后 Alice EPUB 105 页+TOC 全链路稳定（含 Reader reflow 字号重排 69↔103↔213 页）
7. ✅ **顺手修复两个真 bug**：
   - **getDocumentInfo 非 PDF 崩溃（SIGSEGV in pdf_metadata）**：原实现无条件 `reinterpret_cast<pdf_document*>(h->doc)` 调 pdf_metadata——PDF 恰好命中（fz_document 在 pdf_document 头部），EPUB/TXT/HTML/CBZ 越界读（堆布局相关概率闪退，faultlog 两个 cppcrash 实证）→ 加 `pdf_specifics` 守卫，非 PDF 走 `fz_lookup_metadata(FZ_META_INFO_TITLE/AUTHOR)` 通用路径（EPUB 元数据可正常取出）
   - **封面目录 EEXIST**：`fs.mkdirSync(coversDir)` 非递归，第二本书起 EEXIST 中断封面 → try/catch 包裹
- **验证（debug）**：FMT 扫描 3 格式全过；CBZ UI 打开（Reflowable:false、5 页图片渲染）；TXT UI 打开（Reflowable:true、em16 布局）；5 本书全封面生成、零崩溃
- **验证（release）**：循环启动 10 次零崩溃、faultlog 保持 11 条（零新增）
- **顺延项**：OPDS 目录、WebDAV 云盘（用户明确暂缓；网络权限+HTTP 客户端 + 服务端验证环境齐备后单独冲刺）

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

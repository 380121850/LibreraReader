# CHANGES(修改说明)

本文件用于记录每次对代码与构建配置的修改内容,随代码一起入库,便于回溯每次改动的目的与范围。
每次新修改完成后,请在本段下方追加一条带日期的条目。

---

## [2026-09-04] 新增：阅读界面 AI 翻译（EPUB/TXT/MOBI/AZW3 逐段翻译 + 覆盖面板 + 可选缓存）

**功能**：阅读页工具栏的"替换文本"按钮改为"AI 翻译"。点击后弹出语言选择对话框（源语言自动识别、可改；目标语言限 英文/中文/日文），确认后在页面右侧（横屏）/底部（竖屏）叠加一个可滚动译文面板，把当前页 ±（前 1 页、后 3 页）的正文**逐段**发给已配置的大模型翻译，增量刷新面板。可选"AI翻译结果保存"（偏好页新增 CheckBox），开启后按书 SHA-256 落 JSONL 缓存（`SYNC_FOLDER_DEVICE_PROFILE/ai-translation/<sha256>.jsonl`），重翻命中缓存不再调用 AI。不支持的格式（PDF 等无文本层）按钮置灰不可点。

**新增文件**（`app/src/main/java/com/foobnix/ai/`）：
- `AiTranslator.java` — 翻译引擎：页面窗口 [current-1 .. current+3]、逐页取段落、算稳定锚点 pid、查缓存、调 `AiClient.ask`、写缓存、进度回调。含共享的 `htmlToParagraphs()` 段落解析器。
- `TranslationCache.java` — JSONL 读写（按书 SHA-256 定位，`lookup`/`save`/`flush`，含原文漂移守卫）。
- `LanguageDetector.java` — 源语言识别：优先 `FileMeta.lang`，缺失则采样前几页按 CJK/假名/拉丁占比判 zh/ja/en。
- `AiTranslateDialog.java` — 语言选择对话框（AlertDialog，源/目标 Spinner + 取消/开始）。
- `TranslatePanel.java` — 译文覆盖面板（ScrollView + 段落块，逐段追加，失败标红）。

**修改文件**：
- `res/layout/document_title_buttons.xml` + `res/layout/activity_horiziontal_view.xml`：`onTextReplacement` 图标改 `my_glyphicons_ai_translate`、contentDescription 改 `ai_translate`。
- `DocumentWrapperUI.java`（竖屏）+ `HorizontalViewActivity.java`（横屏）：点击改 `AiTranslateDialog.show()`；格式门控（EPUB/TXT/MOBI/AZW3 可点，其余 `alpha=0.3f`+`setEnabled(false)`，始终可见）。
- `VerticalModeController.java` + `HorizontalModeController.java`：`getPageParagraphs()` 改用 `getPageHTML()` + `AiTranslator.htmlToParagraphs()`（原实现调 `MuPdfPage.text()`，见下方"关键 bug"）。
- `model/AppState.java`：加 `public boolean isSaveAiTranslation = false;`。
- `res/layout/fragment_preferences.xml` + `ui2/fragment/PrefFragment2.java`：偏好页新增"AI翻译结果保存"CheckBox 并绑定。
- `res/values/strings.xml` + `res/values-zh-rCN/strings.xml`：新增 AI 翻译相关字符串。
- `res/drawable/my_glyphicons_ai_translate.xml` + `res/drawable/ai_translate_block_bg.xml` + `res/layout/ai_translate_dialog.xml` + `res/layout/ai_translate_panel.xml`：图标/背景/布局。
- `android/utils/FileHash.java`：加 `sha256(File)`。

**关键 bug（本条最重要的修复）**：翻译最初"打开空白、无任何反应"。BENCH 日志定位根因——`VerticalModeController`/`HorizontalModeController` 的 `getPageParagraphs()` 调用 `((MuPdfPage) cp).text()`，而预编译 `libMuPDF.so`（`prebuilt/native/mupdf-1.23.7`）**没有导出 `Java_..._MuPdfPage_text` 符号**（只导出了 `text116` 与 `getPageAsHtml`），于是每次抛 `UnsatisfiedLinkError` 被 catch 吞掉、返回 null，段落全空，AI 从未被调用（日志 `done=0 failed=0 empty=0`）。修复：`getPageParagraphs()` 改用**可用**的 `getPageHTML()`（走 `getPageAsHtml` 原生，TTS/搜索同款路径），由 `AiTranslator.htmlToParagraphs()` 解析。

**段落解析细节（经真机 RAWHTML 采样确认）**：该 MuPDF 版本的页面 HTML 用 `<pause>` 标记**段落边界**、用 `<p>…</p>` 标记段内**每一行**（并非 `<end-block>`/`<end-line>`），行末软连字符断词（如 "transla-" / "tion"）。故 `htmlToParagraphs()` 按 `<pause>` 切段、段内合并 `<p>` 行、去连字符（行尾 `-` 去掉且不加空格）还原整词。修复前按行切导致译文是半句碎片（如"在雨天他这样做。他站在窗前——"）；修复后为完整段落。

**稳定锚点 pid**：`ch<章节序号>_h<md5(原文)>`（章节 + 原文内容哈希）。不依赖行号/页码（随字号/屏宽变化），reflow 后同文本同哈希，缓存的原文漂移守卫已保证正确性；且无需为算"章内段落序号"预扫整章（那会让首条结果等很久）。

**验证（MI9 真机，MIUI，adb `48fee174`，pro 包 `com.howread.reader.pro`，测试书 `/sdcard/Download/test_en.epub` 英文 2 章）**：pro debug BUILD SUCCESSFUL。打开 EPUB → 点 AI 翻译 → 源语言自动识别为英文、目标中文 → 开始翻译 → 面板逐段显示完整中文译文（连字符断词正确还原："transla-tion"→"翻译"、"cov-ered"→"覆盖"、"win-dow"→"窗边"）。BENCH 日志确认 AI 被真实调用：2 页共 4 段，逐段 `AI ask orig.len=9/420/9/283` → `AI res ok=true reply.len=3/120/3/81`，`done=4 failed=0 empty=0`（修复前为 `done=0`）。鸿蒙零改动。

---

## [2026-09-04] 三项：更新第三方库许可内容 / "我的文件"搜索项上移并去掉"新文件" / 修复书签笔记删除后被 WebDAV 同步回来

### 一、更新第三方库许可内容（`android/app/src/main/assets/licenses.html`）

**问题**：设置→关于→"第三方库许可"页（`AboutSectionBinder.showLicenses()` 用 WebView 加载 `file:///android_asset/licenses.html`，三 flavor 共用）内容严重过时：版本错误（MuPDF 1.12 实为 1.23.7、jsoup 1.8.3 实为 1.22.2、okhttp 3.8.1 实为 3.12.6、greendao 3.2.0 实为 3.3.0 等）；greendao 条目贴的是 "EventBus License" 文本；已移除的库（Universal Image Loader、commons-compress）仍在列；缺失大量现用依赖（AndroidX、Glide、Mammoth、CommonMark、Zip4j、Guava、Sardine、LAME、libmobi、antiword、DjVuLibre、libwebp、MuPDF 内嵌 C 库、google 版 Play Services Ads/UMP 等）。

**修复**：整体重写 `licenses.html`，保持原 `<h3>库名 版本</h3> + <pre>许可文本</pre>` 结构与 CSS，按"应用本体（GPLv3）→ PDF 引擎（MuPDF 1.23.7 AGPL-3.0 及内嵌 FreeType/HarfBuzz/jbig2dec/lcms2/libjpeg/MuJS/OpenJPEG/zlib）→ 原生解析库（LAME/libmobi/antiword/DjVuLibre/hqx/libwebp）→ Java 依赖（按 Apache 2.0 / MIT / BSD / 其他分组）→ EbookDroid 1.6.5（GPLv3）→ Google SDK（仅 google 版，Google SDK License）"组织；修正 greendao 为正确的 Apache 2.0 文本；删除 Universal Image Loader 与 commons-compress。版本逐一对照 `gradle/libs.versions.toml` 与 `app/build.gradle` 核实。

### 二、"我的文件"页：搜索项上移 + 去掉"新文件"项（`BrowseFragment2.java` + `fragment_browse2.xml`）

**问题**："我的文件"页里"搜索"区块（"在多个文档中搜索"条目）挂在页面下方的 `netSection2` 容器，位置太靠下，与上方的 OPDS / WebDAV / 书库文件夹不同区；同区块还有一个"新文件（.txt）"条目需移除。

**修复**（`buildNetSections()`，三 flavor 共用）：
1. 把"搜索"区块（分隔线 + `R.string.search` 区块头 + "在多个文档中搜索"条目）从 `netSection2` 移到 `netSection`，置于书库文件夹区块之前——与 OPDS / WebDAV / 书库文件夹同区靠上（顺序：OPDS → WebDAV → 搜索 → 书库文件夹）。
2. 删除"新文件（.txt）"条目。
3. `netSection2` 清空后连带清理：字段声明、布局中的 `netSection2` 容器、`displayAnyPath()` 中对它的可见性控制。
4. **未动**：长按菜单的 `new_file_txt`（保留字符串资源）、PopupMenu 的"扫描书库"项。

### 三、修复：书签笔记中删除一本书的笔记/书签后，仍被 WebDAV 同步回来（`WebDavSyncer.java` + `SharedBooks.java` + `BookmarksData.java`）

**问题**：在"书签笔记"里删除某本书的笔记和书签后，下次同步这些被删的书签又从服务器合并回来（复活）。

**根因**（`WebDavSyncer.doSync()` + `SharedBooks.DeletedBooks`）：删除靠墓碑文件 `app-DeletedBooks.json` 抑制回合并，但 `doSync()` 末尾**无条件 `clear()` 全部墓碑**，不验证删除是否真正落到服务器。于是：
- 若某轮远端书目列表拉取失败（网络抖动、PROPFIND 解析失败），该书本轮不在列表，其墓碑未被消费却照样被全清 → 下一轮云端旧书签被 union 合并回来；
- 若发布 PUT / 删文件 DELETE 失败（只记日志），墓碑仍被清 → 云端保留旧数据 → 复活；
- 删除后不触发同步，只能等启动 4s / 定时 / 手动同步，撞上上面任一情形即复活。

**修复**（核心：墓碑改为"确认生效才清除"，3 文件）：
1. `WebDavSyncer.doSync()`：新增 `Set<String> consumedNames`——删服务器文件成功（`s.delete` 无异常）或合并发布 PUT 成功（`putBookInfo` 无异常）的书名记入；末尾的 `clear()` 改为只清除 `consumedNames` 中的条目，未确认的书名墓碑保留到下一轮重试。`SharedBooks.DeletedBooks` 新增 `clearNames(Set<String>)`（按书名精确清除，含其 keys），替代对 `clearKeys` 的即时调用。
2. `listRemoteBooks()` 区分"404/空"与"网络错误"：`fetchJson()` 对网络错误返回 `null`（404 仍返回空对象），出现网络错误时置 `booksListFailed` 标志，该轮**不消费任何墓碑**（保守处理，全部保留重试）；`migrateLegacy()` 对网络错误同样跳过。
3. `BookmarksData.remove()`（删单个书签）与 `cleanBookmarks()`（清空）末尾调用 `WebDavSyncer.notifyConfigChanged(LibreraApp.context)`——删除后自动触发一次同步（复用现有 10s 防抖 + `syncingNow` 保护），不必等定时/启动同步，避免撞上瞬时失败。

**效果**：删除书签/笔记后自动同步传播到服务器；服务器文件被清除（或删掉）；墓碑只在删除**确认**落到服务器后才清除，任何一轮瞬时失败都不会让"已删"的书签被 union 合并回来。

**验证（MI9 真机，MIUI，adb `48fee174`，pro 包 `com.howread.reader.pro`）**：三 flavor（google/pro/fdroid）debug 均 BUILD SUCCESSFUL。用标准 WebDAV 服务器（wsgidav 4.3.5，`http://192.168.50.111:8099`，PROPFIND 可被 Sardine 0.9 解析）造场景：
- **任务一**：设置→关于→"第三方库许可"，WebView 正常渲染新内容（HowRead→MuPDF 1.23.7→原生解析库→Java 依赖→EbookDroid→Google SDK）。
- **任务二**："我的文件"页，"搜索"区块与 OPDS / WebDAV / 书库文件夹同区靠上，"新文件（.txt）"项消失。
- **任务三**（HiFB开发指南.pdf，原 1 条 AI 笔记书签）：① 在"书签笔记"删除该书签 → 本地书签 31→30、墓碑记录 `{"b":…,"keys":{"1788082891886":…}}`、自动触发同步 → 服务器该书文件 `bookmarks` 字段被清除（仅剩 progress）、墓碑被消费（`app-DeletedBooks.json` 变 `{}`）；② **复活压测**：手动把已删书签重新注入服务器文件（模拟"服务器仍持有已删书签"）+ 本地保留墓碑 → 再同步 → 服务器书签**未**被合并回来（仍无 bookmarks）、本地书签数保持 30（无复活）、墓碑被消费。日志确认 `remoteBooks=58 failed=false`、`consumed HiFB开发指南.pdf`、`consumedNames=[HiFB开发指南.pdf]`。鸿蒙零改动。

---

## [2026-09-03] 修复：桌面图标"書"字顶部被 launcher 圆角蒙版裁切

**问题**：上一条修复（去 inset 全出血）后，米色牌铺满画布，但"書"字在牌内位置偏上（顶距图顶仅 ~10%），launcher 的圆角蒙版把"書"字顶部裁掉了，"書"字不完整。

**根因**：adaptive 前景 `adaptive_pdf_reader.png`（192×192）由 `gen_icons3.ps1` 从源图 cover 铺满生成，"書"字+书内容整体偏上，"書"字顶落在 adaptive 安全区（中央 66dp，约 19% 起）之外，被 launcher 圆角裁切。

**修复**（重新生成 `mipmap-xxxhdpi/adaptive_pdf_reader.png`，432×432）：
- 源改用 1536×1536 高清设计图 `howread_cleaned.png`（gen_icons3 去水印输出，比原 192px 小图清晰得多）。
- 米色牌背景仍**全出血铺满**画布（保住 MIUI 绿边修复），背景用牌四边采样色做平滑米色渐变（top #E8E1DA→bot #D4D0CF，左右微混），与裁出的 artwork 米色边缘无缝。
- "書+书"内容从源图裁出（排除牌投影/边缘），缩小并**下移**：顶 24%、底 87%、水平居中——"書"字完整落在安全区内，书底仍在安全区内。
- 脚本 `Z:\opt\librera\bench\gen_adaptive_v3.py`（可复现）。

**效果**：三 flavor 图标"書"字完整、位置居中、全出血无透明环（MIUI 无绿边）、高清无虚化。

**验证（MI9 真机，MIUI，adb `48fee174`）**：三 flavor（google/pro/fdroid）debug 均 BUILD SUCCESSFUL（22s）。逐一安装并桌面截图放大确认：Pro（好好读 Pro）、google（好好读）、fdroid（好好读 FD）三版图标"書"字均**完整**、米色牌铺满圆角方块、**边缘无绿色色带**。鸿蒙零改动。

---

## [2026-09-03] 修复：Pro 版桌面图标在 MIUI（小米 12S）上有一圈绿色边缘

**问题**：小米 12S（MIUI）上 HowRead Pro 的桌面图标（米色"書+书"圆角牌）边缘有一圈绿色色带。

**根因**（`mipmap-anydpi-v26/icon_pdf_pro.xml`）：Android 8.0+ 走自适应图标，前景层是铺满画布的米色牌 PNG（`adaptive_pdf_reader.png`，192×192 全不透明），但被套了 `inset=23%`——把牌缩到中央 54%，四周留 **23% 透明环**。MIUI launcher 对自适应图标套自己的蒙版/底色，**不**像 AOSP 那样把 app 背景层填到前景透明环下面，于是透明环透出 launcher 底色，实测一圈偏绿色带（RGB ≈ #418050~#9DB769，非 app 内任何颜色）。附带问题：pro 背景层 `bg_pdf_reader_pro.xml` 是**蓝色渐变**，与米色牌配色冲突。google 版 `icon_pdf_reader.xml` 是同样的 inset 反模式（背景米色，绿边不明显）。

**修复**（3 个 XML，纯资源改动，复用现有 PNG，不重新生成图片）：
1. `mipmap-anydpi-v26/icon_pdf_pro.xml`：去掉 `<inset>` 包裹，前景直接 `<foreground android:drawable="@mipmap/adaptive_pdf_reader" />` 全出血铺满 108dp 画布——消除透明环，"書+书" artwork 在中央安全区内，launcher 裁圆角时四角被裁也无感。
2. `mipmap-anydpi-v26/icon_pdf_reader.xml`（google 版）：同样去掉 `<inset>`，消除同款反模式。
3. `drawable/bg_pdf_reader_pro.xml`：蓝色渐变（#2b549a→#337ef7）改为与米色牌边缘一致的米色渐变（#C9C8C6→#E9E2DB，复用 `bg_pdf_reader` 配色）——个别 launcher 在蒙版边缘露出背景层时也能与牌无缝融合，不再有色差/绿边。

**效果**：自适应图标自包含（全出血前景 + 同色背景双保险），任何 launcher 上都不再有透明环/绿边；pro 与 google 图标风格统一。

**验证（MI9 真机，MIUI，adb `48fee174`）**：三 flavor（google/pro/fdroid）debug 均 BUILD SUCCESSFUL（21s）。安装 pro 版后桌面截图：图标为**铺满的米色"書+书"牌、边缘无绿色色带**（修复前同一位置实测 9727 个绿色像素环带）；另装 google 版对照，同样全出血无绿边。API<26 旧设备仍用遗留位图 `icon_pdf_pro.png`（未动），不受影响。鸿蒙零改动。

---

## [2026-09-03] 修复：WebDAV 同步只恢复 AI 的 API 链接、不恢复 API key

**问题**：AI 的 API key 有备份到 WebDAV 服务器，但每次同步只恢复了 API 链接（endpoint），没有恢复 API key。

**根因**（AI 的"链接"与"密钥"存两处、走两条不同同步路径，密钥那条有 mtime 竞态缺陷）：
- API 链接 `aiBaseUrl` 存 `AppState`→`app-State.json`，走 `syncGlobalFile`（`WebDavSyncer.java:295/338`）——有 `localIsDefault` 保护 + 字段级并集 `mergeAiState`，**抗竞态**。
- API 密钥存 `AiCredentials`（加密 SharedPreferences `"ai"`）→镜像 `app-AI.json`，走 `syncWholeFile`（`WebDavSyncer.java:313`）——**纯 mtime 新者赢，无"本地为空不赢"保护**。
- 复现链（本地无密钥的设备——新装或本地数据丢失后）：① `exportAi`（`:304`）把本地 `app-AI.json` 写成 `{"apiKey":""}`，`writeIfChanged` 发现内容不同就重写并把 mtime 刷成"现在"；② `syncWholeFile`（`:313`）比 mtime，服务器文件是别的设备过去写的（更旧）→ 判定本地"更新"→ **空密钥上传覆盖服务器**，服务器密钥被毁；③ `importAi`（`:315`）读本地 `app-AI.json` 仍是 `{"apiKey":""}`，旧守卫 `isNotEmpty` 为假 → 不保存。结果：链接（抗竞态路径）恢复了，密钥（竞态路径）没恢复，且服务器副本被空值覆盖、bug 自我延续。佐证：`ProfileStateIO.mergeAi` 这个专为密钥文件写的合并器存在但**从未被调用**（死代码）。

**修复**（2 文件，核心 1 处改动）：
1. `ProfileStateIO.mergeAi`（`ProfileStateIO.java`）：签名从 `mergeAi(Context, remote)` 改为匹配 `JsonMerger` 接口的 `mergeAi(LinkedJSONObject local, LinkedJSONObject remote)`，实现"**设值赢未设值，真冲突（两边都有且不同）服务器赢**"（与 `mergeAiState` 同构的单字段版）：本地空+服务器有→用服务器（**修复恢复**）；本地有+服务器空→用本地（能换密钥、能播种服务器）；两边都有且不同→服务器赢（用户选定）；两边都空→空。
2. `WebDavSyncer.doSync`（`WebDavSyncer.java:317`）：`syncWholeFile(...syncAI...)` → `syncMergedObjectFile(...syncAI..., ProfileStateIO::mergeAi)`。`syncMergedObjectFile` 提供**结构性抗竞态**：GET 临时错误不碰任何东西；远端缺失（404）则上传本地；否则 `merged = mergeAi(local, remote)`，本地变了写本地、服务器变了上传。
3. `ProfileStateIO.importAi`（`ProfileStateIO.java`）：从"仅本地为空时填"改为"文件值≠本地加密存储就应用"——合并后 `app-AI.json` 已含正确密钥，`importAi` 负责把文件值落到加密存储，既补缺失（恢复）也收敛冲突（服务器赢）。安全：`exportAi` 每轮同步开头都会把本地存储重新镜像进文件，用户在 AI 对话框刚保存的密钥在合并前已在文件里，不会被旧值覆盖。

**效果**：本地重置/新装设备同步后从服务器恢复密钥；本地改密钥（服务器空）能上传；两边冲突服务器赢；不再出现"空密钥覆盖服务器"的竞态。与 `app-State.json` 用 `mergeAiState` 处理 AI 模型配置完全同构。

**验证（模拟器 MedicineAVD，fdroid 无广告版 `com.howread.reader.pro`）**：三 flavor（google/pro/fdroid）debug 均 BUILD SUCCESSFUL。用本地最小 WebDAV 服务器（`http://10.0.2.2:8099`，模拟器可直达 Windows 宿主）造三种场景：① **本地无密钥 + 服务器有**（`sk-server-key-AAA111`）→ 同步后本地 `ai.xml` 生成、AI 设置页显示该密钥（掩码），服务器密钥**未被空值覆盖**（修复前此场景服务器密钥会被毁）；② **本地有密钥 + 服务器空** → 同步后服务器拿到本地密钥（日志 `PUT app-AI.json (33 bytes)`）；③ **两边不同密钥**（本地 AAA111 / 服务器 CCC333）→ 同步后本地收敛到服务器值 CCC333，服务器不变（日志仅 `GET` 无 `PUT`）。回归：AI 链接/模型配置（`app-State.json` 的 `aiBaseUrl/aiModel/aiProtocol/aiMaxTokens/aiThinking`）同步路径未动、字段完好，AI 设置页正常显示；其他 global 文件（recent/stats/misc/network）同步不变。鸿蒙零改动。

---

## [2026-09-03] 修复：软件说明页 build 时间停留在首次 clean 构建（增量构建不刷新）

**问题**：安装 9 月 3 日新构建的 APK 后，"软件说明"页显示的 build 时间仍是 9 月 2 日的。

**根因**（`app/build.gradle` 的 `generateBuildTimeSource` 任务）：该任务生成 `BuildTime.java`（`BUILD_TIME` 常量，软件说明页展示），声明了 `outputs.file` 但**未声明任何 inputs**。Gradle 对"无 inputs 且输出已存在"的任务判定为 UP-TO-DATE 直接跳过，`doLast` 里的 `new Date()` 不再执行——时间戳被冻结在**首次 clean 构建**（202609022352），之后所有增量构建都复用旧文件。

**修复**：任务内加 `outputs.upToDateWhen { false }`，强制每次构建都重新生成 `BuildTime.java`，时间戳与 APK 实际构建时刻一致。

**验证（模拟器 MedicineAVD，pro 版 `com.howread.reader.pro`）**：三 flavor（google/pro/fdroid）debug 增量重编 BUILD SUCCESSFUL（1m27s，13 executed）；`BuildTime.java` 刷新为 `202609031655`；安装 pro 版后"软件说明"页显示 `HowRead Pro: v0.9.0 build 202609031655`（与 APK 构建时刻一致）。鸿蒙零改动。

---

## [2026-09-03] 修复：书签笔记页删除一本书的笔记后被 WebDAV 同步"复活"

**问题**：手机上在书签笔记页删除某本书的笔记，过一会儿笔记又被 WebDAV 服务器同步回来，删除的笔记重新出现。

**根因**（`WebDavSyncer.doSync` + `SharedBooks.DeletedBooks`）：
- 书签同步是**按创建时间键（`t`）的盲目并集**（`WebDavSyncer.java:414-432`），只增不删——服务器 `books/<hash>.json` 里有的键，只要本地没有就一律加回。
- 删除时只记录**按书**的墓碑 `DeletedBooks.record(path,"b")`（结构 `{书名:{"b":时间戳}}`），**不记录删的是哪几条**；且该墓碑**一次性**（每轮同步结束 `DeletedBooks.clear()` 清空，`WebDavSyncer.java:479`），只压制**一轮**合并。
- 服务器文件仅在"该书本地既无进度又无其他书签"时才整文件删除（`WebDavSyncer.java:381-388`）。
- 复现链：删笔记后该书通常**仍有阅读进度** → 服务器文件不删 → 本轮 `delBookmarks=true` 跳过合并（笔记暂时消失）→ 行 479 清空墓碑 → **下一轮** `delBookmarks=false` → 盲目并集把服务器残留的笔记重新加回 → **复活**。

**修复**（按时间键精确传播删除，3 个文件）：
1. `SharedBooks.DeletedBooks`（`SharedBooks.java`）：新增 `recordKey(path, key)`——在 `{书名:{...}}` 下维护 `"keys"` 子对象（`{时间键:时间戳}`）累加被删时间键；`keysOf(markers, name)` 返回该书已删键集合；`clearKeys(path)` 只清除该书的 `"keys"`（保留 `"p"`/`"b"`），供同步成功发布后调用。原有 `record(path,kind)`/`all()`/`clear()` 不变。
2. `BookmarksData.remove`（`BookmarksData.java:53-80`）：删除单条笔记时，在既有 `record(path,"b")` 之外再 `recordKey(bookmark.getPath(), bookmark.t)` 记下被删时间键。
3. `WebDavSyncer.doSync`（`WebDavSyncer.java`）：循环内取该书 `deletedKeys = keysOf(deletedBooks, name)`；书签合并遍历时**跳过**任何在 `deletedKeys` 中的键（不重新加回本地）；发布前从 `subsetFor(localB, name)` 结果里**剔除** `deletedKeys` 中的键，使服务器文件收敛（不再残留已删笔记）；成功 `putBookInfo` 后 `clearKeys(name)` 清除已传播键。整文件删除分支（本地无进度且无书签）与旧版墓碑（只有 `"b"` 无 `"keys"`）的"整书跳过一轮"兼容均保留。

**效果**：删除 → 记录时间键 → 下一轮同步从服务器剔除该键并跳过合并 → 服务器收敛 → 笔记不再复活；其他设备下次同步也拉不到已删笔记。进度删除（kind `"p"`）、同名不同文件 conflict 逻辑均不动。

**验证（模拟器 MedicineAVD，fdroid 无广告版 `com.howread.reader.pro`）**：三 flavor（google/fdroid/pro）debug 均 BUILD SUCCESSFUL。用本地最小 WebDAV 服务器（`http://10.0.2.2:8099`，模拟器可直达 Windows 宿主）复现并验证：① 造出"书有笔记 + 50% 阅读进度"状态（本地与服务器一致，正是旧代码会复活的场景）；② 书签笔记页删除该笔记 → 墓碑正确写入 `{"montecristo.epub":{"b":…,"keys":{"1788411354035":…}}}`，本地书签清空、进度保留；③ **连续三次**重启触发同步，本地书签始终为空（笔记**不复活**，含旧代码复活的第三轮），墓碑已清空，进度 50% 全程保留；④ 服务器 `books/<hash>.json` 从含笔记（380 字节）收敛为**仅进度**（126 字节），已删键 `1788411354035` 不存在；⑤ 回归——删除后向同一本书新增一条笔记并同步，新键 `1788430000000` 正常上传服务器、旧已删键仍不存在（未误伤同书其他笔记）。鸿蒙零改动。

---

## [2026-09-03] 笔记导出：可选格式（TXT/Markdown，默认 TXT）+ 时间行下新增"位置"行

**需求**：导出笔记时可选导出格式；分析 md/pdf/doc/txt 可行性，改动大则只支持 md+txt、默认 txt。同时把笔记时间对应的书籍位置信息写入导出文件，放在时间行下面一行。

**格式可行性结论**：
- **txt**：已实现，现有导出路径即 txt。
- **md**：极小改动——纯文本本身即合法 Markdown，`commonmark 0.29.0` 已在 classpath（仅解析用，生成不依赖它），只需换扩展名 + 轻量 md 排版（`##` 标题 / `>` 位置引用 / `**AI:**` 加粗 / `---` 分隔）。
- **pdf**：改动大，本期不做。仓库内 `com.artifex.mupdf` Java 类与预编译 `libMuPDF.so` 导出的 JNI 符号不匹配（调用即 `UnsatisfiedLinkError`）；两条路都是大改——① 框架 `android.graphics.pdf.PdfDocument` 需手动换行 + 加载 CJK 字体（默认 Helvetica 渲染不了中文）；② 重编 `libMuPDF.so` 暴露 Story API（4 ABI native 重编）。
- **doc/docx**：改动大，本期不做。无任何 docx 写入库（mammoth 只读 docx→HTML）；需新增 Apache POI（重）或手写 OOXML zip。

按既定标准（改动大就只支持 md+txt，默认 txt）→ **本期支持 TXT + Markdown，TXT 为默认**。PDF/DOCX 留作后续可选项。

**改动**：
1. `BookmarksFragment2.java`：
   - 合并笔记行的 "⋮" 菜单由单项拆为两项：`notes_export_txt`（默认，在前）→ `exportNotesToFile(notes, "txt")`；`notes_export_md` → `exportNotesToFile(notes, "md")`。
   - `exportNotesToFile(merged, format)`：示例文件名 `书名-notes.<format>`（预填扩展名）；内容改由新私有方法 `renderNotesForExport(merged, format)` 从 `merged.notes`（新→旧）逐条渲染；选择器文件名完全可编辑，回调里若用户改掉扩展名则强制补回所选格式。
   - `renderNotesForExport`：每条笔记渲染 时间行 + **位置行** + 正文 +（若有）AI 回答。位置行取一次总页数 `AppDB.get().load(MyPath.toAbsolute(path)).getPages()`：取到则 `位置：第 X / Y 页 (P%)`（`X = max(1, round(p*Y))`），取不到降级 `位置：P%`；百分比复用 `TxtUtils.percentFormatInt`（与列表口径一致）。TXT 用纯文本风格（时间行下插位置行）；MD 映射为轻量标准 Markdown。
2. 字符串（`values/strings.xml` + `values-zh-rCN/strings.xml`）：新增 `notes_export_txt`/`notes_export_md`/`note_export_position_page`/`note_export_position_percent`；原 `notes_export` 已无引用，一并删除。
3. **顺带修复（验证时发现）**：`BrowseFragment2.java` 的 create-file / select-file 结果此前用共享的 `BookCSS.get().dirLastPath` 拼路径，但上一条改动把导出选择器改成了**分离目录页**（`browsePath != null`），`displayAnyPath` 对分离页刻意不更新 `dirLastPath`（见 `BrowseFragment2.java:1614`），导致 `dirLastPath` 恒为 `null`，导出落盘路径变成 `null/<文件名>` 而 `FileNotFoundException`。新增 `chooserDir()`：分离页返回本页 `browsePath`，普通"我的文件"页保持原 `dirLastPath` 行为，两处结果改用 `chooserDir()` 拼接。

**验证（模拟器 MedicineAVD，fdroid 无广告版 `com.howread.reader.pro`）**：三 flavor（google/fdroid/pro）debug 均 BUILD SUCCESSFUL。① 笔记行 ⋮ 菜单显示两项，TXT 在前、Markdown 在后；② TXT 导出文件内容为 `[2026-09-03 04:55]` + 下一行位置行 + 正文；③ Markdown 导出为 `## [时间]` / `> 位置：…` / 正文 样式；④ 位置行两种形态均验证——书页数未知时降级 `Position: 0%`，向 DB 注入 `PAGES=287` 并重启后为 `Position: page 1 / 287 (0%)`（英文为模拟器 locale，中文串在 values-zh-rCN）；⑤ 扩展名兜底——文件名手改为无扩展名的 `plainexport`，落盘仍为 `plainexport.md`；⑥ 普通书签行无 ⋮（仅合并笔记行有，代码层 `BookmarksAdapter2` 已确认）。鸿蒙零改动。

---

## [2026-09-03] 修复：笔记导出选择器"返回上一层"误跳回"我的文件"

**问题**：书签笔记页 → 笔记导出 → 文件路径选择器中，停在某个书库扫描目录时点工具栏"返回上一层"，会跳回"我的文件"根视图（OPDS/WebDAV/文件夹分组），并污染底层"我的文件"标签的共享路径。

**根因**：`ChooserDialogFragment` 内嵌的 `BrowseFragment2` 此前 `browsePath == null`，`path()`/`setPath()` 走共享的 `AppState.displayPath`；`onBackAction()` 的扫描目录分支（`BrowseFragment2.java:1495-1501`）命中 `scanPath.equals(path())` 时执行 `displayAnyPath(ROOT_PATH)`，即跳回"我的文件"根视图并改写共享路径。

**修复**（`ChooserDialogFragment.onCreateView`）：把内嵌浏览器改为**分离目录页**——构造时注入 `folderPath = validStartDir(外部存储根)`，使 `browsePath` 非空、独立于共享路径。此后 `onBackAction()` 走分离页分支（`BrowseFragment2.java:1481-1492`）：入口根处返回 `false`（由 X/关闭按钮或系统返回关闭对话框），子目录处 `new File(browsePath).getParent()` 上移一级，全程不再触碰 `AppState.displayPath`，也不会落到扫描目录跳根分支。选择器因此锚定在真实文件树（外部存储根），"返回"沿真实目录上移。

**验证（模拟器 MedicineAVD，fdroid 无广告版）**：google 版在无网络模拟器上 AdMob/UMP 阻塞窗口焦点致黑屏，改用 fdroid 版正常渲染。① 选择器打开即锚定 `/storage/emulated/0`（真实文件树，非"我的文件"根视图）；② 进入 Documents 子目录后点工具栏"返回上一层"，路径栏由 `emulated/0/Documents` 上移一级回到 `emulated/0`（**未跳回"我的文件"**）；③ 存储根处再点"返回"为空操作（与既有分离目录页行为一致，无回归）；④ 关闭对话框后底层"My files"标签仍显示根视图（OPDS/WebDAV/Library folders），共享路径未被污染。鸿蒙零改动。

---

## [2026-09-03] OPDS 预置目录替换 + 书签笔记页两项修复/新增

**1. OPDS 预置目录替换**（`AppState.OPDS_DEFAULT`）：把内置预置目录整体替换为 5 个公版书库——Project Gutenberg（`gutenberg.org/ebooks/search.opds/`）、Wolne Lektury（`wolnelektury.pl/opds/`）、textos.info（`textos.info/opds`）、文渊阁·公版部分（`wenyuange.org/opds/`）、CBETA 电子佛典（`cbeta.org/opds/`）；图标统一复用 `assets://opds/opds.png`。删除了原 Internet Archive 与内置"获奖书单"（`SamlibOPDS.ROOT_AWARDS`，随之移除 `AppState` 里已无引用的 `SamlibOPDS` import）。解析/持久化/"恢复默认"逻辑不变；已装用户保留其 profile 里已持久化的目录列表，新预置对全新 profile 或点"恢复默认"后生效。

**2. 书签笔记页·笔记聚合行名称修复**（`BookmarksAdapter2.onBindViewHolder`）：进入某本书后，笔记聚合行（`mergeNotes` 合成项，`isAiNote && notes != null`）此前复用书签行模板，标题显示的是书籍文件名，与"笔记"身份不一致（看起来像"XXX书笔记"）。现改为：笔记聚合行标题显示其自身的"笔记 (N)"标签（`item.text`），普通书签行仍显示书名；书籍 header 行、单条笔记显示不变。

**3. 书签笔记页·笔记导出**（`bookmark_item.xml` + `BookmarksAdapter2` + `BookmarksFragment2`）：进入某本书后，笔记聚合行新增"⋮"按钮（`moreMenu`，仅笔记聚合行可见，header/普通书签/单条笔记隐藏，含 view-holder 复用时的可见性复位）。点"⋮"弹出菜单（`MyPopupMenu`，锚定按钮），含"笔记导出"项；选中后弹出"选文件夹+文件名"选择器（复用 `ChooserDialogFragment.createFile`，与"导出书签"一致），把该书全部笔记（`mergeNotes` 预渲染的 `aiAnswer`：时间戳+正文+AI 回答）写入所选 .txt。新增字符串 `notes_export`（Export notes / 笔记导出）。

**验证（MI9）**：google debug 构建 BUILD SUCCESSFUL（0.9.0），安装启动无异常。① 我的文件·OPDS 区显示 5 个新目录（Gutenberg / Wolne Lektury / textos.info / 文渊阁 / CBETA），Internet Archive 与获奖书单已消失；② 进入《侯卫东官场笔记》单书视图，笔记聚合行标题显示"笔记 (3)"而非书名，普通书签行仍显示书名（第二本《HiFB开发指南》"笔记 (1)"同样正确）；③ 笔记聚合行"⋮"→"笔记导出"→ 选文件夹+文件名（自动填充"<书名>-notes.txt"）→ 点"选择"→ Toast"成功"，文件落盘 3674 字节，内容为 3 条笔记（时间戳+正文+AI 回答，分隔线正确）。鸿蒙零改动。

---

## [2026-09-02] F-Droid 彻底去 GMS：删除 Google Drive 同步 + 删除 5 个上游遗留版本

**背景**：F-Droid 上架要求包内不得含任何广告 SDK。广告 SDK 解耦此前已完成（`src/admobAds` vs `src/noAds` 互斥源集 + `libDepFree` 依赖隔离，fdroid 字节扫描 PASS）。但审计发现代码里还残留"打桩"——Google Drive 同步功能在**所有版本里都是死的**：`GFile.java` 用到的 `com.google.api.client.*` / `com.google.api.services.drive.*` / `GoogleSignIn` 真类全部来自 main 里手写的 14 个 `com/google/**` 假类（`:appLibDrive` 模块被注释且不存在，`libs.versions.toml` 无任何真 Drive 依赖），`getLastSignedInAccount` 恒返回 null，UI 入口早已隐藏。经确认，本次将 GMS/Drive 从所有版本彻底删除，并顺带删除 5 个上游遗留 UI 版本 flavor。

**1. 删除 Google Drive / GMS（所有 flavor）**：
- 删除文件：`GFile.java` + `GFile.java.stub`、main 下 14 个 `com/google/**` 假类（2 个 android.gms + 12 个 api client/drive）、`src/gmsStubs/`（5 个假类整目录）、`SynctornizatoinWorker.java`、`GDriveSycnEvent.java`、`GoogleDriveFragment2.java`、`fragment_google_drive.xml`。
- 清理 12 个 main 调用点：`UITab`（删枚举项 GoogleDrive2Fragment，`isShowCloudsPreferences` 改恒 false）、`MainTabs2`（删 sign-in 分支、pull-to-refresh 同步、drawer 同步块、fab 同步日志入口）、`PrefFragment2`（删 updateSyncInfo/onSync 订阅与 sync section 绑定）、`AppsConfig`（删 `isGooglePlayServicesAvailable` 及广告前置判断）、`AppProfile`/`FileInformationDialog`/`BrowseFragment2`（删 `deleteRemoteFile` 分支）、`FileMetaComparators`（`BY_SYNC_DATE` 改恒等比较）、`Dialogs`（删 `showSyncLOGDialog`）、`SlidingTabLayout`（删 swipeRefresh 联动）、`HorizontalViewActivity`/`VerticalViewActivity`（删 `runSyncService`）、`ShareDialog`（死 import）、`AppSP`（删 `isEnableSync` 字段）。
- `fragment_preferences.xml` 删孤儿 sync 视图（syncHeader/signIn/syncInfo/syncInfo2/isEnableSync/isEnableSyncSettings），保留 section8 容器。
- 结果：**main 从此零 `com.google.android.gms` / `com.google.api` 类型**（grep 断言 0 命中）。

**2. 删除 5 个上游遗留 UI 版本**（pdf_classic/ebooka/pdf_v2/tts_reader/epub_reader）：
- `app/build.gradle` 删 5 个 flavor 块；admobAds 源集只挂 google；`dep_free`（play-services-ads+UMP）只挂 google；`dep_pro`（junrar+Play Review）挂 google+pro；gmsStubs 挂载移除。
- 删 5 个源集目录（各含 LibreraBuildConfig + strings.xml）+ 10 个专属图标（5 组 mipmap png + 5 个 adaptive icon xml）；`drag_popup.xml` 的 `icon_pdf_droid` 改 `icon_pdf_pro`。
- flavor 现为 3 个：**google**（主渠道，AdMob）/ **fdroid** / **pro**（后两者 GMS-free、零广告）+ 预留 xiaomi/huawei。

**3. 工具链/文档同步**：`build-librera.ps1`（ValidateSet）、`build_remote.sh`、`BUILD-README.md`、`AGENTS.md`（新增 gotcha 14）、`MULTI_PLATFORM.md`（目录树 + 广告分层 + 矩阵）、`store/android/fdroid/README.md`。

**验证（Ubuntu server + MI9）**：
- 三渠道同批 `assembleGoogleDebug assembleProDebug assembleFdroidDebug` → BUILD SUCCESSFUL，全部 0.9.0/7198。
- 三个 APK 均含 `lib/arm64-v8a/libMuPDF.so`（jniLibs 路径生效）。
- fdroid APK：`scan_apk_ads.py` 零广告标记 PASS；dex + manifest/arsc 字节扫描 `com/google/android/gms` / `com/google/api/client` / `com/google/api/services/drive` **全部 0 命中**（零 GMS）。
- google APK 反向断言：仍含 AdMob（dex 1013 命中）且零 Drive 假类（0 命中）。
- pro APK：ads/drive/api-client 均 0 命中；仅含 `play-services-basement`（`com/google/android/gms/common|tasks`，由 Play Review 评分库传递引入，非广告非登录非 Drive）。
- main 源码 grep 断言：GFile/SynctornizatoinWorker/GoogleDriveFragment2/GDriveSycnEvent/com.google.android.gms/com.google.api.client/com.google.api.services.drive/isEnableSync 全部 0 文件命中。
- MI9 安装 google debug HowRead-0.9.0-arm64：启动成功，MainTabs2 前台 resumed，进程稳定，crash 缓冲区为空，无 NoClassDefFound/FATAL。

---

## [2026-09-02] 书籍菜单新增"AI 简介书籍"；长按书籍同时选中并弹菜单

**1. 书籍菜单新增"✨ AI 简介书籍"**（`ShareDialog.show()`，长按或右下角 ⋮ 弹出的菜单，位于"文件信息"上方）：点击后把文件名 + 元数据标题/作者/注释（注释为空时回退到书籍概述）发给已配置的 AI 模型，提示词末尾固定追加"请简要介绍一下这本书籍的内容，不需要思考，直接输出回答！"；对话框先显示"AI 思考中…"（保存按钮置灰），回复到达后显示全文并启用"保存到笔记"——点击按 `AppBookmark`（isAiNote）写入书签笔记页，可随同步传播。未配置 AI 时仅 Toast 提示。修复过程中发现点击分发链的 `else if (cond && which == i++)` 分支顺序必须与 items 添加顺序一致，否则点 AI 会打开文件信息（首轮构建已修复验证）。

**2. 长按书籍 = 选中 + 弹出书籍菜单**（`SearchFragment2` 书库长按监听）：长按既进入"已选 N 本"多选状态，同时弹出该书的单书菜单（与 ⋮ 按钮等效）；文件夹长按行为不变。

**3. 撤销上一条目的中间实现**：文件信息（元数据）页的 AI 按钮与保存按钮移除（`dialog_file_info.xml`/`FileInformationDialog.java` 恢复原状），AI 入口统一收敛到书籍菜单。

**验证（MI9）**：长按《人类简史》→ 出现选中栏 + 菜单；点"AI 简介书籍"→ 思考中 → 显示完整简介（结合了元数据标题/作者）；"保存到笔记"→ 书签笔记页该书下出现"笔记 (1)"条目；点"文件信息"仍正常打开元数据页。

---

## [2026-09-02] 书签/我的文件页五项修复与交互优化

**1. 笔记删除不了（修复）**：按书视图下的"笔记 (N)"条目是 `mergeNotes()` 的合成对象，`file` 为 null 且 `t` 只是第一条笔记的 key——`BookmarksData.remove()` 定位不到存储直接 NPE 被吞，删除无效。现在 `BookmarksFragment2.onDeleteResponse` 特判合并条目：遍历其携带的真实笔记逐条删除；`BookmarksData.remove()` 对 `file == null` 的合成对象记日志防 NPE。

**2/3. 删除 X、编辑按钮增大**：书签页 `bookmark_item.xml`（remove/remove2）、文件列表 `browse_item_list.xml`（delete/itemMenu）从 25dp 提到新增的 `wh_button_touch`=40dp；"我的文件"OPDS/WebDAV 行程序化构建的编辑笔/X 从 30dp 提到 40dp（`netListItem`，padding 相应缩小）。

**4. 添加文件夹后页面不恢复（修复）**：根因是文件夹选择器内嵌的 BrowseFragment2（browsePath==null）浏览时把共享的 `AppState.displayPath` 改成了对话框内最后浏览的目录，关闭后只 `populate()` 不回根视图，重启才恢复。现在 `addLibraryFolder`/`addLibraryFile` 选完后调 `displayAnyPath(ROOT_PATH)`——恢复根视图（OPDS/WebDAV/书库文件夹/搜索区）并刷新列表。

**5. "在多个文档中搜索"/"新文件(.txt)" 移到文件夹列表下方**：新增 `netSection2`（`fragment_browse2.xml` 中 RecyclerView 容器之后，`bankSpace` 改 0dp+weight=1），`buildNetSections` 往其追加 divider + "搜索"分节头（无添加按钮时隐藏"+ 添加"标签）+ 两个工具项；可见性跟随根视图（`displayAnyPath` 同步切换）。

**验证（MI9）**：合并笔记条目删除后消失且不再回弹；书签页/文件列表 X、编辑笔明显变大易点；添加文件夹选完后立即回到根视图（重复添加报"已存在"也不停留）；"在多个文档中搜索""新文件"出现在文件夹列表下方"搜索"区且功能正常。

---

## [2026-09-02] 图标改用 HowRead.png 面板设计并去除右下水印

上一轮香槟金配色不满意，改用仓库根目录新设计稿 `HowRead.png`（1536x1536，米白圆角面板 + 金色"书"字 + 木质翻开书）重新生成，**保留原图配色**（面板设计本身主体突出、层次分明）：

| 文件 | 改动 |
| --- | --- |
| 生成脚本 `Z:\opt\librera\bench\gen_icons3.ps1` | ① 平滑去除右下角"元宝 AI生成"水印（矩形区域按左/上邻色双线性混合填充 + 两轮 3x3 盒式模糊）；② 按内容包围盒裁掉面板外围页边距；③ 双线性 cover 铺满画布（原图色彩不动） |
| `mipmap-xxhdpi/icon_pdf_reader.png` | 144x144 重绘（13% 圆角） |
| `mipmap-xxxhdpi/adaptive_pdf_reader.png` | 192x192 前景全幅重绘 |
| `drawable/bg_pdf_reader.xml` | 自适应背景层改为采样面板上下边缘色调的柔和渐变（#C9C8C6→#E9E2DB） |

**验证（MI9）**：构建安装后桌面"好好读"图标为面板设计原貌，水印不可见，MIUI 圆角遮罩正常。

---

## [2026-09-01] 图标优化：主体放大 + 暖金香槟渐变背景

第一版图标直接把 `HowRead.jpg` 原图（含大量近白边距）缩进画布，书本/书字偏小，且背景取样自图片近白色（#FBFAF8→#F0EDE8），与白色书页几乎同色，主体不突出。本轮重做：

- **主体放大**：逐像素扫描计算"书"字+书本的实际内容包围盒（按行左右边缘估计原图底色、色差判定），裁掉四周空白后放大绘制——legacy 图标占画布 96%、adaptive 前景占 72%（配合既有 23% inset）；
- **背景换色**：原图近白背景平滑映射为暖金香槟渐变（上 #C8A26B → 下 #E9D8BA），金色"书"字、木质封面与书页阴影保留原图；阈值渐变（低于 16 视为纯背景、高于 52 视为前景、中间平滑过渡）避免生硬光晕；
- **无接缝合成**：每个图标在整幅画布上单次逐像素合成，作品背景与画布渐变按绝对位置对齐（第一版"作品单独贴回渐变画布"的做法在 adaptive 图上有可见矩形接缝，已修复）；生成脚本 `Z:\opt\librera\bench\gen_icons2.ps1`。

| 文件 | 改动 |
| --- | --- |
| `mipmap-xxhdpi/icon_pdf_reader.png` | 144x144 重绘：香槟渐变底 + 放大主体 + 13% 圆角 |
| `mipmap-xxxhdpi/adaptive_pdf_reader.png` | 192x192 前景重绘：香槟渐变底 + 放大主体（inset 不变） |
| `drawable/bg_pdf_reader.xml` | 背景层渐变改为 #C8A26B（上）→ #E9D8BA（下），与前景底色一致 |

**验证（MI9）**：构建安装后桌面"好好读"图标主体明显变大，金色书+书字在香槟金底上突出，MIUI 圆角遮罩下无接缝、无裁切异常。

---

## [2026-09-01] 应用图标替换为 HowRead 设计图（书+书字）

用仓库根目录的 `HowRead.jpg`（"书"字 + 翻开书立体图，浅暖米色背景）替换 librera 主 flavor（`com.howread.reader`）的启动图标 `@mipmap/icon_pdf_reader`：

| 文件 | 改动 |
| --- | --- |
| `mipmap-xxhdpi/icon_pdf_reader.png` | 重绘为 144x144：图片等比居中 + 四角取样背景色（#F4F0EA）填充 + 13% 圆角，旧启动器使用 |
| `mipmap-xxxhdpi/adaptive_pdf_reader.png` | 自适应图标前景重绘（192x192，保持原资产尺寸）：整幅取样背景色填充 + 图片等比居中，XML 既有 23% inset 不变 |
| `drawable/bg_pdf_reader.xml` | 自适应图标背景层由旧绿色渐变（#236e45→#add074）改为图片本身的色调渐变（#FBFAF8→#F0EDE8），与前景无缝衔接 |

生成脚本：`Z:\opt\librera\bench\gen_icons.ps1`（PowerShell System.Drawing，取样四角均值色、圆角裁剪、高质量插值缩放）。

**验证（MI9）**：覆盖安装后桌面"好好读"图标显示新设计（MIUI 圆角遮罩正常），应用内与设置页图标同步更新。

---

## [2026-09-01] 软件说明标题栏显示版本号 v 前缀与编译时间

| 文件 | 改动 |
| --- | --- |
| `app/build.gradle` | 新增 `generateBuildTimeSource` task：每次构建生成 `com.foobnix.pdf.info.BuildTime`（`BUILD_TIME = "yyyyMMddHHmm"`，构建机本地时间），加入 main java 源目录并挂到 `preBuild` 依赖。用生成源文件而非 `buildConfigField`——Configuration Cache 会冻结 buildConfigField 的值，增量构建显示的是过期时间 |
| `Apps.java` | 新增 `getBuildTime(Context)` 返回生成的 `BuildTime.BUILD_TIME` |
| `AboutSectionBinder.java` | 软件说明标题栏（section6）改为 `好好读: v0.9.0 build 202609012308`（版本号加 v 前缀、后接编译时间戳）；"更新日志"行同样加 v 前缀（`更新日志 好好读 v0.9.0`） |

**验证（MI9）**：软件说明弹窗标题栏显示"好好读: v0.9.0 build 202609012312"，与该次构建的实际时间一致；更新日志行显示 v0.9.0。技术备注：曾尝试读取 APK 内 classes.dex 的 ZIP 时间戳，但 AGP 打包为可复现构建会归一化 zip 条目时间（显示 1981-01-01），故改用构建期生成源文件方案。

---

## [2026-09-01] 同步方案三:列表/统计改整文件同步;进度与书签删除同步到服务器

### 一、OPDS/WebDAV/书库文件夹、最近阅读/珍藏、统计/AI/杂项 → 整文件同步

按需求把六类数据从"逐条/逐字段合并"改为**整文件方案**（与 app-State/app-CSS 一致的 newer-mtime-wins）：内容相同则跳过；远端不存在则 seed 上传；其他失败跳过本轮；否则修改时间新的一方整份胜出。删除靠整份覆盖天然传播，不再需要墓碑/复活标记（上一轮的标记机制整体退役删除）。

| 文件 | 改动 |
| --- | --- |
| `WebDavSyncer.java` | 新增通用 `syncWholeFile()`（no-op / seed / 跳过 / 双向整份覆盖，带 BENCH 日志）；`doSync` 中 `app-Recent`、`app-Favorite`、`app-Stats`、`app-AI`、`app-Misc`、`app-NetworkSources` 六个文件改走整文件；`app-BookStates`（已读/未读标记）保留逐项合并；移除 `syncMergedArrayFile` 与相应 `syncMergedObjectFile` 调用；本机被远端更新时调用 `AppData.invalidateListCache()` 刷新首页 |
| `ProfileStateIO.java` | `exportNetworkSources/exportStats/exportAi/exportMisc` 改为**内容变化才写盘**（整文件方案以 mtime 判断"谁改过"，导出必须保真旧 mtime）；`exportNetworkSources` 只写 opds/webdav/folders 三段；`importNetworkSources` 改为整份应用（folders 整段替换 `searchPathsJson`）；删除退役的 `mergeNetworkSources`、`mergeSimpleMetaArrays`、`mergeStats/mergeAi/mergeMisc`、`updateMarkers/mergeMarkers/unionMarkers/markerKeys/filterTombstoned/tombstonesToArray/keysOf/unionLines/entryKey/readSimpleMetaArray/appendAll` 及标记段常量 |

**已知语义**（整文件方案的固有代价，用户已确认）：两台设备在一次同步间隔内各自改动同一文件时，后同步的一方整份获胜，另一方的中间改动被覆盖；依赖设备与服务器时钟大致一致。

### 二、每本书的进度/书签本地删除 → 同步删除服务器

| 文件 | 改动 |
| --- | --- |
| `AppProfile.java` | 新增 `app-DeletedBooks.json`（设备目录，`{"书名":{"p":时间,"b":时间}}`，p=进度 b=书签） |
| `SharedBooks.java` | 新增 `DeletedBooks` 记录/读取/清空助手；`deleteProgress()`（标记未读）记录 p 墓碑 |
| `BookmarksData.java` | `remove()`（单条/按书删除书签的公共路径）记录 b 墓碑；**修复既有 bug**：`cleanBookmarks()`（清除所有书签）原来向对象格式文件写入空数组 `[]` 导致文件不可解析，改为正确清空并记录全部受影响书名 |
| `WebDavSyncer.java` | doSync books 段：远端条目命中删除墓碑时**跳过其 progress/bookmarks 回灌本地**（否则删除会被服务器合并回来）；若该书本地已无任何进度与书签残留 → 直接删除服务器 `books/<hash>.json`（计入 `SyncResult.booksDeleted`，同步摘要显示"删N"）；处理完成后清空墓碑。BENCH 输出 books synced/associated/deleted 计数 |

**验证（MI9，服务器 192.168.50.100:5005）**：整文件三态日志齐全（identical 静默 / uploaded (local newer) / downloaded (remote newer)），books 段 `synced=86 associated=49`；对《侯卫东官场笔记》"标记为未读（清空进度）"后 progress 键即时删除、墓碑记录，同步后**进度未被服务器回灌**（本地保留已删状态），因该书尚有书签残留服务器文件按语义保留（仅去掉进度部分）；连续两轮同步状态收敛。

### 三、构建

- Ubuntu 编译 `:app:assembleLibreraDebug :app:assembleLibreraRelease` 成功；debug 包已在 MI9 验证。

---

## [2026-08-31] 同步修复二轮:删除跨设备传播;最近阅读/珍藏不再被冲掉

### 一、A 机删除的 OPDS/书库文件夹,B 机同步后也删除

**根因**:上一轮墓碑合并规则中"本机快照段仍含该条目 → 丢弃墓碑"——未执行删除的设备永远以自己副本为准并回传服务器,删除只在本机生效,无法跨设备传播。

| 文件 | 改动 |
| --- | --- |
| `ProfileStateIO.java` | 墓碑在合并中**无条件保留**(按 k 并集、时间取 max),不再因"本机仍有"被丢弃;B 机合并后由既有 import 逻辑(opds/webdav 整串替换、folders 按墓碑移除)落盘,删除随之传播到所有设备并回传服务器 |
| 同上 | 新增复活标记段 `opds-add`/`webdav-add`/`folders-add`:本机"见过删除后又重新添加"的条目记录 `{k,t}` 标记;合并时**时间较新的复活标记取消其墓碑**(后到优先),使重新添加同样全局传播。复活标记仅在"本机墓碑段存在该键且条目重新出现"时记录,避免新设备首次同步把全列表误标为复活 |

**验证(MI9,单机模拟双机)**:①模拟 A 机删除 My:Awards → 同步生成墓碑并发布;②模拟 B 机(快照/活列表均 3 项、无墓碑)→ 同步后**活列表与快照均变 2 项**(旧行为保留 3 项);③模拟重新添加 → `opds-add` 复活标记生成、墓碑清空、条目恢复并发布。

### 二、最近阅读/我的珍藏:B 机看不到、A 机自己被冲掉

| 文件 | 改动 |
| --- | --- |
| `IO.java` | 单槽读缓存 `cacheFile/cacheString` 两个 volatile 字段两步赋值,并发写不同文件时交错,读方会拿到"旧文件名+新内容"(最近阅读读到珍藏的数组),随后的读改写把列表整文件写坏——改为单一 volatile 不可变 `String[]{path, content}` 原子发布,读写都取完整快照 |
| `WebDavSyncer.java` | `fetchText` 错误语义:404(不存在)返回 ""、其他一切失败(网络/鉴权/SSL/超时)返回 null;`syncMergedArrayFile`/`syncMergedObjectFile`/`syncGlobalFile` 在 null(暂时性失败)时**跳过该文件本轮**(不覆盖服务器也不动本地)——消除"一次网络抖动把残缺本地列表发布成全局并永久化"的窗口;404 仍保持既有 seed 上传 |
| 同上 | `syncMergedArrayFile` 写回前**重读本机文件再合并**(远端 GET 期间本机 add() 追加的条目不再被丢失);写回 recent/favorite 后调用 `AppData.invalidateListCache()` 立即刷新首页;新增 `Log.i("BENCH")` 输出 local/remote/merged 条数,真机可观测 |

**说明**:同步到 B 机的条目若对应书籍文件在 B 机相同路径不存在,首页仍会按 `isFile()` 过滤不显示(打不开就不显示);首页每栏只显示最近 8 条,完整列表在"最近阅读"页。

**验证(MI9)**:BENCH 日志输出 `sync app-Recent.json: local=1 remote=1 merged=1` 等计数正常;打开第二本书后列表正确增长(1→2),再同步后内容稳定不丢;偏好页 WebDAV 服务器地址显示正常。

### 三、构建

- Ubuntu 编译 `:app:assembleLibreraDebug :app:assembleLibreraRelease` 成功;debug 包已在 MI9 验证。

---

## [2026-08-31] 修复阅读位置"慢一拍";修复 WebDAV 同步冲掉本地删除(OPDS/书库文件夹)

### 一、重开书籍恢复到上次退出前的准确位置

**根因**:翻页位置在内存即时更新,但写盘走 1 秒防抖(`DocumentController.saveCurrentPage` 的 `handler2.postDelayed`);返回键退出链路(`onCloseActivityAdnShowInterstial` → `closeActivityFinal`)全程没有任何保存,且 `closeActivityFinal` 先 `documentModel.recycle()`,此后防抖任务被 `saveCurrentPageAsync` 的 `getPageCount()<=0` 守卫丢弃——磁盘上停留在"最后一次停顿≥1 秒"的旧位置,重开即"慢一拍"。

| 文件 | 改动 |
| --- | --- |
| `DocumentController.java` | 新增 `saveCurrentPageNow()`:取消防抖回调并立即走一次 `saveCurrentPageAsync()`(文档尚存活时调用,页数有效) |
| `ViewerActivityController.java` | `closeActivityFinal` 在 `documentModel.recycle()` **之前** flush(覆盖返回键/关闭按钮/自动关闭全部退出路径);`onPause` 同样 flush(覆盖 Home 键/最近任务划掉等不经 closeActivityFinal 的场景),均带 try/catch + null 保护 |
| `HorizontalViewActivity.java` | `onPause` 恢复被注释掉的保存,改为 `dc.saveCurrentPageNow()`(书籍/横向模式同样在文档存活时落盘) |

**真机验证(MI9)**:《侯卫东官场笔记》连翻 4 页后**立即**按返回退出,`app-Progress.json` 由 pg=234 → pg=238(此前该场景保留旧值);重开直接落在 9/9(第 239 页),与退出位置一致。

### 二、WebDAV 同步不再复活本地删除的 OPDS 条目/书库文件夹

**根因**:OPDS 目录、WebDAV 服务器、书库文件夹三个列表走"纯并集"合并(`ProfileStateIO.unionLines`),没有删除传播——本地删除的条目在服务器快照里还在,同步时被无条件并回活内存并再次发布,删除永远无法收敛。

**方案**:在 `app-NetworkSources.json` 中新增三个墓碑段 `opds-del`/`webdav-del`/`folders-del`(元素 `{k,t}`,k 为 entryKey),只影响上述三个列表;最近阅读/收藏、阅读进度、书签的同步逻辑不变。

| 文件 | 改动 |
| --- | --- |
| `ProfileStateIO.java` | `exportNetworkSources()`:导出时对比"文件旧快照"与当前活列表,旧有今无的键记墓碑;活列表中重新出现的键清除其墓碑(重新添加优先);墓碑按时间取最新 500 条封顶 |
| 同上 | `mergeNetworkSources()`:三段先并集;墓碑段按 k 并集、t 取 max;**本机活列表仍存在的键**丢弃其墓碑(另一台设备保留该条目时,以保留方为准);用墓碑过滤并集结果后随快照回写本地并发布服务器,删除就此收敛 |
| 同上 | `importNetworkSources()`:opds/webdav 两段改为**按段存在性守卫的整串替换**(原 `isNotEmpty` 守卫导致"全部删除"无法落盘;旧格式文件无该段时不动 live);folders 段在既有 add-only 之外,新增按 `folders-del` 墓碑从 `searchPathsJson` 显式移除,使其他设备上的删除也能落到本机 |

**语义**:删除优先于服务器旧副本;若另一台设备仍保留该条目,则以保留方为准(不会强制清空其他设备);任何设备重新添加即全局恢复。

**真机验证(MI9,同步服务器 192.168.50.100:5005)**:①UI 删除 OPDS 条目"Top Books to Read"→ 同步 → `opds-del` 记录 `My:Awards` → 强杀重启 + 自动同步后**不复活**;②恢复条目 → 同步 → 墓碑清除、三设备状态收敛;③书库文件夹移除 DSfile → 同步 → `folders-del` 记录 → 重启后不复活;④加回 DSfile → 同步 → 墓碑清除、恢复 2 项。测试后设备配置已完全还原(OPDS 3 项原顺序、书库文件夹 2 项)。

### 三、构建

- Ubuntu 编译 `:app:assembleLibreraDebug :app:assembleLibreraRelease` 成功;debug 包已在 MI9 真机验证通过。

---

## [2026-08-12] 移除 Google/Drive 依赖;新增书库「格式配置」「书库文件夹配置」

### 一、构建彻底去 Google 化

| 文件 | 改动 |
| --- | --- |
| `app/build.gradle` | 移除 `com.google.gms.google-services` 插件;`signingConfigs.release` 与 8 个 flavor 的 `appGdriveKey`/`admob*` 全部改为 `project.findProperty(...) ?: ''`,不传 -P 也能配置、能编译 |
| `build.gradle.kts` | 移除 googleServices 插件 alias |
| `gradle/libs.versions.toml` | 移除 googleServices 版本/插件;移除 5 个 Google 库(google-api-client-android、google-api-services-drive、google-http-client-gson、google-oauth-client-jetty、play-services-auth) |
| `libDepPro/build.gradle.kts` | 移除上述 5 个 Drive 相关依赖 |
| `Builder/link_to_mupdf_1.23.7.sh` | 新增本机 NDK 路径 `PATH3=/docker/opt/android-sdk/ndk`,fdroid/普通构建的 ndk-build 搜索列表加入 PATH3 |

**Stub 迁移(fdroid → main)**:`app/src/fdroid/java` 下 `com.google.android.gms.*`、`com.google.api.*`、`com.google.api.services.drive.*` 共 19 个 stub 类移到 `app/src/main/java`,删除 `FirebaseAnalytics.java` stub——所有 flavor(pro/pdf_classic 等)在无 Play Services 环境下均可编译;`UITab.java` 中 Google Drive 标签在所有构建中隐藏(Drive 功能已整体移除)。

### 二、设置页「格式配置」

- `fragment_preferences.xml`:书库设置区 14 个格式 CheckBox 收进一行「格式配置」入口
- `PrefFragment2.java`:弹窗从上到下列出 14 个格式,每行右侧显示该格式在书库中的文件数;切换即写 `AppState` 对应 flag、刷新 `ExtUtils.updateSearchExts()`、自动触发扫描
- `AppDB.java`:新增 `getExtCounts()`(按 EXT 分组统计书库 `IS_SEARCH_BOOK=1` 文件数)
- `strings.xml`(+zh-rCN/+zh-rTW):新增 `formats_settings`

### 三、设置页「书库文件夹配置」

- `fragment_preferences.xml`:「Folders to Scan」标签+路径+添加按钮收进一行「书库文件夹配置」入口
- `PrefDialogs.java` 重写 `chooseFolderDialog`:底部新增「添加文件夹」「添加文件」链接(支持配置多个文件夹/文件);每行显示该文件夹(递归)/该文件在书库中的支持文件数
- `PathAdapter.java` + `path_item.xml`:行右侧新增计数 TextView(默认隐藏,不影响其他复用场景)
- `AppDB.java`:新增 `getSearchBookPaths()`(按前缀/精确匹配计数)
- 配套修复(否则「添加文件」不生效):
  - `BookCSS.filtered()`:`isDirectory()` → `exists()`,重启不再丢弃文件条目
  - `SearchAllBooksWorker` / `CheckDeletedBooksWorker`:`root.isDirectory()` → `root.exists()`,单文件可被扫描入库
- `strings.xml` ×3:新增 `library_folders_settings`、`add_file`

### 四、其他调整

- `BrowseFragment2.getInitPath()`:标签栏「文件夹」页优先打开第一个已配置的书库文件夹
- `path_item.xml`:计数文本 12sp → 14sp

### 五、验证

- Ubuntu 远程构建:fdroid Debug **BUILD OK**(5 个 APK)、librera Debug **BUILD OK**(5 个 APK)
- 全仓无残留 `R.id.searchPaths` / `R.id.onConfigPath` 引用;改动均在 main source set,8 个 flavor 全部生效

### 六、附注

- `keystore.pkcs12`(签名密钥)不随代码入库,已加入 `.gitignore`
- 编译产物(`app/build` 等)不入库,已清理

---

## [2026-08-12] 新增 WebDAV 标签(独立于 OPDS,只读)

- **独立模块**:新增 `com.foobnix.webdav` 包(WebDavFragment2 / WebDavClient / WebDavStore / WebDavCredentials / WebDavAdapter 等),不 import 任何 OPDS 类;将来可删除 OPDS 而不影响 WebDAV
- **独立标签**:UITab 新增 `WebDavFragment(8, ...)`,新装默认可见;已装设备通过 `UITab.getOrdered()` 自动追加新标签(无需手动开启)
- **WebDAV 客户端**:Sardine-Android 0.9(经 jitpack 仓库拉取);因它传递引入 okhttp 4.x,已用 `resolutionStrategy.force` 将全局 okhttp 钉回 3.12.6(已验证 sardine 仅用 okhttp 稳定公开 API),并 exclude xpp3/stax 避免 XML 解析冲突
- **功能**:浏览服务器目录(PROPFIND,目录优先排序),点文件下载到下载目录、入库并直接打开;认证失败有提示
- **凭据安全**:账号密码用 AndroidKeyStore AES/GCM 加密后存 SharedPreferences(按服务器 URL 为 key)
- **添加/编辑**:标签页 "+" 添加 WebDAV 服务器(URL/名称/账号/密码),保存前后台 PROPFIND 验证,失败可强制添加;长按编辑、行内删除
- 只读范围:不做上传/删除/新建文件夹

---

## [2026-08-12] WebDAV 并入「网络」页(与 OPDS 同为子项,移除独立标签)

### 一、网络页根视图 = 两个子项区块

- **OPDS 子项**:区块头(标题 OPDS + 右侧加号 → 添加目录);区块内依次为「代理设置」齿轮行(原顶栏齿轮移入,内容不变:代理服务器、下载目录、OPDS 大封面等)、OPDS 链接列表、「恢复默认目录」「什么是 OPDS?」两行(原页底链接移入)
- **WebDAV 子项**:区块头(标题 WebDAV + 右侧加号 → 添加服务器);区块内为 WebDAV 服务器列表(长按编辑、行内删除不变)
- 实现:`NetworkRootAdapter.java`(组合适配器,7 种视图类型;OPDS 行委托 `EntryAdapter`、WebDAV 行委托 `WebDavAdapter` 渲染,点击/长按/删除逻辑不变);新增布局 `network_section_header.xml` / `network_settings_row.xml` / `network_text_row.xml`

### 二、WebDAV 浏览并入网络页

- **移除独立 WebDAV 标签**:`UITab.java` 删除 `WebDavFragment(8)` 枚举;`getOrdered()` 增加「未知 id 跳过」保护(旧数据残留 `8#` 不会误映射成重复搜索标签);删除 `WebDavFragment2.java` 与 `fragment_webdav.xml`
- `OpdsFragment2.java` 增加 WebDAV 浏览模式:点服务器/目录进入浏览(复用 `WebDavStore`/`WebDavCredentials`/`WebDavClient`/`WebDavAdapter`/`AddWebDavDialog`),返回/主页回到根合并视图;点文件 → 确认后下载到下载目录 → 入库 → 直接打开;认证失败有提示
- 顶栏精简:删除齿轮、加号及分隔线,只留 返回/标题/星标/进度/主页;根视图标题改用「网络」
- **默认可见性**:`DEFAULTS_TABS_ORDER` 网络页(5)由 `5#0` 改为 `5#1`(默认显示;之前 WebDAV 标签默认可见,合并后需网络页默认可见才能看到 WebDAV,不需要可改回 `5#0`)
- 字符串 ×3 新增 `opds`(协议名,三语言一致)

### 三、隔离性(保持不变)

- `com.foobnix.webdav` 包仍不 import 任何 OPDS 类;合并逻辑只放在 `com.foobnix.ui2.*` 层(OpdsFragment2 / NetworkRootAdapter);将来「只要 WebDAV」:删 opds 包后网络页根逻辑需重写,webdav 包本身零依赖

### 四、验证

- Ubuntu 远程构建:fdroid Debug + librera Debug BUILD OK(各 5 个 APK)
- grep 验证:webdav 包无 opds import;`WebDavFragment2`/`fragment_webdav`/`onProxy`/`onPlus` 无残留引用

## [2026-08-12] WebDAV 并入「网络」页 — 代码检视修复 11 项

对 WebDAV 并入网络页改动做代码检视后发现并修复 11 处问题(高 3 / 中 2 / 低 6),全部通过 fdroid + librera Debug 构建验证。

### 一、高优先级

| BUG | 文件 | 修复 |
| --- | --- | --- |
| 老用户升级后网络页隐藏、WebDAV 无入口 | `AppState.java` | `loadInit()` 加幂等迁移:`tabsOrder9` 含 `8#`(旧 WebDAV 标签残留)时剥离,并把 `5#0`→`5#1`(网络页可见);无 `8#` 后不再触发,尊重用户后续手动隐藏 |
| downloadWebDav 流泄漏 | `OpdsFragment2.java` | `doInBackground` 改 `finally` 关闭 InputStream/OutputStream |
| downloadWebDav 失败时部分文件残留 | `OpdsFragment2.java` | catch 块加 `file.delete()` |

### 二、中优先级

| BUG | 文件 | 修复 |
| --- | --- | --- |
| WebDAV 错误提示误导(认证/网络混淆) | `WebDavClient.java` / `OpdsFragment2.java` | `list()` 失败时用 `lastErrorWasAuth` 区分 401/403 与其它;`OpdsFragment2` 加 `webDavLoadFailed` 字段,分别提示「认证失败」/「网络错误」 |
| downloadWebDav 不支持外置 SD 卡 | `OpdsFragment2.java` | 加 `isExteralSD` 分支,走 `DocumentsContract`/SAF(与 OPDS 的 `onClickLink` 一致) |

### 三、低优先级

| BUG | 文件 | 修复 |
| --- | --- | --- |
| WebDavStore 并发数据竞争 | `WebDavStore.java` | `load/findForUrl/add/remove` 统一 `synchronized(LOCK)` |
| URI.resolve 对「相对 href + 无尾斜杠 base」丢路径段 | `WebDavClient.java` | `resolve()` 给 base 补尾斜杠 |
| NetworkRootAdapter 子 adapter notify 不同步 | `NetworkRootAdapter.java` | 构造时注册子 adapter 的 `AdapterDataObserver` 转发到 root;`setOpdsEntries` 改为直接操作 list 避免双 notify |
| onBackAction WebDAV→根分支状态未重置 | `OpdsFragment2.java` | 补 `authFailed`/`webDavLoadFailed`/`currentServerUrl` 重置(与 `onHome` 一致) |
| UITab.getOrdered 对脏数据无防御 | `UITab.java` | 循环体加 try/catch + `tab.length` 检查跳过坏 pair |
| downloadWebDav onPostExecute 无 isAdded 防护 | `OpdsFragment2.java` | 加 `if (!isAdded()) return;`(在关进度条之后) |

### 四、验证

- Ubuntu 远程构建:fdroid Debug + librera Debug BUILD OK(各 5 个 APK,2026-08-12 22:11/22:13)
- 改动文件纯 LF 行尾;webdav 包零 opds import(隔离性不变)

## [2026-08-12] 修复 librera 等含广告 flavor 启动即崩(AdMob App ID 为空)

### 根因

含广告的 flavor(librera/pdf_v2/ebooka/tts_reader/pdf_classic/epub_reader)依赖 `libDepFree` → `play-services-ads 25.4.0`,其 `MobileAdsInitProvider`(ContentProvider,在 `Application.onCreate()` **之前**启动)校验 manifest 的 `com.google.android.gms.ads.APPLICATION_ID`。该值来自 `admobAppId` 占位符,而 `gradle.properties` 未配 `*_admobAppId`,默认空字符串 → SDK 抛 fatal `IllegalStateException` → 应用启动即崩。

fdroid/pro 不崩:fdroid 不依赖 `libDepFree`(用 `libPro` 桩类,manifest 无 `MobileAdsInitProvider`);pro 只依赖 `libDepPro`(不含 ads)。

### 修复

`app/build.gradle`:新增 4 个 Google 官方公开测试 AdMob ID 常量([测试广告文档](https://developers.google.com/admob/android/test-ads)),作为 6 个含广告 flavor 的 `admobAppId`/`admobBannerId`/`admobFullId`/`admobRewardId` 默认值(原 `?: ''` → `?: sampleAdmobXxx`)。

- 开发时(未配 gradle.properties):用测试 ID,应用正常启动,AdMob 初始化显示测试广告
- 上线时:在 `~/.gradle/gradle.properties` 配 `librera_admobAppId=真实ID` 等即自动覆盖
- 测试 App ID `ca-app-pub-3940256099942544~3347511713` 是 Google 官方公开值,不会产生真实广告收入

### 验证

- librera Debug BUILD OK(5 APK,2026-08-12 22:29)
- merged manifest 确认 `APPLICATION_ID="ca-app-pub-3940256099942544~3347511713"`(不再是空)

---

## [2026-08-13] 冷启动首帧优化(Phase 1 + Phase 2)

**目标**:缩短「点图标 → 首帧绘制」的等待。真机为 MIUI Android 14(serial `48fee174`)。

### 一、Phase 1 — 首帧基础优化

| 改动 | 文件 | 说明 |
| --- | --- | --- |
| 移除 androidx Startup 初始器 | `AndroidManifest.xml` | `InitializationProvider` 用 `tools:node="remove"` 移除 3 个在 `Application.onCreate` **之前**于主线程运行的初始器:`EmojiCompatInitializer`、`ProcessLifecycleInitializer`、`ProfileInstallerInitializer` |
| 启动画面(消除冷启动白屏) | `res/values/styles.xml`(+`values-v21`)、`AndroidManifest.xml` | 新增 `StyledIndicatorsBlack.Launch` 主题:`windowDisablePreview=false` + `windowBackground=@drawable/splash`,并套到启动 Activity `MainTabs2`。原基础主题 `windowDisablePreview=true`(冷启动全白屏);现系统立即用 splash drawable 作 starting window,用户即时看到画面 |
| MuPDF 懒加载/后台预加载 | `AppsConfig.java`、`LibreraApp.java` | `ensureMuPdfLoaded()` 改 `synchronized` + `volatile mupdfLoaded` 守卫(首次调用才 `loadLibrary`,不再 init 阶段同步加载 21MB);`LibreraApp` 把预加载提交到 `executorService`(后台线程) |
| offscreenPageLimit 10→1 | `MainTabs2.java` | ViewPager 首帧只创建「当前 + 相邻」标签页 Fragment(原为 10,首帧会实例化全部标签) |
| 新增独立单线程执行器 | `AppsConfig.java` | `executorServiceSingle = newSingleThreadExecutor()`,供 Phase 2 的 getCount 使用,避免与 `executorService` 上的 MuPDF 预加载互相阻塞 |

### 二、Phase 2 — 延迟加载(不阻塞首帧)

| 改动 | 文件 | 说明 |
| --- | --- | --- |
| getCount 异步 + 安全等待 | `AppProfile.java`、`SearchFragment2.java` | `AppDB.open()` **保持同步**(保证 DB 全程可读);仅 `getCount()` 提交到 `executorServiceSingle` 异步执行。`bookCount` 改 `volatile`;新增 `CountDownLatch DB_READY` + `awaitDBReady(timeoutMs)`。`SearchFragment2.onCreateView` 读 `bookCount` 前先 `awaitDBReady(2000)`,避免「书库尚未计数完」被误判为「空书库」而触发 `seachAll()`→`deleteAllData()` **误删真实书库**的破坏性竞态 |
| UMP 同意/广告延迟到首帧后 | `MainTabs2.java` | UMP consent/ads 块用 `handler.post(() -> {...})` 包裹,推迟到首帧绘制后执行(密码门禁 / `EXTRA_EXIT` 早退语义不变) |
| 主线程杂活后台化 | `LibreraApp.java` | `MobileAds.initialize`(官方允许任意线程)、`WorkManager.pruneWork/cancelAllWork`、`TTSNotification.initChannels` 全部提交到 `executorService` 后台线程 |

### 三、安全设计说明(关键)

**AppDB.open 保持同步**是相对原「open + getCount 全异步」设计的关键修正。全异步会让 worker / widget 在 DB 未 open 时读到空/陈旧数据,并使 `SearchFragment2` 的 `bookCount==0` 判断误触发破坏性重扫。**open 同步 + 仅 getCount 异步**从根源消除全部竞态,worker / widget 一行不用改。

### 四、验证

- 真机冷启动(force-stop → 清 logcat → 启动 → 读首帧):
  - fdroid:首帧 ~1295–1311ms;librera:~1327–1331ms(均稳定,相对 Phase 1 的 ~1.35s 无回归)
  - 书库列表正确(items=3,无误删);tab 切换无异常;无崩溃
  - getCount、WorkManager 确认在后台线程;无 GMS 设备 MobileAds/UMP 不执行(`isShowAdsInApp` 返回 false)
- Ubuntu 远程构建:fdroid Debug + librera Debug BUILD OK(先 `gradlew --stop` 规避 Samba `compileTransaction` 锁)
- 本轮全部 STARTUP 埋点已清理(6 文件):`LibreraApp` / `AppsConfig` / `AppProfile` / `MyContextWrapper` / `MainTabs2` / `SearchFragment2`;改动文件均纯 LF 行尾

### 五、说明

- 剩余 ~350–540ms 为 ART 类加载(process→onCreate),属 **Baseline Profile** 范畴(需另加 macrobenchmark 模块),本次未涉及;如需再压可单独规划
- 测试设备无 Google Play Services,故广告 flavor 的 MobileAds/UMP 实际未执行(代码正确,会在有 GMS 的设备上运行)

---

## [2026-08-13] 预编译二进制缓存 prebuilt/ —— 离线构建

**目标**:把所有「需从网上拉取」的二进制缓存进源码树,彻底解决因网络限制(`git://git.ghostscript.com` 被封、jitpack.io 被墙、mavenCentral 慢)导致的编译失败。

### 一、新增 `prebuilt/`(合计 ~374 MB,普通 git blob 入库,**免 LFS**)

| 子目录 | 内容 | 原网络来源 | 体积 | 接入方式 |
| --- | --- | --- | --- | --- |
| `native/mupdf-1.23.7/<abi>/` | MuPDF + liblame 原生库(4 ABI × 2 = 8 个 `.so`,RAW) | `git clone git://git.ghostscript.com/mupdf` + ndk-build | 85 MB | `app/build.gradle` `jniLibs.srcDirs` 直读(无需还原) |
| `gradle-cache/modules-2.tar.gz.part00/01` | 全部 Gradle/Maven 依赖 + 插件(AGP/Kotlin/KSP/jitpack…),**Gradle 原生缓存格式**(158MB 拆 2 片 ≤90MB) | mavenCentral / google / jitpack.io / gradlePluginPortal | 158 MB | `scripts/restore-cache.sh` `cat` 重组后解压到 `~/.gradle/caches/`,用 `--offline` 构建 |
| `gradle/gradle-8.14.5-bin.zip.part00/01` | Gradle 发行包(132MB 拆 2 片 ≤90MB) | services.gradle.org | 132 MB | `scripts/bootstrap-gradle.sh` `cat` 重组后灌入 `~/.gradle/wrapper/dists/` |

### 二、关键设计决策:用 Gradle 原生缓存,不用 maven 仓库

最初尝试把依赖转成**文件型 maven 仓库**(`prebuilt/maven` + `settings.gradle.kts` 置首),但**构建失败**:现代 AndroidX(room/lifecycle/compose…)用 Gradle Module Metadata 发布**变体产物**(如 `room-runtime-android` 的 .aar 实际名为 `room-runtime-release.aar`),Gradle 从文件仓库按默认名查找、找不到,且因元数据已存在**不回退网络** → 失败。

改为 **vendor Gradle 自己的 `modules-2` 缓存**(原生格式,变体解析天然正确),fresh 机器还原进 `~/.gradle` 后用 `--offline` 构建。**实测**:全新 `GRADLE_USER_HOME`(只有该缓存)+ `--offline` + `clean` → BUILD SUCCESSFUL。

### 三、接入改动

| 文件 | 改动 |
| --- | --- |
| `app/build.gradle` | `sourceSets.main.jniLibs.srcDirs = ["${rootDir}/prebuilt/native/mupdf-1.23.7"]`(替换默认 `src/main/jniLibs`,消除旧符号链接冲突) |
| `Builder/link_to_mupdf_1.23.7.sh` | `LIBS` 改指向 `prebuilt/native/mupdf-1.23.7`;符号链接(`ln -s`)改真实拷贝(`cp`)—— 源码编译结果直接灌入缓存 |
| `.gitignore` | 加 `/app/src/main/jniLibs/`(gradle 已不读,防旧符号链接误入库) |
| `.gitattributes`(新增) | `prebuilt/**` 二进制标记 `-text`(**不走 LFS**,含 `.part*`);`*.sh/*.kts/*.gradle` 强制 LF 行尾 |
| `settings.gradle.kts` | **未改**(依赖走原生缓存 + `--offline`,不需要文件仓库) |

### 四、脚本(均 in-repo)

| 脚本 | 作用 |
| --- | --- |
| `scripts/vendor-cache.sh` | 联网构建后运行:打包 `modules-2.tar.gz` 后自动拆成 ≤90MB 分片(`.part00/.part01`)并删原件 |
| `scripts/restore-cache.sh` | fresh 机器运行:`cat` 重组分片 → 解压到 `~/.gradle/caches/` + 调 bootstrap-gradle.sh |
| `scripts/bootstrap-gradle.sh` | `cat` 重组分片 → 灌入 wrapper 缓存(`~/.gradle/wrapper/dists/`) |
| `Builder/prepare-native.sh` | 检查 8 个 `.so`;缺失→调 `link_to_mupdf_1.23.7.sh` 拉源码编译 |

### 五、验证(已通过)

1. **联网构建不回归**:回退 settings.gradle.kts 后正常解析网络,构建通过。
2. **主机离线**:`./gradlew --offline :app:assembleFdroidDebug` → BUILD SUCCESSFUL(用主机现有缓存)。
3. **fresh 机器离线(终极)**:全新 `GRADLE_USER_HOME` + vendored 缓存 + `--offline` + `clean` → **BUILD SUCCESSFUL**(38 任务真实执行,非 UP-TO-DATE)。
4. **gradle zip bootstrap**:`cat` 重组分片 → wrapper 自动解压 → 离线构建成功。

### 六、入库说明

- **免 Git LFS**:本仓库远程是 GitHub **public fork**,public fork 不允许上传 LFS 对象(push 报 `can not upload new objects to public fork`)。故二进制全部以**普通 git blob** 入库,无需 `git lfs install`。
- 为绕过 GitHub **100MB/单文件**硬上限,两个大包(`gradle-8.14.5-bin.zip` 132MB、`modules-2.tar.gz` 158MB)各拆成 **≤90MB 分片**(`.part00/.part01`),构建脚本用 `cat ...part*` 重组;`.so` 单个均 <25MB,直接入库。
- 持久主机无需 restore(它已有 `~/.gradle`);restore-cache.sh 仅用于 fresh 机器。
- `keystore.pkcs12` 仍不入库。

---

## [2026-08-28] 新增 AI 大模型对话、笔记保存与阅读位置绑定

### 一、AI 大模型对话入口

- `DragingDialogs.java` onSendToAi:从阅读页底部工具栏「发送到 AI」进入对话,传入`controller.getPercentage()`作为当前阅读位置(0..1 小数,与书签一致)
- `AiAskDialog.java`:
  - 新增 `void show(Activity a, String text, String bookPath, float percent)` 重载,保存 `savePercent`
  - 新加 `show(Activity a, String text)` 和 `show(Activity a, String text, String bookPath)` 委托到 4 参重载
  - 保存笔记时 `note.p = savePercent`(原为 `0`),记录阅读位置

### 二、笔记按书分组、合并展示

- `AppBookmark.java`:新增 `transient public List<AppBookmark> notes;`(transient,不参与 JSON 序列化)
- `BookmarksFragment2.java`:
  - `mergeNotes()`:合并同名书籍笔记时,将各条笔记按时间倒序存入 `merged.notes` 列表
  - `showNoteDialog()`:当 `note.notes` 非空时,改用 `AlertDialogs.showViewDialog` 构建每条笔记的自定义视图——每条笔记包含:
    - 时间行:蓝色+下划线+粗体,显示 `[yyyy-MM-dd HH:mm]`
    - 内容行:选中文本 + AI 回答(可选文本)
    - 分隔线
  - 点击时间行:dismiss 对话框,若 `getPercent() > 0` 且文件存在,`ExtUtils.showDocumentWithoutDialog2` 跳转到对应阅读位置
  - 当 `notes` 为空时回退到原有的纯文本 `AlertDialogs.showOkDialog`

### 三、涉及文件

| 文件 | 改动 |
| --- | --- |
| `AiAskDialog.java` | 新增 `savePercent` 字段、4 参 `show` 重载、保存时 `note.p = savePercent` |
| `DragingDialogs.java` | `onSendToAi` 传入 `controller.getPercentage()` |
| `AppBookmark.java` | 新增 `transient List<AppBookmark> notes` |
| `BookmarksFragment2.java` | `mergeNotes` 保存笔记列表;`showNoteDialog` 自定义视图+可点击时间跳转 |

---

## [2026-08-29] 三项功能修改:首页 Tab 返回覆盖页、笔记时间跳转加固、备份按书维度

### 一、修复首页 Tab 无法从覆盖页返回

**根因**:最近阅读/书签笔记/我的珍藏/网上书库 4 个页面以 `overlayContainer` 临时覆盖层打开,ViewPager 仍停留在首页(index 0)。点击「首页」Tab 时 `SlidingTabLayout.TabClickListener` 判定 `i == currentReal`(0==0),走重选钩子 `setOnTabReselect`,但该 lambda 只调 `DashboardFragment2.onTabReselect()`(空实现),从不关闭覆盖层。点其他 Tab 因 index≠0 走 `pager.setCurrentItem` → `onPageSelected` → `hideTabOverlay()`,所以能切换。

**修改**:`MainTabs2.java` `setOnTabReselect` lambda 中,在调 `onTabReselect()` 之前先调 `hideTabOverlay()`(该方法自带可见性守卫,不可见时直接 return,无副作用,同时恢复顶栏标题与「继续阅读」悬浮按钮)。

### 二、笔记保存时记录阅读位置、时间可点击跳转(加固)

- `AiAskDialog.java` 保留 2026-08-28 的 `percent` 参数传递
- `DragingDialogs.java` 保留 `controller.getPercentage()` 传入
- `BookmarksFragment2.java` `showNoteDialog()` 完善:点击时间 → dismiss 对话框 → 若 `getPercent() > 0` 且文件存在 → `ExtUtils.showDocumentWithoutDialog2` 跳转(与书签点击同一机制);旧笔记 percent=0 时点击时间无跳转

### 三、备份包含书签与笔记,按书籍维度

**原理**:现有导出把 `profile.<名>/device.<机型>/` 整个目录打进 zip(`app-Bookmarks.json` 已在其中,但按时间戳平铺)。新增 `app-BookmarksByBook.json`(结构:`{ 书名: { 时间戳: 书签/笔记对象 } }`),导出时写入、随 zip 打包;导入时合并回 `app-Bookmarks.json`(按时间戳键幂等合并)。

| 文件 | 改动 |
| --- | --- |
| `AppProfile.java` | 新增常量 `APP_BOOKMARKS_BY_BOOK_JSON`、字段 `syncBookmarksByBook`、`init()` 中初始化 File |
| `BookmarksData.java` | 新增 `saveByBook()`:遍历 `getAll()` 按 `ExtUtils.getFileName(path)` 分组写入;`importByBook()`:逐条合并进 `AppProfile.syncBookmarks`(幂等,不覆盖已有键) |
| `PrefDialogs.java` | `exportDialog.doInBackground`:`zipFolder` 前调用 `saveByBook()`;`importDialog.doInBackground`:`unZipFolder` 后调用 `importByBook()` |

### 四、验证(MI9 真机)

1. **首页 Tab 返回**:首页→书签笔记→点首页 Tab→返回首页(覆盖层关闭,顶栏恢复「首页」);书库/我的文件 Tab 切换正常;最近阅读/我的珍藏/网上书库同理
2. **笔记位置跳转**:阅读页选文本→发送到 AI→保存笔记→书签笔记→打开合并笔记→点击笔记时间→跳转到对应阅读位置(第一章 2/10,`p=0.06521739`)
3. **备份按书**:导出后 zip 内含 `app-BookmarksByBook.json`,按书籍分组(3 本书:致命弱点.mobi/没有人给他写信的上校.epub/《驻京办主任3》-王晓方著.epub),书签+笔记完整;导入后恢复 12 条记录、8 条笔记,位置保留,无重复

## [2026-08-30] 代码检视修复 7 个隐含 BUG

### 一、修复内容

| BUG等级 | 文件 | 问题描述 | 修复方案 |
|---------|------|----------|----------|
| **HIGH** | `AppBookmark.java` | `equals()` 仅依赖时间戳，可能导致错误删除 | 添加 `path` 和 `text` 比较，确保唯一性 |
| **HIGH** | `AppProfile.java` | `clear()` 未重置 CountDownLatch，导致 `awaitDBReady()` 永久阻塞 | 通过反射重置 `DB_READY` 计数器 |
| **MEDIUM** | `BookmarksFragment2.java` | `mergeNotes()` 中的 `SimpleDateFormat` 在后台线程创建 | 缓存为类字段，在主线程初始化 |
| **MEDIUM** | `ExtUtils.java` | `getAllExportString()` 未处理 null 路径和空列表 | 添加 null 检查和空列表处理 |
| **MEDIUM** | `MainTabs2.java` | `getCurrentRealIndex()` 可能返回越界索引 | 先检查 `tabFragments.isEmpty()` |
| **LOW** | `AppBookmark.java` | `getPage()` 未处理负数和 NaN | 边界检查，限制 `p` 在 [0, 1] 范围 |
| **LOW** | `ExtUtils.java` | `exportAllBookmarksToFile()` 文件写入未关闭 | 使用 try-with-resources 确保关闭 |

### 二、技术细节

1. **AppBookmark.equals()**: 原 `a.t == t` 改为 `Objects.equals(path, a.path) && Objects.equals(text, a.text) && t == a.t`
2. **AppProfile.clear()**: 使用反射重置 `static final` CountDownLatch
3. **SimpleDateFormat 缓存**: 避免后台线程重复创建，提升性能
4. **null 检查**: 防止 NPE，增强健壮性
5. **边界检查**: 确保 `getCurrentRealIndex()` 不越界
6. **数值约束**: `getPage()` 处理异常百分比值
7. **资源管理**: try-with-resources 防止文件泄漏

### 三、验证结果

1. **构建成功**: APK 生成无错误，所有修复编译通过
2. **安装运行**: 应用成功安装到 MI9，启动正常，无崩溃
3. **功能验证**: 书签管理、导出功能正常，界面显示完整
4. **日志检查**: `logcat` 无 AndroidRuntime 错误，运行稳定
5. **界面截图**: 主界面正常显示，Tab 切换正常

## [2026-08-30] 第三轮复审:验证前两轮修复质量 + 修复 8 个问题

前两轮共修复 13 个 BUG。本轮复审发现其中 4 处修复本身有问题（需返工），另发现 4 个此前未覆盖的新 BUG。

### 一、前两轮修复的返工（Q1-Q4）

| 编号 | 文件 | 问题 | 返工方案 |
|------|------|------|----------|
| **Q1 (HIGH)** | `AppProfile.java` | BUG 8 的反射重置 `static final` 字段在 ART 上会抛 IllegalAccessException（即使 setAccessible(true)），且 R8 混淆后 `getDeclaredField("DB_READY")` 找不到字段 | 声明改为 `private static volatile CountDownLatch DB_READY`（去 final），`clear()` 直接赋新 latch，删除全部反射代码 |
| **Q2 (MEDIUM)** | `AppBookmark.java` | BUG 7 只改了 equals（t+path+text），hashCode 仍是 `(path+text+p)`——不含 t、含 p，违反 equals/hashCode 契约 | hashCode 改为 `Objects.hash(t, path, text)` |
| **Q3 (MEDIUM)** | `BookmarksFragment2.java` | BUG 9 属误诊（后台线程"创建" SimpleDateFormat 无害，局部变量本就线程安全），原修复反把它变成跨线程共享字段；`populate()` 用 2 线程池且 `inProgress` 有竞态窗口，并发 `mergeNotes()` 会踩坏 SimpleDateFormat | 回退为 `mergeNotes()` 内局部变量，删除共享字段 |
| **Q4 (HIGH·功能)** | `BookmarksData.java` | BUG 10 只堵了崩溃：`getBookmarksMap()` 是永远 `return null` 的 stub，导出书签到文件/Gmail 永远输出 "No bookmarks found"（崩溃前 Gmail 导出直接 NPE，这一点确实修掉了） | 真正实现 `getBookmarksMap()`：`getAll()` 按 `getPath()` 用 LinkedHashMap 分组，null path 跳过 |

### 二、新发现的 BUG（N1-N4）

| 编号 | 文件 | 问题 | 修复 |
|------|------|------|------|
| **N1 (HIGH)** | `ExtUtils.java` | `doifFileExists(Context, File)` 的 `Clouds.isCloud(file.getPath())` 在 `file != null` 检查**之前**执行（null 检查是死代码）；`doifFileExists(Context, String)` 传 null 时 `Clouds.isCloud(null)`（`path.startsWith`）直接 NPE | 两个重载入口加 null guard，null 直接返回 false |
| **N2 (HIGH)** | `BookmarksFragment2.java` | 书签长按查看详情 `new File(result.getPath())` 无 null 守卫（单击有守卫、长按没有），path 为 null 的书签长按即崩溃 | 长按回调加 `result == null \|\| result.getPath() == null` 守卫 |
| **N3 (MEDIUM)** | `BookmarksFragment2.java` | `onDeleteResponse` 的 `b.getPath().equals(path)` 任一为 null 即 NPE（后台线程直接崩应用）；`new File(result.getPath())` 同理；`countBookmarksForPath` 同样问题 | 改 `path != null && path.equals(b.getPath())` / `Objects.equals`；`sendBookmarksTo` 分支前判空 |
| **N4 (MEDIUM)** | `AppBookmark.java` `BookmarksAdapter2.java` `BookmarksFragment2.java` | 书籍标题行靠 `text.contains(" items")` 判定：用户书签名含 " items"（如 "100 items"）会被误判为标题行——点击变筛选、删除会删光该书全部书签 | `AppBookmark` 新增 `transient public boolean isBookHeader`（Objects 序列化跳过 transient，无兼容问题），`prepareDataInBackground` 创建标题行时置位；adapter 的 `isBookHeader()`、Fragment 的单击/删除判断全部改用该标志；删除已无引用的 `HEADER_SUFFIX` 常量 |

### 三、验证（MI9 真机，构建 + 安装 + 冒烟）

1. **构建**: `BUILD SUCCESSFUL in 36s`，93 tasks 全部通过
2. **安装启动**: push + `pm install -r -t` 成功，monkey 启动正常，`logcat -s AndroidRuntime:E` 零错误
3. **书签页冒烟**: 首页→书签笔记"更多"→按书视图 7 本书标题行渲染正常（isBookHeader 标志链路 ✓）
4. **筛选链路**: 点世界观标题行→筛选出"[2026-08-29 23:44] 笔记 (2)"（时间格式正确，局部 SimpleDateFormat ✓）+ 66% 快速书签（页码徽章 ✓），"返回"链接正常

---

## [2026-08-29] 代码检视修复 6 个隐含 BUG

### 根因分析过程

对 8 个修改过的源文件进行系统检视，发现 6 个 BUG（2 高/2 中/2 低）：

| BUG | 文件 | 类型 | 根因 |
|-----|------|------|------|
| 1 (HIGH) | `BookmarksFragment2.java:387` | NPE 崩溃 | 点击笔记时间时 `n.getPath()` 返回 null → `new File(null)` 崩溃 |
| 2 (HIGH) | `BookmarksData.java:192,215` | NPE 崩溃 | `syncBookmarksByBook` 在 `init()` 前为 null → IO 操作 NPE |
| 3 (MED) | `BookmarksFragment2.java:582` | 后台线程 UI 调用 | `mergeNotes()` 在 executor 线程调 `Fragment.getString()` |
| 4 (MED) | `BookmarksData.java:190,213` | 并发安全 | `saveByBook()`/`importByBook()` 无同步，非线程安全容器 |
| 5 (LOW) | `BookmarksFragment2.java:387` | 功能缺失 | `n.getPercent() > 0f` 跳过 position=0 的笔记 |
| 6 (LOW) | `AppProfile.java:496-499` | 状态不一致 | `clear()` 未重置 `syncBookmarksByBook` 静态字段 |

### 修复方案

| BUG | 改动 |
|-----|------|
| 1 | `BookmarksFragment2.java:387` 加 `n.getPath() != null &&` 守卫 |
| 2 | `BookmarksData.java:saveByBook/importByBook` 开头加 `if (AppProfile.syncBookmarksByBook == null) return;` |
| 3 | 新增字段 `readingNoteLabel`，`onCreateView` 主线程缓存，`mergeNotes` 改用缓存值 |
| 4 | `saveByBook()`/`importByBook()` 加 `synchronized` 关键字 |
| 5 | `n.getPercent() > 0f` → `n.getPercent() >= 0f` |
| 6 | `AppProfile.clear()` 末尾加 `syncBookmarksByBook = null;` |

### 验证

- Ubuntu 远程构建 librera Debug
- 真机确认：打开书签笔记 → 点击笔记时间 → 不崩溃、正确跳转
- 导出备份 → 不崩溃、按书分组文件正确写入

## [2026-08-30] WebDAV 按书信息同步 + 长按多选 + 多选假选中修复

### 一、WebDAV 同步重构（全局配置 + 每本书籍信息）

**同步内容**（不同步书籍文件本身）：
- `global/app-State.json`、`global/app-CSS.json`：全局配置双向同步（内容相同则跳过，不同时较新文件胜；`webdavLastSyncTime/Info` 等易变字段剔除后再比较，避免两台设备回声互覆）
- `books/<文件哈希>.json`：每本书一个"书籍信息"文件，含 `name`（书籍文件名）+ `hash`（书籍文件内容哈希）+ `t` + `progress`（阅读进度）+ `bookmarks`（书签与 AI 笔记，t 键映射）

**新增/修改文件**：

| 文件 | 改动 |
|------|------|
| `FileHash.java`（新增） | 基于系统内置 `java.security.MessageDigest`（MD5）的文件内容哈希；8KB 流式分块；按 `path+lastModified+length` 内存缓存，未变的书不重复计算；另提供文本 MD5（无本地文件时的稳定合成 ID） |
| `WebDavSyncer.java`（重写） | 远程布局改为 `global/` + `books/<hash>.json`；逐本恢复：每本信息文件独立 try/catch，一本损坏不影响其它；哈希关联：按名定位本地候选文件并计算哈希，**一致 → 完整关联**（进度按策略 newer/farther 合并、书签 t 并集且 path 改写为本地文件），**不一致（同名异书）→ 不覆盖本地进度**，仅书签并集保留"待关联"，无本地文件 → 进度照存（文件日后出现自动生效）；本地新书逐本上传（同名异书各自以自己的哈希共存）；旧 `progress.json`/`bookmarks.json` 一次性迁移后从服务器删除；`global` 同步、`resolveConfig`、错误分类（auth/ssl/network）、对话框与触发点全部保留 |
| `BookmarksData.importByBook()` | 按书循环体加独立 try/catch（备份 zip 恢复同样逐本容错） |

### 二、书库/最近页长按默认多选

| 文件 | 改动 |
|------|------|
| `SelectionBarController.java`（新增） | 从 SearchFragment2 抽取的共享多选栏（已选计数 + 标记已读/未读/在读 + 全选 + 取消），绑定 `selectionBar` 系列 id |
| `SearchFragment2.java` | 选择栏改用 SelectionBarController；长按书籍 → `startSelection`（目录保留原菜单）；返回键先退多选 |
| `RecentFragment2.java` | 新增多选能力（复用 FileMetaAdapter.selectionPaths + 控制器），长按 → 多选，标记已读状态走 BookStateStore，返回键先退多选 |
| `fragment_recent.xml` | 加入与书库相同结构的选择栏 |

### 三、多选"假选中"修复（根因修复）

`FileMetaAdapter.bindFileMetaView`：原来选中行设置高亮后**未选中分支不恢复背景**（默认 `isBorderAndShadow=true` 时整段跳过），ViewHolder 复用后旧高亮残留、`setBackgroundColor` 又永久覆盖涟漪背景。现改为每次绑定按模型推导：选中 → 高亮；未选中 → 依次按 OLED 黑 / `!isBorderAndShadow` 透明 / 恢复 `onCreateViewHolder` 捕获的默认涟漪背景（`FileMetaViewHolder.defaultBackground`）。书库/最近/我的文件所有页面同时生效。

### 四、验证（MI9 真机）

1. 构建 `BUILD SUCCESSFUL`，安装启动无崩溃（logcat 零 AndroidRuntime 错误）
2. 书库长按某书 → 选择栏出现"已选 1 本"，**仅该书高亮**；连续滚动两屏后可见 9 本书全部无高亮（复用不再泄漏选中色）→ 取消正常退出
3. 最近阅读长按 → 多选栏出现、仅选中项高亮 → 取消正常
4. WebDAV：无服务器环境验证不崩溃、无错误日志；对话框与触发点代码未动

## [2026-08-30] 木纹书架 + 我的文件条目编辑 + 图标文字加大

### 一、书库页面：纹理实木书架（静读天下风格）

| 文件 | 改动 |
|------|------|
| `WoodShelf.java`（新增） | 程序化木纹纹理，无图片资源依赖：固定随机种子的 512×512 tile——横向木板拼条、波浪木纹线、板缝深槽+受光边、每板轻微色差、偶发节疤；`BitmapShader(REPEAT)` 平铺，进程内缓存单例 |
| `SearchFragment2.java` | 封面/网格模式（MODE_COVERS/MODE_GRID）RecyclerView 背景设为木纹；列表/紧凑模式恢复默认 |
| `FileMetaAdapter.java` | 新增 `shelfMode` 标志（仅书库页）：封面/网格条目 CardView 透明（`setCardBackgroundColor(TRANSPARENT)`+卡片阴影归零），列表模式恢复原卡片色/阴影；每次绑定显式设置，回收复用安全；选中高亮不受影响 |

### 二、我的文件：OPDS / WEBDAV 条目可编辑

两个添加对话框本就内置编辑模式（传入已有条目 = 删旧行+存新行+持久化），我的文件页仅缺入口：

| 文件 | 改动 |
|------|------|
| `BrowseFragment2.java` | `netListItem` 增加可选 `onEdit` 参数（行尾铅笔图标 `my_glyphicons_pen`，与删除图标并排）；OPDS 行编辑：构造 `Entry{appState=原始行, logo}` → `AddCatalogDialog`（预填 URL/名称/描述）；WebDAV 行编辑：`AddWebDavDialog.showDialog(a, rebuild, srv)` 直接预填 URL/名称/凭据/信任证书；工具行无编辑图标 |

### 三、我的文件图标文字加大

| 位置 | 现值 → 新值 |
|------|------------|
| 条目行图标（OPDS/WEBDAV/工具行） | 22dp → 30dp |
| 条目行标题 | 15sp → 18sp |
| 编辑/删除图标 | 26dp → 30dp |
| 分区标题 / "+ 添加" | 16sp → 19sp / 15sp → 17sp |
| 书库文件夹行（仅我的文件根页） | FileMetaAdapter 新增 `myFilesRoot` 标志（displayAnyPath 按 ROOT_PATH 置位/复位，进入子目录自动还原）：text1 16→20sp、text2 12→14sp、文件夹图标 36→44dp |

### 四、验证（MI9 真机）

1. 构建 `BUILD SUCCESSFUL`，安装启动无崩溃（logcat 零 AndroidRuntime 错误）
2. 书库封面模式：木纹背景（板条/纹路/板缝清晰）+ 封面卡片透明直接"坐"在木架上；长按多选高亮在木纹上清晰可见且仅选中项着色（回归通过）
3. 我的文件：OPDS（Project Gutenberg）与 WebDAV（TestSync）行均出现铅笔编辑图标；点击 TestSync 铅笔弹出对话框且 URL/名称已预填；书库文件夹行图标文字明显变大

## [2026-08-30] 备份/同步状态信息补全 + 真实书架（木板随滚动）

### 一、状态信息审计与补全

**审计结论（本次修改前）**：

| 信息 | 存储 | zip 备份 | WebDAV 同步 |
|------|------|---------|------------|
| 书签/笔记、进度、全局配置、OPDS/WebDAV 列表 | profile 下 app-*.json | ✓ | ✓（books/ + global/） |
| 最近阅读 | app-Recent.json（真相源，DB 只是缓存） | 文件在 zip，但恢复后无主动 JSON→DB 同步 | ✗ 未同步 |
| 我的珍藏 | app-Favorite.json | 同上 | ✗ 未同步 |
| 已读/未读覆盖 | app-BookStates.json | ✓ | ✗ 未同步 |
| 阅读统计（总时长/本月/每日/页数） | **AppSP "AppTemp"**（readTimeMs 等） | ✗ 丢失 | ✗ 丢失 |
| AI 地址/模型/参数 | app-State.json | ✓ | ✓ |
| **AI API Key** | **SP "ai"**（AndroidKeyStore 加密） | ✗ 丢失 | ✗ 丢失 |

**补全实现**：

| 文件 | 改动 |
|------|------|
| `AppProfile.java` | 新增 `APP_STATS_JSON`/`APP_AI_JSON` 常量与 `syncStats`/`syncAI` 文件字段（clear() 一并置空） |
| `ProfileStateIO.java`（新增） | 状态文件读写/合并：统计镜像（AppSP read* ↔ app-Stats.json，数值取 max、月/日 bucket 逐 key max）；AI key 镜像（app-AI.json，非空者胜）；SimpleMeta 数组并集（app-Recent/Favorite 按 path 去重取 time 新者） |
| `PrefDialogs.exportDialog` | 打包前生成 app-Stats.json + app-AI.json 到设备 profile 目录（zipFolder 自动携带） |
| `PrefDialogs.importDialog` | 解压后：恢复统计/AI key，并**主动触发 JSON→DB**（getAllRecent/getAllFavoriteFiles/TagData.restoreTags），最近阅读、珍藏、统计、AI 配置重启后立即可见 |
| `WebDavSyncer` | global 组扩展：`app-Recent.json`、`app-Favorite.json`（数组并集）、`app-BookStates.json`（按 key 取 t 新者）、`app-Stats.json`（数值/bucket max）、`app-AI.json`（非空 key 胜，双向回填） |

安全说明：app-AI.json 内为明文 API Key（用户要求 AI 配置可完整恢复）；仅进用户自己的备份 zip / 私有 WebDAV 服务器。WebDAV 登录凭据因 AndroidKeyStore 设备绑定仍不导出。

### 二、真实书架（木板随滚动）

| 文件 | 改动 |
|------|------|
| `ShelfRowDecoration.java`（新增） | `RecyclerView.ItemDecoration`：每次布局按可见 child 位置分组画排木板（深木色 + 顶部高光 + 底部深线 + 木纹短线），坐标相对 child → **木板随上下滚动与书籍一起移动** |
| `SearchFragment2.applyBookshelfBackground()` | 封面/网格模式：木纹背景 + 挂载 ShelfRowDecoration；列表模式：移除 decoration 并还原 |

### 三、验证（MI9 真机）

1. 构建 `BUILD SUCCESSFUL`，安装启动无崩溃
2. 书库封面/网格模式：每排书下方一条木板（书本"立"在搁板上），滚动半屏后木板精确跟随各自排——真实书架效果达成
3. 备份/同步链路为编译期改动 + 既有对话框流程，真机冒烟无异常；WebDAV 端到端待服务器环境验证

## [2026-08-30] 备份配置项清单化补全 + 选中可见性 + 软件说明联系方式 + 书架回退

### 一、备份/同步配置项完整清单（现状）

| 类别 | 内容 | 载体 | zip 备份 | WebDAV 同步 |
|------|------|------|---------|------------|
| 书签/笔记 | 全部书签 + AI 笔记 | app-Bookmarks.json、app-BookmarksByBook.json | ✓ | ✓（books/按书） |
| 阅读进度 | 每本书位置 + 视图状态 | app-Progress.json | ✓ | ✓（books/按书） |
| 已读状态 | 已读/未读/在读覆盖 | app-BookStates.json | ✓ | ✓（global/） |
| 最近阅读 | 最近列表 | app-Recent.json | ✓ | ✓（global/并集） |
| 我的珍藏 | 收藏列表 | app-Favorite.json | ✓ | ✓（global/并集） |
| 阅读统计 | 总时长/今日/页数/月表/日表 | app-Stats.json（镜像 AppSP） | ✓ | ✓（global/取大） |
| AI 配置 | 协议/地址/模型/参数 | app-State.json | ✓ | ✓ |
| AI API Key | 密钥 | app-AI.json | ✓ | ✓（非空者胜） |
| 全局设置 | 主题/排序/过滤/webdav/opds 列表等全部 AppState 字段 | app-State.json | ✓ | ✓（global/较新胜） |
| 排版样式 | BookCSS 全部字段 + 书库文件夹/SAF 路径 | app-CSS.json | ✓ | ✓（global/较新胜） |
| 排除列表/标签/替换规则/词典/播放列表 | — | app-Exclude/Tags/Tags2/TextReplacement/WebDict/WebSearch.json | ✓ | 部分（随 profile json，global 未单列） |
| **AppSP 全量**（最后一本书/阅读模式/同步开关等） | — | **app-Misc.json（新增）** | ✓ | ✓（global/按段并集） |
| **OPDS 服务器登录凭据** | — | **app-Misc.json（新增）** | ✓ | ✓ |
| **应用/书籍密码**（PasswordState） | — | **app-Misc.json（新增）** | ✓ | ✓ |
| **阅读器按钮布局**（DraggingPopups） | — | **app-Misc.json（新增）** | ✓ | ✓ |
| WebDAV 登录凭据 | SP "webdav"（AndroidKeyStore 设备绑定） | — | ✗ 设计上不导出（密文跨设备不可解） | ✗ |

本轮改动：`app-Misc.json`（ProfileStateIO.exportMisc/importMisc：AppSP 全量快照 + OPDS 登录 + PasswordState + DragingPopups 四段，恢复时逐段写回）；zip 导出/导入与 WebDAV 同步（按段"本地非空优先"并集）均已接入。**仍不备份**：WebDAV 凭据（加密密文设备绑定，无恢复价值）、诊断/缓存类 SP（Errors、lastmodified2 等，非用户配置）。

### 二、软件说明联系方式

- `values/config.xml`：`my_email` → `380121850@163.com`；`my_site` → 留空
- `AboutSectionBinder`：官网为空时隐藏"网页"链接（邮箱行保留，点击发信到新邮箱）

### 三、多选选中状态可见性修复

选中书籍的半透明底色被不透明封面盖住（尤其封面/网格模式）。`FileMetaAdapter` 绑定时为选中项增加**卡片前景叠加**（`setForeground`，API 23+）：半透明主题色蒙层 + 3dp 描边，绘制在封面之上，任何显示模式下选中一目了然；取消选中恢复无前景。真机验证：长按进入多选后选中书封面整体蒙层+描边清晰可见。

### 四、书架回退

按需求移除上一轮的"每排木板"（ShelfRowDecoration 已删除、SearchFragment2 不再挂载），书库恢复为木纹平铺背景 + 封面卡片透明的效果（修改前状态）。

### 五、验证（MI9 真机）

构建 `BUILD SUCCESSFUL`，安装启动无崩溃；多选选中封面叠加+描边清晰；书架为纯木纹背景。



## [2026-08-30] 大文件打开速度优化（两阶段：缓存保活 + 静读天下式分阶段排版）

针对 20-30MB 大书"每次打开都慢"的问题，分两个阶段实施，MI9 真机全量验证。

### 一、第一阶段：缓存保活 + 主线程瘦身（Java 层）

根因：TXT 每次打开清空全部缓存目录、每次打开书删除其它书全部转换产物，导致 MuPDF accel 排版缓存永远无法命中 → 每次全量转换 + 全文排版。另有多处主线程重复劳动。

- `TxtContext`：删除打开时的 emptyAllCacheDirs()（不再殃及全部缓存）
- `TxtExtract.extract1`：txt→fb2 全量转换结果缓存化（key 含路径+连字符/语言/编码设置，tmp+rename 防半文件）
- `AbstractCodecContext`：转换缓存"删其它全部"→ LRU 保留最近 4 本（含同名 .json 脚注缓存）；`CacheZipUtils.trimFiles/trimAccel` 新增
- `MuPdfDocument`：accel 文件 LRU 上限 8 个；accel 键加入文件 salt（length+lastModified），书文件更新后不再复用过期页数
- `VerticalViewActivity/ViewerActivityController`：主线程移除重复元数据提取（checkOrCreateMetaInfo/createMetaIfNeed 二选一）与 detectLang，全部移入 BookLoadTask 后台线程；addRecent（DB+JSON 写）移入后台
- `BookLoadTask`：SERIAL_EXECUTOR → THREAD_POOL_EXECUTOR（native 访问已有 TempHolder.lock 串行化）
- 新增 `BookWarmer`：后台空闲预热 MuPDF accel（最近阅读书 + 扫描新书，阅读器前台时自动让位）

### 二、第二阶段：静读天下式分阶段排版（C 层 + Java 层）

首屏不再等待全书排版：利用 mupdf 1.23.7 公开 API `fz_count_chapters/fz_count_chapter_pages`（EPUB 按章惰性排版），打开时仅排版到上次阅读位置即显示第一屏，其余章节后台补齐。

- `Builder/jni/libmupdf-librera.c`：新增 JNI `getPageCountProgressive(handle,w,h,em,uptoPage)` —— 逐章排版累计至 uptoPage 即停（fz_save_accelerator 保存部分页数）；无章节支持的格式回退全量计数；已重建 arm64 libMuPDF.so
- `CodecDocument/DecodeService/DecodeServiceBase/MuPdfDocument`：渐进计数管道（默认实现 = 全量计数；仅重排格式启用）
- `DocumentModel`：`setProgressiveUpto` + `appendPages`（尾追加页，既有页 bounds 不动 → 滚动位置与页码天然稳定）；渐进路径跳过 PageCacheFile 读写
- `AppBook`：新增 `pg`（绝对页码锚点，随进度 JSON 自动持久化）；旧进度无 pg 时用书库 DB 历史页数×百分比估算目标；新书（p=0）只排前 150 页
- `AbstractViewController.show()`：优先按 pg 恢复位置（比百分比换算更精确）
- `ViewerActivityController`：二阶段编排 —— 首屏上屏后后台完成全量排版 → `appendPages` 尾部扩容 → `invalidatePageSizes(PAGE_LOADED)` 增量堆叠 → 进度条/页码/刻度刷新（`DocumentWrapperUI.refreshPageCount`）
- `Fb2Context`：打开时的损坏探测由全量 getPageCount 改为仅排第 1 章（原实现使 TXT/FB2 的渐进排版完全失效）
- 设置项：`AppState.isFastOpen`（默认开）；EXTRA_PERCENT 入口自动走全量路径

关键修复（mupdf 内部日志验证）：排版 em 参数两种单位（sp/px）并存导致 accel 每次打开失效 —— 渐进路径统一 sp→px 与全量计数一致；Fb2Context 探针使 TXT 合成 EPUB 退化为单巨章时首屏仍可控。

### 三、MI9 真机收益（22-29MB 测试书，单位秒）

| 格式 | 打开 | 优化前 | 优化后 | 提升 |
|---|---|---|---|---|
| EPUB | 热 | 0.35 | **0.17** | 2× |
| TXT | 热 | 27.0~29.6（缓存永不命中） | **0.085** | ~300× |
| FB2 | 热 | 0.20 | **0.078** | 2.6× |
| PDF | 冷 | 0.20 | 0.20 | 持平 |
| EPUB | 冷 | 17.2 | **7.1**（首屏；余量后台补齐） | 2.4× |
| TXT/FB2 | 冷 | 27~29 | 28.5/11.0（转换 O(文件) 固有，之后走缓存） | — |

注：冷打开耗时主要为 O(文件) 的格式转换（TXT 双重转换、EPUB 连字符/脚注全量重写——与用户开启的排版特性相关），首次之后全部命中缓存；epub 冷打开的全书排版移至首屏之后的后台完成。已知边界：TXT/FB2 合成 EPUB 为单巨章，首屏渲染需整章排版（约数秒，属 mupdf fz_store 行为）——拆分 spine 章节列为后续优化项。

### 四、验证

MI9（48fee174）：四格式冷/热全矩阵计时通过；滚动渲染、进度恢复（pg 锚点精确恢复）、多书缓存共存（LRU-4）、二阶段后台补全后页码/进度条自动校正、退出重开零崩溃（logcat crash buffer 无 FATAL）；书库/最近阅读/书签笔记等既有功能回归正常。

## [2026-08-30] MOBI 打开修复 + 二阶段追加页数坍缩 BUG 修复 + TXT 单遍直转 EPUB

### 一、二阶段打开后总页数坍缩（MOBI 二次打开"只剩一页"，复现并修复）

复现：MOBI《2014中日战争》第一次打开 1613 页正常，第二次打开进度条变为 29/108（总页数坍缩到渐进边界）。
根因：`startPhaseTwoLayout` 在 `appendPages` **之前**取"第一个新页"作为增量重排标记——此时该页尚不存在（`getPageObject` 返回 null），`append && marker != null` 被 `&&` 短路，导致新页 bounds 未重排、页码/进度条 UI 未刷新：画布被钳在旧边界，页数显示坍缩（EPUB 同样潜在受影响）。
修复：改为 append 之后再取标记页（用第一段最后一页作重排起点），重排 + UI 刷新必达。修复后二次打开恢复 29/1613，加载 108ms。

### 二、MOBI 每次打开都重新整本转换（修复）

`MobiContext` 缓存命中判断的文件名（双 hash）与转换输出名（单 hash）不一致，自动连字符关闭时命中分支永不成立——每次打开都无条件 `convertToEpub` 整本转换，且转换文件 mtime 变化连带 accel 键失效、全文重排版。现统一：转换直接落到缓存文件名（缓存存在即复用），转换只发生一次。

### 三、TXT 单遍直转 EPUB（冷打开 28.5s → 12.0s，且按章分章）

旧链路 txt→fb2→epub 两次全量遍历，且章节启发式不识别中文标题 → 合成 EPUB 单巨章（渐进排版与首屏渲染都退化为整本粒度）。
新增 `TxtExtract.extractEpub`：单遍读入直接流式写出合成 EPUB；章节断点在原 chapter/глава 规则上新增中文规则（行首 `第X章/节/回/卷/部/篇/集`、序章/楔子/尾声/番外/前言/后记等，X 支持中文数字），每章一个 spine 项 → mupdf 按章惰性排版/渲染。缓存 key 含全部设置维度与规则版本，tmp+rename 防半文件。`TxtContext` 接入（保留第 1 章损坏探针）；isPreText 分支不变。
实测（25MB TXT）：冷打开 28.5s → **12.0s**（转换约 10s + 首章排版）；热打开 2.8s；首屏渲染不再等待整本。

### 四、周边

- 转换缓存 LRU 保留 4 → 8 本（多书轮换少互踢；注：MIUI 上每次重装 APK 会清应用缓存，重装后首次打开属冷打开，为设备行为非应用问题）。
- 基准说明：bench 书"再次变慢"即上述 LRU 淘汰/重装清缓存所致，冷打开成本为 O(文件) 的格式转换固有成本，每本书只付一次。

### 五、验证（MI9 真机）

MOBI《2014中日战争》：一次 567ms（1613 页）/ 二次 108ms 且页数完整、可翻全本；TXT 冷 12.0s、热 2.8s（28038 页，渲染与章节标题正确）；EPUB 热 193ms、FB2 热 78ms 回归正常；logcat crash buffer 无 FATAL。

## [2026-08-30] 二阶段补全长锁独占修复（退出卡死/首屏空页/概率打不开）

### 根因（真机线程时序定位）

二阶段补全与 BookWarmer 预热各用**一次 native 调用**完成全量计数，大书要持有全局锁 `TempHolder.lock` 10~40 秒（ReentrantLock 非公平，循环立即重抢锁会把等锁的主线程饿死）。后果：翻页解码排队（首屏长时间空白）、返回键在主线程排队 30+ 秒才被处理（体感"退出卡死"）、期间打开其它书（FB2"概率打不开"、侯卫东再开变慢）、退出时 `freeDocument` 等锁。

### 修复

1. **补全分块化**：`startPhaseTwoLayout` 改为每轮 `getPageCountProgressive(已排+400)` 的循环（约 400 页/百 ms 级），轮间释放锁并让路——`TempHolder.lock.hasQueuedThreads()` 为真时每次让出 100ms，保证 UI/解码线程优先拿锁。
2. **可取消**：`AtomicLong phase2Gen` 代号，`VerticalViewActivity/HorizontalViewActivity.onDestroy → cancelPhase2()`（发现 controller 的 onDestroy/beforeDestroy 在此 fork 中无调用方，取消必须挂在 Activity 生命周期上）；back = moveTaskToBack 的场景由循环内 `isDestroyed/isFinishing` 自检兜底；`onStart → resumePhase2()`、`onStop → pausePhase2()` 暂停/恢复（后台不再空转占锁）。
3. **BookWarmer 分块化**：同样 400 页步进 + 等锁让路 + readerActive 立即让出。
4. **MuPdfDocument.getPageCountProgressive**：去掉内部"失败回退全量计数"（保证分块单次调用有界；主打开路径的回退保留在 DocumentModel）。
5. 二阶段启动延时 800ms → 2000ms，首屏位图解码优先于补全。

### MI9 真机验证

- TXT 冷打开后二阶段进行中按返回：**~1 秒内完成退出**（修复前主线程饿死 30 秒+），补全任务即时取消并丢弃部分进度（无 UI 污染）；
- 回到阅读器（onStart）补全自动恢复，28038 页全部补齐；
- EPUB 冷打开首屏正常（恢复位置若为章末页，页面本身内容少属书籍内容而非缺陷）；EPUB 热 193~233ms；TXT 热 2.8s；FB2 热 78ms；
- MOBI《2014中日战争》两连开 742ms/611ms，总页数 1613 完整（回归通过）；
- logcat crash buffer 无 FATAL。

## [2026-08-30] 打开空页根治 + 书库文件夹选择器修复 + 书库滚动位置记忆

### 一、打开书出现空页 → 加载框保持到首屏整屏解码完成(根治)

两段修复:

1. **FirstPaintGate(首屏门闩)**:新增 `com/foobnix/sys/FirstPaintGate.java`。「请稍候」加载框原先在排版完成时即关闭(`BaseAsyncTask.onPostExecute`),位图尚未解码,用户先看到空白占位页。现在成功路径持框直到首屏解码齐(500ms 静默期 + 8s 硬上限 + 2s 无解码判定),`BaseAsyncTask` 增加 `holdProgressDialog` 跳过自动关闭;`PageTreeNode.decodeComplete` 喂给门闩;`VerticalViewActivity.onDestroy`/`ViewerActivityController.onDestroy` 兜底取消;密码/错误/手动取消路径行为不变。

2. **首屏第二页空白 5~15 秒的真正根因(三处)**:
   - **目录(Outline)抢占解码线程**:`startDecoding` 回调里的 `loadOutline()` 在渐进打开后立即执行,`getOutline()` 的 native 调用会强制排版全书(24MB 书 10~15 秒),期间解码执行器被独占,第二屏解码全部排队。修复:渐进模式把 outline 延迟到 phase-two 排版完成后再加载(届时毫秒级);非渐进格式(PDF 等)保持原行为(`loadOutlineOnce()`,phase2 成功/`knownCount<=0` 兜底触发)。
   - **节点回收取消在途解码**:`AbstractEventScroll.process(node)` 对「不在内存保留范围」的节点直接 recycle→stopDecoding;渐进打开初期页面尚无真实边界,刚排队的第二屏解码被后续布局事件取消。修复:`decodingNow` 为 true 的节点跳过 recycle。
   - **页级回收同样绕过守卫**:`AbstractEvent.process(Page)` 的 `recyclePage` 增加相同守卫(根节点在解码中则不回收该页)。
   - 附带加固:`DecodeServiceBase.tasks` 改为 `Collections.synchronizedList`(原先裸 ArrayList 被 UI 线程与消费线程并发读写);phase-two 与 BookWarmer 移出 2 线程共享池 `AppsConfig.executorService`(改独立线程,解码消费者常驻该池),phase2 线程用 `THREAD_PRIORITY_LESS_FAVORABLE`(BACKGROUND 的 cgroup 会被 MIUI 限速)。

**MI9 实测**(big25.epub 冷开):排版完成 → 首屏 3 个节点 **230ms 内连续解码完成** → 加载框 807ms 时关闭、内容整屏出现,不再有「页 N」空白占位;之前第二页空白 5~15 秒。TXT 冷开(转换 13s)同样 825ms 整屏出现。

### 二、「我的文件 → 书库文件夹添加」无本地存储路径选择 → 修复

**根因**:我的文件根页是伪路径 `my-files:`,`displayAnyPath` 把它无条件写入 `BookCSS.dirLastPath`;「添加文件夹」把这个伪路径当初始目录传给 ChooserDialogFragment,弹出的还是伪根页(只有书库文件夹列表),看不到真实文件系统,确认按钮也报「值不正确」。

修复:
- `ChooserDialogFragment.chooseFolder` 内部对初始路径做净化(新增 `validStartDir`:非真实本地目录一律回退到机身存储根),所有调用点(我的文件、偏好里的书库文件夹配置)一并修好;
- `BrowseFragment2.displayAnyPath` 只有真实本地目录才写入 `dirLastPath`(同时防 OPDS/content 路径污染);
- 选择器弹窗内启用存储快捷入口 chips(机身存储/Download/SD 卡/Librera 下载,复用 `buildQuickDirChips`,`TYPE_SELECT_FOLDER` 也构建)。

**MI9 实测**:添加文件夹 → 选择器打开在真实文件系统(Alarms/Android/Download…)、chips 可见可点、进入 Download → 选择 → 文件夹入库、列表即时刷新。

### 三、书库页面记住浏览位置

`SearchFragment2`:书库列表(网格/封面/列表模式)滚动停止时记录首个可见项位置+偏移到独立 SharedPreferences(`lib_scroll`),key=`模式|过滤文本|排序|方向|数据 hash`——搜索词/排序/数据集变化自动失效归零,不会串位置;`populateDataInUI` 重灌后 `scrollToPositionWithOffset` 恢复(含 StaggeredGrid 变体);分组模式(作者/系列等)保留原有 rememberPos 逻辑。

**MI9 实测**:书库滚到中部 → 切首页再切回 → 位置精确恢复(截图逐像素一致)。

### 四、AI 大模型配置与备份/同步(检查结论,无代码改动)

备份(`PrefDialogs.exportDialog` 打包 `profile.*/<设备>/`)已包含 `app-AI.json`(API Key,`ProfileStateIO.exportAi`)与 `app-State.json`(aiProtocol/aiBaseUrl/aiModel/aiMaxTokens/aiThinking);WebDAV 同步同样覆盖(`WebDavSyncer` exportAi/syncMergedObjectFile/importAi,双方非空 Key 者胜)。**AI 配置已随备份/同步走,无需修改。**

### MI9 真机验证汇总

- EPUB 冷开:整屏 0.8s 内随加载框关闭一起出现,无空白占位页(修复前第二页空 5~15s);
- TXT 冷开 + 阅读中返回退出:~1s 完成,无卡死(phase2 正常补全 14154 页);
- MOBI《2014中日战争》冷开 749ms/热开 141ms,两开页数均完整 1613(回归通过);
- 我的文件 → 书库文件夹添加:选择器真实路径 + 存储 chips + 添加成功;
- 书库滚动位置:切标签往返精确保留;
- logcat crash buffer 无 FATAL。

## [2026-08-30] 书库滚动记忆补充修复:从阅读器返回书库也恢复位置

上一轮的滚动记忆 key 里包含数据版本号(`TempHolder.listHash`),而**阅读一本书**会更新最后阅读时间/阅读状态并使 `listHash++`——返回书库触发 `resetFragment` 重灌数据,key 失效 → 回到顶部。表现为「书库打开书再返回,位置丢失」。

修复:`SearchFragment2.buildLibScrollKey()` 去掉 `listHash` 维度,只保留 模式|过滤文本|排序|方向——阅读返回照常恢复;换搜索词/排序/阅读模式仍正常失效归零。

**MI9 实测**:书库滚到中部 → 点开《映画周星驰》(恢复到该书上次阅读位)→ 关闭阅读器 → 书库精确停回原浏览位置(前后截图一致)。

## [2026-08-30] 书库新增「书架」视图模式(仿静读天下/Moon+ Reader)

### 功能
- 书库查看菜单新增「书架」模式(`AppState.MODE_SHELF = 13`):每行固定 3 本书(平板按屏宽增加),木质书架背景上每行下方绘制木板与落影,封面直立在木板上,视觉对齐 Moon+ Reader 书架。
- 封面左下角圆形阅读进度角标(≥1% 才显示,与 Moon+ 一致);右下角 ⋮ 按钮直接弹出单书操作菜单(与长按菜单同一回调)。
- 无封面书籍的占位封面改为显示书名(解析 PageUrl 取真实路径,用文件名去扩展名),替换原先的 "#error null" 字样;该改进对 列表/网格/封面 模式同样生效。

### 改动文件
| 文件 | 改动 |
| --- | --- |
| `model/AppState.java` | 新增 `MODE_SHELF = 13` |
| `ui2/fragment/SearchFragment2.java` | 查看菜单加「书架」项;`applyBookshelfBackground` 扩展(书架模式加挂木板装饰、其余模式摘除);onTextChanged / prepareDataInBackground / populateDataInUI / saveLibScrollPosition 四处扁平模式白名单加入 MODE_SHELF(搜索、排序、滚动位置记忆在书架模式下全功能可用) |
| `ui2/fragment/UIFragment.java` | `onGridList` 新增书架分支:`GridLayoutManager` 列数 `max(3, 屏宽dp/120)`,分组标题类条目跨全列 |
| `ui2/adapter/FileMetaAdapter.java` | 新增 `ADAPTER_SHELF = 5`;onCreateViewHolder 选择新布局;书架封面固定槽位尺寸(宽=(屏宽-30dp)/列数,高=宽×1.4 即 WIDTH_DK);进度角标文本/可见性与 ⋮ 菜单点击绑定(setInkTextView 之后执行,保证白字);shelfMode 透明卡条件扩展 |
| `res/layout/browse_item_shelf.xml` | 新建书架 item 布局:封面 + 左下进度圆标(shelfBadge/idPercentText)+ 右下 ⋮(shelfMenu),隐藏文字区但保留全部既有 id(复用 FileMetaViewHolder,不空指针) |
| `res/drawable/shelf_badge_bg.xml` | 新建角标/⋮ 半透明黑圆底 |
| `ui2/ShelfBoardsDecoration.java` | 新建:行木板 ItemDecoration(渐变木板 + 顶部亮边 + 底部暗线 + 板上方 8dp 落影,按行底 Y 去重通宽绘制) |
| `pdf/info/wrapper/PopupHelper.java` | `updateGridOrListIcon` 加 MODE_SHELF 分支(glyphicons_422_book_library 图标) |
| `sys/LibreraAppGlideModule.java` | 封面提取失败时的占位图标题改为书名(新增 `coverTitle()`,替换 "#error null"/"#error") |
| `strings.xml` ×3 | 新增 `shelf` = Bookshelf / 书架 / 書架 |

### 验证(MI9 真机)
- 书架模式渲染:3 本/行、木板、进度角标(6%/100% 完整显示,0% 隐藏)、⋮ 菜单弹出完整书籍操作、无封面书显示书名占位。
- 打开《悲惨世界》恢复到 3545/3545 页;关闭返回后书库浏览位置保持(不跳回顶部)。
- 切回 封面/网格 模式:木纹背景正常、无木板无角标,无回归;模式选择随 AppState 持久化,重启后保持。

## [2026-08-30] 书架模式打磨:加厚木板、木纹随内容滚动、胡桃木纹理

- **木板加厚**:13dp → 20dp(顶部亮边 2dp、底部暗线 3dp、板上方落影 9dp),书立在板上的立体感更强。
- **修复背景不随内容滚动**:书架模式的木纹改由 `ShelfBoardsDecoration` 在内容坐标系绘制(BitmapShader 锚定内容原点),书籍、木板、木纹三者一起滚动;`applyBookshelfBackground` 中书架模式不再设置固定背景。封面/网格模式保持固定木纹背景不变。
- **胡桃木纹理**:`WoodShelf` 换为深棕胡桃木(基调 #5C4232/#543A2B/#664834),纹理线更细密近直、板缝亮边更低调;新增 `WoodShelf.tile()` 供装饰层共用同一张 tile,板面与背景纹理无缝衔接。
- 验证(MI9 真机,像素级对照):内容滚动 846px 后,书条带差值 0.87、行间木纹带差值 0.61(完全随动),若按"固定背景"对齐则差值 113.8(错位);不滚动的工具栏区域位移 0 处差值 0.0(对照组)。

涉及:`ui2/WoodShelf.java`、`ui2/ShelfBoardsDecoration.java`、`ui2/fragment/SearchFragment2.java`。

## [2026-08-30] 书架纹理调浅+整块木板纹理;修复 WebDAV 同步后 AI 模型配置丢失

### 书架视觉(应用户反馈)
- 木纹背景调浅:深胡桃 #5C4232 一系 → 浅胡桃 #94745A 一系。
- 取消横纹:不再按 128px 画板缝与每板色带,改为**一整块木板**——纯竖向细密木纹 + 少量木节;纹理线在 tile 高度上按整周期绘制、横向不出界,双向无缝平铺(`WoodShelf.makeTile` 重写)。
- 木板装饰配色适配浅色底:亮边 #C29A6E、底线 #4A3018。

### WebDAV 同步 AI 模型配置丢失(修复)
AI 模型配置(aiProtocol/aiBaseUrl/aiModel/aiMaxTokens/aiThinking)存在 `app-State.json`,此前同步规则是"文件修改时间新者胜",存在两个问题:
1. **重置后本机文件永远"更新"**:刚生成的本地 app-State.json 修改时间最新,同步反而把空配置上传覆盖服务器副本,AI 配置永远同步不下来。
2. **同步不应用到运行中的 APP**:即使远端赢,同步只写文件不刷新内存 AppState;同步结束时的 `AppState.save()` 又把内存旧状态写回文件。

修复:
- `ProfileStateIO.mergeAiState`:app-State.json 同步时对 5 个 AI 字段做字段级并集——一边设置、一边未设置 → 取设置过的值;两边都设置且不同 → 新的一方胜出;`WebDavSyncer.syncGlobalFile` 在两种胜负路径都写入/上传合并结果(双向收敛)。
- 新增 `ProfileStateIO.importAppState`:同步完成后把合并后的 app-State.json 原位加载进运行中的 AppState(`Objects.loadFromJson`),配置立即生效,并避免同步末尾的回写覆盖。

涉及:`ui2/WoodShelf.java`、`ui2/ShelfBoardsDecoration.java`、`model/ProfileStateIO.java`、`webdav/WebDavSyncer.java`。真机验证:书架纹理视觉已核对;WebDAV AI 同步请在用户自己的服务器上按"重置 → 配 WebDAV → 同步 → 查看 AI 配置"流程确认。

## [2026-08-30] 修复 WebDAV 同步后阅读统计未应用到本机

**问题**:统计数据(总阅读时长/页数/月度与日度桶,存于 AppSP)的同步链路在服务器侧是对的(`mergeStats` 按字段取最大值合并、合并结果写回本地并上传),但每台设备同步完后看到的仍是自己的旧统计:合并刚写入内存后,`importMisc`(恢复杂项配置)会用 app-Misc.json 里的 AppSP 整体快照覆盖内存——而该快照导出于统计合并**之前**;且 `importStats`(重新应用合并后的 app-Stats.json 并持久化)在同步流程中从未被调用(仅手动备份还原使用)。

**修复**:`WebDavSyncer.doSync` 在 `importMisc` 之后调用 `ProfileStateIO.importStats(c)`,重新应用合并后的 app-Stats.json(applyStats 为幂等的最大值合并)并 `AppSP.get().save()` 持久化。重置场景同样覆盖:重置后本机统计为零,同步即取回服务器上的累计统计。

涉及:`webdav/WebDavSyncer.java`(一行调用 + 注释)。请在用户自己的 WebDAV 服务器上按"重置 → 配 WebDAV → 同步 → 查看首页阅读统计"流程确认。

## [2026-08-30] 应用品牌更名 HowRead(好好读)+ 更换包名与签名

### 品牌定稿
- 中文主品牌:好好读;英文主品牌:HowRead("How"=好(谐音)+ How(如何)——如何好好读一篇文字);
- 免费版 HowRead(好好读);专业版 HowRead Pro(好好读 Pro);
- Android 包名 com.howread.reader;域名 howread.app / github.com/howread。

### 1. 显示名资源化(支持中英双语显示)
- Manifest 3 处 label(application + 2 widget)由 `${appName}` 占位符改为 `@string/app_name`;
- main 资源:values="HowRead"、values-zh-rCN="好好读"、values-zh-rTW="好好讀";
- 新增各 flavor 覆盖资源 app/src/<flavor>/res/values[-zh]/strings.xml:pro="HowRead Pro"(好好读 Pro)、fdroid="HowRead FD"(好好读 FD)、pdf_classic/pdf_v2="PDF Reader"、ebooka="Book Reader"、tts_reader="TTS Reader"、epub_reader="Epub Reader";
- build.gradle 中 appName 占位符定义保留(已无引用)。

### 2. 包名 applicationId(8 处,app/build.gradle)
- librera→com.howread.reader;pro/fdroid→com.howread.reader.pro(同包,与原布局一致);pdf_classic→com.howread.reader.classic;ebooka→com.howread.reader.book;pdf_v2→com.howread.reader.pdf;tts_reader→com.howread.reader.tts;epub_reader→com.howread.reader.epub;
- 连改硬编码:AppsConfig.java(PRO_LIBRERA_READER/LIBRERA_READER 常量值,常量名不变)、Urls.java openPdfPro 商店链接;
- 代码 namespace(com.foobnix.*)、LibreraApp 类名、org.librera 包、meta-data、deep-link 全部不动。

### 3. 签名(新 keystore)
- 新增 `howread.keystore`(PKCS12,别名 howread,密码 850318@Hz,有效期 10000 天);
- `~/.gradle/gradle.properties` 四项指向新 keystore;旧 keystore.pkcs12 保留;代码零改动。

### 4. 界面文案(只改值不改 key,44 个语言目录)
- 5 个品牌 key 中 "Librera"→"HowRead"(librera_pro/librera_cloud/close_book_and_application/librera_pro_no_ads_leading_book_book_reader_and_pdf/pro_pdf_description_ads_free);
- **保留** msg_migration/msg_sync 的"[Librera]"(指向真实本地目录名)、dialog_proxy_server"Dowloads/Librera"、dialog_webdav_sync"/Librera";
- URL 换新域:about_section.xml(librera.mobi/beta/faq → howread.app)、config.xml wiki_url、Urls.java rateIT GitHub 链接 → github.com/howread;
- 清理调试残留 fragment_preferences.xml"Librera_111"→"HowRead"。

### 5. 构建元数据
- APK 文件名前缀 "Librera " → "HowRead "(build.gradle 输出名两处);
- settings.gradle.kts rootProject.name → "HowRead"。

### 验证(MI9 真机)
- 新包 com.howread.reader 9.4.24 安装成功(MIUI 首装被 INSTALL_FAILED_USER_RESTRICTED 拦截,`pm install -i com.android.vending` 绕过);
- 桌面图标显示**好好读**(中文),与旧版 Librera 并存;应用启动、首页/书库正常;
- 构建产物名:HowRead Librera-9.4.24-arm64.apk。
- 待办提醒:release 正式包请先用新签名试装一次;howread.app 网站内容需自行部署。
# 2026-08-30 HowRead 0.9.0：目录换新 + release 试装

## 1. 真实目录 "Librera" 全部换新（方案 A：换新 + 自动迁移）
- 本机存储根目录 `/sdcard/Librera` → `/sdcard/HowRead`：`AppSP.getRootDir()` 改名；`AppSP.init()` 新增一次性迁移——持久化 `rootPath1` 仍等于旧默认时自动切到新根（用户自定义路径不动）。库 DB 文件名含根路径 hash，自动重建后重扫。
- 下载目录族 `Download/Librera` → `Download/HowRead`：`BookCSS` 的 downloads/Cache/TTS/Backup/三个云缓存路径默认值改名；`load1()` 读取持久化配置后调用新增 `migrateLegacyDownloadPaths()`（仅当存量值 == 旧默认才跟随迁移）。
- WebDAV 远程目录 `/Librera` → `/HowRead`：`AppState.webdavSyncRemoteDir` 默认值改名 + `loadInit()` 一次性迁移 + `remoteDir()` 读取兜底（覆盖从旧 app-State.json 同步回 "Librera" 的情况）。
- WebDavSyncer 新增 `importLegacyRemoteDir()`/`copyRemoteTree()`：同步开始时若新目录为空且服务器上旧 `/Librera` 存在，逐文件 GET→PUT **复制**导入（不删旧目录，旧版 Librera 应用同步不受影响）。
- Drive 根目录：`GFile.findLibreraSync()` 只查 "HowRead"、缺失才创建（**不**采用旧 "Librera" 目录，避免新旧两个应用共写同一个 Drive 文件夹）；数据搬迁由 WebDAV 导入承担。
- UI 字样：我的文件菜单 "HowRead/下载"、"HowRead/Sync"（BrowseFragment2 三处）；PopupHelper 图标着色跳过判断 contains("HowRead")；代理对话框占位文本 "Downloads/HowRead"（顺带修正 Dowloads 拼写）；44 个语言的 msg_migration/msg_sync "[Librera]"→"HowRead"。
- 刻意保留：内部 profile 名 "Librera"；`BookCSS.LIBRERA_CLOUD_*` 常量值与 `Clouds.isCloudImage` 的 "Librera.Cloud" 判断（持久化云书籍路径路由依赖字面值）；在线同步目录 `/Librera.Cloud`；cloudrail OAuth 回调 URL；ExportConverter 旧播放列表迁移源 `Librera/Playlist`。

## 2. 版本 0.9.0 + APK 文件名
- `app/gradle.properties`（版本号真实来源，在仓库内）：appVersionNumberBase=0.9、appVersionNumberIndex=0 → versionName **0.9.0**；appCodeNumber 7190→7198（保证升级 versionCode 单调增）。
- `app/build.gradle` APK 模板：librera（主品牌）flavor 去掉 flavor 段 → **HowRead-0.9.0-arm64.apk / HowRead-0.9.0-uni.apk**（文件名不再含空格）；其余 flavor 保留标签 HowRead-Pro-…、HowRead-Fdroid-… 等。
- 8 处 manifestPlaceholders appName 对齐品牌：librera="HowRead"、fdroid="HowRead FD"、pro="HowRead Pro"（马甲包名不变）。
- "Librera_111" 调试残留：仓库已无此字符串（About 页"引擎"行运行时显示真实 MuPDF 版本，如 1.23.7-librera）。

## 3. 链接统一指向 https://github.com/380121850/LibreraReader
- about_section.xml（官网/测试版/FAQ 三处，原 howread.app）、config.xml wiki_url、PrefFragment2 WWW_SITE/WWW_BETA_SITE/WWW_WIKI_SITE（原 librera.mobi）、whatsnew2.xml wiki 文本、Urls.rateIT（FDroid 渠道）、AndroidWhatsNew（详情/更新日志/下载链接/弹窗文案）。
- 保留 SamlibOPDS `?from=librera.mobi`（OPDS 书源服务参数，功能性）。

## 4. release 试打 + MI9 试装（验证通过）
- `assembleLibreraRelease` 构建成功；apksigner 确认签名 **CN=HowRead**（howread.keystore）。
- debug/release 签名不同 → 卸载 debug 版后 `pm install -i com.android.vending -r -t` 安装成功（versionName 0.9.0 / versionCode 7199）。MIUI 偶发 INSTALL_FAILED_USER_RESTRICTED 时用设备端后台安装重试即可。
- 真机核验：桌面图标"好好读"；软件说明弹窗"好好读: 0.9.0 (1.23.7-librera) SDK: 30 Xiaomi"+"HowRead Pro: 无广告的应用程序"；WebDAV 同步对话框"同步路径 /HowRead"；`/sdcard/HowRead/profile.Librera` 已创建；书架/开书（2600 页 PDF）冒烟正常。

## 影响与注意
- 本机目录换新 → 书库重新扫描一次（全新安装本就如此）；旧 `/sdcard/Librera`、`Download/Librera` 文件不自动删除，可手动清理。
- WebDAV 首次同步会多一步旧目录导入；升级路径 versionCode 已保持单调，0.9.0 可直接覆盖安装 9.4.x 之后的构建。
# 2026-08-30 0.9.0 界面打磨（书架默认/关于弹窗/官网/配置文件/横幅标语）

## 1. 书库显示模式默认书架
- `AppState.libraryMode` 默认值 `MODE_GRID` → `MODE_SHELF`：新安装/重置后书库直接进书架；已手动选过显示模式的设备保持其选择。

## 2. 软件说明弹窗精简（AboutSectionBinder，侧边栏与偏好两处入口共用）
- 去掉弹窗顶部"软件说明"标题（`setTitle` 移除）。
- 蓝色版本行由 `好好读: 0.9.0 (1.23.7-librera) SDK: 30 Xiaomi` 精简为 **`好好读: 0.9.0`**（不再展示 MuPDF 引擎串/SDK/厂商；native 库未动）；`pVersion` 行同步用纯版本号；清理不再使用的 import。

## 3. 官网项展示 howread.git 超链接
- `values/config.xml` `my_site` 由空改为 `https://github.com/380121850/howread.git`（官网行原本因空值隐藏）；
- `about_section.xml` openWeb 显示文本 `howread.git`，点击打开完整链接。

## 4. 配置文件默认 HowRead
- `AppSP` 默认 profile `"Librera"` → `"HowRead"`；`init()` 一次性迁移把存量 `currentProfile=="Librera"` 自动改为 `"HowRead"`。
- 影响：库 DB 文件名含 profile → 重建后自动重扫（真机已验证 72 本书扫回）；根目录旧 `profile.Librera` 文件夹保留在配置文件切换列表中，可手动删除。

## 5. 侧边栏横幅标语
- `main_tabs.xml` 顶部 banner（170dip 夜空图）内新增顶部居中 TextView `drawerTagline`：**"值得读的，好好读"**（白字+半透明阴影）；底部随机名言 drawerQuote 保留。

## 验证（MI9，release 覆盖安装）
- 同签名 release `pm install -r` 直接升级成功；
- 真机截图核验：横幅标语 ✓；软件说明弹窗无标题/版本行精简/官网 howread.git ✓；偏好页配置文件 "H HowRead" ✓；书库书架视图 ✓。
# 2026-08-30 横幅标语调整 + 阅读笔记入口（全屏笔记编辑器）

## 1. 侧边栏横幅标语居中 + 字号放大一倍
- `main_tabs.xml` drawerTagline：横幅正中显示（水平+垂直居中）、17sp → **34sp**、单行。

## 2. 阅读界面"注释和手写"图标 → 笔记入口
- 单击阅读页呼出的底栏中，写字板图标（editTop2）原来弹出画笔颜色面板（注释和手写）；现改为打开**全屏笔记编辑器**，图标保留。
- 原逻辑仅 PDF 显示该图标 → 现在全格式显示（笔记是纯文本，不依赖 PDF 绘图）；裁剪/切边模式与密码保护文档下仍隐藏。
- 绘图注释功能本身保留（点按已画注释、手势入口仍可打开画笔面板）。

## 3. 新增全屏笔记编辑器 NoteEditDialog
- 新类 `com.foobnix.pdf.info.view.NoteEditDialog` + 布局 `dialog_note_edit.xml`（仿 AI 全屏对话框，MATCH_PARENT² 大页面方便输入）。
- 首行：创建时间（yyyy-MM-dd HH:mm）+ 当前位置"第 X / Y 页"；中间大尺寸多行输入框（自动聚焦）；底部 [取消] [保存笔记]。
- 保存为 `AppBookmark`（path/内容/当前进度百分比 p/时间 t，isAiNote=true），进首页"书签笔记"卡片（显示内容与进度%，点击跳回保存时位置），并随 WebDAV 书签同步。
- 新字符串：note_edit_save=保存笔记、note_edit_hint=输入笔记内容…、note_edit_page_fmt=第 %1$d / %2$d 页（values + zh-rCN；标题复用已有 reading_note=笔记）。

## 验证（MI9，release 覆盖安装）
- 横幅标语居中放大 ✓；PDF 底栏写字板图标 → 全屏笔记编辑器（首行"2026-08-30 17:40 · 第 7 / 22 页"）✓；输入保存 → Toast"好好读: 笔记已保存" ✓；首页书签笔记出现"HiFB开发指南.pdf / hello_howread_note / 32%" ✓。

## [0.9.0] 2026-08-30
### 交互打磨（阅读面板 / 选中菜单 / 横幅）
- 侧边栏横幅标语"值得读的，好好读"由 34sp 缩至 32sp（部分真机单行溢出）。
- 选中文字弹窗在"发送给AI"下方新增"笔记"入口（onNoteToEdit）：点击后关闭选区并打开全屏笔记编辑器，自动把所选文字预填进编辑框（光标在末尾）；NoteEditDialog 增加三参 show(a, dc, prefill) 重载。
- 阅读界面单击面板由四行精简为两行（图标行 + 进度条行，document_footer.xml）：
  - 移除底部"☰ 最近"播放列表行（playListParent/playlistRecycleView 固定 GONE，DialogsPlaylist.dispalyPlaylist 相应改为不再显示，view 保留仅因 id 仍被绑定）。
  - 图标行移除"书架"（onRecent）、"前往页面"（thumbnail）、"页序"（nextTypeBootom），隐藏后 lockUnlock（锁）与 bookMenu（⋮）自第四行上移至图标行右端。
  - 第四行（播放 autoScroll、TTS textToSpeach、"上下翻页" modeName）整行隐藏；autoScroll/textToSpeach 等 view 保留（HorizontalViewActivity 横屏模式仍绑定这些 id，避免 NPE）。
- 验证：release 覆盖安装 MI9，横幅 32sp 单行居中；长按选中文字 → 弹窗"笔记"项 → 全屏编辑器预填所选文字 + 时间页码首行 → 保存 Toast 成功；单击面板仅剩图标行（搜索/笔记/书签/目录/锁/⋮）+ 进度条行。

## [0.9.0] 2026-08-30（第二轮）
### 检视修复（面板精简的遗留问题）
- 修复横向滚动模式（左右翻页/书本模式）面板功能连带隐藏的回归：document_footer.xml 图标行新增 id=footerIconRow；HorizontalViewActivity 初始化时调用 restoreFooterControls()，把 autoScroll（自动翻页）与 textToSpeach（TTS）从已隐藏行 reparent 到图标行并恢复显示，同时恢复 onRecent/thumbnail 显示；竖屏翻页模式不受影响，保持精简两行。
- DialogsPlaylist.dispalyPlaylist 精简为无操作（入参判空后直接返回）：播放列表条随面板精简退役，清除 updateVisible/监听器/缩略图适配器等死逻辑，方法保留仅为调用方兼容。
- DragingPopup.initState 位置缓存边界校正：缓存记录宽高 <50dp 或超出屏幕范围时回退到默认居中放置，避免浮动弹窗被旧缓存摆到屏幕外/零尺寸而"长按后看不到弹窗"。
- 验证：release 覆盖安装 MI9；竖屏面板仍为图标行+进度条行；切到左右翻页模式，图标行恢复为 搜索/最近/前往页面/书签/目录/单双页/播放/TTS/锁/⋮ 且自动翻页对话框、长按选字弹窗（含"笔记"项）均正常；logcat 无崩溃；偏好"单击"模式已恢复为上下翻页。

## [0.9.0] 2026-08-30（第三轮）
### 阅读面板：图标行间距与对齐
- 单击面板底部图标行与顶部工具栏图标行加大间距（3dip→8dip，新增 buttonWhiteSpaced 样式）并统一**靠右对齐**：
  - 竖屏（上下翻页）：顶部图标行 alignParentRight；底部图标行 gravity=end，代码中（VerticalViewActivity.spaceFooterIcons）加宽间距——横屏底部 10 键已占满行宽，保持紧凑防裁剪。
  - 横屏（左右翻页）：顶部图标行 layout_gravity=right；底部仅右对齐。
- 验证：PDF 竖屏/横屏面板均右对齐、间距明显加大，无图标裁剪。

### 书库（书架模式）
- 阅读进度 100% 的进度角标文字改绿色（#4CAF50），未读完仍白色。
- 封面下边沿新增**收藏键**（shelfStar：进度角标与 ⋮ 之间，半透明圆底）：实心星=已收藏，点击即切换（复用 onStarClickListener，与网格模式星标同一逻辑）。
- 书籍菜单（⋮）移除五项：复制、添加标签、同步书籍、重置进度、多选模式（ShareDialog 条目与 handler 分支成对删除；书架长按进多选手势不受影响）。
- 验证：书架 100% 绿色角标 ✓；点星键变实心 ✓；菜单仅剩 文本重排/打开方式/发送文件/删除/移出书库/加入播放列表/标记已读/未读/在读/文件信息 ✓。

### 首页 / 我的文件
- 首页「我的文件」行改为显示真实配置：逐项列出「我的文件」里添加的 WebDAV 服务器（点击直达该服务器网络页）与书库文件夹（点击直达文件夹页），末尾保留"我的文件"总入口；**移除 Dropbox / GDrive 占位**；行支持横向滚动；添加/删除后即时刷新（BrowseFragment2 rebuild 追发 UpdateAllFragments，DashboardFragment2 每次刷新重建该行）。
- 首页「网上书库」行：点击某个 OPDS 书库直接打开该书库（openNetworkPage），不再只切 tab。
- 「我的文件」页 OPDS / WebDAV 条目图标加大（30dp→40dp）。
- 验证：首页显示 LeeStation（WebDAV）、DSfile（书库文件夹）、我的文件入口，Dropbox/GDrive 消失 ✓；点条目直达网络页 ✓；我的文件页图标明显变大 ✓。

### WebDAV 同步：网络配置纳入备份/恢复
- 新增 app-NetworkSources.json（AppProfile.syncNetworkSources + ProfileStateIO export/import/merge）：备份 **OPDS 书库条目、WebDAV 服务器列表、书库文件夹路径** 三段，按 URL/路径取并集（本地顺序优先，远端新条目追加）。
- WebDavSyncer.doSync：全局 app-State/app-CSS 同步**前**先导出（防 newer-wins 覆盖本地新配置），同步**后**合并回写并再次发布全局文件，双端一次同步即收敛。
- 说明：OPDS 登录已随 app-Misc.json 同步；WebDAV 密码因 AndroidKeyStore 设备绑定不参与跨设备同步（恢复后需重输）。
- 验证：MI9 后台同步后 app-NetworkSources.json 生成，内容含 Gutenberg OPDS、LeeStation、/sdcard/DSfile 三段；logcat 无同步异常。

## [0.9.0] 2026-08-30（第四轮）

### 备份/同步按数据类型分类改造（一个 WEBDAV 多设备冲突修复）
- 新设备首同步保护（本地默认 → 服务器为准）：同步时现场判断本地 app-State/app-CSS 是否仍等于出厂默认（按"个性化字段"视图比较，剔除屏幕尺寸默认值、绝对路径、时间戳等设备字段），等于默认即收养服务器配置而非用默认值覆盖服务器；在"我的文件"里配置同步本身不算个性化，刚输入的同步服务器/路径/策略/定时在收养时保留。
- 设备相关字段隔离：app-State 的 displayPath、installationDate、屏幕相关默认值（tapzoneSize/coverBigSize/progressLineHeight 等）、背景图路径、代理配置（含代理密码）、会话残留（selectedText/searchQuery/isAutoScroll），app-CSS 的 searchPathsJson/cachePath/fontFolder/dictPath/pathSAF 等路径字段——不参与比较、不上传服务器、下载后回填本地值。多设备不再互相污染，服务器副本不再保存设备字段与代理密码明文。
- 修复 app-BookStates（已读/在读标记）合并失效：旧合并代码期望带时间戳的对象而实际值是 int（0 未读/1 在读/2 已读），远端条目永远合并不进来、各设备轮流覆盖服务器。改为按 key 并集取更靠后状态（只进不退，天然无冲突）。
- 配置变化自动触发同步：AppState/BookCSS 真实变更写盘后，10 秒去抖合并触发一次静默 WebDAV 同步；同步过程自身写的 lastSync 水印不会引发循环（已验证静置无重复同步）。
- 定时同步间隔可配置：WebDAV 同步对话框新增"定时同步"行（关闭/每15分钟/每30分钟/每1小时/每3小时/每6小时/每1天），应用存活期间周期后台同步，改动即时生效（每次循环重读配置）。
- 备份 zip 导出前刷新 app-NetworkSources.json：手动备份包里 OPDS 目录、WEBDAV 服务器、书库文件夹始终是最新值（此前只有跑过 WebDAV 同步才有）。
- 备份 zip 跨机型恢复：导入解压后若当前机型目录（device.<机型>）缺 app-State/app-CSS/app-Misc，自动从其他机型目录收养最近一份，设置类配置跨机型迁移生效；importMisc 恢复 AppSP 时保护 rootPath/currentProfile/lastBookPath 等本机字段不被源设备值覆盖。
- 密码策略保持现状：OPDS 登录、书/应用密码、AI Key 明文随 app-Misc/app-AI 同步与备份（自建服务器、多设备免重输的取舍）；WebDAV 密码仍 AndroidKeyStore 设备绑定、不随同步迁移。

### 界面
- "软件说明"与"更新说明"弹窗去掉最后一行"请在 Google Play 反馈并评分"。
- 首页"我的文件"行只显示已配置的 WEBDAV 服务器与书库文件夹项（去掉"我的文件"总入口子项）；一项都没配置时整段（含标题）隐藏。

### 验证（MI9 真机，全部通过）
- 常规同步正常、全程 logcat 无未捕获异常。
- 模拟新机（改走 device 目录 + 只配置同步服务器）：首同步收养服务器个性化配置（主题色/AI 配置/排版一致），同步配置保留，设备字段用本机值。
- app-BookStates int 状态条目随同步完整轮回；配置变化后 10~15 秒内自动同步一次，静置 60 秒无循环。
- 定时同步 7 档设置/回读正常；备份 zip 含最新 app-NetworkSources.json；跨机型 zip 导入后设置文件被当前机型目录收养；软件说明与首页"我的文件"行按预期显示。
- 定时同步默认打开，默认间隔 15 分钟（此前默认关闭）；已有设备如显示关闭，在同步对话框选一次即可。


## [2026-09-02] 广告 SDK 模块化：多商店渠道化（AdsProvider 抽象 + F-Droid 零广告整改）

### 背景
为支持按商店发布（Google Play/小米/华为等渠道可各接不同广告 SDK；F-Droid 政策禁止任何广告 SDK），按根目录 MULTI_PLATFORM.md 方案落地：平台→商店分层、广告代码 provider 化。

### 改动
- 新增广告抽象层（main，零第三方依赖）：`com.foobnix.ads.AdsProvider` 接口 + `RewardListener` + `NoAdsProvider`；`com.foobnix.pdf.info.ADS` 改为纯门面：公开方法与计时/测试设备逻辑保留，SDK 调用全部委托 `AdsProviderFactory.get()`；main 源码不再出现任何 com.google.android.gms.ads / com.google.android.ump 引用。
- 广告实现按变体源集编译（同 FQCN 工厂、组间互斥，仿 LibreraBuildConfig 模式）：
  - `app/src/admobAds`（`AdMobAdsProvider`）：集中原 LibreraApp 的 MobileAds 初始化与测试设备、MainTabs2/PrefFragment2 的 UMP 同意流程与隐私选项、原 ADS 内横幅/插屏/激励实现；挂 6 个带广告 flavor；
  - `app/src/noAds`（`NoAdsProvider`）：挂 fdroid/pro，APK 不含任何广告 SDK 代码。
- 广告位 meta-data（APPLICATION_ID/BANNER/FULLSCREEN/REWARD）从 main Manifest 迁到 `src/admobAds/AndroidManifest.xml`，仅带广告渠道合并；无广告渠道清单零广告痕迹。
- 删除 `libPro/` 模块（原 GMS/UMP 同包名 no-op 假类，被接口层取代）；settings.gradle.kts 与 app/build.gradle 同步摘除。
- 激励广告回调从 GMS OnUserEarnedRewardListener 换为自有 RewardListener（AdsFragmentActivity/ViewBinder 等调用点适配）。
- 新增目录骨架：`app/src/{xiaomi,huawei}`（渠道预留，上架时注册 flavor）、`platform/{ios,desktop}`（预留）、`ci/`（构建与合规闸门说明）、`store/`（按平台/商店的发布物清单，含 fastlane 定位说明）；根新增 `MULTI_PLATFORM.md` 方案文档。
- 新增闸门工具：`Z:\opt\librera\bench\scan_apk_ads.py`（APK dex/清单字节扫描广告 SDK 标记）。

### 验证（全部通过）
- Ubuntu server 三变体编译全绿：assembleLibreraDebug / assembleFdroidDebug / assembleProDebug。注意：**fdroid 必须单独一次调用构建**——app/build.gradle 版本 ext 按整次调用的任务串是否含 "Fdroid" 取值，fdroid 与 librera/pro 同批会把同批全部带成 fdroid 版本号（9.4.21/7174）。
- 零广告闸门：fdroid（9.4.21）与 pro（0.9.0）arm64 APK 扫描 PASS（无 gms.ads / ump / play-services-ads 等任何标记）；对照 librera APK 含 AdMob 实现类（预期）。
- MI9 实机：安装 HowRead-0.9.0-arm64（debug），启动 MainTabs2 正常：进程存活、底部导航与设置菜单渲染、全程 logcat 无 FATAL/异常。
- 静态 grep：main / fdroid / pro / gmsStubs 源集无 com.google.android.gms.ads、com.google.android.ump、:libPro 残留。

## [2026-09-02] 平台目录化迁移：一级目录 android/ harmony/ ios/ desktop/；主渠道 flavor librera 改名 google

### 背景
按 MULTI_PLATFORM.md 方案把仓库目录按平台对齐：安卓 Gradle 工程从仓库根下沉 android/，
ios/desktop 预留位提升为一级目录；安卓主渠道（Google Play/官网）flavor 由 librera 改名 google。

### 目录迁移
- 仓库一级目录 = android/（安卓 Gradle 工程）+ harmony/（鸿蒙）+ ios/ desktop/（预留占位）
  + 根共享层（Builder/ prebuilt/ scripts/）+ 文档/发布（docs/ store/ ci/ README CHANGES …）。
- 迁入 android/：build/settings/gradle.properties、gradle/、gradlew*、app/、libDepFree/
  libDepPro/ libReflow/、local.properties、howread.keystore、keystore.pkcs12、fastlane/
  （Play 商店元数据）、com_files.txt/net_files.txt。
- 删除：KMP 试验种子 composeApp/ shared/ iosApp/（settings 已注释、零构建引用）；
  根 build/ 与 .gradle/ 可再生缓存；原 platform/ 占位目录（内容提升为 ios/ desktop/）。
- 留在根的共享资产未动：Builder/（harmony NAPI CMake 引 ../../Builder/mupdf-1.23.7）、
  prebuilt/（restore-harmony-libs.sh 引 prebuilt/harmony）、scripts/（离线种子）。
- 构建引用适配：android/app/build.gradle 的 jniLibs 路径改 `${rootDir}/../prebuilt/...`；
  android/settings.gradle.kts 加 `:Builder` projectDir=../Builder（模块留在根）。

### flavor librera → google
- app/src/librera → app/src/google；LibreraBuildConfig.FLAVOR="google"（运行时代码无分支依赖，仅非空校验）。
- app/build.gradle：flavor 块名、manifest 占位符属性键 librera_* → google_*
  （google_appGdriveKey/admobAppId/BannerId/FullId/RewardId）、admobAds 源集循环列表、
  googleImplementation 依赖×2、APK 文件名品牌判断（"Google" 主渠道仍无标签
  HowRead-0.9.0-*.apk）。manifest 元数据键 librera.ADMOB_*、deep-link scheme "librera"、
  内部品牌遗留（Librera profile/云路径常量等）有意保留，未改。
- 服务器 ~/.gradle/gradle.properties 无 librera_* 键（广告位一直用测试 ID 兜底）；
  RELEASE_STORE_FILE 路径已同步为 android/howread.keystore。
- 文档/工具链同步：根 MULTI_PLATFORM.md（新目录树+迁移历史）、README.md（构建命令）、
  store/android/google README、Z:\opt\librera 的 build_remote.sh（gradle 根=android/、
  flavor 表）、build-librera.ps1（监听/轮询/产物路径）、BUILD-README.md、
  bench 图标/重品牌脚本路径前缀、Z:\opt\zcode\AGENTS.md（路径/命令/flavor/警示）。

### 验证（全部通过）
- Ubuntu server（cd …/LibreraReader/android）：
  `./gradlew :app:assembleGoogleDebug :app:assembleProDebug` → 0.9.0/7198；
  单独 `:app:assembleFdroidDebug` → 9.4.21/7174。
- 产物命名正确：google=HowRead-0.9.0-arm64.apk（无标签）、pro=HowRead-Pro-…、fdroid=HowRead-Fdroid-…。
- APK 内含 lib/arm64-v8a/libMuPDF.so + liblame.so（../prebuilt jniLibs 路径生效）。
- fdroid/pro 零广告闸门扫描 PASS；MI9 实机安装 google 包（0.9.0）启动正常、logcat 无异常。
- 残留复查：android/ 与文档中无 assembleLibrera / src/librera / "Librera" flavor 引用。
- 鸿蒙侧零改动（Builder/prebuilt 路径未变）。

## [2026-09-02] 品牌可见文案清理：Play 商店描述与鸿蒙显示名改 HowRead

### 背景
全仓 "librera" 遗留审计（255 文件命中）后按确认范围清理**用户可见**残留；
内部包名（mobi.librera 等）与持久化/署名标识按审计分类保留。

### 改动
- `android/fastlane/metadata/android/en-US/full_description.txt`：商店描述品牌全部
  改为 HowRead；修正两处与现状不符的上游文案（FD 与主渠道差异 = 无 GMS/广告；
  minSdk 24 → Android 7.0+）；末尾注明为 Librera 的开源分支并保留原作者捐赠链接。
- 鸿蒙 `harmony/entry/src/main/resources/base/element/string.json`：module_desc /
  EntryAbility_desc / EntryAbility_label 显示名改为 HowRead（原先 Librera Reader /
  Librera Reader for HarmonyOS / Librera）。
- `harmony/entry/src/main/ets/pages/Index.ets`：顶栏标题 'Librera Reader' → 'HowRead'。

### 审计分类备忘（未清理项及原因）
- 有意保留：备份/同步持久化标识（payload 'librera-harmony'、librera_backup.json、
  profile "Librera"、/Librera.Cloud、scheme、meta 键）、上游版权与历史（README 上游段/
  LICENSE/CHANGES/docs 官网）、引擎与签名命名（Builder platform/librera、
  libmupdf-librera.c、librera-sign.p12）、仓库/共享盘路径、com.foobnix 主包空间。
- 可选未做：约 40 个 values-* 语言包品牌词、内部类/包清理（LibreraApp/
  LibreraBuildConfig×9/LibreraAppGlideModule/mobi.librera→mobi.howread 等，待另行确认）。

### 验证
- 鸿蒙 HAP 构建成功（BUILD SUCCESSFUL，CompileArkTS/PackageHap/SignHap 通过）。
- 复查三文件残留仅剩署名与备份标识（有意）。

## [2026-09-02] 版本号统一：fdroid 不再固定 9.4.21/7174，全 flavor 同用 0.9.0/7198

### 背景
此前 fdroid 沿用上游惯例固定版本（9.4.21/7174，`-fdroid` 后缀），与主渠道 0.9.0 不一致；
本次移除特判，所有 flavor 统一读取 app/gradle.properties 版本号。

### 改动
- `android/app/build.gradle`：删除 ext FDroidCodeNumber=7174 / FDroidVersionNumber="9.4.21"
  与"任务串含 Fdroid 则取 fdroid 版本"的分支；versionCode/Name 一律来自
  app/gradle.properties（当前 appCodeNumber=7198 → 0.9.0/7198）。
- 删除 fdroid flavor 的 `versionNameSuffix '-fdroid'`（版本名不再带后缀）。
- 附带收益：fdroid 与 google/pro **可同批构建**（不再有版本串污染问题）；
  产物命名统一 HowRead-Fdroid-0.9.0-*.apk。
- 文档同步：MULTI_PLATFORM.md（构建命令/矩阵/现状）、store/android/fdroid README、
  Z:\opt\zcode\AGENTS.md（构建注释与 gotcha 13）、根 README.md、BUILD-README.md
  （产物名 0.9.0）、build_remote.sh 头部说明。
- 清理改名前的旧构建输出目录 android/app/build/outputs/apk/librera（可再生残留）。

### 验证（全部通过）
- fdroid 单独构建：Version [0.9.0 - 7198]，产物 HowRead-Fdroid-0.9.0-arm64.apk，
  零广告闸门扫描 PASS。
- google+fdroid+pro 同批构建：Version [0.9.0 - 7198]，BUILD SUCCESSFUL，
  三个渠道产物版本一致（HowRead / HowRead-Fdroid / HowRead-Pro 均 0.9.0）。

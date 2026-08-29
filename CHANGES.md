# CHANGES(修改说明)

本文件用于记录每次对代码与构建配置的修改内容,随代码一起入库,便于回溯每次改动的目的与范围。
每次新修改完成后,请在本段下方追加一条带日期的条目。

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



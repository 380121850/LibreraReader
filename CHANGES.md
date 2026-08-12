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



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

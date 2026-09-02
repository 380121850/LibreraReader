# MULTI_PLATFORM.md — HowRead 多平台代码结构方案（2026-09-02 已按平台目录化落地）

发布矩阵：**手机三端（安卓 / 鸿蒙 / iOS）+ 桌面（Windows/Linux）**，安卓按商店细分
（F-Droid / Google / 小米 / 华为…）。**2026-09-02 已完成平台目录化迁移**：仓库一级
目录 = `android/` `harmony/` `ios/` `desktop/`，安卓主渠道 flavor 由 librera 改名 google。

---

## 1. 平台 → 商店 划分（总览）

```
                     L0 共享原生引擎（仓库根共享层）
              C/C++：MuPDF 1.23.7 单一源码树 + djvu/hqx/lame/mobi…
              Builder/mupdf-1.23.7  →  prebuilt/ 各端预编译产物
                     ▲ 薄胶水层（每平台各写各的，只做绑定不写业务）
        ┌────────────┼──────────────┬──────────────┐
   android(JNI)  harmony(NAPI)     ios(预留)     desktop(预留)
    Builder/jni   mupdf_napi.cpp    Swift/C       JVM/C++
        └────────────┴──────────────┴──────────────┘
     业务逻辑 + UI 各端原生（鸿蒙 ArkTS 跑不了 Java/KMP，边界不强推统一）
        ┌────────────┴──────┐
   android 按商店细分        harmony=华为 / ios=苹果（唯一渠道）
   app/src/google|fdroid|xiaomi|huawei + ads 源集
```

## 2. 代码目录（迁移后现状，2026-09-02）

```
LibreraReader/                          ← 单仓库（git 根）
│
├─ android/                             [平台] 安卓 Gradle 工程（原仓库根，2026-09-02 下沉）
│   ├─ settings.gradle.kts              含 :Builder → projectDir=../Builder
│   ├─ gradlew gradle/ app/ libDepFree/ libDepPro/ libReflow/
│   ├─ fastlane/                        Play 商店元数据（纯安卓）
│   ├─ howread.keystore keystore.pkcs12 local.properties
│   └─ app/src/
│       ├─ main/                        [广告无关] 平台共享代码
│       ├─ google/                      flavor=google（主渠道 Google Play/官网）
│       ├─ fdroid/  xiaomi/  huawei/    F-Droid / 预留渠道
│       ├─ admobAds/ noAds/             AdMob 实现 / 零广告实现（按渠道编入）
│       └─ gmsStubs/                    登录 stub（fdroid/pro）
├─ harmony/                             [平台] 鸿蒙 NEXT 工程（AppScope+entry+NAPI）
├─ ios/                                 [平台·预留] 未来 iOS（SwiftUI + .xcframework）
├─ desktop/                             [平台·预留] 未来 Win/Linux（暂缓）
│
├─ Builder/                             [共享 L0] 引擎源码 + JNI 胶水（harmony 也引用）
├─ prebuilt/                            [共享 L0 产物] native/(安卓4ABI) harmony/(2ABI) gradle 离线种子
├─ scripts/                             [共享工具] 离线构建种子脚本
├─ store/                               [发布] 按平台/商店的上架清单（见 §4 矩阵）
├─ ci/                                  [发布] 构建/合规闸门（预留）
├─ docs/                                [官网] Jekyll 营销站（与代码文档隔离）
├─ README.md CHANGES.md LICENSE.txt logo.jpg
└─ MULTI_PLATFORM.md                    本文档
```

已删除（2026-09-02）：KMP 试验种子 `composeApp/ shared/ iosApp/`（settings 曾注释、
不进任何构建，方案弃用）、原 `platform/` 占位目录（ios/desktop 已提升为一级）。

### 广告代码分层（2026-09-02 落地）
- 接口（main，零依赖）：`com.foobnix.ads.AdsProvider` + `RewardListener`；
  `com.foobnix.pdf.info.ADS` 纯门面（策略计时在门面，SDK 调用全委托）。
- 实现按变体编译（同 FQCN `AdsProviderFactory`，组间互斥）：
  - `src/admobAds`（`AdMobAdsProvider` + manifest overlay）：挂 6 个带广告 flavor
    （google/pdf_classic/ebooka/pdf_v2/tts_reader/epub_reader）；
  - `src/noAds`（`NoAdsProvider`）：挂 fdroid/pro → **APK 无任何广告 SDK 代码**。
- 新增广告网络 = 新源集实现 AdsProvider + 按渠道挂载；每包只编一个 provider；
  广告位 ID 经 manifest placeholder 按 flavor 注入。
- 原 `libPro/`（GMS/UMP no-op 假类）已删除；main grep 断言无 gms.ads/ump 引用。

## 3. 构建命令（Ubuntu server；禁止 Windows 侧构建）

```bash
# 安卓（gradle 根 = android/）。版本号统一来自 app/gradle.properties
# （2026-09-02 起各 flavor 同版本，fdroid 不再固定 9.4.21/7174）：
ssh ... "source ~/.bashrc && cd /docker/opt/librera/LibreraReader/android \
  && ./gradlew :app:assembleGoogleDebug :app:assembleProDebug"
ssh ... "cd /docker/opt/librera/LibreraReader/android && ./gradlew :app:assembleFdroidDebug"

# 鸿蒙：cd .../harmony && bash build_hap.sh（未变）
```
各渠道可同批构建；fdroid 若单独调用只是输出目录习惯，不影响版本号。

## 4. 商店 × 广告 SDK 矩阵

| 平台 | 商店 | 代码形态 | 广告方案 | 关键要求 |
|---|---|---|---|---|
| Android | F-Droid | flavor fdroid | 无（NoAdsProvider，APK 零广告类） | 全开源依赖；版本与主渠道统一（app/gradle.properties）；去 USE_BIOMETRIC；CI 闸门 |
| Android | Google Play/官网 | flavor google（主渠道） | AdMob（src/admobAds；google_* 属性→manifest） | DATA SAFETY/隐私页；UMP 内置 |
| Android | 小米 | [预留] src/xiaomi | 按需 AdMob 或穿山甲/优量汇（新源集） | 备案/隐私；加固后重签 |
| Android | 华为(安卓包) | [预留] src/huawei | 华为 Ads(HMS) 或先无广告 | 无 GMS；AGC 签名 |
| HarmonyOS | 华为(鸿蒙包) | harmony/ | 暂缓（AppGallery 变现远期） | AGC Profile；PORTING_PLAN 补功能 |
| iOS | 苹果 | [预留] ios/ | 远期评估 AdMob iOS | TestFlight/隐私标签 |
| Desktop | Win/Linux | [预留] desktop/ | 无 | 暂缓（JVM/Compose 或 C++/Qt） |

## 5. 各端现状与差距
- **Android**（android/）：app 模块 ~1083 Java 文件；flavor 单维（版本×商店混在，
  演进可选 edition×store 双维）；版本号：app/gradle.properties
  （appVersionNumberBase/Index、appCodeNumber=7198）；各 flavor 同版本（0.9.0）。
- **鸿蒙**（harmony/）：NEXT API 24；ArkTS 10 文件；NAPI 22 函数；缺口表见 PORTING_PLAN.md。
- **可移植逻辑盘点**：`com/foobnix/ext`（格式解析近零 Android 依赖）、model/dao2、
  opds、webdav 传输核心、AiClient —— JVM 桌面/服务端可整块复用；UI 系深度绑定安卓。

## 6. 构建与 CI 规划
- Ubuntu server（192.168.50.111, lee）手工 SSH；ci/ 沉淀：build_android.sh 模板、
  F-Droid 闸门 `scan_apk_ads.py`（Z:\opt\librera\bench\，对 fdroid 产物字节扫描，
  检出广告 SDK 即失败；主渠道包反向断言含 AdMob）。
- 密钥只在构建机：~/.gradle/gradle.properties（RELEASE_* → android/howread.keystore）、
  harmony/signing/；不入库。

## 7. 迁移历史（2026-09-02 已完成，勿再执行）
原"仓库根=安卓工程"已整体迁入 android/（gradle 根 = android/）。迁移要点备忘：
- app/build.gradle jniLibs 用 `${rootDir}/../prebuilt/native/...`（rootDir=android/）；
- settings 里 `:Builder` 模块 projectDir=../Builder（引擎在共享根）；
- Builder/prebuilt/scripts 必须留在根（harmony CMake/restore 脚本硬依赖）；
- 外部工具已同步：Z:\opt\librera\build_remote.sh、build-librera.ps1、BUILD-README.md、
  bench 图标/重品牌脚本、Z:\opt\zcode\AGENTS.md；
- keystore 移至 android/，服务器 ~/.gradle RELEASE_STORE_FILE 已指向新路径。

## 8. 明确不做
Flutter/KMP 大重写、CloudRail 复活、三端业务层强行统一。

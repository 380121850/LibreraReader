# xiaomi 渠道覆盖目录（预留）

小米应用商店渠道包在真正上架时使用本目录，本次只建立目录与说明，不含渠道逻辑。

## 上架时需要做的（见 MULTI_PLATFORM.md 渠道矩阵）
1. 在 `app/build.gradle` 注册 `xiaomi` productFlavor（复制 `librera` 的配置：
   `applicationId` 可与主渠道相同 `com.howread.reader`，`appName`/图标可换渠道皮）。
2. 挂广告源集：按商店采用的广告 SDK 选择——
   - 用 AdMob：`sourceSets[xiaomi].java.srcDirs += ['src/admobAds/java']` +
     `manifest.srcFile('src/admobAds/AndroidManifest.xml')`（与本目录同目录级）；
   - 接穿山甲/优量汇：新建独立 `src/pangleAds/` 之类的源集 + `AdProvider` 实现
     （接口在 `main/java/com/foobnix/ads/AdsProvider.java`），**每包只编一个 provider**。
3. 渠道 Manifest overlay 放本目录 `AndroidManifest.xml`（渠道号 meta-data、
   隐私政策、备案号相关声明等）。
4. 发布物资料放 `store/android/xiaomi/`（截图/文案/签名加固说明）。

## 注意
- 目录为空时不会进入构建；`src/<渠道>` 目录名与 flavor 名一一对应（fdroid 先例）。
- 国内无 GMS 设备上 AdMob 不展示广告：现有 `AppsConfig.isShowAdsInApp`
  运行时门控自动兜底，应用不会崩溃。

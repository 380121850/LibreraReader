# Google Play / 官网主渠道（google flavor）

- **构建**：`assembleGoogle*`（gradle 根 = android/，flavor 目录 app/src/google，
  包名 `com.howread.reader`；版本号随 app/gradle.properties：
  appVersionNumberBase/Index、appCodeNumber）。fdroid 需单独一次调用（勿混批）。
- **广告**：AdMob（app/src/admobAds），广告位 ID 默认 Google 测试 ID，
  正式 ID 经 `~/.gradle/gradle.properties` 的 `google_admob*`（AppId/BannerId/
  FullId/RewardId）与 `google_appGdriveKey` 属性注入 manifest。
- **签名**：release 用 RELEASE_* 属性（android/howread.keystore, alias howread）。
- **商店元数据**：android/fastlane/（标题/描述/图标/截图）。
- **上架清单（Google Play）**：账号与 DATA SAFETY 表单、隐私政策 URL、
  内容分级、按 ABI 输出（arm64/arm/x86/x86_64/uni）。
- **合规**：UMP 同意流程内置（EEA）；面向儿童需另配 family policy。

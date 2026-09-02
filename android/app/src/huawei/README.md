# huawei 渠道覆盖目录（预留：华为 AppGallery 安卓包）

华为应用市场（AppGallery，安卓包）上架时使用本目录，本次只建立目录与说明。

## 上架时需要做的（见 MULTI_PLATFORM.md 渠道矩阵）
1. 在 `app/build.gradle` 注册 `huawei` productFlavor（可复制 `librera` 配置）。
2. 广告 SDK：国内华为设备无 GMS，AdMob 不会展示；若要变现需接华为 Ads Kit：
   - 新建 `src/hmsAds/` 源集，实现 `com.foobnix.ads.AdsProvider`（参考
     `src/admobAds/java/com/foobnix/ads/AdMobAdsProvider.java` 的结构），
     依赖华为 Maven 仓库的 `com.huawei.hms.ads:*`；
   - 或先用无广告（挂 `src/noAds` 的 NoAdsProvider 思路），上线后再加。
3. AppGallery 需要 AGC 签名（与根工程 debug 签名无关），密钥/Profile 不入库。
4. 发布物资料放 `store/android/huawei/`。

## 注意
- 目录为空时不会进入构建。
- 与鸿蒙版（`harmony/`，另一个独立工程）是两回事：这里是安卓 APK 渠道包。

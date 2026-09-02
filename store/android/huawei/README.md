# 华为 AppGallery 渠道（安卓包，预留）

与鸿蒙版（store/harmony/huawei）分开管理；本目录管**安卓 APK 上架**：

- 开发者实名/企业认证 + ICP 备案信息填报；隐私政策页。
- AGC（AppGallery Connect）创建应用：包名 `com.howread.reader`，
  签名用 AGC 生成的 Profile 与正式 keystore（可沿用 RELEASE 证书指纹登记）。
- 广告：国内华为设备无 GMS → AdMob 不展示；要变现接华为 Ads Kit
  （HMS，代码结构预留见 `app/src/huawei/README.md`）。
- targetSdk：华为商店有 targetSdk 版本要求，上架前核对当前版本。
- 上架材料：截图/文案/更新日志 + 华为要求的隐私与权限声明（音频焦点、
  前台服务 mediaPlayback 需说明用途）。

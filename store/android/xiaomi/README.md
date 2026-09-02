# 小米应用商店渠道（预留）

上架前准备（代码目录 `app/src/xiaomi/` 的 README 有对应构建说明）：

- 开发者账号实名 + 软著/备案号；隐私政策页（应用内设置已含）。
- 包名 `com.howread.reader`（可与其他商店同包名，各自签名）。
- 加固：小米商店建议/要求渠道包加固（如 360/腾讯乐固/小米自有），
  加固后**重新签名**，正式签名需对加固厂商开放（用 RELEASE 同一 keystore）。
- 广告：按上架时策略选 AdMob（无 GMS 自动不展示）或接穿山甲/优量汇
  （每包一个 provider，见 app/build.gradle 注释）。
- 渠道包：如需要多渠道统计号，在 `src/xiaomi/AndroidManifest.xml` 放
  UMENG/小米渠道 meta-data。
- 上架材料放本目录：截图/文案/更新日志（zh-CN）。

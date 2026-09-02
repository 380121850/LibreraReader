# 华为 AppGallery 鸿蒙包渠道（harmony/ 工程）

鸿蒙版唯一渠道（HarmonyOS NEXT，`harmony/` 工程）：

- 包名：`com.foobnix.pdf.reader`（AppScope/app.json5）；版本随 harmony 工程维护。
- 签名：AGC（AppGallery Connect）HarmonyOS 应用 Profile 与 .p12 ——
  本地开发调试用 `harmony/signing/`（gen_signing.sh 生成 debug 材料，密码走环境变量）。
- 上架前核对：compatibleSdkVersion 6.1.1(24) 以上版本要求、权限最小化
  （当前仅 INTERNET）、隐私政策（鸿蒙/AGC 有单独表单）、截图与文案。
- 广告/变现：华为 AppGallery 统一变现方案（后续单独评估，当前无广告）。
- 功能缺口：按 `harmony/PORTING_PLAN.md` 缺口表决定哪些先补再上架。

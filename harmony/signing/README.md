# HarmonyOS 签名材料（本地生成，不入库）

本目录下除 `gen_signing.sh`、`make_material.js`、`README.md` 外的所有文件
（p12 私钥、material 解密密钥、enc 密码、证书链、p7b）均为**本地私有材料**，
已被 `.gitignore` 挡住，严禁提交。

## 首次使用（clone 后）

```bash
bash signing/gen_signing.sh
```

脚本一键完成（全程无需 DevEco IDE / 华为云）：

1. 生成 ECC P-256 密钥与三级证书链（root CA → 应用 CA → 应用签名证书）
2. 签发 debug profile（`librera-debug.p7b`，bundle: com.foobnix.pdf.reader）
3. 生成 hvigor 专用 `material/` 解密目录，并把加密后的
   storePassword/keyPassword **自动回填**到 `../build-profile.json5`
4. `verify-profile` 自检

默认 keystore 密码 `LibreraDebug`，可用环境变量覆盖：
`LIBRERA_SIGN_PW=xxx bash gen_signing.sh`

之后正常构建即可产出签名 HAP：

```bash
DEVECO_SDK_HOME=<sdk路径> hvigorw PackageApp
# → entry/build/default/outputs/default/entry-default-signed.hap
```

## 说明

- hvigor 的 SignHap 强制把 build-profile.json5 里的密码按
  AES-128-GCM 密文解密（密钥来自 storeFile 同级的 `material/` 目录）。
  `make_material.js` 按相同算法生成 material 并加密明文密码，
  详见脚本内注释。
- 自签名证书链仅适用于模拟器/本机调试；真机与上架需在 AGC
  申请正式证书，届时将 AGC 的 p12/p7b/cer 放入本目录并重跑
  `make_material.js` 加密密码即可。
- 证书有效期 10 年；重新生成材料后旧 HAP 不受影响，新构建使用新链。

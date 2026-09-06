# HowRead 自动测试环境依赖清单

## 1. 测试工作站（Windows，UI 层执行机）

| 依赖 | 版本/路径 | 用途 | 状态 |
|---|---|---|---|
| Python | 3.13 | 测试框架 | ✅ 已装 |
| uiautomator2 | pip 包（最新） | 设备驱动 | ✅ 已装 |
| Pillow + numpy | pip 包 | 翻页画面变化/主题切换对比 | ✅ 已装 |
| PyYAML | pip 包（可选，无则用内置简版解析） | cases.yaml 解析 | 可选 |
| adb | `C:\Users\lee\AppData\Local\Android\Sdk\platform-tools\` | 设备通信 | ✅ |
| SSH key | `C:\Users\lee\.ssh\id_ed25519` | 触发服务器构建/JVM 测试 | ✅ |

## 2. 真机设备池（UI-Device 层）

| 设备 | serial | 安卓 | ABI | 备注 |
|---|---|---|---|---|
| Xiaomi MI9 | `48fee174` | 11 (SDK30) | arm64 | MIUI：已关 `verifier_verify_adb_installs`；首次新包安装可能需人工点一次"继续安装" |
| Huawei P20 (SNE-AL00) | `3JJ4C18904004595` | 10 (SDK29) | arm64 | 会自动息屏（驱动已处理常亮）；开发者选项-USB 安装需放行 |
| Huawei KSA-AL10 | `NETNU20617301956` | 9 (SDK28) | armeabi-v7a | 32 位（自动用 `-arm` 包）；书库首扫慢 |

三台均需：USB 调试授权、屏幕解锁无密码（驱动只做滑掉锁屏）、已授权"USB 安装"。

## 3. AVD（UI-AVD 层）

| 项 | 值 |
|---|---|
| 名称 | `MedicineAVD`（本机已存在） |
| 启动 | `android_start_emulator(avd=MedicineAVD)` 或 Android Studio GUI |
| serial | `emulator-5554`（标准首启动） |
| APK | x86_64 或 uni 包（run_all 按 ABI 自动选） |
| 注意 | AVD 英文 locale 时用例走英文关键词兜底（Dashboard/Browse/Settings） |

## 4. 构建服务器（JVM 层 CI runner + APK 构建）

| 依赖 | 值 |
|---|---|
| 主机 | `lee@192.168.50.111`（SSH key 认证，每条命令前 `source ~/.bashrc`） |
| JDK | OpenJDK 17（JAVA_HOME 已配） |
| Android SDK /Gradle | 离线缓存已配（vendor-cache） |
| 单测任务 | `./gradlew :app:testGoogleDebugUnitTest`（ci/autotest/run_unit.sh 封装） |
| Robolectric | 4.16（toml 已配；首次运行会从 Maven Central 下载 android-all jar，需外网） |
| 测试报告 | `android/app/build/reports/tests/test<Flavor>DebugUnitTest/index.html` |

## 5. 测试数据（fixtures，工程内管理）

| 文件 | 路径 | 用途 |
|---|---|---|
| big25.pdf (28MB) | `ci/autotest/teskbook/big25.pdf` | 翻页/内存专项大书 |
| alicesadventures.epub | `ci/autotest/teskbook/alicesadventures.epub` | EPUB 冒烟/书签/TTS |
| test.pdf / test.epub / demo.mobi 等 | `ci/autotest/teskbook/` | 多格式备用样本 |
| 设备侧路径 | 推送到各机 `/sdcard/Download/`（书库扫描根） | run_all 自动推送 |

**⚠️ teskbook/ 不入库**：测试书目均为本地文件，已加入 `ci/autotest/.gitignore`（连同 `results/`、`__pycache__` 一起被忽略），**禁止提交到代码仓**。新环境部署时需先按下列来源放置书目，否则 UI 层用例（SM-04/05、FN 系列、PF-03）会因找不到文件而失败/SKIP：

| 文件 | 来源/再获取方式 |
|---|---|
| big25.pdf / .epub / .fb2 / .txt（四格式 ~100MB 压力书） | 由生成脚本 `Z:\opt\librera\bench\genbooks.py` 现场生成（`python genbooks.py`，在输出目录运行）；该脚本在 bench 目录保留，不入库 |
| alicesadventures.epub / test.epub | Project Gutenberg《Alice's Adventures in Wonderland》EPUB（改名为 alicesadventures.epub） |
| test.pdf | 工程内 `harmony/entry/src/main/resources/rawfile/test.pdf` 的副本（首页预览用书） |
| demo.cbz / demo.tiff / demo.html / demo.mobi / demo.txt / demo.xps / tts.html | 手工生成的小样本（几 KB~几百 KB），丢失时可按格式手造最小文件替代 |

## 6. WebDAV 测试服务器（同步用例 SY-01）

| 项 | 值 |
|---|---|
| 脚本 | `Z:\opt\librera\test_webdav.py`（Basic auth 独立服务器） |
| 端口/账号 | `8765` / howread / howread123（cases.yaml `webdav` 段） |
| 启动 | `python Z:\opt\librera\test_webdav.py`（测试前手动或脚本拉起） |
| 场景 | 应用 WebDAV 配置指向 `http://<PC-IP>:8765/` → 上传 → 清数据 → 还原 |

## 7. AI 大模型（AI 功能测试）

| 项 | 值 |
|---|---|
| Mock 服务 | `ci/autotest/tools/ai_mock.py`（OpenAI 兼容 /v1/models + /v1/chat/completions） |
| 启动 | `python ci/autotest/tools/ai_mock.py 8770` |
| 应用侧配置 | AI 大模型 → 协议 `openai`、地址 `http://<PC-IP>:8770/v1`、模型 `howread-test-model`、key 任意 |
| 存储 | 配置在 AppState(app-State.json)；API key 在 prefs 文件 "ai"（AndroidKeyStore 加密，mock 不需要真实 key） |
| 原则 | **真实 API key 一律不入库**；自动化只对 mock 验证链路 |

## 8. 网络与其他

- 服务器首跑 Robolectric 需外网下载 android-all jar（~100MB，一次性的）。
- adb 版本 ≥1.0.41；Windows 防火墙放行 8765/8770 端口（设备经 Wi-Fi 访问 PC 时）。
- USB 连接建议插后置端口并用原装线（P20/KSA 在 hub 上偶发掉线）。

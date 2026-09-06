# HowRead 自动测试架构说明

> 位置：`LibreraReader/ci/autotest/`（Windows 侧执行 UI 层；JVM 层在 Ubuntu 构建服务器执行）

## 1. 总体架构

```
                    ┌────────────────────────────────────────────┐
                    │            Ubuntu 构建服务器 (CI runner)      │
                    │  ./gradlew test<Flavor>DebugUnitTest         │
                    │  ├─ 单元层 (JVM): MyMath/StringUtils/TxtUtils │
                    │  └─ 集成层 (Robolectric): AppState/Bookmarks  │
                    └───────────────┬────────────────────────────┘
                                    │ Z: (NFS/SMB 同一目录)
┌───────────────────────────────────┼───────────────────────────────────┐
│  Windows 测试工作站                 │  ci/autotest/run_all.py           │
│  Python 3.13 + uiautomator2        │  ├─ 选包(按ABI) → 安装 → 推fixtures │
│                                    │  ├─ 驱动3真机 + 1 AVD 并行          │
│  adb ──► MI9 (arm64, MIUI)         │  ├─ 每用例: 进度/心跳/超时/重试       │
│  adb ──► P20 (arm64, EMUI)         │  └─ results/<时间戳>_<层级>/        │
│  adb ──► KSA (armv7, EMUI)         │      report.md + run.log + 证据     │
│  adb ──► MedicineAVD (x86_64)      │                                    │
└───────────────────────────────────┴───────────────────────────────────┘
        │
        ├── tools/ai_mock.py        OpenAI 兼容 mock（AI 功能测试）
        └── Z:\opt\librera\test_webdav.py  WebDAV 测试服务器 :8765（同步用例）
```

## 2. 目录结构

```
ci/autotest/
├── docs/               TEST_PLAN.md（方案+矩阵）、ARCHITECTURE.md、ENV_DEPENDENCIES.md
├── config/
│   ├── devices.json    真机/AVD 档案、flavor 包名、fixtures 路径
│   └── cases.yaml      全局参数 + case_meta（每用例 layer/priority/timeout/retries）
├── lib/
│   ├── driver.py       Device 驱动：uiautomator2 封装 + adb 代理 + crash 守护
│   │                   + 进度/心跳/超时/重试 + 证据留存（截图/uidump/logcat）
│   └── report.py       Markdown 报告（失败用例附截图/日志/备注）
├── cases/
│   └── ui/             tc_smoke.py (SM-01~07) / tc_function.py (FN-01~08) / tc_special.py (PF/ST)
├── fixtures→teskbook/  测试书目（big25.pdf、test.pdf、test.epub、demo.mobi 等）
├── tools/ai_mock.py    AI 大模型 mock 服务
├── archive/            归档的僵尸单测（从 app/src/test 迁出）
├── results/            每次运行一个 <时间戳>_<层级>_<层>/ 目录；LATEST.txt 指向最新
├── run_all.py          UI 层入口（--level/--serial/--avd/--flavor/--serial-exec）
└── run_unit.sh         JVM 层入口（服务器：gradlew testXxxDebugUnitTest）
```

JVM 层测试代码位于工程标准源集：`android/app/src/test/java/com/foobnix/autotest/`（单元层 4 个类 + Robolectric 集成层 2 个类），随 `gradlew testXxxDebugUnitTest` 执行，报告在 `android/app/build/reports/tests/`。

## 3. 关键设计

### 3.1 用例执行骨架（防卡死）
```
run_case(id, name, fn):
  1. 打印进度行 [HH:MM:SS][serial][n/N] ▶ ID 开始
  2. Heartbeat 线程（默认 30s 一跳）
  3. for attempt in 1..retries+1:
       fn 在独立线程执行，future.result(timeout=case超时)
       超时 → FAIL(TIMEOUT) + 截图 + dump + 强停 app
       PASS/SKIP → 跳出
  4. 打印结束行（耗时/尝试次数/备注），结果落盘
```

### 3.2 crash 守护
每个用例前后维护 logcat 行游标，增量扫描 `FATAL EXCEPTION` / `ANR in <pkg>`；命中即 FAIL 并现场取证。SM-07 做收尾兜底扫描。

### 3.3 设备适配层（实现期确认的事实）
| 问题 | 方案（固化在 driver/run_all） |
|---|---|
| KSA 是 armeabi-v7a | `find_apk` 按 `ro.product.cpu.abilist` 选包（arm64/arm/x86_64/uni） |
| MIUI 安装确认弹窗 | `-i com.android.vending` + 弹窗 watcher 自动点"继续安装" |
| 隐式 VIEW intent 被 MIUI 静默丢弃 | 按扩展名传正确 MIME（application/pdf|epub|mobipocket） |
| 默认设置滑动不翻页 | 点击分区（右 0.9w/左 0.1w），页码用中央菜单 `N/M` 验证 |
| 首启"所有文件访问"弹窗 | adb 预授权 + UI 兜底自动点"是" |
| P20 自动息屏 | 前置 WAKEUP + dismiss-keyguard + stay_on_while_plugged_in=7 |
| 个别机型 dump 编码错乱 | dump_has_text 内置 GBK→UTF-8 自恢复 |
| meminfo `TOTAL PSS:` 带逗号 | 正则 `TOTAL(?:\s+PSS)?:?\s+([\d,]+)` 去逗号解析 |

### 3.4 AI 功能测试
App 的 AI 配置存于 AppState（aiProtocol/aiBaseUrl/aiModel），key 在 SharedPreferences 文件 "ai"（AndroidKeyStore 加密）。测试时启动 `tools/ai_mock.py`（OpenAI 兼容 /v1/chat/completions 固定回复），把应用 AI 地址指向它即可端到端验证"AI 简介书籍"，无需真实 API key。

## 4. 与 CI 的集成点（演进）

- JVM 层可直接挂入现有 GitHub Actions `deploy.yml`（build 之后加 `gradlew test` 步骤）。
- UI 层需要设备池：当前为手动接设备 + Windows 工作站；后续可用 Jenkins agent 常驻。
- 结果目录 `results/LATEST.txt` 便于 CI 产物归档_latest 报告。

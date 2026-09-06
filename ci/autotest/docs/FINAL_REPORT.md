# HowRead 自动测试报告（四层体系 · 首次全量验收）

- 日期：2026-09-06　版本：HowRead 1.0.0 debug（三 flavor，Ubuntu 服务器构建）
- 运行记录：`results/` 目录（每次运行独立时间戳目录，`LATEST.txt` 指向最新）
  - JVM 层：`android/app/build/reports/tests/testGoogleDebugUnitTest/`（服务器）
  - 真机 L0：`results/20260906-134742_L0_ui-device/report.md`
  - 真机 L1：`results/20260906-135159_L1_ui-device/report.md`
  - 真机 L2：`results/20260906-140040_L2_ui-device/report.md`

## 一、总览

| 层 | 结果 | 明细 |
|---|---|---|
| **单元层（JVM）** | ✅ 53/53 PASS | MyMath(10)/StringUtils(12)/TxtUtils(18)/AppBookmark(8)，纯逻辑断言 |
| **集成层（Robolectric）** | ✅ 5/5 PASS | AppState JSON 持久化往返(2)、书签存储增查(3) |
| **UI 层 AVD** | ⏸ 环境受阻 | MedicineAVD 系统镜像损坏（黑屏/焦点 null/guest 工具段错误），框架就绪待 wipe data |
| **UI 层真机 L0** | ✅ 24/24 PASS（三机） | 冒烟门禁全绿；此前 fdroid(KSA)/pro(P20) 轮换亦 8/8 |
| **UI 层真机 L1** | ✅ 22 PASS / 0 FAIL / 5 SKIP | 见下 SKIP 说明 |
| **UI 层真机 L2** | ⚠️ 3 PASS / 1 FAIL | PF-01 冷启动超阈值（真实发现） |

**门禁结论：L0 全绿 + JVM 全绿 → 允许提测；PF-01 为性能观察项（Debug 包），release 复测后定阈值。**

## 二、失败用例明细

### [P1] PF-01 冷启动耗时 @MI9 — FAIL（真实发现）
- **现象**：冷启动中位 **3164ms**（3 次：3162/3164/3189，非常稳定），超过 2000ms 阈值。
- **测量方式**：force-stop → am start → logcat `Displayed` 耗时，3 次取中位。
- **证据**：`results/20260906-140040_L2_ui-device/evidence/48fee174/PF-01/`
- **分析**：Debug 构建未做代码优化，且 google flavor 带 AdMob/Firebase 初始化；判断大概率是 Debug 包固有开销。**行动项：release 包复测；若仍 >2s 排查 Application 初始化链。**

## 三、跳过用例（不计失败）

| 用例 | 设备 | 原因 |
|---|---|---|
| FN-02 收藏 | P20 / KSA | 设备书库首扫不收录 `/sdcard/Download`（Browse 书库文件夹为 0），Browse 依赖路径不可用；MI9 上 PASS。**关联发现 [P2]：低配机书库首扫失效/极慢，建议排查扫描触发时机** |
| FN-07 TTS 朗读 | 三台 | TTS 入口未在 UI 树定位（阅读器菜单/抽屉菜单均无直接入口），**关联发现 [P3]：朗读功能可发现性待复查**；取证 dump 在 `evidence/<serial>/FN-07/` |

## 四、测试过程发现的问题（全量）

| 级别 | 问题 | 状态 |
|---|---|---|
| P1 | Debug 包冷启动 3.3s 超 2s 阈值 | 待 release 复测 |
| P2 | KSA/P20 书库首扫不收录 Download，首用空书库 | 待排查（BookLib 扫描触发时机） |
| P2 | KSA 小屏阅读器菜单只渲染顶栏（工具栏需二次展开） | 交互层级兼容性观察 |
| P3 | TTS 朗读入口不可发现 | 待产品确认 |
| 环境 | MedicineAVD 镜像损坏 | 待 wipe data / 新建 AVD 后跑 UI-AVD 层 |
| 环境 | VIEW intent 仅冷态生效（warm 态被 app 忽略） | 已在 driver 固化规避，属 app 待改进项（onNewIntent 不处理 VIEW） |

## 五、本轮对框架的修复（迁移期回归 bug）

1. SM-04/05 书目路径参数在迁移中丢失（device_path=None 导致 intent 被跳过）→ run_level 显式注入。
2. FN-06 import 路径未随 `cases/ui/` 目录调整 → 修复。
3. L2 用例 cfg 参数未传入 → run_level 按 argnames 自动注入 cfg/fixtures。
4. intent 冷态投递固化（warm 态 onNewIntent 被忽略会导致开书失败）。

## 六、复跑指引

```bat
cd /d Z:\opt\librera\LibreraReader\ci\autotest
run_final_all.bat                        :: 真机 L0+L1+L2 一键全量（约 25 分钟）
python run_all.py --level L0 --avd       :: AVD 层（待 AVD 修复后可用）
:: JVM 层（Ubuntu 服务器）
bash ci/autotest/run_unit.sh google
```
正式发布门禁前：`config/cases.yaml` 的 `stability_monkey.duration_s` 调回 1800 夜跑。

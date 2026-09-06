# HowRead 自动测试报告

- 运行标识: 20260906-125928_L0_ui-avd
- 层级: L0　flavor: google　APK: -
- 生成时间: 2026-09-06 13:05:54

## 总览: 1 台设备 → **3 PASS / 5 FAIL / 0 SKIP**（共 8）

## MedicineAVD (emulator-5554, 14/SDK34) — flavor=google version=0.9.0 → 3 PASS / 5 FAIL / 0 SKIP

| ID | 用例 | 层 | 优先级 | 结果 | 尝试 | 耗时 | 备注 | 证据目录 |
|---|---|---|---|---|---|---|---|---|
| ENV | 版本确认 | env | - | PASS | 1 | 0.0s | versionName=0.9.0 pkg=com.howread.reader |  |
| SM-01 | 安装/升级安装 | ui | P0 | PASS | 1 | 1.7s |  | emulator-5554/SM-01 |
| SM-02 | 冷启动 | ui | P0 | FAIL | 2 | 36.2s | 步骤 cold_start 异常: 主界面关键元素 10s 未出现（启动 5.3s） | emulator-5554/SM-02 |
| SM-03 | Tab 遍历 | ui | P0 | FAIL | 2 | 216.3s | 步骤 traverse 异常: Tab 不可达: ['首页', '书库', '我的文件', '偏好'] | emulator-5554/SM-03 |
| SM-04 | 打开 PDF 翻页 | ui | P0 | FAIL | 2 | 39.1s | 主界面 10s 内未就绪 | emulator-5554/SM-04 |
| SM-05 | 打开 EPUB 翻页 | ui | P0 | FAIL | 2 | 39.8s | 主界面 10s 内未就绪 | emulator-5554/SM-05 |
| SM-06 | 退出重进持久性 | ui | P0 | FAIL | 2 | 48.2s | 步骤 restart 异常: 重启 15s 后首页未显示最近阅读/已读书目 | emulator-5554/SM-06 |
| SM-07 | 全程无 crash | ui | P0 | PASS | 1 | 0.8s |  | emulator-5554/SM-07 |

## 失败用例详情

### [P0] SM-02 冷启动 @emulator-5554 (FAIL) — 尝试 2 次

- **问题描述**: 步骤 cold_start 异常: 主界面关键元素 10s 未出现（启动 5.3s）
- **截图**: `emulator-5554/SM-02/fail_cold_start_125951.png`, `emulator-5554/SM-02/fail_cold_start_130010.png`
- **UI dump**: `emulator-5554/SM-02/fail_cold_start.xml`

### [P0] SM-03 Tab 遍历 @emulator-5554 (FAIL) — 尝试 2 次

- **问题描述**: 步骤 traverse 异常: Tab 不可达: ['首页', '书库', '我的文件', '偏好']
- **截图**: `emulator-5554/SM-03/fail_traverse_130157.png`, `emulator-5554/SM-03/fail_traverse_130346.png`
- **UI dump**: `emulator-5554/SM-03/tabs_dump.xml`

### [P0] SM-04 打开 PDF 翻页 @emulator-5554 (FAIL) — 尝试 2 次

- **问题描述**: 主界面 10s 内未就绪
- **截图**: `emulator-5554/SM-04/fail_open_130405.png`, `emulator-5554/SM-04/fail_open_130425.png`
- **UI dump**: `emulator-5554/SM-04/fail_open.xml`
- **logcat**: `emulator-5554/SM-04/fail_logcat.txt`

### [P0] SM-05 打开 EPUB 翻页 @emulator-5554 (FAIL) — 尝试 2 次

- **问题描述**: 主界面 10s 内未就绪
- **截图**: `emulator-5554/SM-05/fail_open_130444.png`, `emulator-5554/SM-05/fail_open_130505.png`
- **UI dump**: `emulator-5554/SM-05/fail_open.xml`
- **logcat**: `emulator-5554/SM-05/fail_logcat.txt`

### [P0] SM-06 退出重进持久性 @emulator-5554 (FAIL) — 尝试 2 次

- **问题描述**: 步骤 restart 异常: 重启 15s 后首页未显示最近阅读/已读书目
- **截图**: `emulator-5554/SM-06/fail_restart_130528.png`, `emulator-5554/SM-06/fail_restart_130554.png`
- **UI dump**: `emulator-5554/SM-06/restart_stuck.xml`

## 门禁结论: 存在 5 个失败 → **不满足门禁**

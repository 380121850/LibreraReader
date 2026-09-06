# HowRead 自动测试报告

- 运行标识: 20260906-125023_L0_ui-device
- 层级: L0　flavor: google　APK: -
- 生成时间: 2026-09-06 12:54:45

## 总览: 3 台设备 → **20 PASS / 4 FAIL / 0 SKIP**（共 24）

## MI 9 (cepheus) (48fee174, 11/SDK30) — flavor=google version=1.0.0 → 8 PASS / 0 FAIL / 0 SKIP

| ID | 用例 | 层 | 优先级 | 结果 | 尝试 | 耗时 | 备注 | 证据目录 |
|---|---|---|---|---|---|---|---|---|
| ENV | 版本确认 | env | - | PASS | 1 | 0.0s | versionName=1.0.0 pkg=com.howread.reader |  |
| SM-01 | 安装/升级安装 | ui | P0 | PASS | 1 | 7.7s |  | 48fee174/SM-01 |
| SM-02 | 冷启动 | ui | P0 | PASS | 1 | 5.8s |  | 48fee174/SM-02 |
| SM-03 | Tab 遍历 | ui | P0 | PASS | 1 | 17.4s |  | 48fee174/SM-03 |
| SM-04 | 打开 PDF 翻页 | ui | P0 | PASS | 1 | 82.0s |  | 48fee174/SM-04 |
| SM-05 | 打开 EPUB 翻页 | ui | P0 | PASS | 1 | 80.5s |  | 48fee174/SM-05 |
| SM-06 | 退出重进持久性 | ui | P0 | PASS | 1 | 5.5s |  | 48fee174/SM-06 |
| SM-07 | 全程无 crash | ui | P0 | PASS | 1 | 3.5s |  | 48fee174/SM-07 |

## SNE-AL00 (P20) (3JJ4C18904004595, 10/SDK29) — flavor=google version=1.0.0 → 6 PASS / 2 FAIL / 0 SKIP

| ID | 用例 | 层 | 优先级 | 结果 | 尝试 | 耗时 | 备注 | 证据目录 |
|---|---|---|---|---|---|---|---|---|
| ENV | 版本确认 | env | - | PASS | 1 | 0.0s | versionName=1.0.0 pkg=com.howread.reader |  |
| SM-01 | 安装/升级安装 | ui | P0 | PASS | 1 | 17.5s |  | 3JJ4C18904004595/SM-01 |
| SM-02 | 冷启动 | ui | P0 | PASS | 1 | 14.3s |  | 3JJ4C18904004595/SM-02 |
| SM-03 | Tab 遍历 | ui | P0 | PASS | 1 | 27.0s |  | 3JJ4C18904004595/SM-03 |
| SM-04 | 打开 PDF 翻页 | ui | P0 | FAIL | 2 | 85.1s | 在 Download 中未找到 big25 | 3JJ4C18904004595/SM-04 |
| SM-05 | 打开 EPUB 翻页 | ui | P0 | FAIL | 2 | 85.5s | 在 Download 中未找到 alicesadventures | 3JJ4C18904004595/SM-05 |
| SM-06 | 退出重进持久性 | ui | P0 | PASS | 1 | 13.7s |  | 3JJ4C18904004595/SM-06 |
| SM-07 | 全程无 crash | ui | P0 | PASS | 1 | 10.1s |  | 3JJ4C18904004595/SM-07 |

## KSA-AL10 (NETNU20617301956, 9/SDK28) — flavor=google version=1.0.0 → 6 PASS / 2 FAIL / 0 SKIP

| ID | 用例 | 层 | 优先级 | 结果 | 尝试 | 耗时 | 备注 | 证据目录 |
|---|---|---|---|---|---|---|---|---|
| ENV | 版本确认 | env | - | PASS | 1 | 0.0s | versionName=1.0.0 pkg=com.howread.reader |  |
| SM-01 | 安装/升级安装 | ui | P0 | PASS | 1 | 14.9s |  | NETNU20617301956/SM-01 |
| SM-02 | 冷启动 | ui | P0 | PASS | 1 | 13.6s |  | NETNU20617301956/SM-02 |
| SM-03 | Tab 遍历 | ui | P0 | PASS | 1 | 21.3s |  | NETNU20617301956/SM-03 |
| SM-04 | 打开 PDF 翻页 | ui | P0 | FAIL | 2 | 92.4s | 在 Download 中未找到 big25 | NETNU20617301956/SM-04 |
| SM-05 | 打开 EPUB 翻页 | ui | P0 | FAIL | 2 | 92.1s | 在 Download 中未找到 alicesadventures | NETNU20617301956/SM-05 |
| SM-06 | 退出重进持久性 | ui | P0 | PASS | 1 | 13.7s |  | NETNU20617301956/SM-06 |
| SM-07 | 全程无 crash | ui | P0 | PASS | 1 | 5.8s |  | NETNU20617301956/SM-07 |

## 失败用例详情

### [P0] SM-04 打开 PDF 翻页 @3JJ4C18904004595 (FAIL) — 尝试 2 次

- **问题描述**: 在 Download 中未找到 big25
- **截图**: `3JJ4C18904004595/SM-04/fail_open_125210.png`, `3JJ4C18904004595/SM-04/fail_open_125253.png`
- **UI dump**: `3JJ4C18904004595/SM-04/fail_open.xml`
- **logcat**: `3JJ4C18904004595/SM-04/fail_logcat.txt`

### [P0] SM-05 打开 EPUB 翻页 @3JJ4C18904004595 (FAIL) — 尝试 2 次

- **问题描述**: 在 Download 中未找到 alicesadventures
- **截图**: `3JJ4C18904004595/SM-05/fail_open_125335.png`, `3JJ4C18904004595/SM-05/fail_open_125419.png`
- **UI dump**: `3JJ4C18904004595/SM-05/fail_open.xml`
- **logcat**: `3JJ4C18904004595/SM-05/fail_logcat.txt`

### [P0] SM-04 打开 PDF 翻页 @NETNU20617301956 (FAIL) — 尝试 2 次

- **问题描述**: 在 Download 中未找到 big25
- **截图**: `NETNU20617301956/SM-04/fail_open_125205.png`, `NETNU20617301956/SM-04/fail_open_125252.png`
- **UI dump**: `NETNU20617301956/SM-04/fail_open.xml`
- **logcat**: `NETNU20617301956/SM-04/fail_logcat.txt`

### [P0] SM-05 打开 EPUB 翻页 @NETNU20617301956 (FAIL) — 尝试 2 次

- **问题描述**: 在 Download 中未找到 alicesadventures
- **截图**: `NETNU20617301956/SM-05/fail_open_125337.png`, `NETNU20617301956/SM-05/fail_open_125424.png`
- **UI dump**: `NETNU20617301956/SM-05/fail_open.xml`
- **logcat**: `NETNU20617301956/SM-05/fail_logcat.txt`

## 门禁结论: 存在 4 个失败 → **不满足门禁**

# HowRead 自动测试报告

- 运行标识: 20260906-133532_L0_ui-device
- 层级: L0　flavor: google　APK: -
- 生成时间: 2026-09-06 13:39:42

## 总览: 1 台设备 → **6 PASS / 2 FAIL / 0 SKIP**（共 8）

## SNE-AL00 (P20) (3JJ4C18904004595, 10/SDK29) — flavor=google version=1.0.0 → 6 PASS / 2 FAIL / 0 SKIP

| ID | 用例 | 层 | 优先级 | 结果 | 尝试 | 耗时 | 备注 | 证据目录 |
|---|---|---|---|---|---|---|---|---|
| ENV | 版本确认 | env | - | PASS | 1 | 0.0s | versionName=1.0.0 pkg=com.howread.reader |  |
| SM-01 | 安装/升级安装 | ui | P0 | PASS | 1 | 13.3s |  | 3JJ4C18904004595/SM-01 |
| SM-02 | 冷启动 | ui | P0 | PASS | 1 | 11.0s |  | 3JJ4C18904004595/SM-02 |
| SM-03 | Tab 遍历 | ui | P0 | PASS | 1 | 25.4s |  | 3JJ4C18904004595/SM-03 |
| SM-04 | 打开 PDF 翻页 | ui | P0 | FAIL | 2 | 85.0s | 在 Download 中未找到 big25 | 3JJ4C18904004595/SM-04 |
| SM-05 | 打开 EPUB 翻页 | ui | P0 | FAIL | 2 | 85.9s | 在 Download 中未找到 alicesadventures | 3JJ4C18904004595/SM-05 |
| SM-06 | 退出重进持久性 | ui | P0 | PASS | 1 | 12.8s |  | 3JJ4C18904004595/SM-06 |
| SM-07 | 全程无 crash | ui | P0 | PASS | 1 | 8.7s |  | 3JJ4C18904004595/SM-07 |

## 失败用例详情

### [P0] SM-04 打开 PDF 翻页 @3JJ4C18904004595 (FAIL) — 尝试 2 次

- **问题描述**: 在 Download 中未找到 big25
- **截图**: `3JJ4C18904004595/SM-04/fail_open_133710.png`, `3JJ4C18904004595/SM-04/fail_open_133753.png`
- **UI dump**: `3JJ4C18904004595/SM-04/fail_open.xml`
- **logcat**: `3JJ4C18904004595/SM-04/fail_logcat.txt`

### [P0] SM-05 打开 EPUB 翻页 @3JJ4C18904004595 (FAIL) — 尝试 2 次

- **问题描述**: 在 Download 中未找到 alicesadventures
- **截图**: `3JJ4C18904004595/SM-05/fail_open_133835.png`, `3JJ4C18904004595/SM-05/fail_open_133919.png`
- **UI dump**: `3JJ4C18904004595/SM-05/fail_open.xml`
- **logcat**: `3JJ4C18904004595/SM-05/fail_logcat.txt`

## 门禁结论: 存在 2 个失败 → **不满足门禁**

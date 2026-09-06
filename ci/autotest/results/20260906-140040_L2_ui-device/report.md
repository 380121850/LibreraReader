# HowRead 自动测试报告

- 运行标识: 20260906-140040_L2_ui-device
- 层级: L2　flavor: google　APK: -
- 生成时间: 2026-09-06 14:06:46

## 总览: 1 台设备 → **3 PASS / 1 FAIL / 0 SKIP**（共 4）

## MI 9 (cepheus) (48fee174, 11/SDK30) — flavor=google version=1.0.0 → 3 PASS / 1 FAIL / 0 SKIP

| ID | 用例 | 层 | 优先级 | 结果 | 尝试 | 耗时 | 备注 | 证据目录 |
|---|---|---|---|---|---|---|---|---|
| ENV | 版本确认 | env | - | PASS | 1 | 0.0s | versionName=1.0.0 pkg=com.howread.reader |  |
| PF-01 | 冷启动耗时 | ui | P1 | FAIL | 1 | 14.8s | 步骤 measure_x3 异常: 冷启动中位数 3164ms（阈值 2000ms），各次=[3162, 3164, 3189] | 48fee174/PF-01 |
| PF-03 | 内存趋势 | ui | P1 | PASS | 1 | 41.8s |  | 48fee174/PF-03 |
| ST-01 | 稳定性 monkey | ui | P0 | PASS | 1 | 304.8s |  | 48fee174/ST-01 |

## 失败用例详情

### [P1] PF-01 冷启动耗时 @48fee174 (FAIL) — 尝试 1 次

- **问题描述**: 步骤 measure_x3 异常: 冷启动中位数 3164ms（阈值 2000ms），各次=[3162, 3164, 3189]
- **截图**: `48fee174/PF-01/fail_measure_x3_140058.png`
- **UI dump**: `48fee174/PF-01/fail_measure_x3.xml`

## 门禁结论: 存在 1 个失败 → **不满足门禁**

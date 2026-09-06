# HowRead 自动测试报告

- 运行标识: 20260906-135936_L2_ui-device
- 层级: L2　flavor: google　APK: -
- 生成时间: 2026-09-06 13:59:46

## 总览: 1 台设备 → **1 PASS / 3 FAIL / 0 SKIP**（共 4）

## MI 9 (cepheus) (48fee174, 11/SDK30) — flavor=google version=1.0.0 → 1 PASS / 3 FAIL / 0 SKIP

| ID | 用例 | 层 | 优先级 | 结果 | 尝试 | 耗时 | 备注 | 证据目录 |
|---|---|---|---|---|---|---|---|---|
| ENV | 版本确认 | env | - | PASS | 1 | 0.0s | versionName=1.0.0 pkg=com.howread.reader |  |
| PF-01 | 冷启动耗时 | ui | P1 | FAIL | 1 | 0.9s | 异常: 'NoneType' object has no attribute 'get' / id, *args, **kwargs)     ~~^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^   File "Z:\opt\librera\LibreraReader\ci\autotest\case | 48fee174/PF-01 |
| PF-03 | 内存趋势 | ui | P1 | FAIL | 1 | 3.8s | 步骤 baseline 异常: 'NoneType' object is not subscriptable | 48fee174/PF-03 |
| ST-01 | 稳定性 monkey | ui | P0 | FAIL | 1 | 0.9s | 异常: 'NoneType' object has no attribute 'get' /  in target     fn(self, case_id, *args, **kwargs)     ~~^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^   File "Z:\opt\librera\L | 48fee174/ST-01 |

## 失败用例详情

### [P1] PF-01 冷启动耗时 @48fee174 (FAIL) — 尝试 1 次

- **问题描述**: 异常: 'NoneType' object has no attribute 'get' | id, *args, **kwargs)
    ~~^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
  File "Z:\opt\librera\LibreraReader\ci\autotest\cases\ui\tc_special.py", line 13, in pf01_cold_start
    thresholds = cfg.get("cold_start_threshold_ms", {})
                 ^^^^^^^
AttributeError: 'NoneType' object has no attribute 'get'


### [P1] PF-03 内存趋势 @48fee174 (FAIL) — 尝试 1 次

- **问题描述**: 步骤 baseline 异常: 'NoneType' object is not subscriptable
- **截图**: `48fee174/PF-03/fail_baseline_135943.png`
- **UI dump**: `48fee174/PF-03/fail_baseline.xml`

### [P0] ST-01 稳定性 monkey @48fee174 (FAIL) — 尝试 1 次

- **问题描述**: 异常: 'NoneType' object has no attribute 'get' |  in target
    fn(self, case_id, *args, **kwargs)
    ~~^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
  File "Z:\opt\librera\LibreraReader\ci\autotest\cases\ui\tc_special.py", line 94, in st01_monkey
    m = cfg.get("stability_monkey", {})
        ^^^^^^^
AttributeError: 'NoneType' object has no attribute 'get'


## 门禁结论: 存在 3 个失败 → **不满足门禁**

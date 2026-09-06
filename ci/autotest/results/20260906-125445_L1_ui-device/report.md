# HowRead 自动测试报告

- 运行标识: 20260906-125445_L1_ui-device
- 层级: L1　flavor: google　APK: -
- 生成时间: 2026-09-06 13:01:11

## 总览: 3 台设备 → **19 PASS / 3 FAIL / 5 SKIP**（共 27）

## MI 9 (cepheus) (48fee174, 11/SDK30) — flavor=google version=1.0.0 → 7 PASS / 1 FAIL / 1 SKIP

| ID | 用例 | 层 | 优先级 | 结果 | 尝试 | 耗时 | 备注 | 证据目录 |
|---|---|---|---|---|---|---|---|---|
| ENV | 版本确认 | env | - | PASS | 1 | 0.0s | versionName=1.0.0 pkg=com.howread.reader |  |
| FN-08 | intent 打开 | ui | P0 | PASS | 1 | 23.3s |  | 48fee174/FN-08 |
| FN-01 | 最近列表 | ui | P1 | PASS | 1 | 20.0s |  | 48fee174/FN-01 |
| FN-02 | 收藏 | ui | P1 | PASS | 1 | 63.9s |  | 48fee174/FN-02 |
| FN-03 | 书签 | ui | P0 | PASS | 1 | 77.5s |  | 48fee174/FN-03 |
| FN-04 | 全文搜索 | ui | P0 | PASS | 1 | 28.5s |  | 48fee174/FN-04 |
| FN-05 | 阅读设置 | ui | P1 | PASS | 1 | 23.3s |  | 48fee174/FN-05 |
| FN-06 | 主题切换 | ui | P1 | FAIL | 2 | 3.8s | 异常: No module named 'cases.tc_smoke' / er.py", line 466, in target     fn(self, case_id, *args, **kwargs)     ~~^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^   File "Z:\opt\ | 48fee174/FN-06 |
| FN-07 | TTS 朗读 | ui | P2 | SKIP | 1 | 88.8s | TTS 入口未在已知位置找到（入口待勘探，见取证 dump） | 48fee174/FN-07 |

## SNE-AL00 (P20) (3JJ4C18904004595, 10/SDK29) — flavor=google version=1.0.0 → 6 PASS / 1 FAIL / 2 SKIP

| ID | 用例 | 层 | 优先级 | 结果 | 尝试 | 耗时 | 备注 | 证据目录 |
|---|---|---|---|---|---|---|---|---|
| ENV | 版本确认 | env | - | PASS | 1 | 0.0s | versionName=1.0.0 pkg=com.howread.reader |  |
| FN-08 | intent 打开 | ui | P0 | PASS | 1 | 36.1s |  | 3JJ4C18904004595/FN-08 |
| FN-01 | 最近列表 | ui | P1 | PASS | 1 | 30.5s |  | 3JJ4C18904004595/FN-01 |
| FN-02 | 收藏 | ui | P1 | SKIP | 1 | 34.6s | 设备书库未收录 big25，Browse 收藏路径不可用（环境限制） | 3JJ4C18904004595/FN-02 |
| FN-03 | 书签 | ui | P0 | PASS | 1 | 106.9s |  | 3JJ4C18904004595/FN-03 |
| FN-04 | 全文搜索 | ui | P0 | PASS | 1 | 36.6s |  | 3JJ4C18904004595/FN-04 |
| FN-05 | 阅读设置 | ui | P1 | PASS | 1 | 34.3s |  | 3JJ4C18904004595/FN-05 |
| FN-06 | 主题切换 | ui | P1 | FAIL | 2 | 5.0s | 异常: No module named 'cases.tc_smoke' / er.py", line 466, in target     fn(self, case_id, *args, **kwargs)     ~~^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^   File "Z:\opt\ | 3JJ4C18904004595/FN-06 |
| FN-07 | TTS 朗读 | ui | P2 | SKIP | 1 | 96.1s | TTS 入口未在已知位置找到（入口待勘探，见取证 dump） | 3JJ4C18904004595/FN-07 |

## KSA-AL10 (NETNU20617301956, 9/SDK28) — flavor=google version=1.0.0 → 6 PASS / 1 FAIL / 2 SKIP

| ID | 用例 | 层 | 优先级 | 结果 | 尝试 | 耗时 | 备注 | 证据目录 |
|---|---|---|---|---|---|---|---|---|
| ENV | 版本确认 | env | - | PASS | 1 | 0.0s | versionName=1.0.0 pkg=com.howread.reader |  |
| FN-08 | intent 打开 | ui | P0 | PASS | 1 | 28.6s |  | NETNU20617301956/FN-08 |
| FN-01 | 最近列表 | ui | P1 | PASS | 1 | 33.9s |  | NETNU20617301956/FN-01 |
| FN-02 | 收藏 | ui | P1 | SKIP | 1 | 37.3s | 设备书库未收录 big25，Browse 收藏路径不可用（环境限制） | NETNU20617301956/FN-02 |
| FN-03 | 书签 | ui | P0 | PASS | 1 | 107.0s |  | NETNU20617301956/FN-03 |
| FN-04 | 全文搜索 | ui | P0 | PASS | 1 | 36.2s |  | NETNU20617301956/FN-04 |
| FN-05 | 阅读设置 | ui | P1 | PASS | 1 | 32.5s |  | NETNU20617301956/FN-05 |
| FN-06 | 主题切换 | ui | P1 | FAIL | 2 | 4.9s | 异常: No module named 'cases.tc_smoke' / er.py", line 466, in target     fn(self, case_id, *args, **kwargs)     ~~^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^   File "Z:\opt\ | NETNU20617301956/FN-06 |
| FN-07 | TTS 朗读 | ui | P2 | SKIP | 1 | 95.5s | TTS 入口未在已知位置找到（入口待勘探，见取证 dump） | NETNU20617301956/FN-07 |

## 失败用例详情

### [P1] FN-06 主题切换 @48fee174 (FAIL) — 尝试 2 次

- **问题描述**: 异常: No module named 'cases.tc_smoke' | er.py", line 466, in target
    fn(self, case_id, *args, **kwargs)
    ~~^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
  File "Z:\opt\librera\LibreraReader\ci\autotest\cases\ui\tc_function.py", line 414, in fn06_theme
    from cases.tc_smoke import _same_png
ModuleNotFoundError: No module named 'cases.tc_smoke'


### [P1] FN-06 主题切换 @3JJ4C18904004595 (FAIL) — 尝试 2 次

- **问题描述**: 异常: No module named 'cases.tc_smoke' | er.py", line 466, in target
    fn(self, case_id, *args, **kwargs)
    ~~^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
  File "Z:\opt\librera\LibreraReader\ci\autotest\cases\ui\tc_function.py", line 414, in fn06_theme
    from cases.tc_smoke import _same_png
ModuleNotFoundError: No module named 'cases.tc_smoke'


### [P1] FN-06 主题切换 @NETNU20617301956 (FAIL) — 尝试 2 次

- **问题描述**: 异常: No module named 'cases.tc_smoke' | er.py", line 466, in target
    fn(self, case_id, *args, **kwargs)
    ~~^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
  File "Z:\opt\librera\LibreraReader\ci\autotest\cases\ui\tc_function.py", line 414, in fn06_theme
    from cases.tc_smoke import _same_png
ModuleNotFoundError: No module named 'cases.tc_smoke'


## 跳过用例（环境限制/待勘探，不计失败）

- [P2] FN-07 TTS 朗读 @48fee174: TTS 入口未在已知位置找到（入口待勘探，见取证 dump）
- [P1] FN-02 收藏 @3JJ4C18904004595: 设备书库未收录 big25，Browse 收藏路径不可用（环境限制）
- [P2] FN-07 TTS 朗读 @3JJ4C18904004595: TTS 入口未在已知位置找到（入口待勘探，见取证 dump）
- [P1] FN-02 收藏 @NETNU20617301956: 设备书库未收录 big25，Browse 收藏路径不可用（环境限制）
- [P2] FN-07 TTS 朗读 @NETNU20617301956: TTS 入口未在已知位置找到（入口待勘探，见取证 dump）

## 门禁结论: 存在 3 个失败 → **不满足门禁**

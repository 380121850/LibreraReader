# HowRead 自动测试报告

- 运行标识: 20260906-135159_L1_ui-device
- 层级: L1　flavor: google　APK: -
- 生成时间: 2026-09-06 13:59:36

## 总览: 3 台设备 → **22 PASS / 0 FAIL / 5 SKIP**（共 27）

## MI 9 (cepheus) (48fee174, 11/SDK30) — flavor=google version=1.0.0 → 8 PASS / 0 FAIL / 1 SKIP

| ID | 用例 | 层 | 优先级 | 结果 | 尝试 | 耗时 | 备注 | 证据目录 |
|---|---|---|---|---|---|---|---|---|
| ENV | 版本确认 | env | - | PASS | 1 | 0.0s | versionName=1.0.0 pkg=com.howread.reader |  |
| FN-08 | intent 打开 | ui | P0 | PASS | 1 | 26.5s |  | 48fee174/FN-08 |
| FN-01 | 最近列表 | ui | P1 | PASS | 1 | 24.6s |  | 48fee174/FN-01 |
| FN-02 | 收藏 | ui | P1 | PASS | 1 | 64.5s |  | 48fee174/FN-02 |
| FN-03 | 书签 | ui | P0 | PASS | 2 | 168.8s |  | 48fee174/FN-03 |
| FN-04 | 全文搜索 | ui | P0 | PASS | 1 | 30.8s |  | 48fee174/FN-04 |
| FN-05 | 阅读设置 | ui | P1 | PASS | 1 | 24.4s |  | 48fee174/FN-05 |
| FN-06 | 主题切换 | ui | P1 | PASS | 1 | 24.5s |  | 48fee174/FN-06 |
| FN-07 | TTS 朗读 | ui | P2 | SKIP | 1 | 88.4s | TTS 入口未在已知位置找到（入口待勘探，见取证 dump） | 48fee174/FN-07 |

## SNE-AL00 (P20) (3JJ4C18904004595, 10/SDK29) — flavor=google version=1.0.0 → 7 PASS / 0 FAIL / 2 SKIP

| ID | 用例 | 层 | 优先级 | 结果 | 尝试 | 耗时 | 备注 | 证据目录 |
|---|---|---|---|---|---|---|---|---|
| ENV | 版本确认 | env | - | PASS | 1 | 0.0s | versionName=1.0.0 pkg=com.howread.reader |  |
| FN-08 | intent 打开 | ui | P0 | PASS | 1 | 36.1s |  | 3JJ4C18904004595/FN-08 |
| FN-01 | 最近列表 | ui | P1 | PASS | 1 | 33.5s |  | 3JJ4C18904004595/FN-01 |
| FN-02 | 收藏 | ui | P1 | SKIP | 1 | 34.7s | 设备书库未收录 big25，Browse 收藏路径不可用（环境限制） | 3JJ4C18904004595/FN-02 |
| FN-03 | 书签 | ui | P0 | PASS | 1 | 117.0s |  | 3JJ4C18904004595/FN-03 |
| FN-04 | 全文搜索 | ui | P0 | PASS | 1 | 37.9s |  | 3JJ4C18904004595/FN-04 |
| FN-05 | 阅读设置 | ui | P1 | PASS | 1 | 34.9s |  | 3JJ4C18904004595/FN-05 |
| FN-06 | 主题切换 | ui | P1 | PASS | 1 | 33.5s |  | 3JJ4C18904004595/FN-06 |
| FN-07 | TTS 朗读 | ui | P2 | SKIP | 1 | 99.2s | TTS 入口未在已知位置找到（入口待勘探，见取证 dump） | 3JJ4C18904004595/FN-07 |

## KSA-AL10 (NETNU20617301956, 9/SDK28) — flavor=google version=1.0.0 → 7 PASS / 0 FAIL / 2 SKIP

| ID | 用例 | 层 | 优先级 | 结果 | 尝试 | 耗时 | 备注 | 证据目录 |
|---|---|---|---|---|---|---|---|---|
| ENV | 版本确认 | env | - | PASS | 1 | 0.0s | versionName=1.0.0 pkg=com.howread.reader |  |
| FN-08 | intent 打开 | ui | P0 | PASS | 1 | 35.2s |  | NETNU20617301956/FN-08 |
| FN-01 | 最近列表 | ui | P1 | PASS | 1 | 33.8s |  | NETNU20617301956/FN-01 |
| FN-02 | 收藏 | ui | P1 | SKIP | 1 | 37.0s | 设备书库未收录 big25，Browse 收藏路径不可用（环境限制） | NETNU20617301956/FN-02 |
| FN-03 | 书签 | ui | P0 | PASS | 1 | 120.4s |  | NETNU20617301956/FN-03 |
| FN-04 | 全文搜索 | ui | P0 | PASS | 1 | 37.5s |  | NETNU20617301956/FN-04 |
| FN-05 | 阅读设置 | ui | P1 | PASS | 1 | 31.5s |  | NETNU20617301956/FN-05 |
| FN-06 | 主题切换 | ui | P1 | PASS | 1 | 32.7s |  | NETNU20617301956/FN-06 |
| FN-07 | TTS 朗读 | ui | P2 | SKIP | 1 | 99.8s | TTS 入口未在已知位置找到（入口待勘探，见取证 dump） | NETNU20617301956/FN-07 |

## 跳过用例（环境限制/待勘探，不计失败）

- [P2] FN-07 TTS 朗读 @48fee174: TTS 入口未在已知位置找到（入口待勘探，见取证 dump）
- [P1] FN-02 收藏 @3JJ4C18904004595: 设备书库未收录 big25，Browse 收藏路径不可用（环境限制）
- [P2] FN-07 TTS 朗读 @3JJ4C18904004595: TTS 入口未在已知位置找到（入口待勘探，见取证 dump）
- [P1] FN-02 收藏 @NETNU20617301956: 设备书库未收录 big25，Browse 收藏路径不可用（环境限制）
- [P2] FN-07 TTS 朗读 @NETNU20617301956: TTS 入口未在已知位置找到（入口待勘探，见取证 dump）

## 门禁结论: 全部 PASS（SKIP 不计）→ **满足门禁**

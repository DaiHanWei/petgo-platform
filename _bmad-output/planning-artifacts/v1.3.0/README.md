# V1.3.0 规划产物

> 集成分支 `dev_1.3.0`。本版本由**多份 PRD** 组成，各在自己的功能分支上产出并合回，最终在本目录汇齐。
> 状态：**开工中**（2026-09-09 建目录），尚无 PRD 落地。

## 1. 文件约定

| 件 | 文件名 | 说明 |
|---|---|---|
| PRD（每主题一份） | `PRD-v1.3.0-<主题>.md` | 主题名与功能分支 `feat/1.3.0-<主题>` 一致，如 `PRD-v1.3.0-ops-ui-refactor.md` |
| UI / UX 稿 | `ui-v1.3.0-<主题>.html` 或 `UX-v1.3.0-<主题>.md` | 可选 |
| 架构 delta | `architecture-v1.3.0-delta.md` | **全版本一份**，各主题在其中分节；基线 `../architecture.md` |
| epics | `epics-v1.3.0.md` | **全版本一份**，Epic 编号按下表预分配，避免并行分支撞号 |
| 决策日志 | `决策日志.md` | 跨主题产品决定 |
| 就绪度评审 | `implementation-readiness-report-<日期>-v1.3.0.md` | |

## 2. 主题登记表（开新主题先在这里占一行）

| 主题 | 功能分支 | PRD 文件 | Epic 号段 | 状态 |
|---|---|---|---|---|
| 运营后台 UI 重构 | `feat/1.3.0-ops-ui-refactor` | `PRD-v1.3.0-ops-ui-refactor.md` | Epic 1–? | 待传 PRD |

Epic 号段在拆 epics 前先登记，story 文件名 `<epic>-<n>-<中文名>.md` 由此派生，不同主题不会重复。

## 3. 实现层

story 与 `sprint-status-v1.3.0.yaml` 在 `../../implementation-artifacts/v1.3.0/`。

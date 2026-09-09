# V1.3.0 规划产物

> 集成分支 `dev_1.3.0`。本版本由**多份 PRD** 组成，各在自己的功能分支上产出并合回，最终在本目录汇齐。
> 状态：**开工中**。2026-09-09 首份 PRD（后台 · admin）已落地，见 §2。

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
| 后台（admin）：AB-15A 看板 / 16A 账号 / 17A 场所 / 18A KTP 定价 / 19A IA 与页面形态重整（37 页）/ 20A 暖贴 / 21A 角色配置 | `feat/1.3.0-ops-ui-refactor` | `PRD-v1.3.0-admin.md`（定稿 2026-09-01，修订至 09-08）+ `后台重构逐页规格.md`（AB-19A 逐页）+ `ui-v1.3.0-admin.html`（G0 + 五模板结构稿） | **Epic 1–11** | PRD 已回写（D-1~D-35）；架构 delta 已完成（AD-1~AD-12）；epics 已完成（11 Epic / 59 Story）；**就绪度评审 READY WITH MINOR REVISIONS 已回写**（`implementation-readiness-report-2026-09-09-v1.3.0.md`）；**59 条 story 全部 ready-for-dev**（`../../implementation-artifacts/v1.3.0/`，8284 行）；story 阶段回写见架构 §Story 阶段回写记录；12 项待拍板已于 09-09 全部闭合（D-36～D-46）；**下一步：dev-story 1-1（等指令）** | |

Epic 号段在拆 epics 前先登记，story 文件名 `<epic>-<n>-<中文名>.md` 由此派生，不同主题不会重复。

## 2.1 后台 PRD 引用的外部输入（2026-09-09 处置）

| 文件 | 被谁引用 | 用途 |
|---|---|---|
| ~~`后台功能全量清单-20260901.md`~~ | AB-19A | 不再找回（D-27）：拆 story 时从 `dev_1.3.0` 代码重新生成写操作清单入库 |
| `内容运营所需数据-20260831.sql` | AB-15A | ✅ 已归档本目录；口径已提炼为 PRD §1 ①-a 口径表（D-26），SQL 仅作溯源 |
| `1-3-0prd.md`（App 端 PRD） | AB-17A（FR-112 硬配套）/ AB-18A（FR-120） | ⚠️ 仍缺；随 App 端主题分支传入 |
| `.decision-log.md` | 逐页规格 | 不补（仅历史用途）；本版本决策改记 `决策日志.md` |

## 3. 实现层

story 与 `sprint-status-v1.3.0.yaml` 在 `../../implementation-artifacts/v1.3.0/`。

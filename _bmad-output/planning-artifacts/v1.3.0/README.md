# V1.3.0 规划产物

> 集成分支 `dev_1.3.0`。本版本由**多份 PRD** 组成，各在自己的功能分支上产出并合回，最终在本目录汇齐。
> 状态：**开工中**。2026-09-09 首份 PRD（后台 · admin）已落地，见 §2。

## 1. 文件约定（2026-09-09 改为「一主题一套」，各主题分支并行不撞文件）

| 件 | 文件名 | 说明 |
|---|---|---|
| PRD | `PRD-v1.3.0-<主题>.md` | 如 `PRD-v1.3.0-admin.md`、`PRD-v1.3.0-app.md` |
| UI / UX 稿 | `ui-v1.3.0-<主题>.html` / `UX-v1.3.0-<主题>.md` | |
| 架构 delta | `architecture-v1.3.0-<主题>-delta.md` | 基线 `../architecture.md`；跨主题契约写在先出的 delta 里，后出的引用 |
| epics | `epics-v1.3.0-<主题>.md` | Epic 编号按下表预分配号段 |
| 决策日志 | `决策日志-<主题>.md` | |
| 就绪度评审 | `implementation-readiness-report-<日期>-v1.3.0-<主题>.md` | 评审目录 `validation-<主题>-<日期>/` |
| sprint-status | `../../implementation-artifacts/v1.3.0/sprint-status-v1.3.0-<主题>.yaml` | story 文件同目录，编号靠号段不重复 |

主题名取登记表「主题」列的英文短名（admin / app …），不用分支名。BMAD 技能按 `project-context.md` 的版本 + 主题解析规则定位这些文件。

## 2. 主题登记表（开新主题先在这里占一行）

| 主题 | 功能分支 | PRD 文件 | Epic 号段 | 状态 |
|---|---|---|---|---|
| 后台（admin）：AB-15A 看板 / 16A 账号 / 17A 场所 / 18A KTP 定价 / 19A IA 与页面形态重整（37 页）/ 20A 暖贴 / 21A 角色配置 | `feat/1.3.0-ops-ui-refactor` | `PRD-v1.3.0-admin.md`（定稿 2026-09-01，修订至 09-08）+ `后台重构逐页规格.md`（AB-19A 逐页）+ `ui-v1.3.0-admin.html`（G0 + 五模板结构稿） | **Epic 1–11** | PRD 已回写（D-1~D-35）；架构 delta 已完成（AD-1~AD-12）；epics 已完成（11 Epic / 59 Story）；**就绪度评审 READY WITH MINOR REVISIONS 已回写**（`implementation-readiness-report-2026-09-09-v1.3.0-admin.md`）；**59 条 story 全部 ready-for-dev**（`../../implementation-artifacts/v1.3.0/`，8284 行）；story 阶段回写见架构 §Story 阶段回写记录；12 项待拍板已于 09-09 全部闭合（D-36～D-46）；**执行方式已定（09-09）：云端一口气跑完 Epic 1～9、11（Epic 10 等电商线），不设检查点，本地统一验收；指令见 `../../implementation-artifacts/v1.3.0/README.md`** | |

Epic 号段在拆 epics 前先登记，story 文件名 `<epic>-<n>-<中文名>.md` 由此派生，不同主题不会重复。

## 2.1 后台 PRD 引用的外部输入（2026-09-09 处置）

| 文件 | 被谁引用 | 用途 |
|---|---|---|
| ~~`后台功能全量清单-20260901.md`~~ | AB-19A | 不再找回（D-27）：拆 story 时从 `dev_1.3.0` 代码重新生成写操作清单入库 |
| `内容运营所需数据-20260831.sql` | AB-15A | ✅ 已归档本目录；口径已提炼为 PRD §1 ①-a 口径表（D-26），SQL 仅作溯源 |
| `1-3-0prd.md`（App 端 PRD） | AB-17A（FR-112 硬配套）/ AB-18A（FR-120） | ⚠️ 仍缺；随 App 端主题分支传入 |
| `.decision-log.md` | 逐页规格 | 不补（仅历史用途）；本版本决策改记 `决策日志-admin.md` |

## 3. 实现层

story 与 `sprint-status-v1.3.0-admin.yaml` 在 `../../implementation-artifacts/v1.3.0/`。

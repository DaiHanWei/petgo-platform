# V1.3.0 规划产物

> 集成分支 `dev_1.3.0`。本版本由**多份 PRD** 组成，各在自己的功能分支上产出并合回，最终在本目录汇齐。
> 状态：**实现中**（2026-09-11）。admin / batch-a 两主题规划全套已入库、story 全部 ready-for-dev；batch-b1 刚入库 PRD + UI 稿，epics 未拆。

## 1. 文件约定

**一主题一套**（2026-09-09 起）：PRD、UI 稿、架构 delta、epics、决策日志、sprint-status 各主题独立成文，不再"全版本一份"。

| 件 | 命名 | 说明 |
|---|---|---|
| PRD | `PRD-v1.3.0-<主题>.md` | 主题名与功能分支 `feat/1.3.0-<主题>` 对应 |
| UI / UX 稿 | `ui-v1.3.0-<主题>.html` 或 `UX-v1.3.0-<主题>.md` | 可选 |
| 架构 delta | `architecture-v1.3.0-<主题>-delta.md` | 基线 `../architecture.md`，各主题只写增量 |
| epics | `epics-v1.3.0-<主题>.md` | Epic 编号在各自文件内独立成域 |
| 决策日志 | `决策日志-<主题>.md` | 该主题的产品决定，冲突时以此为准 |
| 就绪度评审 | `implementation-readiness-report-<日期>-v1.3.0-<主题>.md` | |

> ⚠️ **早于本约定入库的主题可能用无后缀文件名**（见下表「产物文件」列，那一列是权威）。
> 按主题定位文件时：先找带主题后缀的名字，找不到就取登记表里登记的实际文件名，**不要猜、不要回退根目录**。

## 2. 主题登记表（开新主题先在这里占一行）

| 主题 | 功能分支 | Epic 号段 | Story | 状态 |
|---|---|---|---|---|
| **admin** · 运营后台 UI 重构 | `feat/1.3.0-ops-ui-refactor` | Epic 1–11 | 59，全部 ready-for-dev | 云端批跑中（2026-09-09 起）；Epic 10 的 6 条跳过，等电商线合入后再做 |
| **batch-a** · 内容体验修复包 | `feat/1.3.0-batch-a-content-fixes` | Epic 1–5 | 19，全部 ready-for-dev | 规划全套已入库（2026-09-10），待开跑 |
| **batch-b1** · 场所发现与社交基建包 | `feat/1.3.0-batch-b1-places-social` | 待拆 | 未拆 | PRD + UI 稿已入库（2026-09-11），已出代码核对报告、4 条决策已定；**卡在 AB-17A（后台场所管理）排期**，其余可拆 epics |

**产物文件**（按主题定位时以本列为准）：

| 主题 | PRD | 架构 delta | epics | 决策日志 | sprint-status |
|---|---|---|---|---|---|
| admin | `PRD-v1.3.0-admin.md` | `architecture-v1.3.0-admin-delta.md` | `epics-v1.3.0-admin.md` | `决策日志-admin.md` | `sprint-status-v1.3.0-admin.yaml` |
| batch-a | `PRD-v1.3.0-batch-a-content-fixes.md` | `architecture-v1.3.0-delta.md` ⚠️无后缀 | `epics-v1.3.0-batch-a.md` | `决策日志.md` ⚠️无后缀 | `sprint-status-v1.3.0-batch-a.yaml` |
| batch-b1 | `PRD-v1.3.0-batch-b1-places-social.md` | 待建 | 待建 | `决策日志-batch-b1.md` | 待建 |

> **batch-b1 另有两份**：UI 稿 `ui-v1.3.0-batch-b1-places-social.html`、落地前核对报告 `代码核对报告-batch-b1.md`（PRD/UI 稿的「现状如此」逐条对代码核实的结果 + 待拍板问题，拆 epics 前必读）。
> batch-b1 对应母版总 PRD 的批次 B1；批次 B2（FR-117 / FR-120 / 场所打卡 ⑧）尚未入库，引用时别与 B1 混说。

> **Epic 号段跨主题会重复，这是允许的**：两主题的 epics 文件与 sprint-status 各自独立，story 文件名带中文名互不覆盖，技能按 sprint-status 里的完整 key 定位 story，不按 `<epic>-<n>` 前缀通配。
> 代价只在口头引用：同一目录下 admin 与 batch-a 都有 `1-1-*`，**说 story 必须带主题**（如「batch-a 的 1-1」）。

## 3. 实现层

story 与各主题的 `sprint-status-v1.3.0-<主题>.yaml` 同在 `../../implementation-artifacts/v1.3.0/`；
云端批跑的起法见该目录 README 与 `scripts/cloud-run-story-loop.sh`。

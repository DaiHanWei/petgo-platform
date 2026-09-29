# V1.3.2 规划产物

> 集成分支 `dev_1.3.2`。本版本 = 原 V1.3.0 批次 B2「插画依赖包」独立成版（2026-09-18 拆版）：**FR-117 Tailsonality 宠物人格测试 · FR-120 宠物护照与场所盖章 · FR-112 §8 场所打卡 · FR-111 里程碑徽章视觉重绘**。
> 文件约定沿用 V1.3.0：**一主题一套**，文件名带主题后缀。

## 主题登记表

| 主题 | 功能分支 | Epic 号段 | Story | 状态 |
|---|---|---|---|---|
| **batch-a** · 插画依赖包（App + 后端 + 后台） | `feat/1.3.2-batch-a-app-feature` | Epic 1–5 | 26 | PRD / 后台 PRD / 内容设计 / UI 稿 / 决策日志 / 代码核对报告已入库（2026-09-28）；架构 delta（AD-1~AD-20）+ epics（26 story）已定稿（2026-09-29）—— **待 sprint planning 与逐条建 story 文件** |

## 产物文件（batch-a）

| 件 | 文件 | 说明 |
|---|---|---|
| PRD（App 端） | `PRD-v1.3.2-batch-a.md` | 原名 `1-3-2prd.md`，原样入库 |
| PRD（后台） | `PRD-v1.3.2-batch-a-admin.md` | 原名 `v1-3-2后台prd.md`；AB-18A 定价 / AB-18B 场所专属章 |
| 内容设计 | `tailsonality-内容设计.md` | FR-117 维度 / 16 角色 / 题库 / 计分 / 全部文案（中文；EN/ID 由 dev agent 翻译，D-8） |
| UI 稿 | `ui-v1.3.2-batch-a.html` | 52 屏；图面多为占位示意 |
| 设计资产清单 | `设计资产清单.md` | 插画产线清单 |
| 编码规则 | `宠物身份码护照编码规则.md` | 护照号规则（外部原文，笔误见决策日志 C-13） |
| **决策日志** | `决策日志-batch-a.md` | **冲突时以此为准** |
| **代码核对报告** | `代码核对报告-batch-a.md` | 文档「现状如此」对代码的核实 + 会被打破的既有测试，拆 epics 前必读 |
| 架构 delta | `architecture-v1.3.2-batch-a-delta.md` | AD-1~AD-20，2026-09-29 定稿（经三路评审）；决策记录 `architecture-batch-a/.memlog.md` |
| epics | `epics-v1.3.2-batch-a.md` | 5 个 Epic · 26 条 story，2026-09-29 定稿 |
| sprint-status | `../../implementation-artifacts/v1.3.2/sprint-status-v1.3.2-batch-a.yaml` | 2026-09-29 生成；26 条 story 文件全部就绪（ready-for-dev）|

**权威顺序**：决策日志 > PRD（App / 后台）> 内容设计 > UI 稿。

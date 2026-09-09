# 规划产物（planning-artifacts）

每个版本一个目录，目录内 README 说明该版本的输入与产出状态。总览见 `../README.md`。

| 目录 | 内容 |
|---|---|
| `architecture.md` | **跨版本基线架构**（V1.0 定稿）。各版本只写 delta，不改此文件 |
| `v1.0.0/` | PRD / UX_DESIGN / UX_EXPERIENCE / TECH_FRAMEWORK / epics / 就绪度评审 / sprint 变更提案 / 里程碑文案 / V1.0 索引 |
| `v1.1.0/` | epics-v1.1 / architecture-v1.1-delta（PRD 源在仓库外，见目录 README） |
| `v1.1.2/` `v1.1.4/` `v1.1.6/` `v1.4.0/` | PRD 输入 + 架构 delta + epics + 就绪度/校验评审 + 决策日志 + UI 稿 |
| `admin-backend/` | 运营后台专题：PRD / UX / architecture / epics / 就绪度评审 |
| `bug-system/` | Bug 同步工具专题：PRD + 评审 |
| `research/` | 技术调研报告 |
| `_incoming/` | 待评估或未归版本的 PRD 候选，**不是权威输入** |

BMAD 工具默认会把新产物写到本目录根（如 `prds/`、`architecture/`）。**产出后请立即移入对应版本目录**，根目录只保留 `architecture.md` 和本 README。

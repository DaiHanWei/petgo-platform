# 自建埋点（analytics-pipeline）

专题线，不挂版本；App 侧随 V1.5.0 切换。分支 `feat/analytics-pipeline`。替代已停用的 PostHog：后端接收 → 原始层（35 天）→ 明细层（3 个月）→ 汇总层（长期），团队经 AI 查数问数。

| 件 | 文件 | 状态 |
|---|---|---|
| 大方向与需求对照 | decisions.md | 定稿 |
| PRD | prd.md | final（27 FR） |
| 架构 delta | architecture.md | complete（基线 `../architecture.md`，冲突以本文件为准） |
| Epics | epics.md | complete（4 epic / 28 story） |
| 就绪度 | readiness.md、review-readiness-independent.md | 修订后可实现 |
| 决策日志 | .decision-log.md | 持续追加 |
| sprint-status | `../../implementation-artifacts/analytics-pipeline/sprint-status-analytics-pipeline.yaml` | 28 story ready-for-dev |

执行顺序：Epic 1（后端管道）→ Epic 2（App 切自建）/ Epic 3（补埋点）→ Epic 4（汇总、对账、AI 查数）。后端先于 App 上生产。

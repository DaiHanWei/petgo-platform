# 实现产物（implementation-artifacts）

每个版本一个目录，与 `../planning-artifacts/<ver>/` 同名。执行入口是目录内的 `sprint-status*.yaml`，其 `story_location` 指向同目录 story 文件。

| 目录 | 内容 |
|---|---|
| `CROSS-STORY-DECISIONS.md` | **跨版本**：表归属、数据生命周期、Flyway 号规则等跨 story 契约。冲突以它为准 |
| `deferred-work.md` | 跨版本：评审中发现、非当前 story 阻断的延后项 |
| `v1.0.0/` | 53 story（7 Epic + Epic 8 里程碑）+ `sprint-status.yaml` + `backend-remaining.md` + `qa/`（V1.0 全量人工测试流程） |
| `v1.1.0/` | 46 story + `sprint-status-v1.1.yaml` + `GEMPAY-INTEGRATION-DESIGN.md` |
| `v1.1.2/` | 19 story + sprint-status + `code-review-pr34-findings.md`（hex/v1.1.2 → main 评审） |
| `v1.1.4/` | 15 story + sprint-status + L2 视觉验收报告 + `code-review-v1.1.4-fix-list.md` |
| `v1.1.6/` | 55 story + sprint-status + L2 视觉验收报告 ×2 |
| `v1.4.0/` | 57 story + sprint-status + 并行开发契约 / 交接 / 签收 / 测试用例集 / 发布材料 |
| `admin-backend/` | 25 story + sprint-status（运营后台专题） |
| `specs/` | 独立 spec / 设计文档（不挂在某个 story 下的功能与修复方案），见目录 README |
| `investigations/` | 线上问题深挖记录 |

story 文件名格式 `<epic>-<n>-<中文名>.md`。**不同版本的编号会重复**（v1.0.0 和 v1.1.0 都有 `1-1-*`），引用时必须带版本目录。

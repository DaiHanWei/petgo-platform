# V1.4.2 规划输入

> 2026-11-04 入库。PRD 与 UI 稿是从 `~/Downloads/` 同步进来的**快照**；设计侧再改时以设计侧为准、重新同步。

## 主题登记

| 主题 `<theme>` | 线 | PRD | UI 稿 | 功能分支 | 状态 |
|---|---|---|---|---|---|
| `social-topic` | 追更宠物 · 新增 S 级里程碑 · 内容话题（FR-153~157、FR-69、FR-165、AB-10A 扩展 / AB-10B / AB-37） | `追更话题/1-4-2追更宠物·里程碑·话题prd.md`（2026-11-03 定稿） | `追更话题/ui-1-4-2-social-topic-integrated-v1.html`（App 43 屏）+ `追更话题/ui-admin-1-4-2-social-topic-integrated-v1.html`（后台 19 屏），2026-11-03 定稿 | `feat/1.4.2-social-topic` | 规划完成，待切分支交云端 |
| （待入库） | 电商（第二份 PRD） | — | — | — | 未入库 |

## 产物文件（social-topic）

| 产物 | 文件 | 状态 |
|---|---|---|
| PRD | `追更话题/1-4-2追更宠物·里程碑·话题prd.md` | 定稿 |
| 架构增量 | `architecture-v1.4.2-social-topic-delta.md` | 完成（2026-11-04） |
| Epics / stories 清单 | `epics-v1.4.2-social-topic.md` | 完成（2026-11-04，7 Epic / 20 story） |
| 就绪度评审 | `readiness-v1.4.2-social-topic.md` | READY（2026-11-04） |
| 决策日志 | `决策日志-social-topic.md` | 2026-11-04 R-1 ~ R-5 |
| sprint-status | `../../implementation-artifacts/v1.4.2/sprint-status-v1.4.2-social-topic.yaml` | 完成（2026-11-04，20 story 均 ready-for-dev，迁移号已预分配；云端须知 `cloud-rules-social-topic.md`） |

## 分支模型

- 集成分支 **`dev_1.4.2`**：两份 PRD 的规划都写在 dev 上（只动文档、不提交实现代码），两条线规划时互相可见。
- 一条线 story 全部 ready-for-dev 后，从最新 `dev_1.4.2` 切 `feat/1.4.2-<theme>`，交云端实现。
- 只 feat → dev 单向合并，不往功能分支反向合并 dev；需要两边代码共存时从 dev 切对齐分支。

## 两份 PRD 的交界（来自 social-topic PRD §6）

- `ck_notifications_type` 约束重建：本线新增 `PET_FOLLOWED`、`PET_DIARY_POSTED`；电商线的优惠券到账通知也要加类型。**后合并的一方负责在同一迁移里 DROP + ADD 重列全集**（值取自当前树里最后一条重建它的迁移）。
- `PushCategory`：本线新增第 10 个分类「追更宠物」；新增通知类型都必须在映射表里指定分类。

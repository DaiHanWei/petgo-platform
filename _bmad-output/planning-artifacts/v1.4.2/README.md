# V1.4.2 规划输入

> 2026-11-04 入库。PRD 与 UI 稿是从 `~/Downloads/` 同步进来的**快照**；设计侧再改时以设计侧为准、重新同步。

## 主题登记

| 主题 `<theme>` | 线 | PRD | UI 稿 | 功能分支 | 状态 |
|---|---|---|---|---|---|
| `social-topic` | 追更宠物 · 新增 S 级里程碑 · 内容话题（FR-153~157、FR-69、FR-165、AB-10A 扩展 / AB-10B / AB-37） | `追更话题/1-4-2追更宠物·里程碑·话题prd.md`（2026-11-03 定稿） | `追更话题/ui-1-4-2-social-topic-integrated-v1.html`（App 43 屏）+ `追更话题/ui-admin-1-4-2-social-topic-integrated-v1.html`（后台 19 屏），2026-11-03 定稿 | `feat/1.4.2-social-topic` | 规划完成，待切分支交云端 |
| `shop` | 电商模块重构（FR-127 ~ FR-134、FR-136、FR-172）：配送前置 / 一页结算 / 优惠券 / PawCoin 自选 / banner 轮播 / 页面重做 / 资金闭环 / 复购下线 / 物流接入 Biteship | `电商/1-4-2电商prd.md`（2026-11-05 快照） | `电商/ui-1-4-2-shop-integrated-v1.html`（App 约 95 屏，A~H 组），2026-11-05 快照 | `feat/1.4.2-shop` | 规划完成（2026-11-05，25 story 均 ready-for-dev），待切分支交云端 |
| `shop-admin` | 电商管理后台（AB-24 / 25 / 27 / 28 / 29 / 38 / 39） | `电商/1-4-2电商后台prd.md`（2026-11-06 快照） | `电商/ui-admin-1-4-2-shop-integrated-v1.html`（后台 69 屏），2026-11-06 快照 | `feat/1.4.2-shop-admin`（电商线合入 dev 后切） | 规划完成（2026-11-06，9 Epic / 20 story），待电商线合入后开工 |
| `exp-reverse` | 问诊挂起 QRIS 退款的成长值人工扣回（修复 1.4.0 FR-143 缺口，不新增 FR / AB）| `电商/1-4-2退款扣回成长值修复prd.md`（2026-11-06 定稿）| 不出稿 | `feat/1.4.2-exp-reverse` | 本地实现完成，3 story review，未提交 |

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

## 产物文件（shop）

> **范围（2026-11-05 操作者拍板）**：本线只做 **App 端 + 后端**（含券、Biteship、资金闭环的服务端逻辑与后台将调用的服务层）。PRD 引用的后台需求（AB-24 券配置、AB-28 异常订单与退款打款、AB-38 运单与 SKU 包裹、AB-39 评价隐藏、banner 配置）出自《142电商后台prd.md》，该文档与后台 UI 稿**未入库**，后台页面**另起一条线**规划。

| 产物 | 文件 | 状态 |
|---|---|---|
| PRD | `电商/1-4-2电商prd.md` | 快照（PRD 头 status: draft，正文【建议】已清零） |
| UI 稿 | `电商/ui-1-4-2-shop-integrated-v1.html` | 快照 |
| 架构增量 | `architecture-v1.4.2-shop-delta.md` | 完成（2026-11-05，D-1 ~ D-24） |
| 决策日志 | `决策日志-shop.md` | 2026-11-05 S-0 ~ S-10、C-1 ~ C-13 |
| Epics / stories 清单 | `epics-v1.4.2-shop.md` | 完成（2026-11-05，就绪度回改后 10 Epic / 25 story） |
| 就绪度评审 | `readiness-v1.4.2-shop.md` | READY（2026-11-05，B1–B3 / I1–I15 / m1–m11 已回改，S-3 ~ S-9 拍板） |
| sprint-status | `../../implementation-artifacts/v1.4.2/sprint-status-v1.4.2-shop.yaml` | 2026-11-05，迁移号已预分配（V20261105_1600 ~ _1650）；云端须知 `cloud-rules-shop.md`；Epic 10 联调留本地 |

## 两份 PRD 的交界（来自 social-topic PRD §6）

- `ck_notifications_type` 约束重建：本线新增 `PET_FOLLOWED`、`PET_DIARY_POSTED`；电商线的优惠券到账通知也要加类型。**后合并的一方负责在同一迁移里 DROP + ADD 重列全集**（值取自当前树里最后一条重建它的迁移）。
- `PushCategory`：本线新增第 10 个分类「追更宠物」；新增通知类型都必须在映射表里指定分类。

## 产物文件（shop-admin）

| 类型 | 文件 | 状态 |
|---|---|---|
| 架构增量 | `architecture-v1.4.2-shop-admin-delta.md` | 完成（2026-11-06，AD-1 ~ AD-18） |
| 决策日志 | `决策日志-shop-admin.md` | O-1 ~ O-24 |
| Epics / stories 清单 | `epics-v1.4.2-shop-admin.md` | 9 Epic / 20 story |
| 就绪度评审 | `readiness-v1.4.2-shop-admin.md` | READY（回改后） |
| sprint-status | `../../implementation-artifacts/v1.4.2/sprint-status-v1.4.2-shop-admin.yaml` | 迁移号已预分配；云端须知 `cloud-rules-shop-admin.md`；文案 `shop-admin-文案三语表.md` |

## 产物文件（exp-reverse）

| 类型 | 文件 | 状态 |
|---|---|---|
| 架构增量 | `architecture-v1.4.2-exp-reverse-delta.md` | 完成（2026-11-06，AD-1 ~ AD-5） |
| 决策日志 | `决策日志-exp-reverse.md` | E-1 ~ E-10 |
| Epics / stories 清单 | `epics-v1.4.2-exp-reverse.md` | 1 Epic / 3 story |
| 就绪度评审 | `readiness-v1.4.2-exp-reverse.md` | READY（回改后，E-1 已确认） |
| sprint-status | `../../implementation-artifacts/v1.4.2/sprint-status-v1.4.2-exp-reverse.yaml` | 迁移号已预分配 |

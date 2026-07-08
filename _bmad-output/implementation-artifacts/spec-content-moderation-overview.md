---
title: 内容审核补充规范 — Story 拆解总览
type: spec-overview
status: draft
source: _bmad-output/planning-artifacts/content-moderation-plan-v1.0.1.md
created: 2026-07-08
owner: Dai
communication_language: 中文
relatedEpics: [Epic 3 内容社交, Epic 2 成长档案, Epic 6 通知, Admin]
---

# 内容审核补充规范 — Story 拆解总览

> 本文件是「内容审核补充规范 v1.0.1」（`planning-artifacts/content-moderation-plan-v1.0.1.md`，下称**方案**）落地为可执行 story 的**纲**。
> 采用项目既有的 `spec-*.md` 自包含增量规格惯例（非新建编号 Epic）；按面板/能力拆为 9 个 story spec + 本总览。
> **权威源 = 方案 v1.0.1**；遇冲突，代码契约以 `CROSS-STORY-DECISIONS.md` 为准，产品规则以方案为准。

---

## 1. 背景与关键前提

内容审核不是从零开发 —— 后端**已存在大量基建**（2026-07-08 核实，file:line 见 §2）。本批 story 多为**在已实现基础上补增量或替换 stub**，符合 R2 rework「增量 + 冻结 Flyway」惯例（决策 E2）。

**执行纪律（沿用 CLAUDE.md）：**
- 后端 → 前端 → 联调三段推进；每条 AC 标 L0/L1/L2 层级。
- Flyway **已冻结到 V46**，本批增量从 **V47 起顺延**，一律新起 `ALTER`，占位号 `V<n>__`，实际号 CI 落地时按执行顺序单调分配（勿照搬本表示例号，会撞）。
- 护栏不变：`ddl-auto=validate`、不可枚举 token 外露、凭证 env 注入不入库、异步只用 `@Async`+DB 状态机（**禁止引入 MQ/新中间件**）、日志禁记 PII/健康数据/令牌。
- length=1 列别建 VARCHAR(1)（Hibernate 映 CHAR(1) → validate 全红）。

---

## 2. 现状 → 缺口对照（决定每个 story 是"新建"还是"增量"）

| 能力 | 现状 | 证据 | 本批动作 |
|---|---|---|---|
| 发布时自动审核**流程** | ✅ 已接线（校验后、落库前调用；非 PASS 分"失败/入队"两支） | `ContentService.java:248-266` | 复用骨架 |
| 真实三方内容安全 API | ❌ **stub**（6 词硬编码黑名单 + 图像 URL 魔法标记） | `ContentModerationService.java:13-16,31-38` | **story 1 替换** |
| 风险评分 / 0.8 阈值 / 超时降级 | ❌ 无（纯字符串匹配） | 同上 | **story 1 新增** |
| 帖子人工审核队列 | ✅ 完整（入队/通过/拒绝/3天超时/24h高亮/审计/通知） **但默认关闭** | `V41`, `ManualReviewService.java`, `AdminSettings.java:11-12` | **story 3 激活 + 改可见性模型** |
| "仅作者可见"挂起态 / 无"审核中"标签 | ❌ 无（现 F10 = 发布前同步闸门，无中间态） | 决策 F10 | **story 3 引入（修订 F10）** |
| 举报（帖子级）+ 按用户去重 | ✅ 已实现（唯一约束 + 幂等） | `V11:17`, `ReportService.java:44-51` | 复用 |
| 举报阈值分级 P0/P1/P2 + 自动下架 + 举报者隐藏 | ❌ 无（全人工、无阈值、无隐藏） | `ReportService.java:22-25` | **story 6 新增** |
| 评论审核 | ❌ 无（创建直接落库） | `CommentService.java:41-67` | **story 4 新建** |
| 评论 / 账号举报 | ❌ 无（仅帖子可举报） | `V11:2` | 账号举报延 V1.2；本批不做 |
| 昵称/宠物名/头像审核 + 重置默认 | ❌ 完全无 | `auth`/`profile` 零命中；`ProfileService.java:106-118` | **story 5（名称）+ story 6（头像）新建** |
| 后台处置全套（下架/驳回/巡查/恢复/批量） | ✅ 已实现 | `AdminModerationService.java`, `AdminContentManageService.java` | 复用 + **story 8 扩展** |
| 队列优先级字段 | ❌ 无（FIFO） | 按 submitted_at 升序 | **story 8 新增** |
| 账号维度违规累计计数 | ❌ 无（仅一次性 `DeletionType.VIOLATION`） | 迁移无 violation_count | **story 9 新增** |
| 审核类通知类型 + Listener | ✅ 已实现（REPORT_REVIEWED / CONTENT_REMOVED / CONTENT_REVIEW_APPROVED / REJECTED） | `V40`, `V43`, `ModerationNotifyListener.java` | 复用 + **story 7 补文案/新类型** |
| 通知文案 i18n（印尼语） | ⚠️ 部分（枚举就绪，方案 §8 文案未落 arb） | `app_id.arb` | **story 7 落地** |
| 账号注销级联（UGC 匿名化保留） | ✅ 已实现（"已注销用户"文案，D1） | Story 7-3 | 复用；story 9 补违规计数联动 |

---

## 3. 关键决策与契约（写各 story 前必读）

- **D-CM1｜修订 F10（重要）：** 现行 F10 是"发布前同步闸门 → 要么发布公开、要么失败留编辑页，无中间态"。方案引入"**审核通过前内容仅作者可见、他人不可见、不显示任何『审核中』标签**"的挂起态。这是对 F10 审核时序模型的**增量修订**。story 3/4 必须显式声明此修订，并同步更新 `CROSS-STORY-DECISIONS.md` 的 F10 条目（追加而非删除原文）。**此项建议先与架构确认再开工。**
- **D-CM2｜可见性不变量（全局）：** 任何内容（帖子/评论）在审核返回**通过**前，一律**仅作者可见**；通过 → 转他人可见；未通过 → 维持仅作者可见并按处置。L1 强制拦截等**即时判定**不进挂起态，仍走"失败 + 提示重试"。
- **D-CM3｜编辑重审 + 陈旧结果作废（全局）：** 帖子/评论/昵称/宠物名/头像被编辑后**重新送审**；审核结果**绑定内容版本**，若出结果时该字段已改成新值 → 旧结果**静默丢弃**、若旧版本在队列中则**移除队列条目**。各 story 自实现本字段的版本键。
- **D-CM4｜名称占位两套不可混用：** "违规名 → 重置为 `user_8f3a2b` / `Pet_8f3a2b` 编码名"（story 5，违规处置）与"注销 → 匿名化显示『已注销用户』i18n 文案"（Story 7-3，PDP 合规）是**两套独立机制**，dev 勿复用同一占位逻辑。
- **D-CM5｜fail-closed 降级：** 三方超时/报错/宕机/配额耗尽 → 内容**不自动放行**，转人工队列（story 1 定义降级策略，story 3/4 消费）。
- **D-CM6｜隐藏才通知：** 仅"隐藏/违规/未通过"负向最终结果推送通知；"通过/保留/恢复"不推送（story 7 收口，删除 §8.6/§8.4 正向通知）。
- **D-CM7｜举报计数天然去重：** 每用户对同一内容仅能举报一次（已实现唯一约束）+ 举报后对举报者隐藏（story 6 新增）→ 举报次数 = 去重后唯一举报用户数，阈值 P0≥10/P1 3-9 合法。
- **D-CM8｜阿里云印尼语能力为前置阻断项：** story 1 接入前必须实测阿里云内容安全对**印尼语文本**的评分能力；若不足，风险分级降级为中英可靠 + 印尼语自建关键词硬匹配兜底。方案 §7 待确认 #12。

---

## 4. Story 清单（9 个，建议执行顺序）

> 每个 story 一份 `spec-content-moderation-<slug>.md`，自包含（含云端执行须知）。顺序即依赖序；story 1 是所有风险分级/降级的地基。

| # | 文件 | 标题 | 范围要点 | 触碰现有代码 | 新 Flyway(占位) | 前/后/联 | 主要 L 层 |
|---|---|---|---|---|---|---|---|
| 1 | `spec-content-moderation-aliyun-provider.md` | 阿里云内容安全接入 + 词库配置 | 替换 stub 为真三方（文字+图像）、风险评分、L1/L2/L3 词库 + 白名单优先级、超时/失败 fail-closed 降级、印尼语实测 | `ContentModerationService`(替换内部)、`admin_settings` | V47（词库规则表） | 后端为主 | L1/L2 |
| 2 | `spec-content-moderation-post-review.md` | 帖子审核可见性新模型 + 激活 FR-12A | 打开 `manual_review_enabled`、≥0.8 路由入队、"仅作者可见"挂起态、编辑重审+陈旧作废、修订 F10 | `ContentService`、`ManualReviewGate`、`content_posts`、Feed/我的发布查询 | V48（content_posts 加审核列） | 后→前→联 | L1/L2 |
| 3 | `spec-content-moderation-comment-review.md` | 评论审核 + 巡查下架 + 通知时机 | 评论创建同步拦截(L1+≥0.8)、失败降级入队、FR-55A 巡查下架/恢复、"新评论"通知仅审核通过后发 | `CommentService`、`comments`、扩展队列支持评论 | V49（comments 加审核列 + 队列 content_type） | 后→前→联 | L1/L2 |
| 4 | `spec-content-moderation-name-review.md` | 昵称/宠物名异步审核 + 重置默认编码名 | 提交先放行→异步审核→违规重置 `user_/Pet_` 编码名、编辑重审、通知 §8.1/8.2 | `ProfileService`(宠物名)、`auth/account`(昵称)、异步队列 | V50（名称审核记录 + 默认名标志） | 后→前→联 | L1/L2 |
| 5 | `spec-content-moderation-avatar-review.md` | 头像异步图像审核 + 重置默认头像 | 头像先放行→异步图像审核→违规重置默认头像、可见窗口期、通知 §8.10 | `ProfileService`/用户头像、复用 story1 图像审核 | V51（头像审核记录） | 后→前→联 | L1/L2 |
| 6 | `spec-content-moderation-report-triage.md` | 举报处置增强：P0/P1/P2 + 自动下架 + 举报者隐藏 | 阈值分级、P0 自动转"仅作者可见"待判、举报后对举报者隐藏、下架通知 §8.9 | `ReportService`、`ContentReport`、Feed 查询、`AdminModerationService` | V52（如需 hide/auto-flag 列） | 后→前→联 | L1/L2 |
| 7 | `spec-content-moderation-notifications-i18n.md` | 通知文案 i18n + 隐藏才通知收口 | 方案 §8 全部模板落 `app_id.arb`/后端串、删正向通知(§8.6/8.4)、新增重置/下架通知类型 | `NotificationType`、arb、各 Listener | V53（通知类型 CHECK 扩展） | 后+前 | L0/L1 |
| 8 | `spec-content-moderation-admin-console.md` | 运营后台审核增强 | 队列优先级、名称/头像处置动作、违规计数展示、留存/日志口径(§5.5) | `admin/moderation/*`、`ManualReviewService`、Thymeleaf 模板 | V54（队列 priority 列） | 后端+SSR | L1 |
| 9 | `spec-content-moderation-account-strikes.md` | 账号违规计数 + 注销默认隐藏联动 | 按账号累计违规计数(§5.4，仅记录不处置)、注销默认内容隐藏、队列条目随注销移除 | 新计数表、`AccountDeletion`/7-3 联动 | V55（violation_counts 表） | 后端为主 | L1 |

**不在本批范围（延后）：** 评论举报入口 FR-57、账号举报 FR-58、后台统一工单视图 FR-59、自动风险标注 FR-60（方案 §6 均标 V1.2.0）；私聊(IM)审核（方案明确不纳入）。

---

## 5. Flyway 占位分配（V47 起，实际号顺延）

| 占位 | story | 内容 | 类型 |
|---|---|---|---|
| V47 | 1 | `moderation_keyword_rules`（L1/L2/L3 词库 + 白名单）| 新表（如选 DB 存词库；若用配置文件则免） |
| V48 | 2 | `ALTER content_posts` 加 `moderation_risk_score` / `review_reason` 等 | ALTER |
| V49 | 3 | `ALTER comments` 加审核状态列 + `ALTER manual_review_queue` 加 `content_type` | ALTER |
| V50 | 4 | 名称审核记录列/表 + `is_system_default_name` 标志 | ALTER/新表 |
| V51 | 5 | 头像审核记录列 | ALTER |
| V52 | 6 | 举报隐藏/自动标注所需列（如需）| ALTER |
| V53 | 7 | 通知类型 CHECK 扩展（NAME_RESET/AVATAR_RESET 等）| ALTER(CHECK) |
| V54 | 8 | `ALTER manual_review_queue` 加 `priority` | ALTER |
| V55 | 9 | `violation_counts` 表 | 新表 |

> 号仅为占位示意；CI 落地时按实际合并顺序单调顺延，勿硬编码。

---

## 6. 云端（headless）执行须知

- ✅ 云端能跑全部 **L0**：`flutter analyze`/`flutter test`/`flutter build apk --debug`、`mvn -B package`。
- ⚠️ **L1**（Docker postgres+redis 真跑 + Flyway validate）：云沙箱不保证有 Docker daemon；默认把 L1 留本地/CI，云端只跑 L0 绿灯并在 Completion Notes 标"L1 待本地/CI 验收"。schema 契约以 CI/L1 绿为准（Hibernate CHAR(1) 等坑只有 L1 才暴露）。
- ❌ **L2**（真阿里云凭证 / 模拟器视觉 / 印尼语实测）必须回本地：story 1 的三方联调、story 2-6 的模拟器可见性验收都是 L2。
- 每个 story spec 末尾附"云端可做/须回本地"清单。

---

## 7. 生成结果（9 份 spec 已产出，2026-07-08）

| # | 文件 | AC | Flyway | 性质 |
|---|---|---|---|---|
| 1 | `spec-content-moderation-aliyun-provider.md` | 12 | V47 | 替换 stub（shim 兼容，ContentService 零改动） |
| 2 | `spec-content-moderation-post-review.md` | 16 | V48 | 激活队列 + 改可见性（修订 F10） |
| 3 | `spec-content-moderation-comment-review.md` | 14 | V49 | 全新（扩 queue.content_type） |
| 4 | `spec-content-moderation-name-review.md` | 14 | V50 | 全新（独立 name_moderation_records 表） |
| 5 | `spec-content-moderation-avatar-review.md` | 22 | V51 | 全新（独立 avatar_reviews 表） |
| 6 | `spec-content-moderation-report-triage.md` | 15 | V52 | 增量（复用 content_reports） |
| 7 | `spec-content-moderation-notifications-i18n.md` | 13 | V53 | 增量（隐藏才通知收口） |
| 8 | `spec-content-moderation-admin-console.md` | 15 | V54 | 增量（queue priority） |
| 9 | `spec-content-moderation-account-strikes.md` | 13 | V55 | 全新（violation_counts） |

合计 **134 条 AC**，Flyway 占位 V47→V55 单调无碰撞。

## 8. 跨-story 决策（2026-07-08 已裁决 → CROSS-STORY-DECISIONS CM1–CM8）

> 生成过程中各 spec 自曝的、需统一口径的点，**已由用户「按推荐」全部拍板并写入 `CROSS-STORY-DECISIONS.md`（CM1–CM8）**。下列为决策留痕（映射见括注）。

1. **[架构] D-CM1 修订 F10** —— 引入"已发布待审、仅作者可见"中间态，改写审核时序模型。story 2/3/6 依赖。**建议先过架构确认**，再据结论追加 CROSS-STORY-DECISIONS 的 F10-CM 行。
2. **[架构] 审核队列是混合架构** —— 帖子+评论共用 `manual_review_queue`（story 3 拥有 `content_type` ALTER，V49）；昵称/宠物名（V50）、头像（V51）各建独立表（审核证据+版本键需求不同）。**已采纳（CM2）**。story 8 §6.1/§6.2 以**分来源多页**覆盖（帖子/评论共队列页 + 名称/头像各处置页，经 story 4/5 待审读接口消费），非单一聚合查询——缺口已回填、R4 按 CM6 定论。
3. **[运营] 单个 ILLEGAL 举报是否即触发 P0 自动挂起** —— story 6 默认取"是"，给了"从严"备选。
4. **[口径] 注销时 violation_counts 行处置** —— D1 级联主张删除/匿名化，方案 §5.5 又要"无限期留存审核数据"，两者对这张计数表口径需统一。
5. **[语义] 两条 priority 轴别混淆** —— `manual_review_queue.priority`（提交时高风险帖入队排序，story 8）≠ 举报 P0/P1/P2（已发布内容处置，story 6）。入队优先级映射由 story 2/3/6 定义。
6. **[运营] `manual_review_enabled` 激活时机/是否灰度** —— story 2 R4。
7. **[已统一] 通知类型命名** —— 以 story 7 为准：单一 `NAME_RESET` + `targetRef` 前缀派生用户/宠物变体（不用 story 4 曾提的 `NAME_RESET/PET_NAME_RESET` 双型）；类型 CHECK 扩展归 story 7（V53），须先于 story 4/5 合并。
8. **[前置阻断] 阿里云印尼语实测**（D-CM8）—— story 1 开工前的 spike，不足则退化为词库硬匹配兜底。
9. **[运营] 降级被拒评论的终态展示/通知边界** —— story 3 R2 待 PM 确认。

## 9. 下一步

- 上述 #1/#2 建议先过架构；#3/#4/#6/#9 需运营/PM 口径。
- 拍板后：追加 CROSS-STORY-DECISIONS（F10-CM + 队列架构 + 命名口径），可选登记 sprint-status.yaml，再按 story 1→9 顺序交云端跑 L0 / 本地跑 L1·L2。

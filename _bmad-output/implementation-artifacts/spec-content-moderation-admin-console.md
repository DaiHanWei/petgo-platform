---
title: 运营后台审核增强 — 队列优先级 / 名称头像处置 / 违规计数展示 / 留存口径
slug: content-moderation-admin-console
type: spec-dev-story
status: ready-for-dev
story_no: 8/9
source: _bmad-output/planning-artifacts/content-moderation-plan-v1.0.1.md
overview: _bmad-output/implementation-artifacts/spec-content-moderation-overview.md
created: 2026-07-08
owner: Dai
communication_language: 中文
relatedEpics: [Admin（Thymeleaf slice）, Epic 3 内容社交, Epic 2 成长档案]
flyway_placeholder: V54（manual_review_queue 加 priority；实际号顺延）
depends_on: [spec-content-moderation-name-review（story4）, spec-content-moderation-avatar-review（story5）, spec-content-moderation-account-strikes（story9）]
verification_layers: 主要 L1（SSR 页需 postgres+redis 起服务真跑）；纯静态部分 L0
---

# 运营后台审核增强 — 队列优先级 / 名称头像处置 / 违规计数展示 / 留存口径

> 本 story 对应《内容审核补充规范 v1.0.1》**§5 运营后台处置指引全部**（§5.1 优先级、§5.2 处置记录、§5.3 评论巡查、§5.4 违规计数、§5.5 留存/日志/申诉）与 **§3.1 FR-12A** 的后台侧。
> **这是增量 story，不是重建。** 后台审核面板（举报队列复核/下架/驳回/批量、内容巡查下架/恢复、人工审核队列浏览/通过/拒绝/超时/24h高亮/审计）**已全套上线**（file:line 见 §2），本 story 只补 4 处增量并接线审计。
> 权威源 = 方案 v1.0.1；代码契约以 `CROSS-STORY-DECISIONS.md` 为准。用中文沟通。

---

## 1. 背景与范围（增量）

后台审核基建在 Epic 4（管理后台）已完整落地：`ManualReviewService`（帖子人工审核队列，**FIFO 无优先级**，按 `submitted_at` 升序）、`AdminModerationService`（举报队列复核/下架/驳回/批量）、`AdminContentManageService`（巡查下架/恢复）、`AdminAuditService`（哈希链 append-only 审计，Epic 2~6 写操作必调）。本 story 在其上做**四处增量**，均为 SSR（Thymeleaf，非 App）：

1. **队列优先级字段（§5.1）** —— `manual_review_queue` 加 `priority` 列（占位 **V54**）。队列排序由现行「纯 `submitted_at` 升序」改为 **`priority` 优先 + 同优先级内 `submitted_at` 升序**；**24h 超期高亮已存在，原样保留**。优先级由生产者（story 2/3 入队时）写入；后台提供运营手动改优先级动作（写操作 → 审计）。
2. **名称/头像违规处置动作（§3.3/§3.4 的后台入口）** —— 后台新增「重置为默认编码名 / 重置为默认头像」的 SSR 操作入口。**重置的领域逻辑归 story 4（名称）/ story 5（头像）**，本 story 只提供运营控制台的表单 + 控制器，委托调用其 reset 服务，并把 §5.2 处置字段传入。
3. **违规计数展示（§5.2/§5.4）** —— 处置界面展示「该账号累计违规次数」（按类型分列）。计数数据源 = **story 9 的 `violation_counts`**；本 story 只读展示，经读端口消费（见 §5.5 契约）。
4. **§5.2 处置决定记录规范 + §5.5 留存/日志/申诉口径** —— 处置表单显式采集「判定依据 / 处置动作 / 备注」并落审计（append-only、无限期留存）；审核证据（原文含 PII）与常规业务日志**分列管理口径**落地；**申诉本版本不提供**（显式记录）。

**不在本 story 范围：** 阿里云三方接入与风险评分（story 1）；帖子/评论审核可见性模型与入队逻辑（story 2/3，本 story 只消费其产出的队列项）；名称/头像 reset 领域服务本身（story 4/5，本 story 只调）；违规计数的写入与统计（story 9，本 story 只读）；评论举报/账号举报/统一工单视图（V1.2.0）。

---

## 2. 现状基线（file:line —— 已有全套，勿重建）

> 相对路径根 = `petgo-backend/src/main/java/com/tailtopia/`；模板根 = `petgo-backend/src/main/resources/templates/`。

**帖子人工审核队列（FR-12A / AB-3C，本 story 主要改造对象）**
- `admin/moderation/service/ManualReviewService.java`
  - `pendingQueue()` L49-55 —— **现按 `submittedAt` 升序**（`queue.findByStatusOrderBySubmittedAtAsc`），本 story 改为优先级排序。
  - `toRow()` L57-65 —— 行投影，含 24h `overdue` 标记（`OVERDUE_THRESHOLD = 24h` L29）。
  - `approve()` L68-78 / `reject()` L81-91 —— 处置 + 通知作者 + 审计（`AuditActions.CONTENT_REVIEW_APPROVED/REJECTED`）。**reject 现不采集判定依据**，本 story §5.2 在此扩展。
  - `scanTimeouts()` L97-117 —— 3 天超时自动 `TIMED_OUT`（`TIMEOUT_THRESHOLD = 3d` L30）。**排序无关，勿改。**
- `admin/moderation/domain/ManualReviewItem.java` —— 实体，表 `manual_review_queue`（L20）。字段 `contentId/submittedAt/status/decidedBy/decidedAt`。`pending()` L54 工厂、`decide()` L63 终态。**本 story 加 `priority` 字段。**
- `admin/moderation/repository/ManualReviewItemRepository.java` —— `findByStatusOrderBySubmittedAtAsc` L13（本 story 换排序方法）、`findByStatusAndSubmittedAtBefore` L16（超时扫描用，勿动）。
- `admin/moderation/dto/ManualReviewRow.java` L11-12 —— 行 record，本 story 加 `priority` 字段并展示。
- `admin/moderation/web/ManualReviewAdminController.java` —— `/admin/manual-review`。入口门控 `SUPER_ADMIN`（`ENTRY_AUTH` L26）；处置门控 `content.takedown`（`DECIDE_AUTH` L27）。`queue()` L38-46（SSR+HTMX，`:: rows` 片段）、`approve()` L59-70、`reject()` L72-83。
- 模板 `admin/manual-review.html` —— 队列页，`rows` 片段 L43，`row-overdue` 高亮 L44，i18n `#{admin.review.*}`。
- 迁移 `db/migration/V41__init_manual_review_queue.sql` —— 建表；`status` CHECK L13-14；索引 `idx_manual_review_queue_submitted` L18（排序索引，仍需，新增复合索引见 §4）。

**举报队列复核（AB-3A/AB-3B）与内容巡查（复用，本 story 仅在其处置面板旁挂违规计数展示）**
- `admin/service/AdminModerationService.java` —— `pendingQueue()`/`queue(status)` L52-83、`takedown()` L86-98、`dismiss()` L101-110、`batch()` L116-133。举报驱动下架，作者通知走既有 `ContentRemovedEvent`，举报人走 `ReportResolvedEvent` 模糊通知。
- `admin/moderation/service/AdminContentManageService.java` —— `browse()` L40、`takedown()` L54-63（**已必填原因**，进审计 summary）、`restore()` L67-71。

**统一审计入口（本 story 所有新写操作必调）**
- `admin/audit/service/AdminAuditService.java` —— `record(actorAccountId, actionType, targetType, targetId, summary)` L44-59。`@Transactional`（REQUIRED）加入业务事务；哈希链 append-only + advisory 锁串行化。**summary 严禁含密码/令牌/签名 URL/健康数据。**
- `admin/audit/service/AuditActions.java` —— 动作常量（UPPER_SNAKE 过去式）。现有 `CONTENT_REVIEW_APPROVED` L64 / `CONTENT_REVIEW_REJECTED` L66 / `CONTENT_REVIEW_TIMED_OUT` L68 / `CONTENT_TAKEN_DOWN` L44 / `SETTING_CHANGED` L62 等。**本 story 新增常量见 §5。**

**admin 域名/凭证：** 对外 `ops.tailtopia.id`，代码零硬编码（走 env `LARK_REDIRECT_URI` + CF 路由）。本 story 不引入任何域名/凭证硬编码。

---

## 3. 目标与非目标

**目标**
- G1：`manual_review_queue` 具备 `priority`（P0/P1/P2）字段，队列按 `priority` 升序 + 同级 `submitted_at` 升序展示；24h 高亮不变。
- G2：运营可在后台对名称/头像违规项执行「重置为默认编码名 / 默认头像」，委托 story 4/5 的 reset 服务，采集 §5.2 处置字段。
- G3：处置界面展示该账号累计违规次数（按类型分列，读 story 9 数据）。
- G4：所有处置写操作采集「判定依据 / 处置动作 / 备注」并落 `AdminAuditService.record`（append-only、无限期留存）。
- G5：§5.5 口径落地——留存无限期（无自动清理任务）；审核证据（原文/PII）与业务日志分列管理（访问控制 + 加密替代脱敏）；申诉不提供，显式记录。

**非目标（明确划走）**
- N1：不实现名称/头像的 reset 领域逻辑与审核记录表（story 4/5）。
- N2：不实现违规计数的写入/累加/统计（story 9）。
- N3：不改帖子/评论审核入队逻辑与可见性模型（story 2/3）；不改超时扫描 `scanTimeouts`。
- N4：不做评论举报/账号举报/统一工单视图/自动风险标注（V1.2.0）。
- N5：不引入 App 端改动（本 story 纯后端 + SSR）。
- N6：不新增自动清理/归档任务（§5.5 留存无限期，反而要确保无 TTL 清理被引入）。

---

## 4. 数据与迁移（Flyway 占位 V54，实际号顺延）

> Flyway 已冻结到 V46（实测最大 `V46__add_vet_qualification_strv.sql`）。本 story 增量一律新起 `ALTER`，占位 `V54`；CI 落地时按实际合并顺序单调顺延（story 1~7 的 V47~V53 若未合并，本迁移取当时最大号+1）。**勿硬编码 V54，勿改既有迁移。**

**`V<n>__add_manual_review_priority.sql`（占位 V54）**

```sql
-- Story 8（内容审核后台增强）：manual_review_queue 加优先级列（§5.1）。
-- 枚举落库 varchar + UPPER-ish（P0/P1/P2）；长度 >1 无 Hibernate CHAR(1) 陷阱。
-- 默认 'P1'（未显式标注的历史/降级入队项归为高优先，避免默认沉底）。
ALTER TABLE manual_review_queue
    ADD COLUMN priority VARCHAR(8) NOT NULL DEFAULT 'P1';

ALTER TABLE manual_review_queue
    ADD CONSTRAINT ck_manual_review_queue_priority
        CHECK (priority IN ('P0', 'P1', 'P2'));

-- 队列页排序：PENDING 内按 priority 升序 + submitted_at 升序。
-- 'P0' < 'P1' < 'P2' 字典序与优先级序天然一致 → 复合索引直接支撑 ORDER BY。
CREATE INDEX idx_manual_review_queue_pending_order
    ON manual_review_queue (status, priority, submitted_at);
```

**说明**
- `priority` 用 `VARCHAR(8)`（值 `P0/P1/P2` 均 2 字符，留冗余；**长度>1 规避 Hibernate6 CHAR(1) → validate 全红 坑**）。
- 存 `VARCHAR` 符合「枚举落库 varchar」命名约定；且 `P0<P1<P2` 字典序恰等于优先级序，`ORDER BY priority ASC, submitted_at ASC` 无需数值映射。
- 默认 `'P1'`：对既有行与「story 2/3 未显式标 priority 就入队」的降级项给一个不沉底的默认（P1=高，仅次于 P0）。生产者可在入队时覆写。
- 现有 `idx_manual_review_queue_submitted`（V41 L18）保留（`scanTimeouts` 的 `submittedAt < cutoff` 仍用）；新增复合索引服务队列页排序。
- **本 story 不为 §5.2/§5.5 建新表** —— 处置决定字段折叠进审计 summary（append-only、无限期留存，见 §5.5）；违规计数读 story 9 的 `violation_counts`。

---

## 5. 后端设计

### 5.1 队列优先级排序（§5.1）

**枚举** 新增 `admin/moderation/domain/ReviewPriority`：`P0, P1, P2`（`@Enumerated(STRING)`，列 `priority` length=8）。语义对齐方案 §5.1：`P0` 紧急、`P1` 高、`P2` 普通。

**实体** `ManualReviewItem` 加 `@Column(name="priority") @Enumerated(STRING) ReviewPriority priority = ReviewPriority.P1;`；`pending(contentId, submittedAt)` 增重载 `pending(contentId, submittedAt, priority)`（生产者 story 2/3 调用；无 priority 时默认 P1）。

**仓储** `ManualReviewItemRepository`：新增
```java
List<ManualReviewItem> findByStatusOrderByPriorityAscSubmittedAtAsc(ReviewStatus status);
```
`pendingQueue()`（`ManualReviewService` L49-55）改调此方法。**`findByStatusAndSubmittedAtBefore`（超时扫描）不动。**

**行投影** `ManualReviewRow` 加 `ReviewPriority priority` 字段；`toRow()` 填入。模板展示优先级徽标（P0 红 / P1 橙 / P2 中性）。

**运营手动改优先级（写操作 → 审计）** `ManualReviewAdminController` 新增
`POST /admin/manual-review/{itemId}/priority`（门控 `DECIDE_AUTH` = `SUPER_ADMIN or content.takedown`），入参 `priority`。`ManualReviewService.changePriority(itemId, priority, actorAccountId)`：仅 `PENDING` 可改（复用 `requirePending`）→ set → save → `auditService.record(actorAccountId, AuditActions.REVIEW_PRIORITY_CHANGED, "MANUAL_REVIEW_ITEM", String.valueOf(itemId), "调整审核优先级 → " + priority)`。

### 5.2 名称/头像违规处置动作（§3.3/§3.4 后台入口）

**契约（与 story 4/5 的边界）：** reset 领域逻辑、审核记录表、默认编码名生成、通知（§8.1/8.2/8.10）**全在 story 4/5**。本 story 提供：
- 运营控制台的**名称/头像待审列表页 + 处置表单 + 控制器**（SSR）。
- 控制器委托调用 story 4/5 暴露的 reset 服务方法，**把 §5.2 处置字段（判定依据/备注）与 actorAccountId 传入**。

**要求 story 4/5 的 reset 服务方法签名（本 story 依赖，接口先行）：**
```java
// story 4 名称 reset 服务（示意）
void resetNameToDefault(long targetRef, ModerationDecision decision, long actorAccountId);
// story 5 头像 reset 服务（示意）
void resetAvatarToDefault(long targetRef, ModerationDecision decision, long actorAccountId);
```
- `targetRef` 为不可枚举 token 或业务 id（对外不露自增 id）。
- `ModerationDecision` = record(`String category`（判定依据/违规类别）, `String note`（备注，可空））—— §5.2 采集字段，由本 story 表单填充。
- **审计在 reset 服务内落**（`NAME_RESET` / `AVATAR_RESET`，常量由 story 4/5 定义），summary 含 category/note（**不含名称原文明文/图片 URL**，仅描述性判定依据；原文属审核证据留在业务库，见 §5.5）。
- reset 触发的用户通知（重置为默认名/默认头像）走 story 4/5 + story 7 文案。

> 若 story 4/5 尚未合并：本 story 的名称/头像处置页与控制器可先落，reset 服务以接口 + `@ConditionalOnMissingBean` 占位实现（抛「功能未就绪」或按 flag 隐藏入口），保证本 story 独立 L0 绿；真实现随 story 4/5 接入。此依赖在 §8/§10 标注。

### 5.3 §5.2 处置决定记录规范落地

方案 §5.2 要求每次处置记录：**判定依据 / 处置动作 / 备注 / 该账号累计违规次数**。落地方式（**不建新表**，折叠进 append-only 审计）：

- **判定依据 + 备注**：由处置表单新增字段采集。
  - 帖子人工审核 `reject`：`ManualReviewService.reject` 增参 `ModerationDecision decision`；审计 summary 由「人工审核拒绝（队列项 #N）」扩展为含 `category` + `note`。`approve` 属正向结果，可选记 note。
  - 名称/头像 reset：经 §5.2 契约传入 story 4/5。
  - 举报下架（`AdminModerationService.takedown`）/ 巡查下架（`AdminContentManageService.takedown` 已必填原因 L54-63）：原因字段即判定依据，已入审计——**本 story 不改其行为，仅在其处置页旁挂违规计数展示（§5.4）**。
- **处置动作**：= `AuditActions` 的 `actionType`（append-only，天然记录动作）。
- **累计违规次数**：**只读展示**，不入审计 summary（避免链上冗余快照）；数据源见 §5.4。

**新增 `AuditActions` 常量（本 story 拥有）：**
- `REVIEW_PRIORITY_CHANGED = "REVIEW_PRIORITY_CHANGED"` —— 队列优先级调整。
（`NAME_RESET` / `AVATAR_RESET` 由 story 4/5 定义，避免与本 story 撞定义；本 story 只调用其 reset 服务。）

### 5.4 违规计数展示（§5.2/§5.4）

- 数据源 = **story 9 `violation_counts`**（按账号 + 类型累加人工判定违规；仅记录不处置）。
- 本 story 引入**只读端口** `ViolationCountReader`（放 `admin/moderation` 包）：
  ```java
  interface ViolationCountReader {
      // 返回该账号各类型累计违规次数快照；未接入时返回空。
      Map<ViolationType, Integer> countsFor(long accountRef);
  }
  ```
  story 9 提供由 `violation_counts` 支撑的实现；story 9 未合并前用 `@ConditionalOnMissingBean` 空实现（全 0 / 展示「—」）。
- 展示位：人工审核队列行、名称/头像处置页、举报处置页的作者信息旁，显示「累计违规：帖子 x / 评论 y / 名称 z / 头像 w」。
- **只读，不触发任何自动限制**（方案 §5.4：本版本仅记录，账号级处置留待下一版本）。

### 5.5 §5.5 留存 / 审核日志 / 申诉口径

**留存无限期（硬约束）**
- 违规内容、处置记录、审计日志**无限期留存，不设自动清理**。
- **本 story 不得引入任何 TTL / 定时清理 / 归档删除任务**触及 `manual_review_queue`、`admin_audit_logs`、内容库、`violation_counts`。审计链本就 append-only（无删除路径）。评审时确认无新增清理逻辑。

**审核日志 / PII 分列口径**
- **常规业务日志（SLF4J/logback JSON）**：延续护栏——严禁记录 PII / 健康数据 / 令牌 / 签名 URL。本 story 新增 `log.info` 仅记 id / actorAccountId / 动作 / count，**不记名称原文 / 内容正文 / 图片 URL**。
- **`AdminAuditService` summary**：属审计元数据，记「判定依据类别 + 备注 + 目标 id」，**不复制被审内容原文明文 / 图片签名 URL**（目标经 `targetType`+`targetId` 引用）。
- **审核证据（原文，可能含 PII）**：留在业务库（`content_posts` 正文、名称字段、头像对象等），经 `ContentService` / story 4/5 服务访问，由 **admin 鉴权（`SUPER_ADMIN` / `content.takedown`）+ 最小授权 + 存储层加密** 管控；**不做脱敏**（脱敏会使审核证据失效，方案 §5.5）。本 story 不把原文搬进日志/审计，从而两条口径不冲突：证据在受控业务库，日志/审计仅引用。

**申诉：本版本不提供（显式记录）**
- V1.1.0 **不提供**申诉/复议入口。帖子被拒不说明具体原因、无申诉通道；误判用户只能重新提交合规内容。
- 本 story 不实现任何申诉相关端点/表/UI；在处置文案与 spec 中显式记录「申诉留待后续版本」，避免后续误判为遗漏。

### 5.6 审计接线汇总（所有新写操作必调）

| 新写操作 | actionType | 归属 | targetType/targetId | summary 要点（无 PII 原文） |
|---|---|---|---|---|
| 改队列优先级 | `REVIEW_PRIORITY_CHANGED`（本 story 新增） | 本 story | `MANUAL_REVIEW_ITEM` / itemId | 「调整审核优先级 → Px」 |
| 人工审核拒绝（含 §5.2 字段） | `CONTENT_REVIEW_REJECTED`（既有 L66） | 本 story 扩展 summary | `CONTENT_POST` / contentId | 「人工审核拒绝（队列项 #N）；依据=<category>；备注=<note>」 |
| 名称重置默认 | `NAME_RESET`（story 4 定义） | story 4，本 story 传入决策字段 | 名称目标 token | 依据/备注（无名称原文明文） |
| 头像重置默认 | `AVATAR_RESET`（story 5 定义） | story 5，本 story 传入决策字段 | 头像目标 token | 依据/备注（无图片 URL） |

---

## 6. 前端设计（Thymeleaf SSR，非 App）

> 全部为运营后台 `templates/admin/` 页面，走既有 `admin/layout.html` + `htmx.min.js` + i18n `#{admin.*}`（中英双语）。域名 `ops.tailtopia.id` 零硬编码。

**6.1 人工审核队列页 `admin/manual-review.html`（改造）**
- 表头新增「优先级」列；行内 P0/P1/P2 徽标（P0 `badge-danger`、P1 `badge-warn`、P2 `badge-neutral`）。
- 行按 `priority` 升序 + `submitted_at` 升序渲染（后端已排序）；`row-overdue` 24h 高亮不变（L44）。
- 「拒绝」表单增字段：**判定依据**（下拉，违规类别，对齐方案 §2.1：违法/仇恨/色情/政治敏感/广告引流/骚扰/其他）+ **备注**（选填文本）。提交到 `POST .../{itemId}/reject`。
- 新增「改优先级」内联控件（下拉 P0/P1/P2 + 提交），提交到 `POST .../{itemId}/priority`。
- 行内展示作者「累计违规」小徽标（读 `ViolationCountReader`）。
- HTMX `:: rows` 片段刷新保持（L43）。

**6.2 名称/头像处置页 `admin/name-avatar-review.html`（新增）**
- 待审列表（名称违规候选 / 头像违规候选，数据来自 story 4/5 的审核记录；story 4/5 未就绪时列表空 + 提示「能力未就绪」）。
- 每行：目标（用户/宠物 token）、当前值预览（名称文本 / 头像缩略，属审核证据，仅授权运营可见）、异步风险分（story 4/5 产出）、作者累计违规。
- 处置表单：**判定依据**（下拉）+ **备注**（选填）+ 「重置为默认编码名 / 重置为默认头像」按钮 → `POST /admin/name-review/{ref}/reset` 或 `/admin/avatar-review/{ref}/reset`（门控 `content.takedown`）。
- 侧栏提示「本版本无申诉通道」（§5.5）。

**6.3 举报处置页 / 内容巡查页（既有，仅挂件）**
- 在 `reports.html` / `content.html` 的作者信息旁挂「累计违规」展示（读 `ViolationCountReader`），不改现有处置流。

**i18n key 约定（新增，落 `messages_zh.properties` / `messages_en.properties`）：** `admin.review.col.priority`、`admin.review.priority.p0/p1/p2`、`admin.review.reject.category`、`admin.review.reject.note`、`admin.review.priority.change`、`admin.nameavatar.title`、`admin.nameavatar.reset.name`、`admin.nameavatar.reset.avatar`、`admin.moderation.strikes.label`、`admin.moderation.noAppeal` 等（中英双语；具体印尼语用户通知文案不在后台 SSR 范畴，属 story 7）。

---

## 7. 验收 AC（标 L0/L1/L2 + 所需环境）

> SSR 页大多 **L1**（需 postgres+redis 起服务 + Flyway `validate` 通过才能真渲染 + 鉴权）；纯编译/单测 **L0**；运营人肉走查 **L2**。

**优先级（G1）**
- **AC1（L1）** 迁移 `V<n>__add_manual_review_priority.sql` 应用后 `mvn spring-boot:run` `/actuator/health=UP`（`ddl-auto=validate` 通过，`priority` 列 + CHECK + 复合索引存在）。环境：Docker postgres+redis。
- **AC2（L0）** `ReviewPriority` 枚举 + `ManualReviewItem.priority` + 新仓储方法编译通过（`mvn -B package`）；`ManualReviewServiceTest` 断言 `pendingQueue()` 返回顺序 = priority 升序 → 同级 submitted_at 升序。环境：无 DB（可 mock 仓储）。
- **AC3（L1）** 造 P0/P1/P2 各若干条 + 打乱 submitted_at，`GET /admin/manual-review` 渲染顺序 = P0 先、同级早提交先；24h 前的条目仍带 `row-overdue`。环境：起服务 + SUPER_ADMIN 会话。
- **AC4（L1）** `POST /admin/manual-review/{id}/priority` 改优先级后队列重排；写入一条 `REVIEW_PRIORITY_CHANGED` 审计（可在审计页查到）。仅 `PENDING` 可改，终态项返错误提示。环境：起服务。

**名称/头像处置（G2）**
- **AC5（L0）** 名称/头像处置控制器 + 页面编译通过；reset 服务以接口消费（story 4/5 未合并时占位实现存在，L0 绿）。环境：无 DB。
- **AC6（L1，依赖 story 4/5）** story 4/5 合并后，后台对名称/头像违规项执行「重置默认」→ 调 story 4/5 reset 服务成功 → 值变默认 → 落 `NAME_RESET/AVATAR_RESET` 审计（含判定依据/备注）+ 触发用户通知。环境：起服务 + story 4/5 就绪。**若 story 4/5 未就绪 → 本 AC 标「待依赖，占位入口不可用」。**

**违规计数展示（G3）**
- **AC7（L0）** `ViolationCountReader` 端口 + 空实现编译通过；页面能渲染「—」当无数据。环境：无 DB。
- **AC8（L1，依赖 story 9）** story 9 合并后，处置页/队列行展示真实按类型累计次数；不触发任何自动限制。环境：起服务 + story 9 就绪。

**§5.2 记录 / §5.5 口径（G4/G5）**
- **AC9（L1）** 人工审核「拒绝」带判定依据+备注提交 → 审计 summary 含依据/备注、**不含内容正文原文**；作者收到既有拒绝通知（文案不变）。环境：起服务。
- **AC10（L0）** 代码走查断言：本 story 新增 `log.*` 无名称原文/正文/图片 URL；审计 summary 不复制原文明文。可用单测 + grep 校验（CI 静态）。环境：无 DB。
- **AC11（L0）** 全仓 grep 确认本 story 未引入任何触及 `manual_review_queue/admin_audit_logs/violation_counts/内容库` 的 TTL/定时清理/删除任务（§5.5 留存无限期）。环境：无 DB。
- **AC12（L0）** 申诉：无任何申诉端点/表/UI；spec 与处置页文案显式标注「本版本不提供申诉」。环境：无 DB。

**通用护栏**
- **AC13（L1）** 所有新写端点门控正确：入口 `SUPER_ADMIN`，处置 `content.takedown`；越权 403（复用既有 `ManualReviewAccessControlTest` 模式补断言）。环境：起服务。
- **AC14（L1）** 每个新写操作在同事务落一条 `AdminAuditService.record`；业务回滚则审计一并回滚（无悬挂审计）。环境：起服务。
- **AC15（L2）** 运营在 `ops.tailtopia.id` 真环境走查队列优先级排序、改优先级、名称/头像处置、违规计数展示，交互与文案符合 §5。环境：本地/预发起服务 + 运营账号。

---

## 8. 依赖与契约

**依赖（本 story 消费，接口先行）**
- **story 4（名称审核）** —— 提供 `resetNameToDefault(targetRef, ModerationDecision, actorAccountId)` + `NAME_RESET` 审计常量 + 名称待审记录读接口 + 默认编码名生成 + 用户通知。本 story 只调 + 传 §5.2 决策字段。
- **story 5（头像审核）** —— 对应头像 reset 服务 + `AVATAR_RESET` + 头像待审记录读接口。
- **story 9（账号违规计数）** —— 提供 `violation_counts` 及 `ViolationCountReader` 实现（按账号+类型）。本 story 只读展示。
  - ⚠️ **执行顺序倒置风险**：overview 编号 story 8 在 story 9 之前，但 G3 展示依赖 story 9 数据。缓解：本 story 引入 `ViolationCountReader` 端口 + 空实现（`@ConditionalOnMissingBean`），独立 L0/L1 绿；story 9 合并后真实现自动接入（AC8 届时验收）。见 §10。

**复用（不重建）**
- `AdminAuditService.record`（哈希链、advisory 锁、REQUIRED 事务）——所有新写操作必调。
- `ManualReviewService` / `ManualReviewAdminController` / `manual-review.html` —— 在其上增量。
- `AdminModerationService` / `AdminContentManageService` / `reports.html` / `content.html` —— 仅挂违规计数展示件，不改处置流。
- `admin/layout.html` + i18n bundle + htmx。

**对外契约不变**
- admin 域名 `ops.tailtopia.id`、Lark OAuth、CF 路由零硬编码。
- 命名映射链：DB `snake_case`（`priority`）↔ Java/Dart `camelCase`（`priority`/枚举 `P0`）↔ 无 App 侧 JSON（纯 SSR）。
- 不外露自增 id：名称/头像处置目标用 token/业务 ref。

---

## 9. 云端（headless）执行须知

- ✅ **云端可做（L0）**：`mvn -B package`（编译 + 单测）；`ReviewPriority`/实体/仓储/DTO/控制器/端口/空实现全 L0；排序单测（mock 仓储）；grep 静态校验（AC10/AC11/AC12）。
- ⚠️ **L1 留本地/CI**：迁移 `validate`（Hibernate 映射契约只有真 postgres 才暴露，如 CHAR 陷阱——本 story `priority` 用 `VARCHAR(8)` 已规避，但仍须 CI/L1 绿才算数）；SSR 页渲染 + 鉴权 + 审计落库（AC1/3/4/6/8/9/13/14）需 Docker postgres+redis 起服务。云沙箱不保证 Docker daemon → 只跑 L0 绿灯，Completion Notes 标「L1 待本地/CI」。
- ❌ **L2 回本地**：`ops.tailtopia.id` 真环境运营走查（AC15）。
- **依赖门槛**：story 4/5/9 未合并时，名称/头像处置与违规计数展示以占位实现保持 L0 绿；对应 L1 AC（AC6/AC8）标「待依赖就绪」。

---

## 10. 风险与待确认

> **【2026-07-08 裁决，已写入 CROSS-STORY-DECISIONS CM1–CM8】**
> - **R1（顺序 8↔9）→ 采用端口占位方案**：`ViolationCountReader` + 空实现先落，story 8 独立可交付，story 9 后补真实现（AC8 届时验收）。
> - **R2（reset 服务签名）→ 锁定**：story 4/5 的 reset 服务须为「接受运营决策字段 `ModerationDecision` + `actorAccountId`」；`NAME_RESET/AVATAR_RESET` 审计常量归 story 4/5（CM8）。
> - **R3/R4（priority 语义）→ CM6 定论**：`manual_review_queue.priority` 是**通用处理紧急度**（≠ 举报 P0/P1/P2 那条轴），入队项到 P0/P1/P2 的映射由生产者 story 2/3/6 定义；未标默认 P1。后台按混合架构（CM2）分来源多页呈现，本 story §6.1/§6.2 已覆盖。
> - **R5（证据 vs 日志）→ CM5 口径**：审核证据留受控业务库、日志/审计仅引用；分列口径成立。

- **R1 执行顺序 8↔9（违规计数）**：G3 依赖 story 9 数据源。**建议**：要么调整执行顺序让 story 9 先落 `violation_counts` + 读接口，要么按本 spec 的 `ViolationCountReader` 端口占位方案先落 story 8、story 9 后补真实现。**需确认采用哪种。** 默认按端口占位方案推进（本 story 独立可交付）。
- **R2 名称/头像 reset 服务归属边界**：本 spec 假定 reset 领域逻辑 + 审计常量（`NAME_RESET/AVATAR_RESET`）在 story 4/5，story 8 只提供控制台 + 传决策字段。**若 story 4/5 未把 reset 设计成「接受运营决策字段 + actorAccountId」的服务方法**，需回对齐签名（§5.2 契约）。建议 story 4/5 定稿时锁定该签名。
- **R3 priority 生产者接线**：`priority` 由 story 2/3 入队时写入才有分级意义；story 2/3 若未接线，则所有项落默认 `P1`（功能不坏，仅无分级）。**确认 story 2/3 spec 是否已含「入队时定 priority」**（P0=举报驱动/法律违规？高风险≥0.8=？降级 fail-closed=？的映射规则需在 story 2/3 明确）。本 story 只消费，不定义映射。
- **R4 §5.1 优先级语义与队列语义的错位**：方案 §5.1 的 P0/P1/P2 原是**举报驱动工单**优先级（举报≥10=P0 等），而 `manual_review_queue` 装的是**帖子高风险/降级**项（非举报驱动）。两者语义不完全重合。本 story 将 `priority` 作为通用「处理紧急度」落在队列上，具体入队项到 P0/P1/P2 的映射交 story 2/3 生产者定义。**需 PM 确认队列 priority 的语义口径**（是否等同 §5.1 三级，还是仅「紧急度」）。
- **R5 审核证据 vs 日志护栏张力**：§5.5 允许审核证据含原文（访问控制替代脱敏），与「日志/审计严禁 PII」护栏并存。本 spec 的口径是「证据留业务库受控、日志/审计仅引用」。**需安全/合规确认该分列口径可接受**（尤其审计 summary 里的「判定依据类别」为受控枚举、非自由原文）。
- **R6 违规计数展示的隐私范围**：在多个后台页展示「累计违规」需确认展示对象（运营均可见 vs 仅特定权限），避免过度暴露用户负面标签。默认与队列处置同权限（`content.takedown`）。

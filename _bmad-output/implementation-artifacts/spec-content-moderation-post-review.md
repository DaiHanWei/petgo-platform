---
title: 帖子审核可见性新模型 + 激活 FR-12A（内容审核 story 2）
type: spec-dev-story
story: content-moderation-2
slug: post-review
status: ready-for-dev
source: _bmad-output/planning-artifacts/content-moderation-plan-v1.0.1.md
overview: _bmad-output/implementation-artifacts/spec-content-moderation-overview.md
depends_on: [content-moderation-1（阿里云内容安全接入，提供风险评分/降级信号）]
revises: CROSS-STORY-DECISIONS.md#F10
communication_language: 中文
created: 2026-07-08
owner: Dai
flyway_placeholder: V48（ALTER content_posts；实际号 CI 落地时按合并顺序单调顺延，勿硬编码）
relatedEpics: [Epic 3 内容社交, Epic 4 管理后台, Epic 2 成长档案]
---

# 帖子审核可见性新模型 + 激活 FR-12A

> 本 story 是「内容审核补充规范」9 份 story 中的**第 2 份**（见 overview §4）。
> 权威产品规则 = 方案 v1.0.1 §3 / §3.1；跨 story 代码契约以 `CROSS-STORY-DECISIONS.md` 为准。
> **本 story 显式修订 F10**（见 §1、§8）。

---

## 1. 背景与范围

### 1.1 为什么这个 story 要修订 F10

现行决策 **F10**（`CROSS-STORY-DECISIONS.md:35`，2026-06-08 反转 F1 落定）规定帖子审核为
**「发布前同步闸门 + 无中间态」**：发布写库前三方自动审核，任一拦截即**发布失败、不落库、停留编辑页、不进人工队列**，
文字图片均过才发布。这是一个**二选一**模型——要么公开发布、要么失败留编辑页，**没有「已提交但尚未公开」的挂起态**。

方案 v1.0.1 §3 / §3.1（FR-12 / FR-12A）引入了 F10 **未覆盖**的第三条路径：

> 风险评分 **≥ 0.8（未命中 L1 硬拦截）** → 不直接拒绝也不直接发布，**转入人工审核队列挂起**；
> 挂起期间帖子**仅作者本人可见、其他用户完全不可见、且不显示任何「审核中」标签**（作者视角与正常发布无异）；
> 运营通过 → 转对他人可见进 Feed；拒绝/超时 → 维持仅作者可见并丢弃。

这是对 F10 审核**时序模型**的**增量修订**（新增中间态），**不是推翻** F10 的「发布时三方自动审核」骨架。
本 story 落地该修订，并在 `CROSS-STORY-DECISIONS.md` **追加**一条 F10 修订条目（追加、不删原文，见 §8）。

> ⚠️ **开工前置**：D-CM1 建议先与架构口头确认「引入已提交待审、仅作者可见的中间态」这一时序模型变更再落码（overview §7-1）。

### 1.2 本 story 范围（做什么）

1. **激活 FR-12A 的产品化路径**：`admin_settings.manual_review_enabled` 从「拦截即失败」的现网默认（false）→ 可打开为「高风险入队挂起」。开关本体已存在（`ManualReviewGateImpl.enabled()`），本 story 补齐**开关打开后的完整语义**并保证**开关关闭时现网行为字节级不变**。
2. **风险分级路由**（消费 story 1 的评分）：
   - **命中 L1 硬拦截** → **即时失败**（`AppException.contentTextBlocked/ImageBlocked`），**不落库、不进挂起态**（无论开关开关）。
   - **风险 ≥ 0.8 且未命中 L1**（或 story 1 的 **fail-closed 降级**信号，D-CM5）→ **路由入队**：落 `UNDER_REVIEW` + `manual_review_queue` PENDING（**仅当开关打开**）。
   - **风险 < 0.8** → 正常发布（`PUBLISHED`），用户无感知。
3. **引入 D-CM2「仅作者可见」挂起态**：改现有 `UNDER_REVIEW` 的**展示语义**——挂起帖子在**「我的发布」/ 成长档案时间线对作者本人可见**、在 **Feed / 名片 / 他人视角完全不可见**、且**前端不显示任何「审核中」标签**。
4. **编辑重审 + 陈旧结果作废骨架**（D-CM3，帖子版本键）：为 `content_posts` 加 `content_version`；定义重审契约与「出结果时版本已变→静默丢弃 + 移除队列条目」不变量与审核期版本守卫。（帖子编辑端点在 V1 尚不存在，本项落**版本键 + 守卫**基建，编辑触发点随未来编辑端点接线，见 §5.5 / §10。）
5. **`content_posts` 加审核列**（V48 占位）：`moderation_risk_score` / `review_reason` / `content_version`。

### 1.3 非目标（不做，边界）

- **不实现帖子编辑 UI / 端点**（当前 create-only，见 §2）——只落版本键与守卫基建。
- **不做评论审核**（story 3）、**不做名称/头像审核**（story 4/5）、**不做举报分级**（story 6）。
- **不实现阿里云真三方**（story 1）——本 story 仅**消费**其 `Verdict`/评分契约；story 1 未落地前用**可测 stub 扩展**（§5.2）跑通 L0/L1。
- **不改 SafetyRuleLayer / 红色态零变现**等安全地基；**不引入 MQ/新中间件**（护栏）。

---

## 2. 现状基线（file:line，复用勿重建）

| 组件 | 位置 | 现状 |
|---|---|---|
| 发布主流程 + 审核分支 | `ContentService.java:207-282` | publish() 已接线：`moderate()` 非 PASS → 按 `manualReviewGate.enabled()` 分「失败/入队」两支（L250-266）。入队路径落 `UNDER_REVIEW` + `enqueue`（L261-265）。**开关关时 L254-258 直接 throw**（现网 FR-12 行为，AC 必须保持不变）。 |
| 审核出站端口 | `ManualReviewGate.java:10-17` | content 定义、admin 实现；`enabled()` + `enqueue(contentId)`。 |
| 开关实现 | `ManualReviewGateImpl.java:26-35` | `enabled()` 读 `AdminSettingsService.isManualReviewEnabled()`；`enqueue` 写一条 PENDING。 |
| 自动审核（stub） | `ContentModerationService.java:18-64` | `Verdict{PASS,TEXT_BLOCKED,IMAGE_BLOCKED}`（L21-25）；6 词黑名单（L31-32）+ 图 URL 魔法标记（L38）。**无风险评分、无 ≥0.8 中间档、无降级信号**。 |
| 人工队列处置 | `ManualReviewService.java:26-135` | 浏览/approve/reject/scanTimeouts；`OVERDUE=24h`（L29）/`TIMEOUT=3d`（L30）；approve→`contentService.approveReview`（L71）；reject/超时→`discardReview`（L84/L105）；审计 + 通知作者。 |
| 队列表 | `V41__init_manual_review_queue.sql` | `id/content_id/submitted_at/status{PENDING,APPROVED,REJECTED,TIMED_OUT}/decided_by/decided_at`。**无 content_version、无 content_type**。 |
| 状态枚举 | `PostStatus.java` | `PUBLISHED` / `UNDER_REVIEW`；列 `varchar(16)` **无 CHECK**（加值免迁移）。 |
| 领域工厂 | `ContentPost.java:74-104` | `publish()` / `pendingReview()`（status=UNDER_REVIEW，L96-101）/ `approveReview()`（→PUBLISHED，L104）。**无 content_version 字段**。 |
| 「我的发布」查询 | `ContentPostRepository.java:55-70` `findMyPosts` | ⚠️ **过滤 `status = PUBLISHED`** → **当前把作者自己的 UNDER_REVIEW 帖排除在「我的发布」外**，与 D-CM2「仅作者可见」**冲突**，本 story 必须改。 |
| Feed 查询 | `ContentPostRepository.java:85-104` `findFeed` | 过滤 `status = PUBLISHED`（L88）→ **他人已看不到 UNDER_REVIEW，符合 D-CM2，无需改**。 |
| 成长时间线查询 | `ContentPostRepository.java:33-50` | 一律 `DeletedAtIsNull` 但 **不过滤 status** → **UNDER_REVIEW 成长时刻会泄漏给他人 / 名片**（名片流 L45-46）。需按「作者本人 vs 他人」分口径，见 §5.4。 |
| 「我的发布」/ Feed service | `FeedService.java:66,107` | `loadFeed` / `myPosts`。 |
| 前端「我的发布」 | `petgo_app/lib/features/me/data/my_posts_repository.dart` | `MyPost` 模型**无 status 字段**（原样渲染后端返回项）。 |
| 前端发布页 | `petgo_app/lib/features/content/presentation/publish_compose_page.dart:111,256,299` | 提交期「审核中」覆盖层（P-39b）是**同步提交 loading**（≤2s），非持久状态标签。 |
| 前端 Feed | `petgo_app/lib/features/content/data/feed_repository.dart` 等 | 直接渲染后端 Feed，无 status 逻辑。 |
| 帖子编辑端点 | 全仓 `content/web/*` **零命中** PutMapping/PatchMapping | **当前无帖子编辑能力**（create-only）→ D-CM3 帖子侧目前无编辑触发点。 |

---

## 3. 目标与非目标

**目标（验收锚点）**
- G1｜开关关闭 → 现网 FR-12 行为**逐字节不变**（命中即失败、不落库、不入队）。
- G2｜开关打开 → 命中 L1 仍即时失败；≥0.8/降级 → `UNDER_REVIEW` 入队挂起；<0.8 → 正常发布。
- G3｜D-CM2 可见性不变量：挂起帖**仅作者可见**（我的发布 + 自己的成长时间线），**Feed/名片/他人零可见**，**无「审核中」标签**。
- G4｜运营 approve → 转 `PUBLISHED` 进 Feed（复用现有 `ManualReviewService.approve`）；reject/超时 → 丢弃（复用 discard）。
- G5｜版本键 + 审核期版本守卫落地（帖子编辑触发点 dormant）。
- G6｜`content_posts` 新增 `moderation_risk_score/review_reason/content_version` 且 `ddl-auto=validate` 绿。
- G7｜F10 修订条目已追加到 `CROSS-STORY-DECISIONS.md`。

**非目标**：见 §1.3。

---

## 4. 数据与迁移（V48 delta）

> Flyway 已冻结到 **V46**；本 story 增量占位 **V48**（overview §5：V47=story1 词库，V48=本 story）。**占位号勿硬编码**——CI 落地时按实际合并顺序单调顺延。全部 **ALTER**，不动既有迁移（决策 E2）。`ddl-auto=validate`，schema 契约以 CI/L1 绿为准。

**`V48__add_content_posts_moderation_columns.sql`（ALTER content_posts）**

```sql
-- 内容审核 story 2（帖子审核可见性新模型 + 激活 FR-12A）。冻结基线 V46，本 story 增量 ALTER。
-- content_posts 加审核元数据：风险分（story1 阿里云评分落库）、入队原因、内容版本键（D-CM3 陈旧作废）。
ALTER TABLE content_posts
    ADD COLUMN moderation_risk_score NUMERIC(4,3),          -- [0.000,1.000] 三方风险分；<0.8 直发不必落，可空
    ADD COLUMN review_reason         VARCHAR(24),           -- 入队原因 UPPER_SNAKE：RISK_HIGH / DEGRADED_FAILCLOSED；非挂起为空
    ADD COLUMN content_version       INTEGER NOT NULL DEFAULT 1;  -- D-CM3 版本键；编辑一次 +1；审核结果绑定此版本

-- review_reason 取值约束（varchar + CHECK，UPPER_SNAKE；允许 NULL）
ALTER TABLE content_posts
    ADD CONSTRAINT ck_content_posts_review_reason
    CHECK (review_reason IS NULL OR review_reason IN ('RISK_HIGH', 'DEGRADED_FAILCLOSED'));
```

**列约定校对**（命名映射链）：`snake_case` ↔ camelCase（`moderationRiskScore`/`reviewReason`/`contentVersion`）；分数用 `NUMERIC(4,3)` 承载 0.000–1.000（**勿用 float**，避免比较误差）；`content_version` `NOT NULL DEFAULT 1` 存量行回填 1；**不建 length=1 列**（避开 Hibernate CHAR(1) 坑）。

> **manual_review_queue 不在本 story ALTER**：队列的 `content_type`（story 3）、`content_version` 快照（随未来帖子编辑端点）留后续迁移。本 story 的版本守卫改为**在 approve 时比对 `content_posts.content_version` 与入队时刻**——但因帖子当前不可编辑（§2），守卫恒等、无实际漂移（见 §5.5、§10-R3）。

---

## 5. 后端设计

### 5.1 激活路径（开关语义收口）

`ContentService.publish`（L248-266）现有骨架**保留**，仅调整分支判据，使之消费 story 1 的**分级信号**而非二值 Verdict：

- **命中 L1 硬拦截**（`TEXT_BLOCKED`/`IMAGE_BLOCKED`）→ **无论开关开关，一律即时 throw**（现有 L254-258 逻辑上移为「硬拦截恒失败」，不再包在 `!enabled()` 里）。这是 D-CM2「L1 即时判定不进挂起态」的落点。
- **≥0.8 / 降级**（新 `RISK_HIGH` / `DEGRADED`，见 §5.2）：
  - 开关 **关**（现网默认）→ **按通过放行发布**（`PUBLISHED`）。理由：现网无队列可挂，且这是 F10 现行行为（只有硬拦截失败）；G1 要求关态行为不变。
  - 开关 **开** → 落 `UNDER_REVIEW`（`ContentPost.pendingReview`）+ 写 `moderation_risk_score`/`review_reason` + `manualReviewGate.enqueue(id)` + `idempotency.store`，**不发 `ContentPublishedEvent`**（不进 Feed、不触发里程碑）。返回 `ContentPostResponse.from(pending)`（HTTP 200，作者视角=已提交成功）。
- **PASS（<0.8）** → 现有正常发布路径不变（L268-281），可选把 `moderation_risk_score` 落库（<0.8 也可为空，节省写）。

> **幂等**：入队路径已 `idempotency.store(key, pending.getId())`（L263），重放取回同一挂起帖，勿重复入队。

### 5.2 消费 story 1 的评分契约（story 1 未落地前的可测 stub 扩展）

story 1 将把 `ContentModerationService.moderate()` 的产物从三值 `Verdict` 扩为**携风险分 + 硬拦截标志 + 降级标志**的结果。本 story 定义**消费侧契约**（story 1 落地时对齐，不得改调用方 `ContentService`）：

建议结果形状（story 1 owns 实现，本 story owns 契约）：
```
ModerationOutcome {
    boolean hardBlocked;        // 命中 L1 → 即时失败
    BlockKind kind;             // TEXT / IMAGE（hardBlocked 时有值）
    double   riskScore;         // [0,1]，落 content_posts.moderation_risk_score
    boolean  degraded;          // 三方超时/错误/宕机/配额（D-CM5 fail-closed）
}
```
判定优先级：`hardBlocked` > `degraded`（`review_reason=DEGRADED_FAILCLOSED`）> `riskScore>=0.8`（`review_reason=RISK_HIGH`）> 放行。

**story 1 未落地前**：在现有 `ContentModerationService` 加**可测占位**——扩 `Verdict` 增 `RISK_HIGH`（并可用文本魔法标记如 `risk-high` / `moderation-degraded` 触发），使 publish 的入队路径能被 L0 单测 + L1 集成端到端触发，**不接真三方**。story 1 合入时替换内部实现即可，`ContentService` 分支不动。

### 5.3 「我的发布」可见性（D-CM2 核心改动）

`ContentPostRepository.findMyPosts`（L55-70）当前 `AND p.status = PUBLISHED` → **改为放行作者自己的挂起帖**：

```java
// 我的发布：作者本人可见自己未软删的 PUBLISHED + UNDER_REVIEW（D-CM2 仅作者可见）。
AND (p.status = PUBLISHED OR p.status = UNDER_REVIEW)
```

已按 `authorId` 收口且 `deletedAt IS NULL`，加入 `UNDER_REVIEW` **不泄漏**（查询仅服务当前登录作者自己）。排序/游标不变（`created_at DESC, id DESC`）。**Feed（`findFeed` L88）保持 `status=PUBLISHED` 不变**——他人零可见。

### 5.4 成长档案时间线可见性（补泄漏口）

成长时间线四查询（`ContentPostRepository.java:33-50`）**不过滤 status**，需按「作者本人 vs 他人/公开」分口径：

- **作者本人自看**（Story 2.4 in-app 时间线）→ 可含 `UNDER_REVIEW`（与我的发布一致，仅作者可见）。
- **他人视角 / 名片 H5 公开流**（Story 2.6，`findRecentGrowthMomentsByEventDate` L45-46）→ **必须仅 `PUBLISHED`**，挂起成长时刻零泄漏。

落法（择一，dev 判定影响面）：给公开/名片取数路径加 `AND status=PUBLISHED` 的**新查询变体**，作者自看路径保留/新增含 `UNDER_REVIEW` 变体；或在 service 层按 `viewerId==ownerId` 选择口径。**统计口径**（`countGrowthMoments`/`countByAuthorIdAndType...AndStatus(PUBLISHED)`，L48-50）维持仅 `PUBLISHED`——挂起不计入统计栏（作者可见其挂起项但统计数暂不含，V1 接受，§10-R2 记）。

### 5.5 编辑重审 + 陈旧结果作废（D-CM3 版本键）

- **版本键**：`content_posts.content_version`（默认 1）。**每次编辑内容 +1**（编辑端点落地时接线）。
- **重审契约**：帖子被编辑 → 走与首次发布同一 `publish` 审核路径重新判定（防「先发干净、再改塞违规」）。
- **陈旧作废不变量**：审核出结果时若 `content_version` 已 > 入队时刻版本（内容已改）→ **旧结果静默丢弃**（不通知、不处置），若旧条目仍在队列 → **移除队列条目**；新版本按新提交重走审核。
- **审核期版本守卫**：`ManualReviewService.approve/reject`（经 `ContentService.approveReview/discardReview`）落地前，比对当前 `content_version`；不匹配 → no-op（幂等）。
- **当前 dormant 说明**：帖子编辑端点在 V1 **尚不存在**（§2），故 `content_version` 恒为 1、守卫恒等、无实际漂移。本 story 只落**版本键列 + 守卫钩子 + 不变量文档**；编辑触发点随未来「帖子编辑」story 接线（§10-R3）。评论/名称/头像的版本键各自在 story 3/4/5 实现（D-CM3 全局，各自 own 字段）。

### 5.6 复用（勿重建）

- 入队 / approve / reject / 超时扫描 / 24h 高亮 / 审计 / 通知作者：**全部复用** `ManualReviewService` + `ReviewTimeoutScanner`，本 story 不改其内部。
- approve 通过后进 Feed + 里程碑副作用：复用 `ContentService.approveReview`（L162-176 发 `ContentPublishedEvent`）。
- 通知文案「隐藏才通知」收口、正向通知删除：归 story 7，本 story 不动 `ModerationNotifyListener`。

---

## 6. 前端设计（petgo_app，portrait-only 浅色）

> 关键结论：D-CM2 的「无审核中标签」在前端**多为「不做额外渲染」**——挂起帖对作者呈现为**普通已发布帖**。改动面很小。

### 6.1 「我的发布」可见性

- 后端改后 `/api/v1/me/posts` 会返回作者自己的挂起帖；`MyPost` 模型**无需加 status 字段**、**不加任何「审核中」徽标**——原样渲染即可（与普通帖视觉一致）。
- 若 `MyPost`/详情后续引入 status，**严禁**据此渲染「审核中」标签（违反 D-CM2）。

### 6.2 发布提交结果

- 开关打开时 publish 返回 200 + `UNDER_REVIEW` 帖：前端**按发布成功处理**（跳成功页 / 落「我的发布」），**不弹「审核中/待审」文案**。
- `publish_compose_page.dart` 的 P-39b「审核中」覆盖层是**同步提交 loading**（≤2s），**保留可接受**（它是 loading 态、非持久状态标签，符合方案「同步 SLA 内几乎无感知」）；但**不得**在结果页 / 帖卡上留持久「审核中」态。命中 L1 硬拦截仍走现有「失败留编辑页 + 提示重试」（草稿保留）。

### 6.3 Feed 过滤

- Feed 由后端 `findFeed` 权威过滤（已排除 `UNDER_REVIEW`），前端**无需改**；AC 用**第二账号**验证挂起帖在 Feed / 名片零可见。

### 6.4 编辑触发重审

- 当前无帖子编辑 UI（§2）→ 前端本 story **不做**编辑入口。未来编辑 story 接线时：编辑提交复用发布审核路径，视觉与新发一致（无审核中标签）。

---

## 7. 验收标准 AC（前后端分段，每条标 L0/L1/L2 + 环境）

> L0 静态（无 DB/凭证，云端可跑）｜L1 集成（Docker postgres+redis + Flyway validate 真跑）｜L2 端到端（真机/模拟器视觉，回本地）。模拟器可见性一律 L2。

### A. 后端

- **AC-B1（开关关＝现网不变，G1）**：`manual_review_enabled=false` 时，命中黑名单 / 图像标记的发布 → `4xx` 即时失败、**不落库、不入队**（`content_posts`/`manual_review_queue` 零新增）。**L1**（postgres）。
- **AC-B2（L1 硬拦截恒失败，G2）**：开关**打开**时，命中 L1（`TEXT_BLOCKED`/`IMAGE_BLOCKED`）仍即时 throw、不落挂起态、不入队。**L0**（`ContentService` 单测，mock moderation + gate）+ **L1**。
- **AC-B3（≥0.8/降级 → 入队挂起，G2）**：开关打开 + moderation 返回 `RISK_HIGH`（或 `DEGRADED`）→ 落 `content_posts.status=UNDER_REVIEW` + `review_reason=RISK_HIGH|DEGRADED_FAILCLOSED` + `moderation_risk_score` 有值 + `manual_review_queue` 一条 PENDING + **无 `ContentPublishedEvent`**（不进 Feed）。**L0**（单测断言分支/事件未发）+ **L1**（真落库 + 队列可查）。
- **AC-B4（<0.8 正常发布）**：moderation `PASS` → `status=PUBLISHED` + 发 `ContentPublishedEvent`，行为同现网。**L0** + **L1**。
- **AC-B5（我的发布仅作者可见挂起帖，G3）**：作者 A `GET /me/posts` **包含**自己的 `UNDER_REVIEW` 帖；`findFeed` / 名片查询对该帖返回空（他人/公开零命中）。**L1**（同库两身份查询断言）。
- **AC-B6（成长档案泄漏口已补，G3）**：A 的挂起 `GROWTH_MOMENT` 在 A 自己时间线可见、在**名片公开流 / 他人视角仅 PUBLISHED**（挂起零泄漏）。**L1**。
- **AC-B7（approve→进 Feed / reject→丢弃，G4）**：`ManualReviewService.approve` → 帖 `PUBLISHED` + 发 `ContentPublishedEvent` + 队列 `APPROVED`；`reject`/超时 → 软删丢弃 + 队列 `REJECTED`/`TIMED_OUT`。**复用现有实现**，回归不破。**L0**（service 单测）+ **L1**。
- **AC-B8（版本键 + 守卫，G5）**：`content_posts.content_version` 默认 1；approve/discard 前版本守卫存在（当前恒等、no-op 安全）。**L0**（单测）+ **L1**（validate 绿）。
- **AC-B9（迁移 validate，G6）**：V48 ALTER 后 `mvn -B package` + Flyway `validate` 绿；`moderation_risk_score` `NUMERIC(4,3)`、`review_reason` CHECK 生效、`content_version NOT NULL DEFAULT 1` 存量回填。**L0**（`mvn -B compile`）+ **L1**（Flyway migrate+validate，CHAR(1) 类坑仅 L1 暴露）。
- **AC-B10（幂等）**：同 `Idempotency-Key` 重放入队路径 → 取回同一挂起帖、队列不重复入条。**L1**。

### B. 前端

- **AC-F1（我的发布无审核中标签，G3）**：开关打开、A 发一条 ≥0.8 帖 → 「我的发布」出现该帖且**无任何「审核中/待审」徽标**（视觉同普通帖）。**L2**（Android 模拟器，真连 `api.tailtopia.id` 或本地 L1 后端）。
- **AC-F2（发布结果不显挂起文案）**：提交 ≥0.8 帖 → 走**发布成功**流（P-39b loading 结束后成功页 / 落我的发布），无「审核中/待审核」提示。**L2**（模拟器）。
- **AC-F3（Feed/他人零可见，G3）**：第二账号 B 的 Feed、以及 A 的名片 H5，**看不到** A 的挂起帖。**L2**（双账号 / 双设备，模拟器）。
- **AC-F4（L1 硬拦截仍留编辑页）**：命中黑名单发布 → 失败 toast + 停留编辑页、草稿保留（现网行为不回退）。**L2**（模拟器）。
- **AC-F5（analyze/test 绿）**：`flutter analyze` + `flutter test` 绿（若改到 model/repo）。**L0**（云端可跑）。

### C. 契约 / 文档

- **AC-C1（F10 修订入账，G7）**：`CROSS-STORY-DECISIONS.md` **追加**一条 F10 修订条目（追加、不删原文，文案见 §8），并在本 story Completion Notes 记录。**L0**（文档 diff review）。

---

## 8. 依赖与契约

### 8.1 依赖

- **story 1（阿里云内容安全接入）**：提供风险评分（0.8 阈值）+ L1 硬拦截判定 + fail-closed 降级信号（D-CM5）。本 story 消费其 `ModerationOutcome` 契约（§5.2）；**story 1 未落地前用可测 stub 扩展**跑 L0/L1，真三方联调（L2）随 story 1 回本地。
- **既有基建复用**（勿重建）：`ManualReviewService` / `ManualReviewGate` / `ReviewTimeoutScanner` / `AdminSettings.manual_review_enabled` / `PostStatus.UNDER_REVIEW` / `ContentPost.pendingReview|approveReview` / `V41` 队列表。

### 8.2 修订 F10（需追加到 CROSS-STORY-DECISIONS.md 的**确切文案**）

> 在 `CROSS-STORY-DECISIONS.md` 决策表**追加**下述新行（**保留原 F10 行不删**，新行注明「修订 F10」）：

| 编号 | 类别 | 决策 | 影响 story |
|---|---|---|---|
| **F10-CM**（2026-07-08，内容审核 v1.0.1，**修订 F10 时序模型**） | 护栏（**F10 增量修订**） | **F10「发布前同步闸门、无中间态」新增第三条路径**：`manual_review_enabled` 打开后，帖子风险 **≥0.8（未命中 L1）或三方 fail-closed 降级** → 落 `UNDER_REVIEW` **挂起**（不再二选一）。挂起期遵循 **D-CM2 仅作者可见不变量**：帖**仅作者本人可见**（我的发布 + 自己成长时间线）、**Feed/名片/他人完全不可见**、**前端不显示任何「审核中」标签**；运营 approve→转 PUBLISHED 进 Feed，reject/超时→软删丢弃。**命中 L1 硬拦截仍即时失败、不进挂起态**（F10 原行为保留）。**开关关闭时 F10 原行为逐字节不变**。版本键 `content_posts.content_version` 落地（D-CM3 陈旧作废，帖子编辑端点未有前 dormant）。**禁 MQ/新中间件**（护栏不变）。 | 内容审核 story 2（本 story）；连带 story 3（评论同款可见性）、story 7（通知隐藏才推） |

> 追加后，F10 原行（`CROSS-STORY-DECISIONS.md:35`）**保留**作为历史底稿；遇冲突以 F10-CM 为准（后者是增量修订）。

---

## 9. 云端（headless）执行须知

- ✅ **云端可做（L0）**：`mvn -B compile|package`（含 V48 ALTER 语法编译期不校验，但 package 走）；`ContentService`/`findMyPosts`/moderation stub 扩展的**单元测试**；`flutter analyze` + `flutter test`。云端只跑到 L0 绿灯，Completion Notes 标「L1/L2 待本地/CI」。
- ⚠️ **L1（本地/CI）**：Docker postgres+redis 真跑 + Flyway `V48 migrate+validate`——`NUMERIC(4,3)`/CHECK/`NOT NULL DEFAULT` 与 Hibernate 映射契约**只有 L1 才暴露**（CHAR(1) 类坑）；schema 契约以 CI/L1 绿为准。入队 / 两身份可见性 / approve 回归也在 L1。
- ❌ **L2（必回本地）**：story 1 真阿里云评分联调；模拟器可见性（AC-F1~F4，Android，真连 `api.tailtopia.id` 或本地 L1 后端 + 真 Google 登录，勿用桩/mock 否则请求被静默吞成兜底）；双账号 Feed/名片零可见验证。
- 三段推进：**后端（激活+路由+可见性+版本键）→ 前端（我的发布/结果页/双账号验证）→ 联调**；一次只碰一侧。

---

## 10. 风险与待确认

- **R1（开工前置，D-CM1）**：F10 时序模型变更（引入中间态）建议先与架构口头确认再落码。**待确认**。
- **R2（统计口径微不一致）**：作者可在「我的发布」/ 自己时间线看到挂起帖，但 `countGrowthMoments` 统计栏仅计 `PUBLISHED` → 挂起成长时刻**可见但不计数**。V1 接受（挂起通常 ≤SLA 秒级、罕见长挂），Completion Notes 记；若产品要求「可见即计数」再调。**待确认**。
- **R3（帖子编辑端点缺失，D-CM3 dormant）**：当前 create-only，`content_version` 恒 1、重审/陈旧作废对帖子**无实际触发点**。本 story 只落版本键 + 守卫 + 不变量文档；**编辑触发点 + 队列版本快照列**随未来「帖子编辑」story 接线。评论/名称/头像的编辑重审各自在 story 3/4/5 落。**已知边界，非缺陷**。
- **R4（开关关 + ≥0.8 的取舍）**：开关关闭时 ≥0.8 **按放行处理**（§5.1），即现网维持「只有硬拦截失败」。这意味着**激活前**高风险内容不被拦——符合 G1「关态不变」，但产品须知悉「FR-12A 真正生效 = 打开开关」。上线时机由运营定。**待确认**（何时打开开关、是否需灰度）。
- **R5（story 1 契约对齐）**：§5.2 的 `ModerationOutcome` 是本 story 拟定的消费契约；story 1 实现时若形状不同，以两 story 联调对齐为准（调用方 `ContentService` 分支语义不得变）。

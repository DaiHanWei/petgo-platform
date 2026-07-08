---
title: 评论审核 + 巡查下架 + 通知时机（内容审核补充规范 · Story 3）
type: spec-dev-story
slug: content-moderation-comment-review
status: ready-for-dev
epic: Epic 3 内容社交（内容审核补充规范批次 · 第 3 份）
source_overview: _bmad-output/implementation-artifacts/spec-content-moderation-overview.md
source_plan: _bmad-output/planning-artifacts/content-moderation-plan-v1.0.1.md（§3.2 FR-55/FR-55A、§4.3、§5.3、§8.5）
depends_on: [spec-content-moderation-aliyun-provider（story 1，审核评分/降级契约）, spec-content-moderation-post-review（story 2，队列可见性模型/F10 修订）]
flyway_placeholder: V49（实际号按合并顺序单调顺延，勿硬编码）
communication_language: 中文
created: 2026-07-08
owner: Dai
---

# 评论审核 + 巡查下架 + 通知时机

> 本 spec 自包含，可直接交 `bmad-dev-story` / 云端 session 执行。**权威源 = 方案 v1.0.1**；代码契约冲突以 `CROSS-STORY-DECISIONS.md` 为准。
> 与用户沟通用中文。三段推进：**后端 → 前端 → 联调**，一次只碰一侧。

---

## 1. 背景与范围

评论（FR-24，一/二级、≤200 字、软删）是本平台目前**唯一完全零审核**的 UGC 面板——`CommentService.createTopLevel/createReply` 校验帖子可见后**直接落库并同步发** `ContentCommentedEvent`，无任何关键词/风险拦截。方案 v1.0.1 §3.2 为评论补三件事：**发送时同步过滤（关键词硬拦截 + 风险分级同步拦截）**、**三方失败 fail-closed 降级**、**运营人工巡查下架/恢复（FR-55A）**，并明确了**「新评论」通知的触发时机**（审核通过、对他人可见后才发）。

**本 story 交付（对应总览 §4 第 3 项）：**

1. **评论创建同步过滤**（FR-55 步骤 3/4/5）：命中 L1 → 即时失败、输入保留、不入队（F13）；风险 ≥0.8（未命中 L1）→ 同步拦截、发送失败、从未发布、无需通知；<0.8 → 通过、立即对他人可见。
2. **失败降级（fail-closed，方案 §4.3）**：三方超时/报错时评论**不自动放行**→ 落 `UNDER_REVIEW` + 转人工队列（扩展 `manual_review_queue` 支持评论），挂起期间**仅作者可见、无「审核中」标签**（D-CM2）。
3. **FR-55A 运营巡查下架/恢复**（管理后台 AB-3B 评论扩展）：任意已发布评论可直接下架（必填原因、记审计），下架后仅作者可见 + 「违规下架」提示；授权运营可恢复。
4. **「新评论」通知时机（方案 §3.2 决策）**：帖主/被回复者「用户 A 评论了你」通知**仅在评论审核通过、对他人可见后**才发；挂起/降级期间不发。
5. **编辑重审 + 陈旧结果作废（D-CM3）**：为评论加内容版本键 + 队列版本绑定，出结果时版本已变则静默丢弃/移除队列条目。V1 无评论编辑端点，故此项为**契约就绪、暂休眠**（见 §5.6）。

**评论为纯文字（≤200 字），无图片审核。**

---

## 2. 现状基线（file:line）

| 关注点 | 现状 | 证据 |
|---|---|---|
| 评论创建 | **零审核**：校验帖子可见 → `comments.save(...)` → **立即** `publishEvent(ContentCommentedEvent)` | `CommentService.java:41-47`（一级）、`51-67`（二级） |
| 评论删除 | 权限矩阵 + 删一级级联软删二级 | `CommentService.java:73-96` |
| 评论表 | `comments`（`parent_id` 自引用两级；`body VARCHAR(1000)`；软删 `deleted_at`）**无任何审核列** | `V9__init_comments.sql` |
| 评论读取 | 只按 `deleted_at IS NULL` 过滤；`topLevel/replies` **不传 viewerId** | `CommentQueryService.java:47-116`、`CommentRepository.java`（`findTopLevel/findReplies/findRepliesForParents/countByPostIdAndDeletedAtIsNull`） |
| 评论 DTO | `CommentResponse.topLevel/reply` 无审核/可见性字段 | `content/dto/CommentResponse.java` |
| 评论编辑 | **不存在**：控制器仅 `POST comments`、`POST replies`、`DELETE comments/{id}` | `content/web/CommentController.java:36-53` |
| 评论通知 | `ContentCommentedEvent` → `ContentNotifyListener.onContentCommented` 推帖主 + 被回复一级作者（自评/自回复不推） | `notify/service/ContentNotifyListener.java:42-57` |
| 审核 stub | `ContentModerationService.moderate(text, imageUrls)` 返回 `Verdict{PASS,TEXT_BLOCKED,IMAGE_BLOCKED}`，**无风险评分/无 0.8 阈值/无降级**（纯 6 词子串匹配 + 图 URL 魔法标记） | `ContentModerationService.java:13-16,31-38,47-55` |
| 人工队列 | `manual_review_queue` **仅面向帖子**（`content_id → content_posts`，无 `content_type`）；`ManualReviewService` 处置硬编码 `CONTENT_POST` + 调 `contentService.approveReview/discardReview` | `V41__init_manual_review_queue.sql`、`ManualReviewService.java:69-117`、`ManualReviewItem.java:27` |
| 队列开关 | `ManualReviewGate.enabled()` 读 `admin_settings.manual_review_enabled`（缺省 false）；`scanTimeouts` 在 `!enabled` 时**空转早返回** | `ManualReviewGate.java`、`ManualReviewGateImpl.java`、`ManualReviewService.java:98-101` |
| 后台内容处置 | `AdminContentManageService.takedown/restore`（帖子，必填原因、关联举报、审计）；DTO `AdminContentRow` | `AdminContentManageService.java:52-71`、`admin/moderation/web/AdminContentManageController.java` |
| 举报 | **仅帖子可举报**（`V11`）；评论无举报入口（FR-57 属 V1.2，**本 story 不做**） | 方案 §6 |

**要点强调：评论当前是全平台唯一「创建即公开、无中间态、无任何审核」的 UGC 通道。** 本 story 首次为其引入「审核通过前的可见性收敛」，因此对**评论读取路径**（加 viewer 维度可见性过滤）与**通知发布时机**（从「创建即发」改为「转可见才发」）都是行为变更，需谨慎不破坏现网正常评论体验（正常路径 <0.8 仍应几乎无感知）。

---

## 3. 目标与非目标

**目标：**
- G1 评论创建走同步过滤：L1 即时失败（不入队）、≥0.8 同步拦截（从未发布）、<0.8 通过立即可见。
- G2 三方超时/报错 fail-closed → 评论 `UNDER_REVIEW` 挂起 + 入人工队列，仅作者可见、无标签。
- G3 FR-55A 巡查下架/恢复：必填原因、审计、下架仅作者可见 + 标签、恢复记日志。
- G4 「新评论」通知仅在评论对他人可见时触发（正常路径即时；挂起/降级期间延迟到审核通过；拒绝/超时则永不发）。
- G5 编辑重审 + 陈旧作废契约就绪（版本键 + 队列绑定），V1 无编辑入口故休眠。
- G6 评论读取按 viewer 维度过滤：非 `VISIBLE` 评论仅作者本人可见；下架评论对作者带标签。

**非目标（本 story 明确不做）：**
- N1 **评论举报入口（FR-57）** —— V1.2.0，本 story 不加任何评论举报路径/去重/阈值。
- N2 图片审核 —— 评论纯文字。
- N3 真实阿里云三方接入 / 印尼语实测 —— 属 story 1；本 story 只**消费** story 1 定义的评论审核判定契约（未落地时用 stub，见 §8）。
- N4 帖子侧队列/可见性模型（story 2）、名称/头像/账号计数（story 4/5/9）、通知文案 i18n 全量落 arb（story 7）。
- N5 评论编辑 API（本平台 V1 无此端点，不在本 story 新增）。

---

## 4. 数据与迁移（V49 delta + 队列扩展）

> Flyway **已冻结到 V46**；本批占位 **V49**（story 1=V47 词库、story 2=V48 帖子审核列）。**实际号按合并顺序单调顺延**（若本 story 先于 story 1/2 合并，号前移）；一律新起 `ALTER`，勿改历史迁移（决策 E2）。`ddl-auto=validate`——列须与 JPA 实体精确对齐。

**`V49__add_comment_moderation.sql`（示意）：**

```sql
-- ① comments 加审核状态 + 内容版本键（评论审核补充规范 · story 3）。
-- moderation_status: 评论对他人的可见性态。存量评论全部视为已公开 → 默认 VISIBLE（grandfather）。
--   VISIBLE       正常通过，对他人可见（正常 <0.8 路径）
--   UNDER_REVIEW  三方降级挂起（fail-closed）：仅作者可见、无标签，待人工队列判定
--   TAKEN_DOWN    FR-55A 巡查下架：仅作者可见 + 「违规下架」标签
--   REJECTED      降级队列被运营拒绝 / 超时丢弃：仅作者可见（终态，无标签或复用下架标签，见 §5.4）
ALTER TABLE comments
    ADD COLUMN moderation_status VARCHAR(16) NOT NULL DEFAULT 'VISIBLE',
    -- 内容版本：body 每次变更 +1，供陈旧审核结果作废（D-CM3）。V1 无编辑端点故恒为 1（休眠契约）。
    ADD COLUMN content_version   INT         NOT NULL DEFAULT 1;

ALTER TABLE comments
    ADD CONSTRAINT ck_comments_moderation_status CHECK
        (moderation_status IN ('VISIBLE', 'UNDER_REVIEW', 'TAKEN_DOWN', 'REJECTED'));

-- 读路径 viewer 可见性过滤高频：VISIBLE 走公开列表；作者看自己非 VISIBLE 的走 author_id。
-- 复合已有 idx_comments_post_toplevel/idx_comments_parent，此处仅补状态维度的部分索引。
CREATE INDEX idx_comments_moderation ON comments (post_id, moderation_status);

-- ② manual_review_queue 扩展为多态：区分帖子 / 评论条目（原表仅帖子）。
-- 存量队列项全部是帖子 → 默认 CONTENT_POST（grandfather）。
ALTER TABLE manual_review_queue
    ADD COLUMN content_type    VARCHAR(16) NOT NULL DEFAULT 'CONTENT_POST',
    -- 入队时捕获的内容版本，出结果时与当前版本比对做陈旧作废（D-CM3）。帖子/评论通用，可空。
    ADD COLUMN content_version INT;

ALTER TABLE manual_review_queue
    ADD CONSTRAINT ck_manual_review_queue_content_type CHECK
        (content_type IN ('CONTENT_POST', 'COMMENT'));
```

**列名映射链：** `moderation_status`↔`moderationStatus`、`content_version`↔`contentVersion`、`content_type`↔`contentType`（snake↔camel，JPA/Jackson 自动桥接）。枚举落库 `varchar` UPPER_SNAKE。**注意护栏：`VARCHAR(16)` 非 length=1，无 Hibernate CHAR(1) 坑。**

> **决策 D-CM-C1（队列多态方案选型）：** 采用**扩列 `content_type`**（而非另建评论专用队列表），复用 `ManualReviewService`/`ManualReviewGate`/后台队列视图，减少重复基建、符合 V1 轻量姿态。代价：`content_id` 不再有单一 FK（原表本就无 FK，仅索引），改由 `(content_type, content_id)` 逻辑寻址；`ManualReviewService` 处置需按 `content_type` 分派（见 §5.3）。

---

## 5. 后端设计

### 5.1 评论审核判定契约（依赖 story 1，本 story 消费）

story 1 将 `ContentModerationService` 内部替换为真三方并新增风险评分/0.8 阈值/降级。本 story **只依赖一个评论文字审核判定的稳定契约**，不关心其内部实现：

```java
/** 评论文字审核结论（本 story 定义/消费；story 1 落地真实评分与降级）。 */
public enum CommentVerdict {
    PASS,        // 风险 < 0.8 且未命中 L1 → 通过，立即对他人可见
    L1_BLOCKED,  // 命中 L1 强制拦截词库 → 即时失败、不入队（F13）
    HIGH_RISK,   // 未命中 L1 但风险评分 ≥ 0.8 → 同步拦截、从未发布
    DEGRADED     // 三方超时 / 4xx-5xx / 配额耗尽 / 宕机 → fail-closed，不自动放行 → 转人工队列
}
```

- 在 `ContentModerationService` 上新增 `CommentVerdict moderateComment(String text)`（与既有帖子 `moderate(...)` 并列）。真实接入时映射：命中 L1 词库→`L1_BLOCKED`；否则评分 ≥0.8→`HIGH_RISK`；<0.8→`PASS`；调用抛超时/错误异常在**本类内**捕获并归为 `DEGRADED`（fail-closed，不外抛，绝不因异常放行）。
- **story 1 未合并时的 stub 落地（保证本 story 可独立 L0/L1 绿）**：沿用现网 6 词黑名单判 `L1_BLOCKED`；文本含可测标记 `high-risk-comment`→`HIGH_RISK`、含 `moderation-degraded`→`DEGRADED`；其余 `PASS`。stub 纯应用内同步实现，**禁引入 MQ/缓存/新中间件**（护栏）。真实接入仅替换本类内部，`CommentService` 调用方不变。

### 5.2 CommentService 创建流程改造（G1/G2/G4）

`createTopLevel`/`createReply` 在 `requireVisible(post)` 之后、`comments.save(...)` 之前插入审核分派。抽出私有 `applyModeration(...)` 复用：

```
verdict = moderation.moderateComment(body)
switch (verdict):
  L1_BLOCKED  -> throw AppException.commentBlocked("内容包含不当词汇，请修改后重试")   // 422，从未落库、不发事件、不入队
  HIGH_RISK   -> throw AppException.commentBlocked("评论不符合友好社区规定，请修改后重试") // 422，从未落库、不发事件、不入队
  DEGRADED    -> save(Comment, status=UNDER_REVIEW); 不发 ContentCommentedEvent; queue.enqueueComment(commentId, version)
  PASS        -> save(Comment, status=VISIBLE); publishEvent(ContentCommentedEvent)   // 现网行为，立即可见 + 即时通知
```

要点：
- **L1 与 HIGH_RISK 均从未落库**（`throw` 前不 `save`）——与方案「评论从未发布、无需通知、用户仍在输入界面」一致；两条走同一新 `AppException.commentBlocked(...)`（新 `ErrorTypes.COMMENT_BLOCKED`，`422 UNPROCESSABLE_ENTITY`，镜像既有 `contentTextBlocked`）。两者 detail 文案不同仅供后端日志/排查；**前端按 error type 映射单一本地化 toast**，不展示 detail 原文（RFC9457 护栏）。
- **DEGRADED**：`Comment.createUnderReview(...)`（`moderationStatus=UNDER_REVIEW`）落库并返回 `CommentResponse`——作者视角看起来正常发出（D-CM2，无标签）；**不发** `ContentCommentedEvent`（G4：挂起期不通知帖主）；调 `ManualReviewGate.enqueueComment(commentId, contentVersion)` 入队。
- **PASS**：与现网一致 `save`（`moderationStatus=VISIBLE` 默认）+ **同步发** `ContentCommentedEvent`（正常路径即时通知帖主/被回复者，行为不变）。
- 二级回复 `createReply` 同理：`DEGRADED` 时同样不发事件；`PASS` 时发（携 `parentAuthorId`，逻辑不变）。归并到一级父的两级约束不动。

**`ManualReviewGate` 扩展**（content 模块出站端口）：新增 `void enqueueComment(long commentId, int contentVersion)`；`ManualReviewGateImpl` 落 `ManualReviewItem.pendingComment(commentId, version, now)`（`content_type=COMMENT`）。
> **降级入队不受 `manual_review_enabled` 开关门控**：该开关是 story 2 帖子「高风险→人工队列」的激活位；评论降级入队是 fail-closed **安全属性**，必须无条件生效（否则降级评论会静默永久挂起或错误放行）。`enqueueComment` 不读该开关。

### 5.3 ManualReviewService 多态处置（消费降级队列，G2/G4）

`ManualReviewService` 现硬编码帖子。改为按 `content_type` 分派：

- `approve(itemId)`：
  - `CONTENT_POST` → 现有逻辑（`contentService.approveReview` + `CONTENT_REVIEW_APPROVED` 通知，story 2 语义不变）。
  - `COMMENT` → 陈旧校验（§5.6）→ `commentService.approveComment(commentId)`：置 `moderationStatus=VISIBLE` **并在此刻发 `ContentCommentedEvent`**（G4：评论转可见 → 触发「新评论」通知）；**不给评论作者发「通过」通知**（D-CM6 正向静默）；审计 `CONTENT_REVIEW_APPROVED`（objectType=`COMMENT`）。
- `reject(itemId)` / `scanTimeouts`：
  - `CONTENT_POST` → 现有逻辑不变。
  - `COMMENT` → `commentService.rejectComment(commentId)`：置 `moderationStatus=REJECTED`（仍仅作者可见，终态，**永不发** `ContentCommentedEvent`）；通知评论作者（负向结果，D-CM6）——复用 §8.5 评论移除文案（临时中文 literal，i18n 归 story 7）。审计 `CONTENT_REVIEW_REJECTED`/`CONTENT_REVIEW_TIMED_OUT`（objectType=`COMMENT`）。
- **`scanTimeouts` 门控调整**：现 `if (!isManualReviewEnabled()) return 0`。评论降级项须**不受该开关影响**被超时扫描（否则开关关时降级评论永挂）。改为：帖子项维持开关门控，**评论项无条件纳入超时扫描**（3 天阈值同帖子）。实现上分两次查询或查询后按 `content_type` 分流处置。
- 队列列表 `pendingQueue()`：`ManualReviewRow` 增 `contentType` 字段；`toRow` 对 `COMMENT` 经 `commentService.findModerationSummary(commentId)` 取预览/作者（禁 admin 直读 content repo，走 `ContentService`/`CommentService` 门面）。后台展示区分帖子/评论（后台完整增强属 story 8，本 story 只保证队列不崩、能处置）。

> **content↔admin 依赖方向**：`ManualReviewService`（admin.moderation）→ 调 content 侧服务，须经 content 模块暴露的服务接口（如 `CommentService.approveComment/rejectComment/findModerationSummary`），**不反向依赖、admin 不直读 comments repo**（沿用 `ManualReviewGate` 端口在 content 定义、admin 实现的既有模式）。

### 5.4 FR-55A 巡查下架 / 恢复（G3）

在 `AdminContentManageService`（AB-3B）新增评论处置（或平行 `AdminCommentManageService`，与帖子对称）：

- `takedownComment(long commentId, String reason, long actorAccountId)`：
  - `reason` 空/空白 → `AppException.validation("下架原因不能为空")`。
  - 经 `CommentService.takedownComment(commentId)`：置 `moderationStatus=TAKEN_DOWN`（幂等：仅 `VISIBLE` 可下架；已下架/挂起给校验错或幂等返回）。
  - 通知评论作者（负向 → 推，§8.5 文案，临时中文 literal）：`NotificationService.send(commentAuthorId, NotificationType.CONTENT_REMOVED, "你的评论已被移除", "...", CONTENT_REMOVED.name(), String.valueOf(postId))`——深链指向帖子详情（作者仍可见自己被下架的评论 + 标签）。
    > 复用既有 `CONTENT_REMOVED` 类型避免本 story 动 `notifications.type` 的 CHECK 迁移（新增专用 `COMMENT_REMOVED` 类型 + i18n 归 story 7）。
  - 审计：新增 `AuditActions.COMMENT_TAKEN_DOWN`，`objectType="COMMENT"`，summary 含原因（原因进审计、**不进作者通知**，与帖子下架一致）。
- `restoreComment(long commentId, long actorAccountId)`：
  - 经 `CommentService.restoreComment(commentId)`：`TAKEN_DOWN`/`REJECTED` → 回 `VISIBLE`（幂等）。
  - **不发任何通知**（恢复=正向，D-CM6）；**不重发** `ContentCommentedEvent`（评论恢复不是「新评论」，避免重复通知帖主）。
  - 审计：新增 `AuditActions.COMMENT_RESTORED`。
- 后台路由（Thymeleaf，沿用 AB-3B 门控 `content.proactive_takedown` / `content.restore` 或 `SUPER_ADMIN`）：
  `POST /admin/comments/{id}/takedown`（表单含 reason）、`POST /admin/comments/{id}/restore`。评论浏览/搜索入口的完整 UI 属 story 8；本 story 最小交付「能对指定评论 id 下架/恢复」+ 后端逻辑闭环。

### 5.5 评论读取可见性过滤（G6，D-CM2）

`CommentQueryService.topLevel/replies` 与 `CommentRepository` 查询须加 **viewer 维度可见性**：一行评论对 viewer 可见 ⟺ `moderation_status='VISIBLE'` **或** `author_id = :viewerId`（作者始终看得到自己的挂起/下架评论）。

- `CommentQueryService.topLevel(postId, cursor, Long viewerId)` / `replies(parentId, cursor, Long viewerId)`：新增 `viewerId` 入参（游客=null → 仅 `VISIBLE`）。控制器从 JWT 取当前用户 id（游客端点允许匿名 → 传 null）。
- 仓储查询在既有 `deleted_at IS NULL` 后追加 `AND (c.moderationStatus = 'VISIBLE' OR c.authorId = :viewerId)`（`:viewerId` 为 null 时退化为仅 `VISIBLE`，用 `(:viewerId IS NOT NULL AND c.authorId = :viewerId)` 写法）。涉及：`findTopLevel`、`findReplies`、`findRepliesForParents`（内嵌回复）。
- **计数一致性**：`commentCount`（详情页）与 inline `replyCount` 改为 viewer 维度——公开可见数（`VISIBLE`）+ viewer 自己的非可见评论，使渲染列表与计数一致。`countByPostIdAndDeletedAtIsNull` → 加 viewer 条件（新增带 viewerId 的计数方法；帖主/他人看到的数可不同，可接受）。
- **`CommentResponse` DTO 增字段** `moderationStatus`（或 `takenDown`/`authorOnly` 布尔）供前端渲染标签：`VISIBLE`→无标签；`UNDER_REVIEW`/`REJECTED`→（对作者）无标签或轻提示（见 §6，按 D-CM2 挂起期无「审核中」标签）；`TAKEN_DOWN`→「违规下架，仅你可见」标签。
- 删除权限矩阵（`CommentService.delete`）不变；下架≠删除（下架是可恢复的可见性态，删除是软删终态）。

### 5.6 编辑重审 + 陈旧作废（G5，D-CM3，休眠契约）

- `comments.content_version` 随 `body` 变更自增（未来评论编辑端点须 `+1` 并重新送审）。
- 降级入队时 `manual_review_queue.content_version` 捕获当时版本；`approve/reject` 处置**前**比对：`queueItem.contentVersion != comment.currentVersion` → 该结果**陈旧**，静默丢弃（不改 `moderationStatus`、不通知）并将队列项置终态移除（不再处置）。新版本按新提交重新走 §5.2。
- **V1 现状**：无评论编辑 API（§2），故 `content_version` 恒 1、陈旧分支不可达。本项为**契约就绪**，保证未来加编辑时时序模型正确；本 story 不新增编辑端点（N5）。实现时把陈旧校验写入 `approve/reject`（防御式），AC 层面标注「休眠/契约」即可，无需构造编辑用例。

### 5.7 事务与护栏
- 审核判定（stub 同步）在发布事务内调用即可；真实三方接入的超时预算/@Async 由 story 1 决定，本 story 契约层不假设异步。
- 通知走既有 `@TransactionalEventListener`(AFTER_COMMIT) + `ContentNotifyListener`；`approveComment` 在其事务提交后发事件 → 保证「评论已 VISIBLE 落库」与「通知」的因果顺序（复用 story 已修的 `REQUIRES_NEW` 通知写入约定，避免 AFTER_COMMIT 吞写）。
- 日志：**禁记评论原文/PII**（业务日志护栏）；审核证据（含原文）若需留存走 story 8/§5.5 的审核日志分列管理，非本 story 常规日志。
- 错误统一 RFC9457 ProblemDetail；不外泄堆栈。

---

## 6. 前端设计（petgo_app）

评论输入与展示在 `features/content/presentation/comment_composer.dart` 与 `comment_section.dart`、`domain/comment.dart`、`data/detail_repository.dart`。

- **F13 输入保留（G1）**：`comment_composer.dart:43-78` 现有 `_send` 已在 catch 分支「保留输入 + toast 重试」。需**区分 422 内容拦截**：当 `ProblemDetail.type == COMMENT_BLOCKED`（L1 或 ≥0.8）→ 保留输入 + 不清空 + toast 用**新本地化串**「评论不符合社区规范，请修改后重试」（区别于网络失败的 `commentSendFailed`）。**不区分 L1/≥0.8**（两者用户体验一致：留在输入框改后重试）。新增 ARB 键 `commentModerationBlocked`（`app_en.arb` + `app_id.arb` 同步；印尼语初稿，母语润色归 story 7）。
- **降级挂起（G2/D-CM2）**：DEGRADED 时后端返回 200 + 已创建评论（`UNDER_REVIEW`）。作者端 `bump` 刷新后，因读路径对作者可见 → 评论**照常出现在列表、无任何「审核中」标签**（D-CM2）。**前端无需特殊处理**，也**不得**加审核中角标。他人端不可见（读路径过滤）。
- **巡查下架展示（G3）**：`CommentResponse.moderationStatus == TAKEN_DOWN` 的评论仅会下发给作者本人 → 该评论卡片渲染**「该评论已被违规下架，仅你可见」**灰态标签（复用现有轻提示样式），正文可保留可置灰。他人端根本收不到该行。新增 ARB 键 `commentTakenDownSelfOnly`（两份 ARB）。`REJECTED` 同样仅作者可见——按 D-CM2 挂起期无标签，终态可复用同一「仅你可见」提示或不加标签（择一，AC 标注即可，建议复用下架标签以免作者困惑于「评论去哪了」）。
- **通知（G4）**：无新增前端交互——帖主收到的「有人评论了你的内容」通知走既有通知中心渲染；本 story 只改后端**发送时机**，App 侧不感知差异。
- i18n：新增用户可见串两份 ARB 同步 + `flutter gen-l10n`；**严禁源码硬编码用户可见字符串**。

---

## 7. 验收 AC（前后端分段 · L0/L1/L2 + 环境）

> L0=静态（`mvn -B compile|package` / `flutter analyze|test`，无 DB）；L1=集成（Docker postgres+redis 真跑 + Flyway validate）；L2=端到端（真三方凭证 / 模拟器视觉）。

### A. 后端

- **AC-B1（L0/L1）** `V49` 迁移在真库执行通过、`ddl-auto=validate` 绿：`comments` 有 `moderation_status`(默认 VISIBLE)+`content_version`(默认1)+CHECK+索引；`manual_review_queue` 有 `content_type`(默认 CONTENT_POST)+`content_version`+CHECK。存量评论/队列项被正确 grandfather。*环境：L1 需 Docker。*
- **AC-B2（L0）** `moderateComment` 返回 4 态；stub：命中黑名单→`L1_BLOCKED`、`high-risk-comment`→`HIGH_RISK`、`moderation-degraded`→`DEGRADED`、其余→`PASS`（单测）。
- **AC-B3（L0/L1）** 创建路由：`L1_BLOCKED`/`HIGH_RISK` → 抛 `COMMENT_BLOCKED`(422)、`comments` 无新行、无 `ContentCommentedEvent`、无队列项；`PASS` → 落库 `VISIBLE` + 发事件；`DEGRADED` → 落库 `UNDER_REVIEW` + 入队(`content_type=COMMENT`) + **不发事件**。一/二级均覆盖。*事件/落库断言 L0 可用 slice/mock；端到端 L1。*
- **AC-B4（L0/L1）** 队列多态处置：`approve` COMMENT → 评论转 `VISIBLE` **并发 `ContentCommentedEvent`**（帖主收到「新评论」通知）、无「通过」通知、审计 objectType=COMMENT；`reject`/超时 COMMENT → `REJECTED`、永不发新评论事件、发评论移除通知、审计。帖子项行为回归不变。
- **AC-B5（L1）** `scanTimeouts`：`manual_review_enabled=false` 时**评论**降级项超 3 天仍被扫描处置为 `REJECTED`（不受开关门控）；帖子项维持开关门控（开关关时不处置）。*环境：L1。*
- **AC-B6（L0/L1）** FR-55A：`takedownComment` 空原因→422；正常→`TAKEN_DOWN` + 评论移除通知（deep-link=postId）+ 审计含原因；幂等（重复下架不重复通知）。`restoreComment`→`VISIBLE`、**无通知**、**不重发新评论事件**、审计。
- **AC-B7（L0/L1）** 读路径可见性：`UNDER_REVIEW`/`TAKEN_DOWN`/`REJECTED` 评论对**他人** `topLevel/replies/count` 均不返回；对**作者本人**返回（`TAKEN_DOWN` 带状态字段）。游客(viewerId=null)仅见 `VISIBLE`。`commentCount`/`replyCount` 与渲染列表一致。
- **AC-B8（L0）** 陈旧作废契约（休眠）：`approve/reject` 在 `queueItem.contentVersion != comment.version` 时静默丢弃、不改状态不通知（防御式单测，用构造版本差；无需真实编辑端点）。
- **AC-B9（L0）** 护栏：无 MQ/新中间件引入；日志不含评论原文/PII；`COMMENT_BLOCKED` 走 ProblemDetail 不泄堆栈；`mvn -B package` 绿。

### B. 前端

- **AC-F1（L0）** 发送评论遇 422 `COMMENT_BLOCKED` → **保留输入**、不清空、toast `commentModerationBlocked`；网络/5xx 失败仍走 `commentSendFailed`（widget 测 mock 两类响应）。
- **AC-F2（L0）** `CommentResponse.moderationStatus` 解析入 domain `Comment`；`TAKEN_DOWN` 卡片渲染「仅你可见」标签，`VISIBLE` 无标签，`UNDER_REVIEW` 无「审核中」标签（D-CM2）。widget 测。
- **AC-F3（L0）** 新增 ARB 键中英/印尼两份齐、`flutter gen-l10n` 后 `flutter analyze` 零告警、`flutter test`（含 microcopy 契约测试）绿；无硬编码用户可见串。
- **AC-F4（L2）** 模拟器（Android）联调：正常评论 <0.8 即时可见 + 帖主收到通知；构造降级评论作者可见/他人不可见；下架评论作者见标签、他人不见。*环境：真机/模拟器 + 真后端，回本地。*

### C. 联调

- **AC-C1（L2）** 端到端：命中 L1 词 → 发送失败输入留存；`high-risk-comment` → 发送失败；正常评论 → 帖主实时通知；`moderation-degraded` → 挂起仅作者可见，后台 approve 后帖主才收到通知、他人才看见（验证 G4 时机）。*环境：L1 真库 + 后台 + 模拟器，回本地。*

---

## 8. 依赖与契约

- **依赖 story 1（阿里云 provider）**：提供 `moderateComment` 的真实评分/L1/降级判定。**story 1 未合并不阻塞本 story**——用 §5.1 stub 落地，L0/L1 绿；story 1 合并后仅替换 `ContentModerationService` 内部，本 story 调用契约（`CommentVerdict` 四态）不变。Completion Notes 标注「真实评分/印尼语实测=L2 待 story1 + 本地」。
- **与 story 2（帖子审核可见性模型）共用人工队列**：本 story 扩 `manual_review_queue.content_type`（`CONTENT_POST`/`COMMENT`）、`ManualReviewService` 改多态分派。**若两 story 并行**：`content_type` 列由先合并者引入、另一方按现状顺延号；`ManualReviewService` 分派逻辑二者需协调（本 story 只加 `COMMENT` 分支，不改 `CONTENT_POST` 语义）。story 2 修订 F10（帖子「已发布待审、仅作者可见」中间态）与本 story 评论 `UNDER_REVIEW` 是**同一 D-CM2 可见性不变量**的两面，须同步更新 `CROSS-STORY-DECISIONS.md` F10 条目（追加，勿删原文）。
- **与 story 7（通知 i18n）**：本 story 复用 `CONTENT_REMOVED` 类型 + 临时中文 literal 发评论移除通知；story 7 落 §8.5 印尼语文案、并可引入专用 `COMMENT_REMOVED` 类型（届时替换本 story 的类型/文案，本 story 不做 `notifications.type` CHECK 迁移）。
- **不依赖评论举报（FR-57）**：本 story 的 FR-55A 巡查下架不经举报路径，纯运营主动。
- **契约对外**：新 `ErrorTypes.COMMENT_BLOCKED`（422）；`CommentResponse` 增 `moderationStatus`；`ManualReviewGate.enqueueComment`；`CommentService.approveComment/rejectComment/takedownComment/restoreComment/findModerationSummary`；`AuditActions.COMMENT_TAKEN_DOWN/COMMENT_RESTORED`。

---

## 9. 云端（headless）执行须知

- ✅ **云端可做（L0 全绿）**：`mvn -B package`（含新迁移编译/单测）、`ContentModerationService.moderateComment` stub 单测、创建分派/队列多态/读路径过滤的 slice/mock 单测、`flutter analyze`/`flutter test`/`flutter gen-l10n`/`flutter build apk --debug`。
- ⚠️ **L1（Docker postgres+redis + Flyway validate）**：云沙箱不保证 Docker daemon。默认留本地/CI；云端只跑 L0，Completion Notes 标「L1（迁移 validate / 队列超时扫描 / 读路径可见性真库）待本地/CI」。schema 契约以 CI/L1 绿为准（CHAR(1) 等坑只有 L1 暴露——本 story 列均 VARCHAR(16)/INT，低风险但仍以 L1 为准）。
- ❌ **L2 必回本地**：模拟器视觉（AC-F4/AC-C1）、真三方评分/印尼语（story 1 依赖）、帖主实时通知端到端。
- Flyway 号：占位 V49，**CI 落地按实际合并顺序单调顺延，勿硬编码**（story 1/2 若未合并，号前移）。

---

## 10. 风险与待确认

- **R1（队列超时门控语义）**：`scanTimeouts` 现整体受 `manual_review_enabled` 门控；本 story 要求评论降级项**无条件**被超时扫描。这与 story 8（队列增强）对开关语义的假设需对齐——**建议实现前与 story 8/架构确认**：是否将门控收窄为「仅帖子自动路由」而超时扫描对所有 `content_type` 生效。（默认按本 spec：评论项不受开关门控。）
- **R2（REJECTED 终态展示与通知）**：降级评论被队列拒绝/超时，评论仍仅作者可见（终态）。方案未显式给「降级评论被拒」的用户端文案——本 spec 决策：复用 §8.5 评论移除通知 + 前端复用「仅你可见」标签。**待 PM 确认**该边界（此路径仅在三方降级这一稀有场景出现）。
- **R3（计数一致性）**：viewer 维度计数使帖主与他人看到的 `commentCount` 可能不同（帖主多算自己挂起/下架的评论）。V1 接受此轻微不一致；若需严格统一为「公开可见数」，去掉计数的 viewer 分支即可（但会与作者所见列表数不符）。**默认按本 spec：计数含 viewer 自身非可见评论。**
- **R4（陈旧作废休眠）**：`content_version` 契约就绪但 V1 无评论编辑端点触发，AC-B8 为防御式单测。若后续加评论编辑，须补 `+1` 自增 + 重新送审 + 真实陈旧用例。
- **R5（story 1/2 合并顺序）**：三者共享 `ContentModerationService` 与 `manual_review_queue`；先后合并会引发迁移号/分派逻辑的小冲突，按 §8 协调，Completion Notes 记录实际落地号。
- **R6（安全攸关回归）**：读路径新增可见性过滤是「只升不降」的收敛，勿在任何查询遗漏 `moderation_status` 条件导致挂起/下架评论对他人泄漏；FR-55A 下架/恢复须过 admin 门控 + 审计，勿埋绕过点（AB-3B 安全节点）。

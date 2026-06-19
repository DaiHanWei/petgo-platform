# TailTopia 平台 API 参考文档

> 本文档由后端 Controller + DTO 源码（权威）与前端 mock 契约（UI 大更新后实际预期）反向核对生成，覆盖 `petgo-backend` 全部对外 REST 端点。
> 生成日期：2026-06-19 · 基线：Spring Boot 4 / Java 21 · 前缀统一 `/api/v1`。

---

## 0. 通用约定

### 0.1 命名与序列化
- DB `snake_case` ↔ Java/Dart `camelCase` ↔ JSON `camelCase`（JPA + Jackson 自动桥接）。
- 时间戳 `instant`：ISO-8601 UTC（如 `2026-06-19T12:34:56.789Z`）。
- 日期 `date`：ISO-8601 LocalDate（`YYYY-MM-DD`）。
- 枚举落库 `varchar` + UPPER_SNAKE，JSON 中即枚举 name。
- **Jackson `NON_NULL`**：值为 null 的字段在响应中直接省略，不会出现该键。

### 0.2 鉴权
| 级别 | 含义 |
|---|---|
| **公开** | 无需 JWT，游客可访问（登录用户仍会被自动识别，如 Feed 状态过滤）。 |
| **USER** | 需 `role=USER` 的 JWT；VET token 访问应得 403。 |
| **VET** | `/api/v1/vet/**`，需 `role=VET` 的 JWT；USER token 访问应得 403。 |

认证流程：
1. `POST /auth/google`（用户）或 `POST /auth/vet/login`（兽医）换取 `accessToken` + `refreshToken`。
2. 后续请求头携带 `Authorization: Bearer <accessToken>`。
3. accessToken 过期 → `POST /auth/refresh` 轮换。
4. 登出 → `POST /auth/logout`（USER）/ `POST /vet/logout`（VET）。

### 0.3 错误格式（RFC 9457 ProblemDetail）
所有错误统一返回 ProblemDetail，绝不外泄堆栈：
```json
{
  "type": "https://tailtopia/errors/<slug>",
  "title": "Unprocessable Entity",
  "status": 422,
  "detail": "可读错误说明",
  "instance": "/api/v1/...",
  "traceId": "..."
}
```
常见状态码：`401` 未登录/凭证无效 · `403` 越权/防枚举 · `404` 不存在或已软删 · `409` 冲突 · `422` 校验失败 · `429` 限流。

### 0.4 游标分页信封
列表端点统一返回：
```json
{ "items": [ ... ], "nextCursor": "...", "hasMore": true }
```
`hasMore=false` 时 `nextCursor` 为 null（被 NON_NULL 省略）。`cursor` 一般为末条的 `epochMillis` 字符串或 ISO 时刻。

### 0.5 安全护栏（写代码须遵守）
- 凭证全部 env 注入，绝不入库/落日志。
- 日志严禁记录：Google idToken / JWT / refreshToken / 邮箱(PII) / 健康数据(症状文字) / 签名 URL / 兽医明文密码。
- 对外标识一律用不可枚举 token，不外露自增 id 做枚举入口（名片/通知/分诊越权一律 403/404 防枚举）。
- 作者注销匿名化（NFR-8）：`authorDeleted=true` 时昵称/头像置 null，前端本地化「已注销用户」。

---

## 1. 认证与账号

### 1.1 `POST /auth/google` — Google 登录（公开）
- 限流 10/min（按 IP）。
- 请求体：`idToken`(string, 必填)。
- 响应 `200`：`accessToken`, `refreshToken`, `role`("USER"), `isNewUser`(bool), `onboardingCompleted`(bool), `profile`(UserProfile 见 §2.1)。

### 1.2 `POST /auth/refresh` — 令牌轮换（公开）
- 限流 30/min。请求体：`refreshToken`(string, 必填)。
- 响应 `200`：`accessToken`, `refreshToken`（新对）。

### 1.3 `POST /auth/logout` — 登出（公开）
- 请求体：`refreshToken`(string, 必填)。响应 `204`。
- Story 7.3：作废 refresh，不删数据；与注销严格区分。

### 1.4 `POST /auth/vet/login` — 兽医账密登录（公开）
- 限流 10/min（防爆破）。请求体：`username`(string, 必填), `password`(string, 必填)。
- 响应 `200`：`accessToken`, `refreshToken`, `displayName`, `role`("VET")。
- 错误 `401`：统一 ProblemDetail，不区分账号不存在/密码错（防枚举）。BCrypt 比对，无忘记密码（走 Admin 重置）。

### 1.5 `DELETE /me` — 账号注销（USER）
- 请求体：`confirmation`(string, 须为 `确认注销`)。
- 响应 `202`（受理，异步级联删除/匿名化，D1/D2）。错误 `422`：确认短语缺失/不匹配。
- Story 7.3，双重确认防误触；仅作用于 JWT sub 本人。

---

## 2. 当前用户 / 主体（`/me`）

> 决策 C1：全平台统一用 `/api/v1/me`，不用 `/users/me`。

### 2.1 `GET /me` — 当前用户聚合视图（USER）
响应 `200`（UserProfile）：
| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | long | |
| `nickname` | string? | 昵称 |
| `displayName` | string? | |
| `email` | string | PII，仅本人聚合视图返回 |
| `avatarUrl` | string? | |
| `petStatus` | enum? | `HAS_PET` / `PLANNING` / `ENTHUSIAST`（宠物状态，影响 Feed 过滤） |
| `onboardingCompleted` | bool | 引导完成 |
| `hasPetProfile` | bool | 是否已有宠物档案 |

### 2.2 `PATCH /me` — 更新当前用户（USER）
- 请求体（均可选，部分更新）：`nickname`(≤20 字), `petStatus`(枚举值), `avatarUrl`(≤1024 字)。
- 响应 `200`：更新后的 UserProfile。错误 `422`：超长/非法枚举。首次设置 petStatus 同时置引导完成。

### 2.3 `GET /me/posts` — 我的发布（USER）
- Story 7.1 / FR-36。query：`cursor`?。
- 响应 `200`：游标信封，`items` 为 FeedItem（见 §3.1）；仅当前用户未软删内容，三类混合时间倒序。
- 删除复用 `DELETE /content-posts/{id}`。

### 2.4 `GET /users/{userId}/mini-profile` — 他人迷你主页（公开）
- Story 3.8 / FR-26。响应 `200`：`nickname`?, `avatarUrl`?, `postCount`(long), `isDeactivated`(bool)。
- 已注销：`isDeactivated=true` + 昵称/头像为 null（前端不弹卡）。V1 无关注数、无主页帖列表。

---

## 3. 内容社区

> 类型枚举 `ContentType`：`DAILY`（日常）/ `GROWTH_MOMENT`（成长瞬间）/ `KNOWLEDGE`（科普）。

### 3.1 `GET /content-posts` — Feed 列表（公开）
- Story 3.2。query：`cursor`?, `category`?（`ALL` 或某 ContentType）。
- 响应 `200`：游标信封，`items` 为 **FeedItem**：
  `id`, `authorId`, `authorNickname`?, `authorAvatarUrl`?, `authorDeleted`(bool), `type`, `body`?(全文，前端截 2 行), `firstImageUrl`?, `likeCount`(long), `createdAt`。
- 登录用户按宠物状态硬过滤；游客全显。Redis 不缓存（状态改即时生效）。

### 3.2 `POST /content-posts` — 发布内容（USER）
- Story 2.3。限流 20/min；支持 `Idempotency-Key` 头去重。
- 请求体（ContentPostCreateRequest）：
  `type`(必填), `petId`?(仅 GROWTH_MOMENT 且须属本人), `text`?(≤1000 字), `imageUrls`?(≤9，每个 ≤1024 字符), `eventDate`?(仅 GROWTH_MOMENT，不可未来)。
- 响应 `201`（ContentPostResponse）：`id`, `type`, `petId`?, `text`?, `imageUrls`?, `dangerLevel`?, `eventDate`?, `createdAt`。
- 发布时三方自动审核命中 → `422`（`content-text-blocked` / `content-image-blocked`），不落库。

### 3.3 `GET /content-posts/{id}` — 内容详情（公开）
- Story 3.3。响应 `200`（作者注销仍 200，非 404）/ `404`(不存在或软删)。
- 字段：FeedItem 全字段 + `imageUrls`?(多图轮播), `commentCount`(long), `liked`(bool, 游客 false), `isAuthor`(bool, 删除入口可见性)。

### 3.4 `DELETE /content-posts/{id}` — 删除内容（USER，仅作者）
- Story 3.6。响应 `204`。软删 + 级联清评论/点赞，幂等。

### 3.5 `GET /content-posts/{id}/comments` — 一级评论分页（公开）
- Story 3.3。query：`cursor`?。响应 `200`：游标信封，`items` 为 **Comment**：
  `id`, `authorId`, `authorNickname`?, `authorAvatarUrl`?, `authorDeleted`, `body`, `createdAt`, `replyCount`(int), `replies`(前 3 条二级回复，其 `replyCount`/`replies` 为 null)。

### 3.6 `POST /content-posts/{postId}/comments` — 发一级评论（USER）
- Story 3.5。请求体：`body`(必填, ≤200 字)。响应 `201`：Comment（replyCount=0, replies=[]）。

### 3.7 `GET /comments/{parentId}/replies` — 展开二级回复（公开）
- query：`cursor`?。响应 `200`：游标信封，均为二级回复（replyCount/replies 为 null）。

### 3.8 `POST /comments/{parentId}/replies` — 发二级回复（USER）
- 请求体：`body`(必填, ≤200 字)。响应 `201`：Comment（二级，绝不三级嵌套）。

### 3.9 `DELETE /comments/{id}` — 删除评论（USER，评论作者或内容作者）
- 响应 `204`。删一级评论级联删其二级回复。

### 3.10 `POST /content-posts/{id}/like` · `DELETE /content-posts/{id}/like` — 点赞/取消（USER）
- Story 3.4。响应 `200`：`liked`(bool), `likeCount`(long)。幂等，返回服务端真值供前端校正乐观更新。

### 3.11 `POST /content-posts/{postId}/reports` — 举报内容（USER）
- Story 3.7 / FR-25。请求体：`reasonType`(枚举 `ILLEGAL`/`MISINFO`/`INAPPROPRIATE`/`HARASSMENT`/`OTHER`)。
- 响应 `202`（无体）。写工单 status=PENDING，不触发自动下架。V1 仅内容举报。

---

## 4. 宠物档案 / 成长档案

> 类型枚举 `petType`：`CAT` / `DOG` / `OTHER`。单账号单宠物。

### 4.1 `POST /pet-profiles` — 创建档案（USER）
- Story 2.2。限流 10/min。请求体：`petType`(必填), `name`(必填, ≤20), `birthday`(必填, 不晚于今天), `avatarUrl`?(≤1024), `breed`?(≤60), `intro`?(≤30)。
- 响应 `201`（PetProfile）：`id`, `petType`, `name`, `birthday`, `avatarUrl`?, `breed`?, `intro`?, `cardToken`, `createdAt`。重复建档 → `409`。

### 4.2 `GET /pet-profiles/me` — 我的档案（USER）
- 响应 `200`（PetProfile）/ `404`（无档案，前端据此进建档表单）。

### 4.3 `PATCH /pet-profiles/me` — 编辑档案（USER）
- Story 2.8。限流 10/min。请求体（部分更新，全可选）：`avatarUrl`?, `name`?, `breed`?, `birthday`?, `intro`?。
- 响应 `200`（PetProfile）/ `404`。cardToken 不变；成功后异步重渲名片 OG 图。

### 4.4 `GET /pet-profiles/me/timeline` — 成长时间线（USER）
- Story 2.4。query：`cursor`?, `limit`?(默认 20)。响应 `200` 游标信封 / `404`。条目 **TimelineItem**：
  `kind`(`HAPPY_MOMENT`/`HEALTH_EVENT`), `date`(instant)；
  快乐时刻专属：`eventDate`?, `postId`?, `imageUrls`?, `text`?；
  健康事件专属：`aiLevel`?(GREEN/YELLOW/RED), `symptomSummary`?。

### 4.5 `GET /pet-profiles/me/calendar` — 日历月视图（USER）
- Story 2.4 / F9。query：`year`(必填), `month`(必填, 1-12)。响应 `200` / `404`：
  `year`, `month`, `days`[]：`day`(1-31), `firstImageUrl`?, `hasHappyMoment`(bool), `hasHealthEvent`(bool)。仅返回有记录日。

### 4.6 `GET /pet-profiles/me/day` — 当天详情（USER）
- query：`date`(必填, YYYY-MM-DD)。响应 `200` / `404`：`date`, `items`[]（TimelineItem，按 created_at 正序）。

### 4.7 `GET /pet-profiles/me/archive-stats` — 档案统计栏（USER）
- Story 2.4 AC5。响应 `200` / `404`：`happyMomentCount`(long), `consultCount`(long), `milestoneCompleted`(long), `milestoneTotal`(int, 猫/狗 30、其他 15)。

### 4.8 `GET /pet-profiles/me/milestones` — 里程碑列表（USER）
- Story 8.1/8.2 / FR-42。响应 `200` / `404`：
  `petName`, `petAvatarUrl`?, `completedCount`(int), `totalCount`(int),
  `groups`[]（顺序 L→M→S）：`level`(S/M/L), `completedCount`, `totalCount`, `items`[]：
  `code`(如 `C-S1`), `title`, `level`, `triggerType`(`SYSTEM_AUTO`/`USER_CHECKIN`/`PUSH_PUBLISH`), `completed`(bool), `completedAt`?。

### 4.9 `GET /pet-profiles/me/milestones/checkin-candidates` — 打卡候选（USER）
- Story 8.4。响应 `200` / `404`：`items`[]：`contentId`(long), `firstImageUrl`?, `eventDate`?, `text`?, `linked`(bool，已被其他里程碑关联则 true 置灰)。

### 4.10 `POST /pet-profiles/me/milestones/{code}/check-in` — 用户打卡（USER）
- Story 8.4。请求体：`contentId`(必填, long)。响应 `200`：完成后的里程碑项（同 §4.8 item）。
- 错误：`422`（非打卡类 / 内容非本人）, `409`（已完成 / 内容已被关联）, `404`（无档案）。

### 4.11 `POST /pet-profiles/me/card-shares` — 名片分享信号（USER）
- Story 8.3 / FR-42。无请求体。响应 `204` / `404`。驱动里程碑 `*-S3`「第一次分享名片」自动完成，幂等。

### 4.12 `POST /health-events/archive-decisions` — 问诊存档决策（USER）
- Story 2.5。限流 20/min，幂等。请求体：
  `sourceType`(`AI_TRIAGE`/`VET_CONSULT`, 必填), `sourceRef`(token, ≤64, 必填, 幂等键), `petId`(必填, 须属本人), `decision`(`ARCHIVED`/`SKIPPED`, 必填), `eventDate`?(null=今天), `symptomSummary`?, `aiLevel`?(GREEN/YELLOW/RED), `adviceSummary`?, `imImageRefs`?(IM 图引用列表，ARCHIVED 时复制到私密桶)。
- 响应 `200`：`sourceRef`, `decision`, `alreadyDecided`(bool)。

### 4.13 `GET /health-events/decision` — 查存档决策状态（USER）
- query：`sourceRef`(必填)。响应 `200`：`decided`(bool)。用于「只问一次」（FR-16）。

### 4.14 `GET /p/{cardToken}` — 名片分享页（公开，HTML）
- Story 2.6。**服务端直出 Thymeleaf HTML**（非 JSON）。失效（token 不存在/账户注销）→ `404` 友好失效页 + `noindex`（防枚举）。
- 内容：Hero（头像+名+陪伴天数）、里程碑徽章、故事数字、最近里程碑动态、快乐时刻照片流（最近 5 条，对外图已去 EXIF）、双 CTA 平台分流、深链 `tailtopia://card/{cardToken}`。不含日常/科普/健康事件详情（隐私边界）。

---

## 5. AI 智能分诊

> 危险等级 `DangerLevel`：`GREEN` / `YELLOW` / `RED`（红色态零变现）。

### 5.1 `POST /triage` — 提交分诊（USER）
- Story 4.1。限流 10/min；支持 `Idempotency-Key` 头。
- 请求体：`symptomText`(必填, 1-2000 字), `imageObjectKeys`?(私密桶 key 列表，≤3，每个 ≤512), `petId`?(预留)。
- 响应 `202`：`triageId`(long), `status`(`PENDING`)。

### 5.2 `GET /triage/{id}` — 查分诊结果（USER，仅本人）
- 短轮询。响应 `200` / `403`（越权或不存在，防枚举）。
- 字段：`status`(`PENDING`/`PROCESSING`/`DONE`/`FAILED`)；以下仅 `DONE` 返回：
  `dangerLevel`, `advice`, `medicationRef`?, `disclaimer`,
  `observation`?（仅 YELLOW 条件倒计时三要素）：`indicators`[], `timeWindow`, `escalationTriggers`[]。

---

## 6. 兽医咨询（用户侧）

> 会话状态机 `SessionStatus`：`WAITING` → `IN_PROGRESS` → `PENDING_CLOSE` → `CLOSED`；分支 `WAITING→CANCELLED`、`IN_PROGRESS/PENDING_CLOSE→INTERRUPTED`、`IN_PROGRESS→WAITING`(退单)。
> `ConsultSource`：`DIRECT` / `AI_UPGRADE`。`ClosedReason`：`RATED`/`UNRATED`。`InterruptReason`：`VET_BANNED`。等待超时 60s（仅弹层，不迁状态）；评分门 30min。

**ConsultSessionResponse 通用体**：`id`, `status`, `source`, `vetId`?, `waitingElapsedSeconds`(long), `timedOut`(bool), `alreadyActive`(bool), `imConversationId`?, `closedReason`?, `interruptedReason`?。

### 6.1 `GET /consult/availability` — 咨询可用性（USER）
- Story 5.2。响应 `200`：`vetOnline`(bool), `expectedWindow`?(离线时文案 key，前端映射 l10n)。仅布尔门控，不透传在线人数。

### 6.2 `POST /consult-sessions` — 发起会话（USER）
- Story 5.3。请求体：`source`?(`DIRECT` 默认/`AI_UPGRADE`), `triageTaskId`?(AI_UPGRADE 必填)。
- 响应 `200`：ConsultSessionResponse。若已有占用态会话则返回现有 + `alreadyActive=true`。

### 6.3 `GET /consult-sessions/active` — 当前占用态会话（USER）
- 响应 `200`（WAITING/IN_PROGRESS/PENDING_CLOSE）/ `204`（无）。

### 6.4 `GET /consult-sessions/{id}` — 轮询会话（USER）
- 响应 `200`：ConsultSessionResponse。据 `timedOut`/`waitingElapsedSeconds` 决定超时弹层。

### 6.5 `PATCH /consult-sessions/{id}/continue-waiting` — 继续等待（USER）
- 响应 `200`。重置等待计时（WAITING→WAITING）。

### 6.6 `DELETE /consult-sessions/{id}` — 取消会话（USER）
- 响应 `200`：状态转 `CANCELLED`，出队。

### 6.7 `POST /consult-sessions/{id}/rating` — 提交评分（USER）
- Story 5.6。请求体：`stars`(必填, 1-5), `comment`?(≤100 字)。
- 响应 `200`：PENDING_CLOSE→CLOSED，`closedReason=RATED`。

### 6.8 `PATCH /consult-sessions/{id}/rating-prompted` — 标记补弹已展示（USER）
- 响应 `204`。ratingPromptState→PROMPTED，不再补弹。

### 6.9 `GET /consult-sessions/pending-rating` — 待补弹评分会话（USER）
- 响应 `200`（CLOSED 且 ratingPromptState=PENDING）/ `204`（无）。进问诊页补弹一次。

### 6.10 `GET /consult/history` — 问诊历史聚合（USER）
- Story 5.8。query：`cursor`?, `limit`?(默认 20，最大 50)。响应 `200` 游标信封。条目 **ConsultHistoryItem**：
  `type`(`AI`/`VET`), `date`(instant)；
  AI 专属：`triageId`, `dangerLevel`(GREEN/YELLOW), `symptomSummary`；
  VET 专属：`sessionId`, `vetDisplayName`, `sessionSummary`?, `userStars`?(1-5/null), `archived`?(bool), `terminalState`(`CLOSED`/`INTERRUPTED`), `closedReason`?, `interruptedReason`?。

---

## 7. 兽医工作台（兽医侧，`/vet/**`，role=VET）

### 7.1 `GET /vet/me` — 兽医自身视图
- Story 5.1。响应 `200`：`id`, `displayName`, `status`(如 `ACTIVE`)。不含 username/passwordHash。

### 7.2 在线态 / 心跳 / 登出（Story 5.2）
| 端点 | 方法 | 请求 | 响应 |
|---|---|---|---|
| `/vet/online-status` | `PUT` | `online`(bool, 必填) | `200`：`online`, `status`(ONLINE/OFFLINE) |
| `/vet/online-status` | `GET` | — | `200`：`online`, `status` |
| `/vet/heartbeat` | `POST` | — | `200`：`online`, `status`（续 Redis TTL） |
| `/vet/logout` | `POST` | — | `204`（登出即离线） |

### 7.3 `GET /vet/consult-sessions/waiting` — 待接单池
- Story 5.5。响应 `200`：**VetInboxItem**[]：`sessionId`, `source`, `aiDangerLevel`?(仅 AI_UPGRADE), `symptomPreview`?(≤40 字截断), `imageCount`(int), `waitingElapsedSeconds`(long), `petName`?, `petSpecies`?(CAT/DOG/OTHER), `petAgeMonths`?(由生日折算), `ownerHandle`?(机主昵称)。
  > 宠物身份经跨模块只读端口富化（`pet_profiles` JOIN + 用户昵称）；注销匿名化后会话已剥 user_id → 这些字段为 null。**不含 `petSex`**（建档不收集性别，前端兜底隐藏）。

### 7.4 `POST /vet/consult-sessions/{id}/accept` · `/end` · `/release` — 写路径
- Story 5.5/5.6。响应 `200`：**VetSessionView 基础视图**：`id`, `status`, `source`, `userId`?, `imConversationId`?, `hasAiContext`(bool)。
- **写路径不富化宠物身份**（`petName`/`petSpecies`/`petAgeMonths`/`ownerHandle` 省略）：CAS 写事务已提交，响应不挂跨模块身份查询，避免富化失败把已成功的写翻成 500（幽灵接单）。前端接单后跳会话页，经 §7.5 单独拉富化顶栏。
- 接单并发由 `@Version` 乐观锁裁决，仅一人成功，余者「已被接走」；WAITING→IN_PROGRESS + 建 IM C2C 会话。

### 7.5 `GET /vet/consult-sessions/{id}` — 会话视图（读路径，富化）
- 响应 `200`：VetSessionView，含 `imConversationId`（供 SDK 加载对话）+ **宠物身份顶栏字段**（`petName`/`petSpecies`/`petAgeMonths`/`ownerHandle`，注销匿名化兜底 null；不含 `petSex`）。

### 7.6 `GET /vet/consult-sessions/{id}/ai-context` — AI 上下文
- Story 5.4。响应 `200`（ConsultAiContextResponse）：`hasAiContext`(bool), `dangerLevel`?, `symptomText`?(健康数据，日志严禁明文), `imageUrls`?(私密桶短 TTL 签名 URL，现签不入库)。DIRECT 会话 → `hasAiContext=false`。

### 7.7 `GET /vet/consult-sessions/{id}/assist` — AI 辅助
- Story 5.5 / FR-5。响应 `200`：`aiReferenceReply`(参考回复，NFR-9 不自动发，兽医采用后可编辑), `historySummaries`[](冷启动空库返回 [])。

### 7.8 `POST /vet/consult-sessions/{id}/end` — 结束会话
- Story 5.6。响应 `200`：VetSessionView，`status`=PENDING_CLOSE，启动 30min 评分门。

### 7.9 `POST /vet/consult-sessions/{id}/release` — 退单（决策 F11）
- Story 5.3 R2。响应 `200`：IN_PROGRESS→WAITING，解绑兽医 + 清 IM + 重置等待计时，releaseCount+1（>2 为异常信号）。仅本会话接单兽医可退。

### 7.10 `POST /vet/consult-sessions/{id}/notify-reply` — 回复后通知用户
- Story 6.2 / FR-22A。响应 `204`。兽医 IM 发完消息后 ping，触发用户「有新回复」推送。

### 7.11 `GET /vet/consult-sessions/in-progress` — 工作台「进行中」Tab（VET）
- 响应 `200`：**VetActiveItem**[]（当前兽医活跃态会话 IN_PROGRESS/PENDING_CLOSE）：`sessionId`, `source`, `petName`?。
  > **不含 `unread`/`lastMessage`**——这两项是腾讯 IM 侧状态，后端 V1 不入库；客户端直接读 IM SDK 取未读数与最近一条消息。

### 7.12 `GET /vet/consult-sessions/history` — 工作台「历史」Tab（VET）
- 响应 `200`：**VetHistoryItem**[]（当前兽医终态会话 CLOSED/INTERRUPTED，时间倒序）：
  `sessionId`, `petName`?, `petSpecies`?, `ownerHandle`?, `date`(instant), `stars`?(1-5/null), `reviewText`?(评价文本), `terminalState`(CLOSED/INTERRUPTED), `summary`?(V1 取 AI 症状快照，DIRECT 无则 null), `dangerLevel`?(AI 初判 GREEN/YELLOW)。

---

## 8. IM / 通知 / 媒体 / 版本

### 8.1 `GET /im/usersig` — IM UserSig 签发（已登录）
- Story 5.5。响应 `200`：`imUserId`, `userSig`(短时), `sdkAppId`, `expireSeconds`(long)。
- MAU 硬门控：role=VET 恒签；role=USER 须有「进行中/待关闭」会话，否则 `403`。SecretKey 仅服务端持有。

### 8.2 通知中心（Story 6.6，USER）
> 类型 `NotificationType`：`VET_REPLY` / `CONSULT_CLOSED` / `CONTENT_LIKED` / `CONTENT_COMMENTED` / `NEW_CONSULT_REQUEST` / `PET_BIRTHDAY` / `COMPANION_ANNIVERSARY` / `MILESTONE_NODE`。

| 端点 | 方法 | 请求 | 响应 |
|---|---|---|---|
| `/notifications` | `GET` | `cursor`?, `limit`?(默认 20，最大 50) | `200` 游标信封；item：`type`,`title`?,`body`?,`deepLinkType`?,`deepLinkToken`?,`read`(bool),`createdAt` |
| `/notifications/unread-count` | `GET` | — | `200`：`count`(long) |
| `/notifications/{token}/read` | `POST` | path `token` | `200`（标记单条已读） |
| `/notifications/read-all` | `POST` | — | `200`（全部已读） |

### 8.3 `POST /media/upload-url` — 预签名上传 URL（USER）
- Story 2.1。限流 30/min。请求体：`scope`(`PUBLIC`/`PRIVATE`, 必填), `contentType`?(如 `image/jpeg`)。
- 响应 `200`：
  `uploadUrl`(预签名 PUT URL), `objectKey`(服务端生成不可枚举 key), `method`("PUT"),
  `headers`(客户端 PUT 须原样携带；`Content-Type` 签入值；公开域含 `x-oss-object-acl: public-read`，私密域省略),
  `publicUrl`?(仅 PUBLIC 返回；PRIVATE 为 null)。
- 单桶（`tailtopia`）+ 对象级 ACL：`public/` 前缀对象 public-read 经 CDN 分发；`private/` 仅短 TTL 签名 URL 访问。真 AccessKey 始终只在后端，签名 URL 不进 INFO 日志。

### 8.4 `GET /app-version` — App 版本信息（公开）
- Story 6.5。响应 `200`：`latestVersion`, `minSupportedVersion`, `iosStoreUrl`?, `androidStoreUrl`?。前端拿不到默认放行（不阻断启动）。

---

## 附录 A：端点速查

| # | 方法 | 路径 | 鉴权 |
|---|---|---|---|
| 1 | POST | `/auth/google` | 公开 |
| 2 | POST | `/auth/refresh` | 公开 |
| 3 | POST | `/auth/logout` | 公开 |
| 4 | POST | `/auth/vet/login` | 公开 |
| 5 | DELETE | `/me` | USER |
| 6 | GET / PATCH | `/me` | USER |
| 7 | GET | `/me/posts` | USER |
| 8 | GET | `/users/{userId}/mini-profile` | 公开 |
| 9 | GET | `/content-posts` | 公开 |
| 10 | POST | `/content-posts` | USER |
| 11 | GET / DELETE | `/content-posts/{id}` | 公开 / USER |
| 12 | GET / POST | `/content-posts/{id}/comments` | 公开 / USER |
| 13 | GET / POST | `/comments/{parentId}/replies` | 公开 / USER |
| 14 | DELETE | `/comments/{id}` | USER |
| 15 | POST / DELETE | `/content-posts/{id}/like` | USER |
| 16 | POST | `/content-posts/{postId}/reports` | USER |
| 17 | POST | `/pet-profiles` | USER |
| 18 | GET / PATCH | `/pet-profiles/me` | USER |
| 19 | GET | `/pet-profiles/me/timeline` | USER |
| 20 | GET | `/pet-profiles/me/calendar` | USER |
| 21 | GET | `/pet-profiles/me/day` | USER |
| 22 | GET | `/pet-profiles/me/archive-stats` | USER |
| 23 | GET | `/pet-profiles/me/milestones` | USER |
| 24 | GET | `/pet-profiles/me/milestones/checkin-candidates` | USER |
| 25 | POST | `/pet-profiles/me/milestones/{code}/check-in` | USER |
| 26 | POST | `/pet-profiles/me/card-shares` | USER |
| 27 | POST | `/health-events/archive-decisions` | USER |
| 28 | GET | `/health-events/decision` | USER |
| 29 | GET | `/p/{cardToken}` | 公开(HTML) |
| 30 | POST | `/triage` | USER |
| 31 | GET | `/triage/{id}` | USER |
| 32 | GET | `/consult/availability` | USER |
| 33 | POST / GET | `/consult-sessions` `/consult-sessions/active` | USER |
| 34 | GET / DELETE | `/consult-sessions/{id}` | USER |
| 35 | PATCH | `/consult-sessions/{id}/continue-waiting` | USER |
| 36 | POST | `/consult-sessions/{id}/rating` | USER |
| 37 | PATCH | `/consult-sessions/{id}/rating-prompted` | USER |
| 38 | GET | `/consult-sessions/pending-rating` | USER |
| 39 | GET | `/consult/history` | USER |
| 40 | GET | `/vet/me` | VET |
| 41 | GET / PUT | `/vet/online-status` | VET |
| 42 | POST | `/vet/heartbeat` · `/vet/logout` | VET |
| 43 | GET | `/vet/consult-sessions/waiting` · `/in-progress` · `/history` | VET |
| 44 | GET / POST | `/vet/consult-sessions/{id}` (+ `/accept` `/end` `/release` `/notify-reply`) | VET |
| 45 | GET | `/vet/consult-sessions/{id}/ai-context` · `/assist` | VET |
| 46 | GET | `/im/usersig` | 已登录 |
| 47 | GET | `/notifications` (+ `/unread-count` `/read-all` `/{token}/read`) | USER |
| 48 | POST | `/media/upload-url` | USER |
| 49 | GET | `/app-version` | 公开 |

---

## 附录 B：前端 mock 与后端契约的对齐状态

UI 大更新后 mock 一度走在前面。2026-06-19 已按「基于前端调整后端」收口，状态如下：

1. ✅ **`/vet/consult-sessions/waiting` + `VetSessionView`**：后端已加 `petName/petSpecies/petAgeMonths/ownerHandle`（JOIN `pet_profiles` + 用户昵称富化）。**`petSex` 不返回**——建档不收集性别，前端工作台兜底隐藏。
2. ✅ **`/vet/consult-sessions/in-progress` + `/history`**：后端已新建两端点（VetActiveItem / VetHistoryItem，§7.11/§7.12）。
3. ✅ **`unread` / `lastMessage`**（进行中卡片）：后端**不返回**——IM 侧状态，客户端读腾讯 IM SDK。前端 `VetActiveItem` 已标注二者来自 IM；卡片在空值时优雅降级（不显角标/最近消息行）。mock 仍带占位值兼任 IM 离线演示。
4. ℹ️ **`GET /me/posts`**：mock 仅回 `{id,type,body,firstImageUrl}`，后端回完整 FeedItem；前端忽略多余字段，无需改动。
5. ℹ️ **`/media/upload-url`**：mock 下 `uploadUrl` 为占位（`_MockOssUploader` 拦截真实 PUT），`publicUrl` 仅 PUBLIC 返回——与后端语义一致，仅地址为假。
6. ℹ️ **错误态**：mock 用 `DEV_STATE` 注入空态/失败态（`feed-empty`/`feed-error`/`triage-red|yellow|green`/`consult-waiting`/`rate`/`notif-empty`/`timeline-empty`）及 `DEV_NOPET`（`/pet-profiles/me` 返 404），仅前端联调用，非真实后端行为。

> **前端已对齐**（2026-06-19）：`VetInboxItem`/`VetSession` 删除 `petSex` 字段，工作台身份行去性别段；进行中卡片空 `lastMessage`/`unread` 优雅降级；mock 同步去 `petSex`。契约由 `test/support/mock_contract_test.dart` 钉死（含「后端不下发 petSex」金标）。

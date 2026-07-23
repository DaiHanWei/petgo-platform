---
title: 昵称/宠物名异步审核 + 重置默认编码名（内容审核 story 4）
type: spec-dev-story
slug: content-moderation-name-review
status: ready-for-dev
version: v1.0.1
source:
  - _bmad-output/planning-artifacts/content-moderation-plan-v1.0.1.md（§3.3 / §2.2 / §8.1 / §8.2 / §3.4 可见窗口期 / §4.3 / §5.4-5.5）
  - _bmad-output/implementation-artifacts/spec-content-moderation-overview.md（决策 D-CM3 / D-CM4 / D-CM5 / D-CM6）
depends_on:
  - spec-content-moderation-aliyun-provider.md（story 1：三方文字审核评分接口 + fail-closed 降级）
flyway_placeholder: V50   # 占位；CI 落地时按实际合并顺序单调顺延，勿硬编码
created: 2026-07-08
owner: Dai
communication_language: 中文
codebases: [petgo-backend, petgo_app]
relatedFR: [FR-0E, FR-11]
---

# 昵称/宠物名异步审核 + 重置默认编码名

> 本 story 属「内容审核补充规范 v1.0.1」落地批的 **story 4**。权威源见 frontmatter；遇代码契约冲突以 `CROSS-STORY-DECISIONS.md` 为准，产品规则以方案 v1.0.1 为准。
> 本文件自包含（含云端执行须知），可直接交 `bmad-dev-story` / 云端 session 执行。

---

## 1. 背景与范围

### 1.1 要做什么
给**用户昵称**（`FR-0E`，注册/我的资料）与**宠物名字**（`FR-11`，宠物档案）补上此前完全缺失的内容审核：

- **先放行、后异步审核**：提交/编辑后名称**立即生效**、用户与他人正常可见（与帖子「先审后发」相反，见 §1.3 可见窗口期权衡）。
- **异步评分路由**：`@Async` + DB 状态机调 story 1 三方文字审核 → 风险评分 → 低风险自动过 / 中风险入人工队列 / 高风险入队标高优先。
- **违规处置＝重置为系统默认编码名**：判定违规后把违规名**直接替换为**系统生成的默认编码名（昵称 `user_<编码>`、宠物名 `Pet_<编码>`），对**所有人（含本人）**展示默认名。**无 7 天期限、无功能限权、无 `***` 屏蔽**。
- **可随时重设 + 编辑重审 + 陈旧作废**：用户随时可重设新名 → 按 D-CM3 重新送审；旧的在途审核结果按版本绑定静默丢弃、旧队列条目移除。
- **违规重置后推送通知**：§8.1（昵称）/ §8.2（宠物名）印尼语原文。

### 1.2 关键区分 —— 违规重置 ≠ 注销匿名化（D-CM4，必须严守）
项目里存在**两套独立的名称占位机制，dev 绝不可复用同一逻辑**：

| 机制 | 触发 | 展示串 | 归属 |
|---|---|---|---|
| **违规重置（本 story）** | 名称被判定违规 | 系统生成的**唯一编码名** `user_8f3a2b` / `Pet_8f3a2b`（真实写入 `nickname` / `pet_profiles.name` 列，是一个真实名字，用户可再改） | 违规处置 |
| **注销匿名化**（Story 7-3 / 决策 D1，已上线，勿动） | 账号注销 | i18n 文案「已注销用户」（**展示层**替换，非改真实列） | PDP 合规 |

> 本 story **只碰违规重置**。不得改动 Story 7-3 的注销匿名化路径；不得把「已注销用户」文案与 `user_<编码>` 混用；两者判定条件、写入位置、通知均不同。

### 1.3 可见窗口期（方案 §3.4，有意接受的权衡）
名称采「先放行、后异步审核」→ 违规名在**异步审核完成前对所有用户可见**，存在污染窗口期（时长＝异步队列延迟）。此为 V1.1.0 有意接受的权衡（换正常用户零等待），问题内容由异步审核 + 举报兜底。本 story 不引入任何「审核中」中间态标签（作者视角与正常改名无异）。

### 1.4 不在本 story 范围
- 头像/宠物头像图像审核（→ story 5 `spec-content-moderation-avatar-review.md`，机制同构、审核对象为图像）。
- 宠物品种（自由输入）、宠物一句话介绍（选填）—— 方案 §1 明确低风险不主动扫描，走举报兜底。
- 运营后台队列 UI 的**优先级排序/处置按钮页面**（→ story 8 `admin-console`）；本 story 只落**后端处置服务 + 队列数据 + 一个可被 story 8 复用的处置入口**，不做 Thymeleaf 页面。
- 账号维度违规累计计数（→ story 9）；本 story 在处置时预留可被 story 9 订阅的领域事件，但不建计数表。

---

## 2. 现状基线（2026-07-08 核实，file:line）

| 事实 | 证据 |
|---|---|
| 昵称更新点，**无任何审核**，仅非空 + ≤20 校验后 `setNickname` | `petgo-backend/.../auth/service/MeService.java:39-48`（`user.setNickname(nn)` 在 `:47`） |
| 昵称初值＝Google `displayName` | `auth/domain/User.java:86`（`u.nickname = displayName`）；`User` 同时有独立 `displayName`(:40) 与 `nickname` 两列，本 story 只审 `nickname` |
| 宠物名更新点，**无任何审核**，仅非空校验后 `setName` | `petgo-backend/.../profile/service/ProfileService.java:110-116`（`profile.setName(n)` 在 `:115`） |
| `auth` / `profile` 包 grep `moderat` **零命中** | 审核基建全在 `admin/moderation/**` 与 `content/**`，名称侧未接线 |
| 现有 `manual_review_queue`（V41）是**帖子专用** | `admin/moderation/domain/ManualReviewItem.java`：`content_id`→content_posts、无 `content_type`、无 `priority`、无风险分。**不复用于名称**（见 §5.1） |
| 三方文字审核评分接口 | 由 **story 1** 提供（本 story 依赖其 client + fail-closed 降级契约） |
| `@Async` + DB 状态机既有范式可照抄 | `triage/domain/TriageStatus.java`、`admin/anomaly/service/ConsultAnomalyService.java`、`content/event/ContentLikedEvent.java`（`@Async` 事件监听） |
| Flyway 已到 **V46**（`V46__add_vet_qualification_strv.sql`） | `db/migration/`；本 story 增量占位 **V50** 顺延 |

---

## 3. 目标与非目标

### 3.1 目标
1. 昵称/宠物名**首次设置与每次编辑**都触发异步审核（D-CM3 编辑重审）。
2. 异步 `@Async` + DB 状态机评分路由：`[0,0.6)` 自动过、`[0.6,0.8)` 中风险入人工队列、`≥0.8` 入队标 `HIGH` 优先。
3. 三方超时/报错/宕机/配额耗尽 → **fail-closed**：不自动判过，转人工队列（D-CM5 / §4.3）。异步任务失败自动重试 ≥3 次指数退避，仍失败落人工队列 + 告警。
4. 违规判定 → 重置为**唯一、不可枚举推断**的系统默认编码名，`is_system_default_name=true`，对所有人（含本人）展示。
5. 用户重设新名 → 清 `is_system_default_name`、重新送审；旧在途结果**陈旧作废**（静默丢弃 + 移除旧队列条目）。
6. 违规重置后推送 §8.1 / §8.2 通知。

### 3.2 非目标
- 不做 7 天期限、逾期限权、`***` 屏蔽（方案 §3.3 已明确取消）。
- 不做申诉入口（方案 §5.5：V1.1.0 无申诉）。
- 不改注销匿名化（D-CM4）。
- 不实现后台队列页面 / 违规计数表（story 8 / 9）。
- 不对宠物品种、一句话介绍做扫描。

---

## 4. 数据与迁移（Flyway 占位 V50）

> ⚠️ 迁移已冻结到 V46，本 story **新起 ALTER/CREATE**，占位号 `V50__`；实际号 CI 落地按合并顺序单调顺延（决策 E2），勿硬编码。
> ⚠️ `length=1` 列禁建 `VARCHAR(1)`（Hibernate 映 `CHAR(1)` → validate 全红）；本迁移**无** length=1 列。
> ⚠️ `ddl-auto=validate`：实体映射须与迁移逐列对齐。

### 4.1 `V50__init_name_moderation.sql`

**(a) 新表 `name_moderation_records`**（名称审核记录 ＝ 名称侧自己的审核状态机 + 人工队列，**不复用帖子 `manual_review_queue`**，理由见 §5.1）

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `bigserial` PK | |
| `target_type` | `varchar(16)` NOT NULL | `NICKNAME` / `PET_NAME`（UPPER_SNAKE 落库） |
| `target_ref_id` | `bigint` NOT NULL | 昵称＝`users.id`；宠物名＝`pet_profiles.id`。**内部外键值，绝不外露**（外露走各自 token） |
| `revision` | `bigint` NOT NULL | 该 target 的**审核版本号**，每次新提交 `+1`，陈旧判定的版本键（见 §5.4） |
| `submitted_value` | `text` NOT NULL | 送审的名称原文。**审核证据**（可能含 PII）→ 依 §5.5 访问控制 + 加密存储 + 最小授权，**严禁写入业务日志** |
| `status` | `varchar(24)` NOT NULL | 状态机见 §5.2：`SCORING`/`AUTO_PASSED`/`MANUAL_PENDING`/`RESOLVED_PASS`/`RESOLVED_VIOLATION`/`SUPERSEDED`/`FAILED_TO_QUEUE` |
| `priority` | `varchar(8)` NOT NULL DEFAULT `'NORMAL'` | `NORMAL` / `HIGH`（`≥0.8`） |
| `risk_score` | `numeric(4,3)` NULL | 三方评分 `0.000–1.000`；降级/未评分为 NULL |
| `decided_by` | `bigint` NULL | 人工处置的 `admin_accounts.id`（自动过/降级为空） |
| `decided_at` | `timestamptz` NULL | |
| `decision_reason` | `varchar(64)` NULL | 违规类别枚举（不外泄给用户，仅运营记录） |
| `retry_count` | `int` NOT NULL DEFAULT `0` | 异步调三方重试次数 |
| `submitted_at` | `timestamptz` NOT NULL | |
| `created_at` | `timestamptz` NOT NULL DEFAULT `now()` | |
| `updated_at` | `timestamptz` NOT NULL DEFAULT `now()` | |

索引：`(target_type, target_ref_id)`、`(status)`（队列扫描）、部分索引 `WHERE status='MANUAL_PENDING'`（队列列表）。
> 时间戳一律 `timestamptz` UTC；枚举 `varchar`＋UPPER_SNAKE；表复数 snake_case（护栏一致）。

**(b) `ALTER users ADD COLUMN is_system_default_name boolean NOT NULL DEFAULT false`**
昵称当前是否为违规重置生成的默认编码名。

**(c) `ALTER pet_profiles ADD COLUMN is_system_default_name boolean NOT NULL DEFAULT false`**
宠物名当前是否为违规重置生成的默认编码名。

> 通知类型枚举 `NAME_RESET` / `PET_NAME_RESET` 的 CHECK 扩展**归 story 7**（V53）统一收口；本 story 若需先行发通知，dev 需与 story 7 对齐类型串命名（见 §8），不要在 V50 里私自扩 CHECK 以免与 V53 撞。

---

## 5. 后端设计（petgo-backend，`com.tailtopia`）

### 5.1 为何新建 `name_moderation_records` 而不复用 `manual_review_queue`
现有 `ManualReviewItem`（V41）语义是「帖子挂起态：`content_id`→content_posts、`content` 未过审前仅作者可见、通过才转公开」。名称是**先放行**（无挂起态）、`target_ref_id` 指向 `users`/`pet_profiles`（非 content_posts）、且要记录 `submitted_value`（审核证据）与 `revision`（陈旧作废版本键）。硬塞会污染帖子队列语义、`content_id` 指向歧义。故名称侧独立建表，自带状态机与人工队列视图。story 8 的后台队列页对两张表**分别读取**渲染。

### 5.2 状态机（`NameModerationStatus`）
```
                      ┌─(score<0.6)──────────────► AUTO_PASSED (终态, 静默)
 提交/编辑 → SCORING ─┼─(0.6≤score<0.8)─► MANUAL_PENDING(NORMAL) ─┬─运营 PASS──► RESOLVED_PASS (终态, 静默)
   (revision++)       ├─(score≥0.8)─────► MANUAL_PENDING(HIGH)   ─┴─运营 VIOLATION► RESOLVED_VIOLATION → 重置默认名 + 通知
                      └─(三方超时/4xx5xx/宕机/配额, 重试耗尽)─► MANUAL_PENDING (fail-closed, risk=NULL)
 任意非终态 record 被新提交取代 ─► SUPERSEDED (终态, 静默丢弃; 若在 MANUAL_PENDING 则移出队列)
```
- 幂等：仅非终态可被处置/超时/取代；`RESOLVED_*` / `AUTO_PASSED` / `SUPERSEDED` 不可再变。
- **注意**：名称侧**无「立即生效前的 L1 硬拦截」**（先放行模型），所有非低分一律入人工队列由运营判定违规，无「系统自动违规重置」的纯评分路径（评分只决定是否入队与优先级，违规与否由运营裁定）。「系统判定违规」在本 story 指**运营在队列上判 VIOLATION 后由系统执行重置动作**，非评分自动重置。

### 5.3 触发点接线（先放行 + 异步送审）
在**同一事务提交后**触发异步审核，避免读到未提交值（照抄 `content/event/ContentLikedEvent.java` 的 `@Async` + `AFTER_COMMIT` 范式；注意 [notify AFTER_COMMIT 事务吞写] 教训 —— 异步内新事务用 `REQUIRES_NEW` 确保 INSERT 落库）。

- **昵称**：`MeService.updateMe`（`:47` `setNickname` 处）—— 当 `req.nickname()` 使昵称**实际变化**时：
  1. 正常 `setNickname(nn)` 立即生效（保持现有行为）；
  2. 若原本 `is_system_default_name=true` 且用户设了新名 → 置 `false`（用户主动脱离默认名）；
  3. 发布 `NameSubmittedEvent(NICKNAME, userId, nn)`（事务提交后 `@Async` 消费）。
- **宠物名**：`ProfileService.update`（`:115` `setName` 处）同构；另**创建档案**路径（`ProfileService.create` / 首次落 `name`）也要发事件（首次提交同样送审）；昵称首次落库同理（注册流 / Google `displayName` 初值也应送审 —— 见 §10 待确认是否对存量/初值补审）。
- 事件消费 `NameModerationService.submitForReview(targetType, refId, value)`：
  1. `revision = 上一条同 target 记录的 revision + 1`；把该 target 所有非终态旧记录置 `SUPERSEDED`（陈旧作废，若在 `MANUAL_PENDING` 一并移出队列）；
  2. 新建 record `status=SCORING, revision, submitted_value=value`；
  3. `@Async` 调 story 1 三方文字审核 → 按 §5.2 路由。

### 5.4 陈旧结果作废（D-CM3，版本绑定）
- 版本键＝`(target_type, target_ref_id, revision)`；每条 record 绑定其 `revision`。
- 三方评分/运营处置结果落库前，**统一校验**：该 record `status` 是否仍为可推进态 **且** 未被更高 `revision` 取代（即它仍是该 target 的最新非终态记录）。若已 `SUPERSEDED`/被取代 → **静默丢弃**结果（不改名、不通知、不处置）。
- 「A→B→C」：B 的 record 在 C 提交时即被置 `SUPERSEDED` 并移出队列，B 的三方结果回来时按上条丢弃；只有 C 的 record 生效。**不用字符串比对**（`revision` 单调，能正确处理 A→B→A 回退）。

### 5.5 违规重置 —— 默认编码名生成（`DefaultNameGenerator`）
- 昵称：`user_` + 6~8 位小写十六进制随机后缀；宠物名：`Pet_` + 同规则。
- **不可枚举推断**（护栏）：后缀用 `SecureRandom`，**不得**由 `user.id` / `pet_profiles.id` / 时间戳派生（否则可反推）。
- **唯一**：生成后校验冲突（昵称查 `users.nickname`、宠物名查 `pet_profiles.name` 是否已存在该值），命中则重生成（上限重试，如 5 次），保证实际唯一。
- 重置动作 `NameModerationService.resetToDefault(record)`（运营判 `VIOLATION` 时调）：
  1. 生成默认名 → 写入真实列（`user.setNickname` / `profile.setName`）；
  2. 置对应 `is_system_default_name=true`；
  3. record → `RESOLVED_VIOLATION`，记 `decided_by/decided_at/decision_reason`；
  4. 发 `NameResetEvent`（供 §8 通知 + story 9 违规计数订阅）。
- 重置对**所有人（含本人）**展示默认名（真实列已改，App/分享页/作者档案统一读同一列，天然一致），无 `***` 屏蔽、无限权。

### 5.6 通知（违规重置后，负向结果 → 推送；D-CM6）
- `NameResetEvent` → `NotificationService` 生成站内通知：
  - `NICKNAME` → 类型 `NAME_RESET`，文案 §8.1（印尼语原文 `Nama penggunamu telah direset ...`），`targetRef` 指向「设置昵称」页（对应按钮 `[Atur Nama Baru]`）。
  - `PET_NAME` → 类型 `PET_NAME_RESET`，文案 §8.2（`Nama hewan peliharaanmu telah direset ...`），`targetRef` 指向该宠物档案「改名」页（`[Atur Nama Hewan Peliharaan]`）。
- `AUTO_PASSED` / `RESOLVED_PASS`（正向）**不推送**（D-CM6）。
- 通知类型枚举扩展 CHECK 由 story 7（V53）落；本 story 引用类型串，命名与 story 7 对齐。文案 arb key 由 story 7 统一落 `app_id.arb`；本 story 后端只发结构化通知（type + targetRef），不硬编码显示串（遵 [i18n 模型：App 按 code/type 本地化，不渲染后端串]）。
- **日志护栏**：通知/审核链路日志**禁记** `submitted_value`、`nickname`、宠物名原文、token（PII）。审核证据只存 `name_moderation_records.submitted_value`（§5.5 访问控制），不进 SLF4J JSON 业务日志。

### 5.7 §2.2 专项规则与误判缓冲（送审 + 队列元数据）
- 昵称/宠物名除通用违规类别外，纳入：商业引流信息（手机号/账号链接）、仿冒官方/知名品牌、纯数字/乱码（人工判断恶意注册）。这些由 story 1 词库/评分覆盖，本 story 负责把名称文本正确送审并携带 `target_type` 供词库按面板配置。
- **误判缓冲**：「臭臭」「臭豆腐」「黑黑」「gendut」「hitam」等拟声词/常见宠物名，评分即便偏高也**不自动重置**（本就无自动重置路径），一律进人工队列由运营重点复审（方案 §2.2 / §9.3 白名单精神）。队列项对运营展示 `submitted_value` 全文以便判断。

### 5.8 API（`/api/v1`，护栏一致）
本 story **不新增对客户端的名称审核查询接口**（用户无感，先放行）。名称提交仍走既有 `/api/v1/me`（昵称）与 `/api/v1/pet-profiles/me`（宠物名，资源小写复数连字符）—— 仅在其 service 内接线送审，接口契约不变。
- 运营处置入口（供 story 8 后台调用，本 story 提供 service 方法 + 一个受 admin 鉴权的内部端点即可，不做页面）：`NameModerationService.decide(recordId, PASS|VIOLATION, adminId, reason)`。
- 错误统一 RFC 9457 ProblemDetail（type/title/status/detail/instance/traceId），绝不外泄堆栈。

---

## 6. 前端设计（petgo_app，Flutter · Riverpod/go_router）

> 名称审核对普通用户**近乎无感**（先放行），前端改动很小：不加「审核中」标签、不加屏蔽，只需正确展示可能被后端重置的默认名 + 处理重置通知跳转。

1. **编辑触发重审＝零 UI 改动**：昵称编辑页（`/me` 资料页）与宠物名编辑（宠物档案编辑页）**提交逻辑不变**，后端自动送审。前端**不显示任何审核状态/进度**（作者视角与正常改名一致，§1.3）。
2. **默认名展示**：昵称/宠物名一律读后端返回的真实值渲染。被重置后后端返回 `user_8f3a2b` / `Pet_8f3a2b` —— App **原样展示**（对本人也是默认名），**无 `***` 屏蔽、无特殊标记**。若响应含 `isSystemDefaultName`（camelCase 桥接），可选用于「提示去改名」的轻量引导（非必需，V1 可不做）。
3. **重置通知跳转**：消息中心收到 `NAME_RESET` → 跳「设置昵称」页；`PET_NAME_RESET` → 跳对应宠物「改名」页。跳转用 `targetRef`（**不用随机 token 兜底**，遵 [notify 跳转改用 targetRef] 教训）。通知文案走 arb 本地化（story 7 落键；本 story 前端只接跳转路由，若 story 7 未就绪则占位 key + TODO）。
4. **无屏蔽/无限权**：不得因名称被重置而禁用任何账号功能（改名、发帖、评论均正常）—— 与「注销匿名化置灰」不同，切勿套用。
5. **命名映射**：DB `is_system_default_name` ↔ Dart `isSystemDefaultName` ↔ JSON `isSystemDefaultName`（若下发）。

---

## 7. 验收 AC（前后端分段 · 每条标 L0/L1/L2 + 所需环境）

### 7.1 后端 AC

- **AC-B1（L0 静态）** `mvn -B compile` 通过：新增 `name_moderation_records` 实体、`NameModerationStatus`/`target_type`/`priority` 枚举、`NameModerationService`、`DefaultNameGenerator`、事件类均编译通过；`users`/`pet_profiles` 实体加 `isSystemDefaultName` 映射。环境：无 DB。
- **AC-B2（L0 单测）** 评分路由单测：`0.59→AUTO_PASSED`、`0.6→MANUAL_PENDING/NORMAL`、`0.79→NORMAL`、`0.8→MANUAL_PENDING/HIGH`、`0.999→HIGH`（边界含闭开区间）。环境：无 DB（三方 client mock）。
- **AC-B3（L0 单测）** 陈旧作废：模拟 A→B→C 三次提交，B 的评分结果回来时 record 已 `SUPERSEDED` → 断言不改名、不发通知、不入队；仅 C 生效。环境：无 DB。
- **AC-B4（L0 单测）** 默认名生成：`user_`/`Pet_` 前缀、后缀为小写 hex、不含 id/时间派生（同 id 多次生成不同后缀）、冲突重试路径。环境：无 DB。
- **AC-B5（L1 集成）** Flyway `validate` 绿：`V50` 迁移 + 实体映射逐列对齐（含 `is_system_default_name` boolean、无 length=1 列坑）。环境：Docker postgres + redis + `mvn spring-boot:run` `/actuator/health=UP`。
- **AC-B6（L1 集成）** 异步状态机真跑：提交违规名（三方 stub 返回高分）→ 断言 record 落 `MANUAL_PENDING/HIGH`；调 `decide(VIOLATION)` → `users.nickname` 变 `user_xxx`、`is_system_default_name=true`、record `RESOLVED_VIOLATION`、生成一条通知 INSERT（`REQUIRES_NEW` 确已提交，非只涨角标）。环境：L1。
- **AC-B7（L1 集成）** fail-closed 降级：三方 client 抛超时/5xx，重试 ≥3 次后 record 落 `MANUAL_PENDING`（risk=NULL）+ 告警日志，**不自动判过**。环境：L1。
- **AC-B8（L1 集成）** 编辑重审 + 队列移除：名称在 `MANUAL_PENDING` 时用户再次改名 → 旧 record `SUPERSEDED` 且不在队列查询结果中，新 record 进 `SCORING`。环境：L1。
- **AC-B9（L1 集成 · 护栏）** 日志抽查：审核/通知链路 JSON 日志**不含** `submitted_value`/昵称/宠物名原文/token；`name_moderation_records.submitted_value` 有原文（审核证据）。环境：L1。
- **AC-B10（L2）** 真阿里云文字审核对**印尼语**名称评分（依赖 story 1 live）：真实提交含印尼语违规词的昵称/宠物名 → 评分入队；提交「臭臭/gendut/hitam」等误判缓冲词 → 不被自动重置、进人工复审。环境：真三方凭证（story 1 D-CM8 印尼语能力已核实）。

### 7.2 前端 AC

- **AC-F1（L0 静态）** `flutter analyze` + `flutter test` 绿：改名提交逻辑不变、无新「审核中」控件；若接 `isSystemDefaultName` 字段则模型解析单测。环境：无设备。
- **AC-F2（L2 视觉 · Android 模拟器）** 后端把某测试账号昵称/宠物名重置为 `user_xxx`/`Pet_xxx` 后，App 冷启/刷新 → 本人与他人视角**均展示默认编码名**，无 `***`、无「审核中」标签、无功能置灰。环境：Android 模拟器 + 连 `api.tailtopia.id` 真后端（[测试连正式后端]）。
- **AC-F3（L2 视觉 · Android 模拟器）** 收到 `NAME_RESET`/`PET_NAME_RESET` 通知 → 点击跳到对应改名页（走 `targetRef`），改新合规名后正常提交、默认名标志清除。环境：同上。
- **AC-F4（L2 · 回归）** 名称被重置的账号，发帖/评论/其它功能不受限（无限权），与注销置灰行为区分。环境：同上。

> L2 视觉一律 Android 模拟器（[模拟器＝Android]）；每屏实现完主动在模拟器打开对应页面给用户看（[sim-open-page]）。

---

## 8. 依赖与契约

- **依赖 story 1**（`spec-content-moderation-aliyun-provider.md`）：三方文字审核**评分接口**（返回 `risk_score` 及命中类别）与 **fail-closed 降级契约**（D-CM5 / §4.3）。story 1 未就绪时，本 story 后端可先对接口打桩完成 L0/L1；L2 印尼语实测（AC-B10）须待 story 1 live（D-CM8）。
- **依赖 story 7**（`notifications-i18n`）：通知类型 `NAME_RESET`/`PET_NAME_RESET` 的枚举 CHECK 扩展（V53）与 arb 文案（§8.1/§8.2）。本 story 先行发结构化通知，类型串命名与 story 7 对齐；arb key 前端占位。
- **被 story 8 复用**：`name_moderation_records` 人工队列 + `decide(...)` 处置入口 → story 8 做后台页面/优先级排序。
- **被 story 9 订阅**：`NameResetEvent` → story 9 违规计数（本 story 只发事件，不建计数表）。
- **D-CM4 硬约束**：与 Story 7-3 注销匿名化两套占位不可混用（见 §1.2）。
- **D-CM3**：编辑重审 + 陈旧作废，与帖子/评论/头像同规则，本 story 用 `revision` 版本键实现。
- **命名/错误/日志护栏**：snake_case↔camelCase 桥接、RFC 9457 ProblemDetail、日志禁 PII、`/api/v1/me`、不可枚举 token —— 全与 CLAUDE.md 一致。

---

## 9. 云端（headless）执行须知

- ✅ 云端可跑：**全部 L0**（`mvn -B compile` + 后端单测 AC-B1~B4；`flutter analyze`/`flutter test` AC-F1）。云端只需跑到 L0 绿灯，Completion Notes 标「L1/L2 待本地/CI」。
- ⚠️ **L1**（AC-B5~B9）：需 Docker postgres+redis + Flyway `validate` 真跑；云沙箱不保证有 Docker daemon → **默认留本地/CI**。schema 契约以 CI/L1 绿为准（Hibernate 映射坑只有 L1 暴露；本迁移已规避 length=1）。
- ❌ **L2**（AC-B10 真阿里云印尼语；AC-F2~F4 Android 模拟器视觉）**必须回本地**：headless 无三方凭证、无模拟器渲染。
- 依赖 story 1 的三方接口：云端阶段用桩，勿在云端尝试连真三方。

---

## 10. 风险与待确认

1. **存量/初值补审**：昵称初值＝Google `displayName`（`User.java:86`）、既有用户/宠物名从未审过。是否对**存量**名称做一次性回扫？建议 V1.1.0 只审**新提交/编辑**，存量走举报兜底（与「先放行」姿态一致），存量回扫留后续。→ 待确认。
2. **注册流首次昵称是否即送审**：注册确认昵称（Story 1.6 `updateMe`）已覆盖；但 Google/Apple 登录写入 `displayName→nickname` 的**自动初值**是否送审？建议送审（防用第三方脏昵称绕过），但要注意别把正常真名误判。→ 待确认。
3. **「系统判定违规」语义**：方案文字有「运营/系统判定违规」。本 story 实现为**评分只路由、违规由运营裁定**（无纯评分自动重置）。若产品要求极高分（如 ≥0.95）自动重置免人工，需新增一条自动重置路径 + 对应通知触发时机（届时仍走 §5.5/§5.6）。→ 待产品确认是否要自动重置档位。
4. **默认名唯一性范围**：昵称在 schema 是否有唯一约束？若无，`DefaultNameGenerator` 的唯一性靠生成时查重 + 随机空间（6~8 hex≈16M~4B）保证，冲突概率极低但非 DB 级强唯一。是否需要给默认名单独加唯一索引？→ 待确认（V1 可不加）。
5. **印尼语评分能力（D-CM8）**：若 story 1 实测阿里云印尼语不足 → 名称评分对印尼语可能只靠自建关键词硬匹配兜底，风险分级仅中英可靠。影响 AC-B10 判定口径。→ 依赖 story 1 结论。
6. **通知类型上线次序**：本 story 若先于 story 7 合并，`NAME_RESET`/`PET_NAME_RESET` 的 CHECK 约束尚未加（V53），发通知会因类型不在 CHECK 白名单而 INSERT 失败。→ 二者需协调：要么 story 7 先合、要么本 story 临时在 V50 加类型（须与 story 7 约定避免 V53 重复）。建议 story 7 先行落类型。

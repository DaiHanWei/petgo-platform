---
title: 头像异步图像审核 + 重置默认头像（story 5）
type: spec-dev-story
slug: content-moderation-avatar-review
status: ready-for-dev
story_order: 5
source:
  - _bmad-output/implementation-artifacts/spec-content-moderation-overview.md
  - _bmad-output/planning-artifacts/content-moderation-plan-v1.0.1.md（§3.4 / §8.10 / §4.2）
depends_on:
  - spec-content-moderation-aliyun-provider.md（story 1，图像审核能力 + 风险评分 + fail-closed 降级）
  - spec-content-moderation-name-review.md（story 4，复用「先放行→异步审核→重置默认」模式与异步队列骨架）
flyway_placeholder: V51
created: 2026-07-08
owner: Dai
communication_language: 中文
covers_fr: [FR-0E（用户头像）, FR-11（宠物头像）]
relatedEpics: [Epic 1 账号, Epic 2 成长档案, Epic 6 通知]
---

# 头像异步图像审核 + 重置默认头像（story 5）

> **权威源**：产品规则以「内容审核补充规范 v1.0.1」§3.4 / §8.10 / §4.2 为准；代码契约以 `CROSS-STORY-DECISIONS.md` + 本批总览 `spec-content-moderation-overview.md` 的 D-CM* 决策为准。
> **一句话范围**：用户头像 / 宠物头像上传或更换后**先放行、立即对他人可见**，再**异步**送三方图像审核；判定违规 → **重置为平台默认头像**（对含本人的所有人展示默认头像），用户可随时重传 → 重新送审（陈旧结果作废）；违规重置推送站内通知（§8.10）。举报路径保留。

---

## 1. 背景与范围

### 1.1 为什么要这个 story
V1.1.0 内容审核方案把 6 类 UGC 面板纳入审核。本 story 落地其中**第 5、6 面板**：用户头像（FR-0E）、宠物头像（FR-11）。这两面板与「昵称/宠物名」（story 4）同属**「先放行、后异步审核」**机制族，区别仅在审核对象是**图像**而非文字——因此本 story 的异步流水线、队列消费、「重置为默认 + 随时重传 + 编辑重审 + 陈旧作废」处置模型**直接复用 story 4 的骨架**，只把「文字审核调用」换成「图像审核调用」、把「重置为编码名」换成「重置为默认头像」。

### 1.2 本 story 做什么（IN）
1. 用户/宠物头像上传或更换后**立即生效、对他人可见**（不改现有即时可见行为），随后触发**异步**图像审核。
2. **三档风险路由**（§3.4 步骤 4-6，阈值锚定 §4.2 图像识别）：
   - 低风险（0–0.6）→ 自动通过，无人工介入。
   - 中风险（0.6–0.8）→ 进人工审核队列，运营判定。
   - 高风险（≥0.8）→ 进人工审核队列，标记**高风险**优先处理（对应 §5.1 P1）。
3. 判定违规 → **重置为平台默认头像**：对所有人（含本人）展示默认头像；用户可**随时**重新上传新头像 → 按「编辑重审」（D-CM3）**重新送审**；若再次违规再次重置。**无 7 天期限、无账号功能限制**（与 story 4 名称处置模型一致）。
4. **编辑重审 + 陈旧结果作废**（D-CM3）：头像被换后重新送审；审核结果**绑定送审时的头像版本**，若出结果时头像已被改成新值 → 旧结果**静默丢弃**、旧队列条目移除。
5. **显式记录「可见窗口期」权衡**（D-CM2 / 方案 §3.4 权衡框）：违规头像在异步审核完成前对所有人可见，由异步 + 举报兜底。本 story 用文档 + 代码注释显式落这条不变量，不做「先审后显」。
6. 违规重置 → 推送站内通知（§8.10，NAME/AVATAR_RESET 类通知）。
7. **举报路径保留**：其他用户举报账号/宠物档案（FR-25，未来 FR-58）时，运营后台可直接查看并处置头像——本 story 只保证审核记录/默认头像标志对后台可读，后台 UI 扩展归 story 8。

### 1.3 本 story 不做（OUT）
- 三方图像审核能力本身（评分、阈值判定、fail-closed 降级、重试）→ **story 1** 提供，本 story 只**调用**其图像审核接口。
- 通知文案的 arb / 后端串落地与「隐藏才通知」全局收口 → **story 7**（本 story 只触发通知类型，文案占位可复用 story 4 已引入的 `*_RESET` 类型）。
- 运营后台队列 UI、优先级展示、头像处置动作按钮 → **story 8**。
- 帖子图片审核（走 FR-12/FR-12A 发布前同步闸门，不在本 story）。
- **远期（明确不做）**：宠物图像白名单 / 宠物图像专项识别（§9.3 思路），误判率高时再评估。

---

## 2. 现状基线（file:line，2026-07-08 核实）

| 关注点 | 现状 | 证据 |
|---|---|---|
| 用户头像字段 | `User.avatarUrl`（`avatar_url VARCHAR(1024)`）；注销时置 null | `auth/domain/User.java:42-43`（字段）、`:182-183`（setter）、`:240`（注销清空） |
| 用户头像更换入口 | `MeService` PATCH `/api/v1/me`：仅存应用自有 URL，**无任何审核** | `auth/service/MeService.java:59-62` |
| 宠物头像字段 | `PetProfile.avatarUrl`（`avatar_url VARCHAR(1024)`） | `profile/domain/PetProfile.java:33-34`、setter `:107-108` |
| 宠物头像入口 | `ProfileService.create`（`:50-74`，落 `avatarUrl`）/ `update`（`:117-118`，换头像）；**无审核**、**无重置默认逻辑** | `profile/service/ProfileService.java:50-74,117-118` |
| 图像审核能力 | 仅帖子发布 stub：`ContentModerationService.moderate(text, imageUrls)` 返回 `PASS/TEXT_BLOCKED/IMAGE_BLOCKED`，图像为魔法标记占位，**无风险评分、无阈值、无异步、无头像面板接入** | `content/service/ContentModerationService.java`（全类，图像 stub 见 `IMAGE_VIOLATION_MARKER`） |
| 头像审核 | **完全无**（`avatar` + `moderation` 零命中；无默认头像常量、无「重置默认」路径） | grep 无命中 |
| 审核通知类型 | 现有 `NotificationType.REPORT_REVIEWED` 等；`ModerationNotifyListener` 已在收口审核通知 | `notify/service/ModerationNotifyListener.java:25-27`、`notify/domain/NotificationType.java` |

**结论**：这是**全新面**。用户头像、宠物头像各有独立更换入口（`MeService` / `ProfileService`），需各自挂异步送审钩子；审核记录、默认头像标志、异步流水线均需新建（复用 story 4 骨架 + story 1 图像接口）。

---

## 3. 目标与非目标

### 3.1 目标
- G1：用户/宠物头像更换后**即时可见不变**，且触发一次异步图像审核（不阻塞更换响应）。
- G2：三档风险路由按 §4.2 图像阈值锚定；违规判定 → 重置默认头像（含本人展示默认）。
- G3：重传 → 重新送审；陈旧结果静默作废、旧队列条目移除（D-CM3）。
- G4：违规重置推送通知（§8.10）。
- G5：**显式落「可见窗口期」不变量**（文档 + 注释 + AC），供 QA/合规审阅。
- G6：举报路径可查处置头像（记录/标志后台可读）。

### 3.2 非目标
- 不改「头像即时生效」体验（不做先审后显）。
- 不做账号功能限制 / 宽限期 / 屏蔽占位（方案 §3.4 明确取消）。
- 不做宠物图像白名单（远期）。
- 不落通知文案 arb（story 7）、不做后台 UI（story 8）。

---

## 4. 数据与迁移（V51 delta）

> Flyway **已冻结到 V46**；本 story 占位 **V51**（实际号 CI 落地时按合并顺序单调顺延，勿硬编码）。一律**新起 ALTER / CREATE**，不改既有迁移。`ddl-auto=validate`——schema 与实体必须一一对得上。

### 4.1 头像审核记录表 `avatar_reviews`（新表）
记录每次送审的**一条审核事件**，绑定审核对象与版本键（用于陈旧作废）。

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `bigserial` PK | |
| `subject_type` | `varchar(16)` | `USER_AVATAR` / `PET_AVATAR`（UPPER_SNAKE，**别用 CHAR/length=1**） |
| `subject_id` | `bigint` | 用户 id 或 宠物档案 id（按 `subject_type` 解释） |
| `avatar_url` | `varchar(1024)` | 送审时的头像 URL（**版本键**：出结果时与当前值比对，不等即陈旧作废） |
| `risk_score` | `numeric(4,3)` | 三方返回风险分（0.000–1.000）；降级/未出分为 null |
| `verdict` | `varchar(16)` | `PASS` / `PENDING_REVIEW` / `VIOLATION` / `STALE_DISCARDED` / `DEGRADED_QUEUED`（UPPER_SNAKE） |
| `status` | `varchar(16)` | 流水线态：`QUEUED` / `AUTO_PASSED` / `MANUAL_PENDING` / `RESOLVED`（UPPER_SNAKE） |
| `priority` | `varchar(8)` | `NORMAL` / `HIGH`（≥0.8 或违禁高置信 → HIGH，对应 §5.1 P1） |
| `created_at` | `timestamptz` | UTC |
| `updated_at` | `timestamptz` | UTC |

- 索引：`(subject_type, subject_id)`；`status` 局部索引供队列拉取。
- 注：**不新建独立人工队列表**——头像的人工待判条目复用 story 4/8 的统一 `manual_review_queue`（story 3 已加 `content_type`；本 story 追加 `content_type` 取值 `USER_AVATAR`/`PET_AVATAR`，无 schema 变更即可复用；若 story 4 尚未把队列 content_type 扩到名称/头像，则本 story 的 V51 顺带 `ALTER manual_review_queue` 的 CHECK 放开取值）。dev 落地时以 story 3/4 实际队列结构为准，在 Completion Notes 记录选型。

### 4.2 默认头像标志（无需新列）
- **不新增 `is_system_default_avatar` 布尔列**：默认头像用**约定 URL 常量**表达（后端常量 `DEFAULT_USER_AVATAR_URL` / `DEFAULT_PET_AVATAR_URL`，指向平台默认头像资源），`avatar_url == 默认常量` 即「当前为默认头像」。这样重置=把 `avatar_url` 写成默认常量，与「用户重传新值」天然区分，无需额外布尔位。
  - ⚠️ 若产品要求区分「用户从未设置头像（历史 null）」与「被重置为默认」，再评估加 `avatar_reset_at timestamptz` 记录重置时刻（供后台/审计）；V1 默认**不加**，在 Completion Notes 标注该取舍。

### 4.3 迁移清单（V51）
1. `CREATE TABLE avatar_reviews`（见 4.1）。
2.（条件）`ALTER manual_review_queue` 放开 `content_type` CHECK 以容纳 `USER_AVATAR`/`PET_AVATAR`——**仅当 story 4 未覆盖时**。

---

## 5. 后端设计

### 5.1 送审钩子（更换即入队）
在两个既有更换入口**事务提交后**触发异步送审（AFTER_COMMIT，避免 story 6 记过的「同步事件 + REQUIRED 吞写」坑；参考 `notify-after-commit-tx-swallow-bug`——本处用 `@Async` 独立事务消费，不复用同步监听器）：

- **用户头像**：`MeService`（`:59-62` 处，`setAvatarUrl` 之后）。仅当 `avatarUrl` 实际变化且**非默认常量**时送审（重置为默认本身不送审）。
- **宠物头像**：`ProfileService.create`（`:63`）与 `update`（`:117-118`）。同样仅在头像变化且非默认常量时送审。

送审动作：写一条 `avatar_reviews`（`status=QUEUED`，`avatar_url=新值`），发 `AvatarReviewRequestedEvent(subjectType, subjectId, avatarUrl)`；`@Async` 监听器消费。

> **护栏**：禁引入 MQ / 新中间件——异步只用 `@Async` + DB 状态机（`avatar_reviews.status`）。签名 URL / 头像 URL **不入日志**，日志只记 `subjectType + subjectId + reviewId + verdict/score`（score 可记，非 PII）。

### 5.2 异步审核消费（`@Async`）
1. 加载该 `avatar_reviews` 行；**陈旧校验（前置）**：若 `avatar_url != 当前对象头像`（用户/宠物已改新值）→ 置 `verdict=STALE_DISCARDED, status=RESOLVED`，**不做任何处置、不通知**（D-CM3）。
2. 调 **story 1 图像审核接口**（`ContentModerationService` 或其重构后的图像方法，输入头像 URL，输出**风险分 + 类别**）。阈值按 §4.2：色情 ≥0.85 / 暴力 ≥0.80 / 违禁 ≥0.75 命中即高风险；综合折算 `risk_score`。
3. **路由**（§3.4 步骤 4-6）：
   - `risk_score` ∈ [0, 0.6) → `verdict=PASS, status=AUTO_PASSED`（无人工、无通知）。
   - [0.6, 0.8) → `status=MANUAL_PENDING, priority=NORMAL, verdict=PENDING_REVIEW`，入 `manual_review_queue`（content_type=对应头像类型）。
   - ≥0.8 → 同上但 `priority=HIGH`（§5.1 P1）。
4. **fail-closed 降级**（D-CM5 / §4.3）：三方超时/4xx/5xx/配额耗尽 → 不自动放行，`verdict=DEGRADED_QUEUED, status=MANUAL_PENDING` 入人工队列；异步任务本身失败 → 重试（≥3 次指数退避，story 1 定义的重试策略），仍失败落队列 + 告警（日志级）。

### 5.3 违规处置：重置为默认头像
运营在人工队列判定「违规」，或异步阶段（若产品允许高置信直接判违规——V1 **不做自动判违规**，一律入队人工判，与 §3.4 一致）后：

1. **陈旧再校验**（处置前再比一次版本键）：若此刻头像已被用户改成新值 → 放弃处置、移除队列条目（D-CM3）。
2. 把对象 `avatar_url` 写成**默认常量**（`DEFAULT_USER_AVATAR_URL` / `DEFAULT_PET_AVATAR_URL`）——对**所有人（含本人）**即展示默认头像。
3. `avatar_reviews.verdict=VIOLATION, status=RESOLVED`。
4. 触发通知（5.5）。
5. **不**限制账号功能、**不**设期限。用户随时重传新头像 → 走 5.1 送审（重新审核）。

> **复用 story 4 重置模式**：story 4 的「重置为编码名 + 随时重设 + 编辑重审」处置服务已存在同构逻辑；头像侧复用其**服务骨架 / 队列判定回调**，仅替换「写编码名」为「写默认头像常量」。dev 落地时抽公共 `ModerationResetService` 或并列同构类，二选一并在 Completion Notes 记录。

### 5.4 举报路径可查（最小保证）
- 头像审核记录（`avatar_reviews`）与当前头像状态对后台**可读**；举报（FR-25）落到账号/宠物档案后，后台复审可查到该对象的头像与最近审核结论。后台**处置动作 UI**（重置按钮、查看历史）归 story 8，本 story 只保证数据可读、`ModerationResetService` 可被后台调用。

### 5.5 通知（触发，文案归 story 7）
- 违规重置 → 发通知，类型 `AVATAR_RESET`（若 story 4 已引入 `NAME_RESET`/`AVATAR_RESET` 系列则复用；否则本 story 临时引入 `AVATAR_RESET` 占位，story 7 统一收口 CHECK 与 arb）。
- 文案锚定 §8.10（「你的头像已被重置…可随时重新上传，无时间限制」，区分用户头像/宠物头像）。
- **仅违规（负向）推送**；PASS / STALE / 恢复**不推送**（D-CM6）。
- 通知落库走 `AFTER_COMMIT + REQUIRES_NEW`（防吞写，见 `notify-after-commit-tx-swallow-bug`）。

### 5.6 命名 / 错误 / 日志契约
- DB `snake_case` ↔ 实体/DTO `camelCase` ↔ JSON `camelCase`；枚举落库 UPPER_SNAKE varchar。
- 对外错误 RFC 9457 ProblemDetail，不泄堆栈。
- 日志 SLF4J JSON，**禁记头像 URL / 签名 URL / PII**；风险分与 verdict 可记。

---

## 6. 前端设计

### 6.1 头像可见窗口期（核心不变量，显式落地）
- **不新增任何「审核中」UI**。头像更换后**立即对所有人（含本人）显示新头像**，与现状一致。这正是方案 §3.4 有意接受的**可见窗口期**权衡（违规头像在异步审核完成前对所有人可见）。前端**不做任何遮挡/占位/审核态渲染**。
- 前端在头像更换相关代码处加注释显式标注：「先放行、后异步审核，可见窗口期为 V1.1.0 有意权衡（D-CM2 / 方案 §3.4）」，避免后人误以为遗漏了审核态。

### 6.2 默认头像展示（本人 + 他人视角）
- 后端把 `avatarUrl` 写成默认常量后，前端**照常渲染该 URL** 即可——默认头像是一张正常的平台默认头像资源，**本人与他人看到的都是这张默认头像**，前端无需分支判断「是否被重置」。
- 若 `avatarUrl` 为 null（历史无头像）走前端既有的兜底占位逻辑；被重置为默认常量则是**真实 URL**，正常加载。二者前端表现应一致（都呈现默认头像观感），dev 确认默认常量 URL 指向的资源与前端 null 兜底占位视觉一致或就用同一张资源。

### 6.3 本人重传路径
- 本人看到自己头像变默认后（+ 收到 §8.10 通知），走**既有换头像入口**（我的页 / 编辑档案）重新上传即可——无需新增「重设头像」专用页；通知内 CTA「重新上传」跳转到既有换头像入口。
- 重传即触发后端 5.1 重新送审（前端无感）。

### 6.4 通知呈现
- 通知中心展示 §8.10 文案（story 7 落 arb）；点击跳换头像入口。跳转用稳定 targetRef（区分用户头像/宠物头像入口），**不用随机 token**（沿用 `notify-after-commit` 记的 targetRef 跳转约定）。

---

## 7. 验收 AC（前后端分段，标 L0/L1/L2 + 环境）

> 层级定义（CLAUDE.md）：L0 静态（无 DB/凭证）；L1 集成（Docker postgres+redis 真跑 + Flyway validate）；L2 端到端（真三方图像审核凭证 / 真机·模拟器视觉）。**真图像审核判定属 L2**。

### 7.1 后端 AC

| # | 验收点 | 层级 | 所需环境 |
|---|---|---|---|
| B1 | `V51` 迁移可 `flyway migrate` 干净跑过；`avatar_reviews` 建表；`mvn -B package` 通过 | L0（compile）/ L1（migrate+validate） | L1 需 Docker postgres |
| B2 | `ddl-auto=validate` 下应用启动通过（实体 ↔ schema 对齐，无 CHAR(1) 坑） | L1 | Docker postgres |
| B3 | 用户头像换新（`PATCH /me`）后即时返回新 URL（不阻塞），并写入一条 `avatar_reviews status=QUEUED` | L1 | Docker + JWT |
| B4 | 宠物头像 create/update 同 B3（写 `PET_AVATAR` 审核记录） | L1 | Docker + JWT |
| B5 | 低风险（<0.6）→ 记录 `AUTO_PASSED`、不入队、不通知 | L1（stub 打桩分数）/ L2（真三方） | L1 桩；L2 真凭证 |
| B6 | 中风险 [0.6,0.8) → 入 `manual_review_queue`（content_type 对应）、`priority=NORMAL` | L1（桩） | Docker |
| B7 | 高风险 ≥0.8 → 入队 + `priority=HIGH`（§5.1 P1） | L1（桩） | Docker |
| B8 | fail-closed：三方超时/5xx/配额耗尽 → 不放行、`DEGRADED_QUEUED` 入队 | L1（桩注入失败） | Docker |
| B9 | 判定违规 → 对象 `avatar_url` 被写成默认常量；`verdict=VIOLATION`；发 `AVATAR_RESET` 通知（AFTER_COMMIT/REQUIRES_NEW 真落库） | L1 | Docker |
| B10 | 陈旧作废：送审后头像已改新值 → 出结果时置 `STALE_DISCARDED`、不处置、不通知、移除队列条目（D-CM3） | L1 | Docker |
| B11 | 重传新头像 → 生成新 `avatar_reviews` 重新送审（旧陈旧作废） | L1 | Docker |
| B12 | 重置为默认头像的写操作**本身不再触发送审**（避免自审循环） | L1 | Docker |
| B13 | PASS/STALE/恢复不推送通知；仅 VIOLATION 推送（D-CM6） | L1 | Docker |
| B14 | 日志不含头像 URL/签名 URL/PII（抽查审核链路日志） | L1 | Docker |
| B15 | 真阿里云图像审核：违规样图 → ≥阈值 → 入队；正常宠物照 → 低分放行（含误判观察） | **L2** | 真三方凭证 + 样图集 |

### 7.2 前端 AC

| # | 验收点 | 层级 | 所需环境 |
|---|---|---|---|
| F1 | `flutter analyze` / `flutter test` 通过 | L0 | 无 |
| F2 | 换头像后本人 + 他人**立即看到新头像**，无「审核中」标签（可见窗口期不变量） | L2 | 模拟器（Android）+ 真后端 |
| F3 | 头像被重置为默认后，本人与他人视角均显示平台默认头像（观感与 null 兜底一致） | L2 | 模拟器 + 真后端 |
| F4 | 收到 §8.10 重置通知，点击 CTA 跳到既有换头像入口，可重新上传 | L2 | 模拟器 + 真后端 |
| F5 | 重传后再次即时可见（触发后端重新送审，前端无感） | L2 | 模拟器 + 真后端 |
| F6 | 换头像相关代码含「可见窗口期有意权衡」注释（代码审阅） | L0 | 无 |

### 7.3 联调 AC
| # | 验收点 | 层级 |
|---|---|---|
| E1 | 端到端：真机换违规头像 → 他人短时可见 → 异步审核判违规 → 双端变默认头像 + 本人收通知 → 重传合规头像通过 | **L2** |

---

## 8. 依赖与契约

- **依赖 story 1（图像审核）**：本 story 调用其图像审核接口（输入头像 URL，输出风险分 + 类别）与 fail-closed 降级/重试策略。若 story 1 尚未落地，本 story 后端可先用**桩分数**跑通 L0/L1（B5-B8 桩），L2（B15/E1）待 story 1 真三方接入后回本地跑。
- **依赖 story 4（名称审核）**：复用「先放行→异步审核→重置默认→随时重设→编辑重审→陈旧作废」处置骨架与统一 `manual_review_queue`（content_type）。若 story 4 先落地，本 story 抽 `ModerationResetService` 公共化；若并行，二者约定同构接口。
- **产出给 story 7（通知 i18n）**：`AVATAR_RESET` 通知类型触发点（文案/CHECK/arb 由 story 7 收口）。
- **产出给 story 8（后台）**：`avatar_reviews` 可读 + `ModerationResetService` 可被后台调用（后台 UI 归 story 8）。
- **契约不变量（写进代码注释）**：
  - D-CM2 可见窗口期：头像先放行后审，异步完成前违规头像对所有人可见——有意权衡。
  - D-CM3 编辑重审 + 陈旧作废：审核结果绑 `avatar_url` 版本键。
  - D-CM5 fail-closed：三方失败不放行、入队。
  - D-CM6 隐藏才通知：仅 VIOLATION 推送。
  - 命名映射链 / RFC 9457 / 日志禁 PII 与 URL。

---

## 9. 云端（headless）执行须知

- ✅ **云端可做（L0）**：`mvn -B package`（后端编译）、`flutter analyze` / `flutter test` / `flutter build apk --debug`。桩分数下的路由单测（B5-B8 逻辑）可在 L0 用纯 JUnit（不连 DB）验证分支。
- ⚠️ **L1（Docker postgres+redis + Flyway validate）**：云沙箱不保证有 Docker daemon；**默认留本地/CI**。云端只跑 L0 绿灯，在 Completion Notes 标「B1-B14 的 L1 部分待本地/CI 验收；schema 契约以 CI/L1 绿为准（Hibernate CHAR(1) 坑仅 L1 暴露）」。
- ❌ **L2 必回本地**：B15（真阿里云图像审核 + 样图集）、F2-F5（Android 模拟器视觉，用户「模拟器」= Android）、E1 端到端——需真三方凭证 + 真后端 + 模拟器，一律 teleport 回本地跑。
- **Flyway 号**：云端落 V51 占位，CI 合并时按实际顺序顺延，勿硬编码撞号。

---

## 10. 风险与待确认

| # | 事项 | 影响 | 处理 |
|---|---|---|---|
| R1 | **可见窗口期污染**（D-CM2）：违规头像在异步审核完成前对所有人可见，窗口长度取决于队列延迟 | 合规/体验风险 | 有意接受的权衡（方案 §3.4）；异步 + 举报兜底；本 story 显式落文档 + 注释，供合规知情。若窗口过长可后续评估「敏感类先审后显」但**本版本不做** |
| R2 | **正常宠物照片被误判**（§3.4 远期风险）：宠物照易触发暴力/违禁类图像模型误报 | 误伤正常用户 | V1 不做白名单；中风险一律入**人工队列**（不自动判违规）作为误判缓冲；L2（B15）用真实宠物样图集观察误判率，超标则记录供后续白名单评估（§9.3，远期） |
| R3 | 阿里云图像审核**印尼/宠物场景**准确度未实测 | 阈值可能失准 | 归 story 1 前置实测（D-CM8 侧重文本；图像同样需样本回归，§7 待确认 #3）；本 story L2 阶段配合样图回归 |
| R4 | 默认头像资源与前端 null 兜底占位**视觉是否一致** | 用户看到「默认头像」与「从未设头像」观感不一 | 6.2 约定二者用同一张资源或视觉对齐；dev 落地时确认默认常量 URL 指向的资源，并在 Completion Notes 记录 |
| R5 | 统一队列 `content_type` 是否已由 story 3/4 扩到头像取值 | 迁移是否需在 V51 顺带 ALTER CHECK | dev 落地时以 story 3/4 实际队列结构为准，二选一（复用/顺带 ALTER）并在 Completion Notes 记录 |
| R6 | 是否需要 `avatar_reset_at` 区分「从未设头像」与「被重置」 | 后台/审计可追溯性 | V1 默认不加（4.2）；若后台 story 8 需要再增量列 |

---

> **一次只碰一侧**：后端（V51 + 送审钩子 + 异步消费 + 重置 + 通知触发）→ 前端（可见窗口期注释 + 默认头像展示 + 通知跳转）→ 联调（L2 端到端）。严格 L0 绿再推进 L1/L2。

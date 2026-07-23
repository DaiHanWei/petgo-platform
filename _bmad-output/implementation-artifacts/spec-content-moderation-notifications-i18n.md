---
title: 内容审核补充规范 — Story 7｜通知文案 i18n + 隐藏才通知收口
slug: spec-content-moderation-notifications-i18n
type: spec-story
status: ready-for-dev
story_index: 7 / 9
source:
  - _bmad-output/planning-artifacts/content-moderation-plan-v1.0.1.md（§8 通知文案、§7#1 润色、§5.5 留存）
  - _bmad-output/implementation-artifacts/spec-content-moderation-overview.md（D-CM6 隐藏才通知）
authority: 产品规则以方案 v1.0.1 §8 为准；代码契约以 CROSS-STORY-DECISIONS.md 为准
created: 2026-07-08
owner: Dai
communication_language: 中文
relatedEpics: [Epic 6 通知, Epic 3 内容社交, Epic 2 成长档案, Admin]
depends_on: [story2 帖子审核, story3 评论审核, story4 名称审核, story5 头像审核, story6 举报处置]
flyway_placeholder: V53（notifications.type CHECK 扩展；实际号 CI 顺延）
---

# Story 7｜内容审核通知文案 i18n + 隐藏才通知收口

> 本 story 是内容审核批次的**通知语义收口层**：把方案 §8 全部通知模板落成 **arb 键（印尼语为主 + 英语）**，后端只发 `type + 参数（targetRef 判别位）`、**绝不下发显示串**（沿用项目 i18n 债：App 按 `type + typeSlug` 本地化，不渲染后端串）；执行 **D-CM6「隐藏才通知」**（删 §8.6/§8.4 正向通知）；补齐既有枚举缺失的 arb 键；新增名称重置 / 头像重置 / 超时丢弃三型。
>
> **它不实现审核判定逻辑本身** —— 判定与发送触发点归 story 2–6；本 story 定义「发什么 type / 带什么参数 / 推不推 / 前端怎么本地化」的统一契约，并改造既有 `ManualReviewService` 的发送点。

---

## 1. 背景与范围

内容审核批次的多个 story（帖子/评论/名称/头像/举报）都会在**负向最终结果**时给用户发站内通知。历史上：

- 通知类型枚举**部分就绪**（`REPORT_REVIEWED` V40、`CONTENT_REMOVED` V40 补回 CHECK、`CONTENT_REVIEW_APPROVED/REJECTED` V43），但**方案 §8 的文案从未落 arb**，且这几个既有类型在 App 通知中心目前落到**兜底默认串**（`notification_center_page.dart:188 / :201` 的 `_ =>`）。
- 方案 2026-07-08 定「**隐藏才通知**」（§8 总原则 / 决策 D-CM6）：仅「隐藏 / 违规 / 未通过」负向最终结果推送；「通过 / 保留 / 恢复」不推送。据此 **§8.6 帖子审核通过通知、§8.4 名称/头像修改通过通知、§8.3 逾期提醒**一并删除。
- 新处置模型（重置为默认编码名 / 默认头像）需要**两个新通知场景**（§8.1/§8.2 名称重置、§8.10 头像重置），当前无对应枚举与文案；§8.8 超时丢弃与 §8.7 人工拒绝**文案不同但现共用一个枚举**，需拆分（App 只能按 type 本地化）。

**本 story 范围：**

1. **arb 落地**：方案 §8 全部保留通知（§8.1/8.2/8.5/8.7/8.8/8.9/8.10 + 既有 REPORT_REVIEWED）→ `app_id.arb`（印尼语，取 §8 原文/浓缩）+ `app_en.arb`（英语）。印尼语值统一标注「母语润色待运营」（§7#1）。
2. **隐藏才通知收口（D-CM6）**：删 §8.6 帖子审核通过正向通知（`ManualReviewService` approve 分支停发、静默转正）；确保 §8.4/§8.3 相关正向/逾期通知**不新增**。
3. **新增通知类型**：`NAME_RESET`（§8.1/8.2）、`AVATAR_RESET`（§8.10）、`CONTENT_REVIEW_TIMED_OUT`（§8.8，从 REJECTED 拆出）。举报驱动下架给作者（§8.9）与评论巡查下架（§8.5）**复用 `CONTENT_REMOVED`**，用 `targetRef` 前缀判别 POST/COMMENT 变体。
4. **CHECK 扩展**：Flyway 增量 `ALTER`（占位 **V53**）把三个新值纳入 `ck_notifications_type`。
5. **前端本地化收口**：`notification_center_page.dart` 的 `_typeLabel/_typeBody/_iconStyle` switch 补齐全部审核类 type；`deep_link_routes.dart` 补重置类深链目标（`targetRef`，非随机 token）。

**非目标（明确不做）：**

- 不实现审核判定 / 风险评分 / 队列消费（story 1–6）。
- 不改举报人模糊闭环语义（`REPORT_REVIEWED` 复用既有 `ModerationNotifyListener`，仅补 arb）。
- 不做 OS 级推送弹窗的多语言（当前 OS push 用后端兜底串=服务端语言，属既有 i18n 债，见 §10）。
- 不做申诉入口（方案 §5.5：V1.1.0 无申诉）。

---

## 2. 现状基线（file:line，复用勿重建）

### 2.1 后端

| 事实 | 位置 | 说明 |
|---|---|---|
| `NotificationType` 枚举 12 值 | `petgo-backend/.../notify/domain/NotificationType.java:21-37` | 含 `CONTENT_REMOVED`(:30)、`REPORT_REVIEWED`(:32)、`CONTENT_REVIEW_APPROVED`(:34)、`CONTENT_REVIEW_REJECTED`(:36) |
| CHECK 约束当前 12 值 | `db/migration/V43__extend_notification_types_content_review.sql:4-7` | V40 补回 `CONTENT_REMOVED`、加 `REPORT_REVIEWED`；V43 加 `CONTENT_REVIEW_APPROVED/REJECTED` |
| 举报人模糊闭环发送 | `notify/service/ModerationNotifyListener.java:24-28` | `send(reporterId, REPORT_REVIEWED, title, body, REPORT_REVIEWED.name(), null)`；文案对下架/驳回一致、不透露结果 |
| 人工审核**通过**发通知 | `admin/moderation/service/ManualReviewService.java:72` | `notifyAuthor(contentId, CONTENT_REVIEW_APPROVED, ...)` ← **D-CM6 要删** |
| 人工审核**拒绝**发通知 | `ManualReviewService.java:85` | `CONTENT_REVIEW_REJECTED`（§8.7）保留 |
| 队列**超时丢弃**发通知 | `ManualReviewService.java:106` | 现同样发 `CONTENT_REVIEW_REJECTED` ← **改发新 `CONTENT_REVIEW_TIMED_OUT`**（§8.8 文案不同） |
| 发送封装 | `ManualReviewService.java:128-131` | `notifyAuthor(...)` → `notifications.send(authorId, type, title, body, null, null)` |
| 通知发送 API | `notify/service/NotificationService.java`（`send(recipientId, type, title, body, deepLinkToken, targetRef)`） | 6 参；`title/body` 落库供 OS push 兜底；`targetRef` 为深链目标引用（非随机 token） |

### 2.2 前端

| 事实 | 位置 | 说明 |
|---|---|---|
| 通知中心按 `type` 本地化 | `petgo_app/lib/features/notify/presentation/notification_center_page.dart:179-203` | `_typeLabel`(:179)/`_typeBody`(:192) `switch(item.type)`，末尾 `_ =>` 兜底（:188 标题、:201 空提示）；审核类 type 目前全落兜底 |
| **不渲染后端 title/body** | `notification_center_page.dart:251-252` | 注释明确「文案按 type 本地化…**不渲染后端 title/body**」——本 story 契约的前端依据 |
| 图标配色按 type | `notification_center_page.dart:164-178`（`_iconStyle`） | 新审核类 type 需补配色 case |
| `targetRef` 已透传 | `notification_center_page.dart:55`（`targetRef: item.targetRef`）；`notification_item.dart` | 判别位（POST/COMMENT、USER/PET）与深链均走 `targetRef`，**无需新增列** |
| 深链路由 | `core/router/deep_link_routes.dart` | 重置类通知的「设置新名称/上传新头像」深链目标在此接线 |
| 现有通知 arb 键 | `petgo_app/lib/l10n/app_id.arb:510-525`、`app_en.arb` 对应 | `notifyType*/notifyBody*`（VetReply…MilestoneNode），审核类**尚缺** |

---

## 3. 目标与非目标

**目标**

- G1：方案 §8 全部保留通知有唯一 arb 键（id + en），印尼语标注待润色；后端只发 type + 参数。
- G2：D-CM6 落实——正向结果（§8.6 通过 / §8.4 修改通过 / 恢复展示）零通知；仅负向（隐藏/违规/未通过/超时）推送。
- G3：新增 `NAME_RESET`/`AVATAR_RESET`/`CONTENT_REVIEW_TIMED_OUT` 三型并纳入 CHECK；§8.5/§8.9 复用 `CONTENT_REMOVED` + 判别位。
- G4：App 通知中心对全部审核类 type 正确本地化（标题/副标题/图标/深链），无兜底串泄漏。
- G5：Flyway 增量 `ALTER`（V53），`ddl-auto=validate` 通过。

**非目标**：审核判定逻辑、OS push 多语言、申诉、举报语义变更、账号级处置（story 9）。

---

## 4. 数据与迁移

### 4.1 V53（占位）— notifications.type CHECK 扩展

新增三个枚举值需纳入 `ck_notifications_type`。沿用 V40/V43 范式（DROP + ADD CHECK 全量重列）。

```sql
-- V53__extend_notification_types_moderation_reset.sql（占位号；实际号 CI 按合并顺序顺延）
-- 内容审核 story7：新增 NAME_RESET / AVATAR_RESET / CONTENT_REVIEW_TIMED_OUT。
-- 保留 CONTENT_REVIEW_APPROVED（D-CM6 后不再发送，但历史行/枚举对称保留，不从 CHECK 移除）。
ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type CHECK (type IN (
    'VET_REPLY', 'CONSULT_CLOSED', 'CONTENT_LIKED', 'CONTENT_COMMENTED', 'NEW_CONSULT_REQUEST',
    'PET_BIRTHDAY', 'COMPANION_ANNIVERSARY', 'MILESTONE_NODE', 'CONTENT_REMOVED', 'REPORT_REVIEWED',
    'CONTENT_REVIEW_APPROVED', 'CONTENT_REVIEW_REJECTED',
    'NAME_RESET', 'AVATAR_RESET', 'CONTENT_REVIEW_TIMED_OUT'));
```

**约束纪律：**
- 冻结迁移不改（V46 及以前）；只新起 `ALTER`（决策 E2）。
- **不删除** `CONTENT_REVIEW_APPROVED`：D-CM6 后停止发送，但枚举与历史行保留（移除会使旧行 validate 失败且破坏审计对称）。
- 判别位（POST/COMMENT、USER/PET）**不落新列**——复用既有 `notifications.target_ref`，无 schema 变更。
- 无需数据回填：既有行 type 均在新 CHECK 集合内。

---

## 5. 后端设计

### 5.1 枚举扩展 `NotificationType.java`

新增三值（UPPER_SNAKE，落库 varchar）：

```java
/** 名称（昵称/宠物名）违规重置为默认编码名 → 通知作者（§8.1/§8.2，story4）。USER/PET 变体经 targetRef 判别。 */
NAME_RESET,
/** 头像（用户/宠物）违规重置为默认头像 → 通知作者（§8.10，story5）。USER/PET 变体经 targetRef 判别。 */
AVATAR_RESET,
/** 帖子人工审核队列超过 3 天未处理、自动超时丢弃 → 通知作者（§8.8，story2/8；与 §8.7 REJECTED 文案不同故拆型）。 */
CONTENT_REVIEW_TIMED_OUT
```

### 5.2 「隐藏才通知」收口（D-CM6）

- **删 §8.6 正向通知**：`ManualReviewService.java:72` 的 `notifyAuthor(contentId, CONTENT_REVIEW_APPROVED, ...)` **移除**——审核通过静默转正（作者本就在「我的发布」可见）。**保留** `:76` 的 `auditService.record(...CONTENT_REVIEW_APPROVED...)`（审计与通知解耦，审计仍记）。`CONTENT_REVIEW_APPROVED` 枚举保留为「内部/审计用、永不产生通知」。
- **§8.4/§8.3 不新增**：名称/头像审核通过（story4/5）与逾期提醒一律不发通知；story4/5 的判定「通过」分支**不得**调用 `notifications.send`。
- **§8.9/§8.5 恢复展示不通知**：被下架内容恢复（story6/8 的「判定误报→自动恢复」）不发通知。

### 5.3 发送点改造与新增（type + 参数，不发显示串）

| 场景 | §ref | 发送点（owner story） | type | targetRef 判别位/深链 | title/body 兜底（OS push，印尼语默认） |
|---|---|---|---|---|---|
| 昵称违规重置 | §8.1 | 名称审核判定违规（story4） | `NAME_RESET` | `user-profile`（→设置新昵称页） | §8.1 原文浓缩 |
| 宠物名违规重置 | §8.2 | 名称审核判定违规（story4） | `NAME_RESET` | `pet-profile:{petId}`（→该宠物档案改名） | §8.2 原文浓缩 |
| 评论违规下架 | §8.5 | FR-55A 巡查下架（story3/8） | `CONTENT_REMOVED` | `comment:{postId}#{commentId}`（→内容详情定位评论） | §8.5 原文浓缩 |
| 帖子人工拒绝 | §8.7 | `ManualReviewService.java:85`（保留） | `CONTENT_REVIEW_REJECTED` | `null`（内容已丢弃、无深链） | §8.7 原文 |
| 帖子超时丢弃 | §8.8 | `ManualReviewService.java:106`（**改 type**） | `CONTENT_REVIEW_TIMED_OUT` | `null`（提示重发、无深链） | §8.8 原文 |
| 举报/巡查下架帖子 | §8.9 | 人工判定隐藏（story6/8） | `CONTENT_REMOVED` | `content-post:{postId}`（→作者本人可见页） | §8.9 原文浓缩 |
| 头像违规重置 | §8.10 | 头像审核判定违规（story5） | `AVATAR_RESET` | `user-profile` 或 `pet-profile:{petId}`（→重传头像） | §8.10 原文浓缩 |
| 举报已处理（举报人） | 既有 4.1 | `ModerationNotifyListener.java:24`（不改逻辑，仅补 arb） | `REPORT_REVIEWED` | `null`（模糊闭环、不导向内容） | 既有模糊文案 |

> **判别位约定**：`CONTENT_REMOVED` 与 `NAME_RESET`/`AVATAR_RESET` 靠 `targetRef` 前缀（`content-post:`/`comment:`/`user-profile`/`pet-profile:`）区分 POST/COMMENT 与 USER/PET 变体，App 据此选 arb body。**不新增枚举、不新增列**。深链一律用 `targetRef`（项目既有约定，非随机 token）。
>
> **兜底 title/body**：后端 `send()` 仍需传 `title/body`（供 OS push 弹窗，服务端语言=印尼语默认，取 §8 原文）；但 in-app 通知中心**一律忽略**并按 type 本地化（前端契约 §6）。此兜底串**属既有 OS-push i18n 债**，不在本 story 消除（§10 记录）。

### 5.4 护栏

- 日志：审核通知发送日志**禁记** PII / 内容原文 / 用户名 / 令牌 / `targetRef` 明文中的敏感段；仅记 `type + recipientId + 结果`（SLF4J JSON）。审核证据原文归 story8 的审核日志（§5.5 访问控制存储），不入通用业务日志。
- 事务：审核通知发送沿用既有 `notify` 收口（注意历史 `AFTER_COMMIT` 吞写 bug 已在 notify 模块修为 `REQUIRES_NEW`；本 story 新发送点复用同一 `NotificationService.send`，不自建事务监听）。
- `snake_case`↔`camelCase`：`target_ref`(DB) ↔ `targetRef`(Java/Dart/JSON)。

---

## 6. 前端设计

### 6.1 arb 键映射表（app_id.arb 主 / app_en.arb）

> 印尼语值取方案 §8 原文/浓缩，**统一标注「母语润色待运营（§7#1）」**（在 arb 的 `@key.description` 或提交 PR note 标注）。App 按 `type`（+ `targetRef` 派生的 USER/PET、POST/COMMENT 变体）选键，**不渲染后端串**。

| type | 判别 | title 键 | body 键 | app_id.arb 值（印尼语·待润色） | app_en.arb 值（英语） |
|---|---|---|---|---|---|
| `NAME_RESET` | USER | `notifyTypeNameReset` | `notifyBodyNameResetUser` | Nama penggunamu direset ke nama default karena melanggar Pedoman Komunitas. Atur nama baru kapan saja. | Your username was reset to a default name for violating the Community Guidelines. Set a new one anytime. |
| `NAME_RESET` | PET | `notifyTypeNameReset` | `notifyBodyNameResetPet` | Nama hewan peliharaanmu direset ke nama default karena melanggar Pedoman Komunitas. Atur nama baru kapan saja. | Your pet's name was reset to a default name for violating the Community Guidelines. Set a new one anytime. |
| `AVATAR_RESET` | USER | `notifyTypeAvatarReset` | `notifyBodyAvatarResetUser` | Foto profilmu direset ke foto default karena melanggar Pedoman Komunitas. Unggah foto baru kapan saja. | Your profile photo was reset to the default for violating the Community Guidelines. Upload a new one anytime. |
| `AVATAR_RESET` | PET | `notifyTypeAvatarReset` | `notifyBodyAvatarResetPet` | Foto hewan peliharaanmu direset ke foto default karena melanggar Pedoman Komunitas. Unggah foto baru kapan saja. | Your pet's photo was reset to the default for violating the Community Guidelines. Upload a new one anytime. |
| `CONTENT_REVIEW_REJECTED` | — | `notifyTypeReviewRejected` | `notifyBodyReviewRejected` | Postingan yang kamu kirim melanggar Pedoman Komunitas TailTopia dan tidak akan dipublikasikan. | Your post violated the TailTopia Community Guidelines and won't be published. |
| `CONTENT_REVIEW_TIMED_OUT` | — | `notifyTypeReviewTimedOut` | `notifyBodyReviewTimedOut` | Postingan yang kamu kirim melanggar Pedoman Komunitas TailTopia. Silakan kirim ulang postinganmu. | Your post violated the TailTopia Community Guidelines. Please submit it again. |
| `CONTENT_REMOVED` | POST | `notifyTypeContentRemoved` | `notifyBodyContentRemoved` | Kontenmu terdeteksi melanggar Pedoman Komunitas dan disembunyikan dari publik. Kamu masih bisa melihatnya di halamanmu. | Your content was found to violate the Community Guidelines and has been hidden from others. You can still see it on your page. |
| `CONTENT_REMOVED` | COMMENT | `notifyTypeCommentRemoved` | `notifyBodyCommentRemoved` | Komentarmu terdeteksi melanggar Pedoman Komunitas dan dihapus dari tampilan publik. Kamu masih bisa melihatnya di riwayatmu. | Your comment was found to violate the Community Guidelines and has been removed from public view. You can still see it in your history. |
| `REPORT_REVIEWED` | — | `notifyTypeReportReviewed` | `notifyBodyReportReviewed` | Terima kasih atas laporanmu. Kami telah menyelesaikan peninjauan. | Thanks for your report. We've completed our review. |

> 标题短句（type 头）另配 arb（示例 id/en）：`notifyTypeNameReset`=「Nama telah direset」/「Name reset」；`notifyTypeAvatarReset`=「Foto profil direset」/「Photo reset」；`notifyTypeReviewRejected`=「Postingan tidak dipublikasikan」/「Post not published」；`notifyTypeReviewTimedOut`=「Postingan gagal dipublikasikan」/「Post couldn't be published」；`notifyTypeContentRemoved`=「Konten disembunyikan」/「Content hidden」；`notifyTypeCommentRemoved`=「Komentar dihapus」/「Comment removed」；`notifyTypeReportReviewed`=「Laporan diproses」/「Report reviewed」。全部印尼语值标注待润色。

### 6.2 `notification_center_page.dart` 改造

1. **变体派生助手**：新增 `String? _refKind` 从 `item.targetRef` 前缀解析（`user-profile`/`pet-profile:`→USER/PET；`content-post:`→POST；`comment:`→COMMENT），供 body 选键。
2. **`_typeLabel`（:179）补 case**：`NAME_RESET`→`notifyTypeNameReset`；`AVATAR_RESET`→`notifyTypeAvatarReset`；`CONTENT_REVIEW_REJECTED`→`notifyTypeReviewRejected`；`CONTENT_REVIEW_TIMED_OUT`→`notifyTypeReviewTimedOut`；`REPORT_REVIEWED`→`notifyTypeReportReviewed`；`CONTENT_REMOVED`→ POST=`notifyTypeContentRemoved` / COMMENT=`notifyTypeCommentRemoved`。
3. **`_typeBody`（:192）补 case**：同上映射到 body 键，`NAME_RESET`/`AVATAR_RESET` 按 `_refKind` 选 User/Pet 变体，`CONTENT_REMOVED` 按 POST/COMMENT 选变体。
4. **`_iconStyle`（:164）补 case**：审核类 type 统一给「审核/警示」配色（如中性灰或品牌紫低饱和），与点赞红/评论紫区分；不引入红色变现禁区外的告警红过度渲染。
5. **兜底不变**：`_ =>` 仍保留（未知 type 走通用），但上述 type 不再落兜底。

### 6.3 深链 `deep_link_routes.dart`

- `NAME_RESET` + `targetRef=user-profile` → 个人资料编辑（改昵称）；`+pet-profile:{id}` → 该宠物档案编辑（改名）。
- `AVATAR_RESET` 同上，落到头像上传入口。
- `CONTENT_REVIEW_REJECTED`/`CONTENT_REVIEW_TIMED_OUT`/`REPORT_REVIEWED`：`targetRef=null` → 无深链（点击不跳，仅展示）。
- `CONTENT_REMOVED` POST → 作者本人可见的内容详情；COMMENT → 内容详情定位评论。
- 深链一律解析 `targetRef`，**不引入随机 token**。

### 6.4 生成物

改 arb 后需 `flutter gen-l10n`（或 build 触发）刷新 `app_localizations*.dart`；确保 `app_en.arb`/`app_id.arb` 键集**对齐**（缺键会 analyze 报错）。

---

## 7. 验收 AC（前后端分段）

> 层级：**L0** 静态（analyze/test/compile，无 DB）；**L1** 集成（Docker postgres+redis + Flyway validate 真跑）；**L2** 端到端（模拟器视觉 / 真机推送）。

### 后端段

- **AC-B1（L0）** `NotificationType` 新增 `NAME_RESET`/`AVATAR_RESET`/`CONTENT_REVIEW_TIMED_OUT` 三值；`mvn -B compile` 通过。
- **AC-B2（L0）** `ManualReviewService` approve 分支（原 :72）**已移除** `CONTENT_REVIEW_APPROVED` 通知发送，`auditService.record(...APPROVED...)` 保留；grep 全仓无任何 `send(...CONTENT_REVIEW_APPROVED...)` 调用。
- **AC-B3（L0）** 超时分支（原 :106）改发 `CONTENT_REVIEW_TIMED_OUT`（原 `CONTENT_REVIEW_REJECTED`）；reject 分支（:85）仍发 `CONTENT_REVIEW_REJECTED`。
- **AC-B4（L1）** Flyway V53 应用后 `ddl-auto=validate` 启动 `/actuator/health=UP`；插入三个新 type 的通知行成功、插入非法 type 被 CHECK 拒。
- **AC-B5（L0）** 审核类通知发送处日志断言：不含内容原文 / 用户名 / `targetRef` 敏感明细（仅 type + recipientId + 结果）。
- **AC-B6（L1）** `send()` 对 `CONTENT_REMOVED` 传入 `targetRef=content-post:{id}` / `comment:{postId}#{id}` 落库正确（`target_ref` 列），无 PII 入库。

### 前端段

- **AC-F1（L0）** `app_id.arb` 与 `app_en.arb` 新增 §6.1 全部键，两文件键集一致；`flutter gen-l10n` + `flutter analyze` 零告警。
- **AC-F2（L0）** `notification_center_page.dart` 的 `_typeLabel/_typeBody/_iconStyle` 覆盖全部审核类 type；`_typeBody` 对 `NAME_RESET/AVATAR_RESET` 按 `_refKind` 选 USER/PET、对 `CONTENT_REMOVED` 选 POST/COMMENT；单测覆盖变体分支。
- **AC-F3（L0）** 通知项**不渲染** `item.title/item.body`（保持 :251-252 契约）；断言渲染取 arb。
- **AC-F4（L0）** `deep_link_routes.dart`：`NAME_RESET/AVATAR_RESET` 按 `targetRef` 路由到名称/头像编辑入口；REJECTED/TIMED_OUT/REPORT_REVIEWED 无深链（点击不跳）。
- **AC-F5（L2·本地）** 模拟器（Android）造 8 类通知，通知中心标题/副标题/图标随 App 语言（id/en）正确本地化，深链点击落到正确页；印尼语文案与 §8 语义一致。

### 契约段（跨栈）

- **AC-C1（L0）** 全仓 grep：正向结果（§8.6 通过 / §8.4 修改通过 / 恢复展示）**无任何** `notifications.send` 调用（D-CM6 收口证据）。
- **AC-C2（文档）** `CROSS-STORY-DECISIONS.md` 追加 D-CM6 通知类型总表（type ↔ §ref ↔ 推送与否 ↔ arb 键），供 story2–6 发送点对齐。

**AC 合计：13 条**（后端 6 + 前端 5 + 契约 2）。

---

## 8. 依赖与契约（与 story2/4/5/6 协同）

| 协同 story | 本 story 提供 | 对方负责 |
|---|---|---|
| story2 帖子审核 | `CONTENT_REVIEW_TIMED_OUT`/`REJECTED` type + arb；approve 停发 | 队列超时/拒绝的**触发**（改 `ManualReviewService` 已在本 story 落）；帖子下架 `CONTENT_REMOVED(POST)` 发送点 |
| story3 评论审核 | `CONTENT_REMOVED(COMMENT)` type 复用 + `notifyBodyCommentRemoved` | FR-55A 巡查下架的发送调用 + `targetRef=comment:` 构造 |
| story4 名称审核 | `NAME_RESET` type + USER/PET arb + 深链 | 名称判定违规→重置→`send(NAME_RESET, targetRef=user-profile|pet-profile:{id})`；**通过不发** |
| story5 头像审核 | `AVATAR_RESET` type + USER/PET arb + 深链 | 头像判定违规→重置→`send(AVATAR_RESET, ...)`；**通过不发** |
| story6 举报处置 | `CONTENT_REMOVED(POST)` 复用；`REPORT_REVIEWED` 既有 | 举报驱动下架→`send(CONTENT_REMOVED, targetRef=content-post:{id})`；误报恢复**不发** |
| story8 管理后台 | 全部下架/重置动作的 type 契约 | 后台处置动作触发对应 `send`；审核日志原文留存（§5.5） |

**统一契约（写入 CROSS-STORY-DECISIONS.md，AC-C2）：** 各 story 发送审核通知时，**只调 `NotificationService.send(recipientId, type, 印尼语兜底title, 印尼语兜底body, deepLinkToken=null, targetRef)`**，type 与 targetRef 前缀严格按本 §5.3 表；**正向结果一律不 send**。

---

## 9. 云端（headless）执行须知

- ✅ **云端可做（L0 全绿）**：枚举扩展 + `ManualReviewService` 改造 `mvn -B compile|package`；arb 增键 + `flutter gen-l10n` + `flutter analyze` + `flutter test`（含 `_typeBody` 变体单测、深链路由单测）。
- ⚠️ **L1 留本地/CI**：V53 迁移 + `validate` 真跑需 Docker postgres；云沙箱不保证 daemon。云端只跑到 L0 绿灯，Completion Notes 标「AC-B4/B6 L1 待本地/CI」。CHECK 契约以 CI/L1 绿为准。
- ❌ **L2 回本地**：AC-F5 模拟器（Android）视觉本地化 + 深链点击、印尼语语义核对必须本地。

---

## 10. 风险与待确认

1. **印尼语母语润色（§7#1）**：§6.1 全部 id 值为草稿/浓缩，**上线前须运营母语复核**（arb 已标注 `待润色`）。当前值可过 L0/L1，L2 语义以运营终稿为准。
2. **§8.7 vs §8.8 拆型**：本 story 主张新增 `CONTENT_REVIEW_TIMED_OUT`（因 App 只能按 type 本地化、两者文案不同）。替代方案=共用 `REJECTED` + 判别参数；选拆型更契合前端约定，代价是多一枚举值 + CHECK 一行。**已在本 story 定为拆型**，若架构反对可回退。
3. **OS push 多语言债**：in-app 已按 type 本地化，但 OS 系统推送弹窗仍用后端兜底串（服务端印尼语），非用户 App 语言。此为既有 i18n 债，本 story**不消除**，记录待后续（需推送侧本地化或客户端本地组装）。
4. **`CONTENT_REVIEW_APPROVED` 保留为惰性枚举**：D-CM6 后永不发送但保留在枚举/CHECK。需 story8 后台不误用其触发通知；已在 AC-C1 grep 拦截。
5. **`targetRef` 判别位耦合**：POST/COMMENT、USER/PET 变体依赖 `targetRef` 前缀约定，各发送方 story 须严格遵守 §5.3 前缀格式，否则 App 落兜底串。建议在后端加一处 `targetRef` 前缀常量/校验工具供各 story 复用（可选，story8 收拢）。

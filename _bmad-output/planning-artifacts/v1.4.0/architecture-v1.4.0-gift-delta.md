---
title: "V1.4.0 gift 主题 · 架构增量"
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
lastStep: 8
completedAt: '2026-10-18'
inputDocuments:
  - _bmad-output/planning-artifacts/v1.4.0/送礼/1-4-0送礼prd.md
  - _bmad-output/planning-artifacts/v1.4.0/送礼/ui-1-4-0-gift-integrated-v1.html
  - _bmad-output/planning-artifacts/v1.4.0/送礼/ui-admin-1-4-0-gift-integrated-v1.html
  - _bmad-output/planning-artifacts/v1.4.0/decision-log.md                         # ③ 节
  - _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-tailrent-level-delta.md  # ① 契约
  - _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-multi-pet-delta.md       # ② 契约
  - _bmad-output/planning-artifacts/architecture.md
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md                  # 「2026-10-18」节
  - _bmad-output/project-context.md
workflowType: 'architecture'
project_name: 'petgo-platform'
user_name: 'Dai'
date: '2026-10-18'
version: v1.4.0
theme: gift
status: complete
baseline: _bmad-output/planning-artifacts/architecture.md
code_baseline: dev_1.4.0 @ 688f9bd5（地基 0.1 + ①② 规划文档；①② 代码在各自功能分支开发中）
---

# V1.4.0 gift 主题 · 架构增量

> 只写本主题新增或改变的技术决定，均对照代码。**权威顺序**：`decision-log.md`（③ 节）> `1-4-0送礼prd.md` > 本文件 > UI 稿。
> 🔴 **与 ①② 并行开发（Dai 2026-10-18 拍板）**：② 的多宠接口（`ContentPetQuery` / `PetQueryService` / 详情页关联宠物）与 ① 的等级页路由在本分支上**不存在**。本线在送礼模块内定义自有端口、先以单宠物方式实现，①② 合入 `dev_1.4.0` 后在对齐分支替换实现并解 App 页面冲突（见 §6）。

## 0. 项目上下文分析（2026-10-18 核实 dev_1.4.0 @ 688f9bd5）

### 0.1 范围
FR-148 送礼（三个入口、礼物目录与专属礼物、按物种排序、数量 ×1~×99、PawCoin / QRIS 单渠道、QRIS 单笔 ≥ Rp10,000、送出动效、我的送礼记录、付款后无法送达自动退回 PawCoin）· FR-149 礼物展示（Diary Tab 统计卡中间格 = 收到礼物总价值、礼物页 = 礼物墙 + 粉丝榜（显示累计金额）、详情页礼物区、H5 名片 Hadiah 列、未读即合并通知）· AB-35 礼物目录 + 送礼 QRIS 最低金额 · AB-36 全站送礼记录 + 抽屉「收送礼记录」。埋点 7 个。UI 稿 App 42 屏 + 3 张图标规格、后台 13 屏。**复杂度：中高**（涉钱；与 ①② 并行）。**OQ-1 合规税务口径上线前须有结论，不阻塞开发。**

### 0.2 已具备（地基 0.1）
`PaymentPurpose.GIFT`（+ `PaymentDisplayNo` PAYGIFT、GemPay "Pet Gift"）· `GiftSentEvent(giftOrderId, senderId, receiverOwnerId, petId, totalAmount)` · `PET_GIFT_RECEIVED` / `GIFT_UNDELIVERED_REFUND` · 抽屉 `data-utab-panel="gifts"` 容器 · `GrowthQueryService.getLevel`（占位：真实用户 Lv1）。

### 0.3 现状要点
1. **PawCoin 不可转让守卫**（`ConsultShopBoundaryTest.walletHasNoCrossUserTransferPath`:179）只扫 `PawCoinWalletService`：禁 `transfer` / `gift` / `移交` / `赠予`、方法形参 userId ≤ 1。送礼只对送礼人 `debit(SPEND)`（FLOAT_LIABILITY → PLATFORM_REVENUE），收礼方不入任何币；钱包服务一行不改。
2. **购买范式** `KeepsakePurchaseService`：幂等键 `{sku}:{refToken}:{渠道}`（一物只买一次，不适用送礼）、显式拒绝 MIXED（:113）、PawCoin 同步成交、QRIS `createIntent(…, Duration.ofMinutes(60))`（`PAY_WINDOW` 硬编码）+ PENDING 行；`KeepsakePaidHandler`（`@EventListener` + `@Transactional(MANDATORY)`）/ `KeepsakePaymentFailedHandler` 按 `KeepsakeSku.fromPurpose` 过滤，GIFT 会被跳过。`createIntent` 限流 20 次 / 分钟（:84）。
3. **全链路无 QRIS 最低金额校验**（`PaymentIntent` 只校验 > 0）；Rp100 下限只在定价配置。
4. **退回**：`credit(userId, coins, type, refType, refId, idemKey)`（:53）；对方科目按 type：REFUND → `REFUND_OUT`、TOPUP → `CASH_IN`、BONUS → `PLATFORM_REVENUE`（:120-126）。
5. **互动通知**：`sendInteraction`（`NotificationService`:177）白名单 `INTERACTION_TYPES`（:46）与 `InteractionCopyRenderer.title`（:113）只认 4 类，须扩 `PET_GIFT_RECEIVED`；未读即合并靠 `uq_notifications_unread_aggregate` + `notification_actors`；推送去抖 `debounceKey`（:294）只认两前缀 → `GIFT:PET:` 天然每次都推；拉黑屏蔽在各监听器（参照 `ContentNotifyListener.suppressed`:69）。
6. **展示依赖**：`VisitorStats(happyMomentCount, consultCount, milestoneCompleted, milestoneTotal)`；访客与主人统计都经 `TimelineService.getStats(ownerId)`（单宠假设，② 改造对象）；`InAppVisitorPetController.requireVisible`（:106）双向拉黑（我拉黑主人 403、主人拉黑我 404）；`ContentDetailResponse` 不含 petId，拉黑单向 `isHidden(viewer, author)`；H5 `card.html`:269-286（Konsultasi :281，Tonggak 为 0 整列隐藏）。
7. **注销**：钱包流水与宠物档案物理删除；`shop_orders` 匿名化保留 → 送礼订单同样保留、宠物外键 `ON DELETE SET NULL`。
8. **后台**：礼物目录照 `AdminUserTagController` + `AdminTagIconService`（PNG / WebP ≤ 512KB、正方形、OSS 公共目录）；导出 `AdminExportWriter.xlsx`；分组 `G_CONFIG` / `G_ORDERS`；权限 `模块.动作`。
9. **App**：`PayChannelPicker` 先关抽屉再 push 充值、不 await（:112）；`showQrPaymentSheet` 有 `onAborted`；钱包流水 `_typeLabel` 只看 type（`refType` 已解析未用）；DiaryHeader → `PetInfoCard` 的 Consultations 格（:166），访客 / 主人共用；详情页 `_actions()` 点赞 + 分享卡；无金额缩写公共工具；动效可用 Rive 或 CustomPainter，`disableAnimations` 有先例。

### 0.4 横切关注点
钱的正确性（每笔幂等、回调与轮询只交付一次、退回幂等且可重试）· 不可转让闭环 · 交付时二次校验可送达 · 拉黑单向规则 + 通知屏蔽 · 礼物快照（下线 / 改名 / 改价不影响已收）· 与 ①② 并行（自有端口 + 对齐 story）。

### 0.5 拍板（2026-10-18，Dai，均按建议）
- **P-1 QRIS 付款后无法送达的退回记账**：钱包流水 `credit(type = TOPUP, refType = GIFT_REFUND)`——总账走 `CASH_IN → FLOAT_LIABILITY`（这笔钱以现金进来，参照充值）；App 按 `refType` 显示「Hadiah dikembalikan」。PawCoin 渠道同步成交、不存在付款后无法送达。**财务确认为上线前置，不阻塞开发。**
- **P-2 退回失败自动重试**：`GiftRefundRetryJob` 每 10 分钟扫 `UNDELIVERED_REFUND_PENDING`，最多 5 次，超过置 `REFUND_MANUAL`，后台异常单显示。
- **P-4 礼物不设 emoji 字段**：通知 / 推送 / Toast 用礼物名（按账号语言取 EN / ID 快照），如「Andi 给你的 Mochi 送了 Tulang ×5」；取代 PRD 文案中的 emoji 写法。
- **P-5 退回通知由后端按账号语言渲染 title / body**（含宠物名、金额、原因），App 直接显示——与 `OPS_ANNOUNCEMENT`、② 名额到期通知同一例外。
- **P-6 封号主人的宠物礼物页开放**（可看礼物墙、粉丝榜、可送礼）；Diary Tab 访客视图对封号主人仍显示失效。
- **P-3 与 ①② 并行**（2026-10-18 拍板）：自有端口 + 单宠过渡实现，①② 合入后对齐分支替换。

### 0.6 技术基线
沿用；不引新依赖（送出动效用 CustomPainter，参照 `milestone_celebration.dart` 的 `_Confetti`，尊重 `disableAnimations`）。后端包 `com.tailtopia.gift.{domain,repository,service,port,listener,web}`、后台 `admin.gift`；App `features/gift/`；金额缩写新建公共工具 `shared/format/idr_compact.dart`（App）与对应 Java 工具（H5）。

## 1. 数据模型

**AD-1 · 礼物目录 `gift_catalog`。** `id`、`public_token VARCHAR(32) UNIQUE`、`name_en` / `name_id VARCHAR(40)`、`icon_url`、`price BIGINT CHECK (≥ 100)`、`for_cat` / `for_dog` / `for_other BOOLEAN`（三者皆 false = 通用）、`kind VARCHAR(16) CHECK IN ('NORMAL','EXCLUSIVE')`、`min_level INT NULL`（EXCLUSIVE 必填，CHECK）、`sort_order INT`、`status VARCHAR(16) CHECK IN ('ONLINE','OFFLINE')`、`created_at` / `updated_at` / `updated_by`。只下线不物理删除。首批 40 个普通礼物由运营在后台录入（图标资产按 UI 稿 I3 出图规范，非代码交付）。

**AD-2 · 送礼订单 `gift_orders`。** `id`、`public_token UNIQUE`、`pet_name VARCHAR(40)`（宠物名快照：宠物删除后退回通知与记录仍可显示，Story 1.1 补）、`sender_id BIGINT NOT NULL`（注销后保留，显示「已注销用户」）、`pet_id BIGINT NULL FK pet_profiles ON DELETE SET NULL`、`owner_id BIGINT NOT NULL`、`gift_id FK gift_catalog`、**快照** `gift_name_en` / `gift_name_id` / `gift_icon_url` / `unit_price`、`quantity INT CHECK (1..99)`、`total BIGINT`、`channel CHECK IN ('PAWCOIN','QRIS')`、`payment_intent_id UNIQUE NULL`、`status CHECK IN ('PENDING','DELIVERED','EXPIRED','FAILED','UNDELIVERED_REFUND_PENDING','UNDELIVERED_REFUNDED','REFUND_MANUAL')`、`undelivered_reason VARCHAR(24)`、`refund_attempts INT`、`refund_error`、`source VARCHAR(16)`（diary_tab / content_detail / gift_page）、`idem_key UNIQUE`、`created_at` / `delivered_at` / `refunded_at`。索引 `(sender_id, created_at DESC)`、`(owner_id, created_at DESC)`、`(pet_id, status)`、`(status)`。

**AD-3 · 展示汇总（交付同事务维护）。** `gift_pet_wall(pet_id, gift_id, unit_price, gift_name_en, gift_name_id, gift_icon_url, count, PRIMARY KEY (pet_id, gift_id, unit_price))`——同一礼物改价后按价位分组，墙按单价降序、同价按数量降序；`gift_pet_supporters(pet_id, sender_id, total_amount, gift_count, last_sent_at, PRIMARY KEY (pet_id, sender_id))`——粉丝榜按 `total_amount DESC, last_sent_at DESC`。宠物总价值 = 墙 `Σ unit_price × count`（只含 DELIVERED）。两表对 `pet_profiles` `ON DELETE CASCADE`。

**AD-4 · 送礼 QRIS 最低金额。** `pricing_config.gift_qris_min_amount BIGINT NOT NULL DEFAULT 10000 CHECK (≥ 100)`；只在送礼服务发起 QRIS 前校验，不进通用 `createIntent`。

## 2. 送礼与交付

**AD-5 · 自有端口 `gift.port.GiftPetDirectory`（P-3）。** `Optional<GiftPet> pet(long petId)`（id、ownerId、name、avatarUrl、species、ownerActive）、`List<GiftPet> petsOfPost(long postId)`。过渡实现 `SinglePetGiftPetDirectory`：读 `pet_profiles` 与 `content_posts.pet_id`（只 Diary 有）。①② 合入后对齐 story 换成 `ContentPetQuery.petsOf` / `PetQueryService.get` 的实现，**签名不变、业务代码不动**。

**AD-6 · 发起 `POST /api/v1/pets/{petId}/gifts`**（`{giftToken, quantity, channel, requestId, source, postToken?}`，需登录 USER）：
- 校验：宠物存在且主人未注销（`ownerActive` 为 false 但未注销的封号主人**允许**）；不是自己的宠物（422 `gift-own-pet`）；`isBlocked(sender, owner)` → 403 `gift-blocked`（只拦单向）；礼物 ONLINE；EXCLUSIVE 时 `getLevel(sender)` 参与且 ≥ `min_level`（否则 403 `gift-locked`）；`quantity ∈ [1, 99]`；`channel = QRIS` 时 `total ≥ gift_qris_min_amount`（否则 422 `gift-qris-below-min`）；MIXED 拒绝。
- 幂等：`idem_key = GIFT:{senderId}:{requestId}`（App 每次点「送出」生成 UUID，防连点）。
- **PawCoin**：同事务 `wallet.debit(sender, total, SPEND, refType = "GIFT", refId = orderId, idemKey = GIFT_COIN:{orderToken})` → `GiftDeliveryService.deliver`；余额不足 409 `pawcoin-insufficient`。
- **QRIS**：`createIntent(sender, GIFT, QRIS, total, "IDR", GIFT_QRIS:{orderToken}, 60 分钟)` + PENDING 订单，返回与 Keepsake 同形的支付信息。

**AD-7 · 交付 `GiftDeliveryService.deliver(order)`。** 交付前再校验可送达（宠物未删、主人未注销、`isBlocked(sender, owner)` 为 false）：
- 可送达 → `DELIVERED`、维护墙与粉丝榜、`publishEvent(GiftSentEvent)`（① 成长值、本线通知均 AFTER_COMMIT 监听）。
- 不可送达（只可能出现在 QRIS 到账时）→ `UNDELIVERED_REFUND_PENDING` + 原因（`PET_DELETED` / `OWNER_DELETED` / `SENDER_BLOCKED`）→ **交付事务提交后**在独立事务里退回（AD-9；不能同事务——`credit` 抛异常会把支付回调事务标成只能回滚，丢掉已到账状态，Story 1.2 核实修正）。
- `GiftPaidHandler`（`@EventListener` + `@Transactional(MANDATORY)`，`purpose = GIFT`）调 `deliver`；`payment_intent_id` 唯一 + 状态守卫保证回调与轮询只交付一次（AC8）。`GiftPaymentFailedHandler` → `FAILED` / `EXPIRED`，不发事件、不通知（AC7）。

**AD-8 · 通知。** `GiftNotifyListener`（AFTER_COMMIT）：`DELIVERED` → 新增专用 `sendGiftReceived`（现有 `sendInteraction` 对同一触发人直接返回不推送，不满足「每份都推」；Story 1.2 核实修正），语义同 `sendInteraction(owner, PET_GIFT_RECEIVED, sender, aggregateKey = GIFT:PET:{petId}, targetRef = 宠物 token…, pushBody = 单笔文案)`；**扩 `INTERACTION_TYPES` 与 `InteractionCopyRenderer`**（标题 / 合并文案「Andi 等 3 人给 Mochi 送了礼物」）；`isHidden(owner, sender)` 为 true 时不通知（AC9）；推送天然不去抖（AC16）。退回 → `send(sender, GIFT_UNDELIVERED_REFUND, …)`（含宠物名、金额、原因），深链送礼记录。

**AD-9 · 未送达退回（P-1 / P-2）。** 交付事务提交后、独立事务执行；`wallet.credit(sender, total, TOPUP, refType = "GIFT_REFUND", refId = orderId, idemKey = GIFT_REFUND:{orderToken})` 成功 → `UNDELIVERED_REFUNDED` + 退回通知；失败 → 保持 `UNDELIVERED_REFUND_PENDING`、`refund_attempts++`；`GiftRefundRetryJob`（每 10 分钟，`@Scheduled`）重试，≥ 5 次置 `REFUND_MANUAL`。送礼人无送礼成长值（未发 `GiftSentEvent`）。

## 3. 展示与查询接口

**AD-10 · App 接口。**
- `GET /api/v1/gifts/catalog?petId=`：按价位升序分组，组内按「适用该宠物物种 → 通用 → 其他」再按 `sort_order`；EXCLUSIVE 按送礼人等级过滤，解锁的放「Eksklusif」组（第一批在前），`lockBlock: {requiredLevel}` = 下一批未解锁专属礼物的最低门槛（全解锁为 null）。
- `GET /api/v1/pets/{petId}/gifts`：汇总（总价值、礼物个数、送礼人数）+ 礼物墙；`GET /api/v1/pets/{petId}/gifts/supporters?cursor=`：粉丝榜（排名、头像、昵称、礼物图标与数量、累计金额；**主人查看时隐藏其拉黑的送礼人**，AC12a）。可见性沿用访客视图的拉黑规则。
- 统计：`VisitorStats` / `ArchiveStatsResponse` **新增** `giftTotalValue`（保留 `consultCount` 供旧版 App）；内容详情（Diary）新增 `giftPets: [{petId, name, avatarUrl, totalValue, wallPreview[]}]`（经 `GiftPetDirectory.petsOfPost`）。
- 我的送礼记录 `GET /api/v1/me/gift-orders?cursor=`（只送出，时间倒序，含状态、宠物是否已删除）。
- H5：`CardPageController` 下发 `giftTotalValue`，`card.html` Konsultasi 列换 Hadiah（始终显示、为 0 显示 Rp0），Tonggak 维持现状。金额缩写规则见 PRD §3.2 影响面（Java 与 Dart 各一份同规则实现 + 同组测试用例）。

## 4. 后台、注销、App

**AD-11 · 后台。** 礼物目录页（`G_CONFIG`，`gift.catalog_view` / `gift.catalog_edit`，照用户标签页；顶部 QRIS 最低金额用服务端确认弹窗、下线用原生 confirm）；送礼记录页（`G_ORDERS`，`gift.order_view`，筛选 + 异常单 + xlsx 导出）；抽屉「收送礼记录」（地基容器，送出 / 收到两子列表）。权限新码不预授；审计照惯例。

**AD-12 · 注销与删除。** 送礼人注销：订单保留（`sender_id` 指向匿名化用户）、粉丝榜与后台显示「已注销用户」；主人注销 / 删宠物：墙与粉丝榜随宠物删除，订单 `pet_id` 置空保留（记录页显示「该宠物已不存在」）。

**AD-13 · App。** `features/gift/`：送礼面板（B 组）与 `PayChannelPicker` **依次弹出不叠层**；`PayChannelPicker` 加可选参数：`qrisDisabledReason`（金额不足置灰 + 提示）、扣款期间保持打开转圈、「先充值」await 回原流程（照 `vet_timed_pay_page`）——不传时行为不变；QRIS 过期 / 失败 `onAborted` 提示；送出动效 CustomPainter 1–2 秒（尊重 `disableAnimations`）；礼物页（E 组）；DiaryHeader 中间格换「礼物总价值」、访客视图加送礼按钮（A 组）；详情页礼物区（A3–A6）；送礼记录（F 组，钱包页入口、流水按 `refType` 显示「Kirim hadiah / Hadiah dikembalikan」）；通知中心（G 组）。

## 5. 实现模式
- 送礼代码**不得**改 `PawCoinWalletService`；送礼服务内标识符与注释避开 `transfer` 字样（守卫可能扩面）。
- 每笔幂等：发起 `GIFT:{sender}:{requestId}`、扣币 `GIFT_COIN:{orderToken}`、意图 `GIFT_QRIS:{orderToken}`、退回 `GIFT_REFUND:{orderToken}`，集中在 `GiftIdemKeys`。
- 订单状态只经 `GiftDeliveryService` / 退回服务改变；汇总表只在交付同事务维护。
- 取宠物只经 `GiftPetDirectory`（对齐时只换实现）。
- 对外标识一律 `public_token`（礼物、订单）；宠物沿用 petId 路由（与访客视图一致）。
- 测试：AC1 ~ AC17、AC10a / AC10b 逐条映射；回调与轮询并发只交付一次；退回幂等与重试；不可转让守卫照常绿。

## 6. 结构、迁移与对齐

**6.1 迁移（预分配）**：`V20261018_1700`（`gift_catalog` / `gift_orders` / `gift_pet_wall` / `gift_pet_supporters`）· `V20261018_1701`（`pricing_config.gift_qris_min_amount`）。

**6.2 与 ①② 的对齐（对齐分支 `feat/1.4.0-gift-align`，①②③ 均合入 `dev_1.4.0` 后本地做）**
- 后端：`GiftPetDirectory` 换 ② 实现；统计 `giftTotalValue` 接到 ② 按宠物的统计；详情 `giftPets` 用 ② 的关联宠物（多宠）。
- App：DiaryHeader（② 切换器 + ③ 礼物格）、详情页（② 关联宠物胶囊 + ③ 礼物区与 A7 选宠物抽屉）、访客视图合并；专属锁区块跳 ① 等级页（`/me/growth`）。
- ① 成长值：`GiftSentEvent` 由 ① 监听，无需改动。

## 7. 校验
覆盖：AC1 / AC2 / AC9 → AD-6 / AD-13 · AC3 / AC3a → AD-10 · AC4 → AD-4 / AD-6 · AC5 → AD-6 / AD-7（成长值由 ① 入账，① 合入前不触发）· AC6 / AC8 → AD-7 · AC7 → AD-7 · AC10 → AD-6（只读可收）· AC10a → AD-7 / AD-9 · AC10b → AD-10 · AC11 ~ AC17 → AD-3 / AD-8 / AD-10 / AD-12；埋点 → AD-13。一致性：无新依赖；钱包服务零改动；地基契约只引用。

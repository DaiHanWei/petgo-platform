---
title: "V1.5.0 会话 ② 送礼与礼包（gift-pack）· 架构增量"
status: complete
completedAt: '2026-11-14'
version: v1.5.0
theme: gift-pack
branch: feat/1.5.0-gift-pack（从 dev_1.5.0 切出；送礼优化 Epic 1~3 在前、超值礼包 Epic 4~7 在后，同一分支串行）
code_baseline: dev_1.5.0（9680309cf，= main 355a37dee + 地基 Story 0.1）
baseline: _bmad-output/planning-artifacts/architecture.md
inputDocuments:
  - _bmad-output/planning-artifacts/v1.5.0/ui-gift-optimization/1-5-0送礼优化prd.md            # PRD A（FR-187~192、FR-225、AB-43）
  - _bmad-output/planning-artifacts/v1.5.0/ui-gift-optimization/ui-1-5-0-gift-optimization-integrated-v1.html
  - _bmad-output/planning-artifacts/v1.5.0/ui-gift-optimization/ui-admin-1-5-0-gift-category-integrated-v1.html
  - _bmad-output/planning-artifacts/v1.5.0/超值礼包/1-5-0超值礼包prd.md                         # PRD B（FR-194/195、AB-41、§3.8 付费抽屉改版）
  - _bmad-output/planning-artifacts/v1.5.0/超值礼包/ui-1-5-0-value-pack-integrated-v1.html
  - _bmad-output/planning-artifacts/v1.5.0/超值礼包/ui-admin-1-5-0-value-pack-integrated-v1.html
  - _bmad-output/planning-artifacts/v1.5.0/运营活动规则引擎/1-5-0运营活动规则引擎prd.md §3.3 / §6   # ⑥ 调用本线发放接口
  - _bmad-output/planning-artifacts/v1.5.0/decision-log.md
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md「2026-11-14 V1.5.0 六会话共用地基」
---

# V1.5.0 会话 ② 送礼与礼包 · 架构增量

> 只写本线新增或改变的技术决定，均对照 `dev_1.5.0` 代码核实。**权威顺序**：`CROSS-STORY-DECISIONS.md` > `decision-log.md` > 两份 PRD（变更记录最晚的一条为准）> 本文件 > UI 稿。PRD 与代码现状冲突处已在 §1 列出，按本文件决定执行。
>
> 路径缩写：`B/` = `petgo-backend/src/main/java/com/tailtopia/`，`R/` = `petgo-backend/src/main/resources/`，`A/` = `petgo_app/lib/`，`T/` = `petgo-backend/src/main/resources/templates/admin/`。

## 0. 范围

| 部分 | 需求 | 本线做什么 |
|---|---|---|
| A 送礼优化 | FR-187 选礼物目录、FR-188 包装页与祝福语、FR-189 送出动画、FR-190 主人拆礼物、FR-191 礼物详情 / 访客 / 举报、FR-192 去粉丝榜与总价值、FR-225 / AB-43 礼物分类 | 后端 + 后台 + App |
| B 超值礼包 | FR-194 礼包购买、FR-195 权益次数与「Koin & Jatah Saya」、AB-41 后台、§3.8 通用付费抽屉改版 | 后端 + 后台 + App |
| 对外契约 | 会话 ⑥ 运营活动规则引擎调用的「发放权益次数」接口 | §5，**Story 4-1 最先做**，单独可合进 dev |

不做：选礼盒 / 付费礼盒、补差价升级、手动扣减次数、新增送礼埋点（PRD A §4）、商城优惠券并入合并页。

## 1. 已核实的代码现状与 PRD 出入（以本表处置为准）

| # | PRD / UI 稿写法 | `dev_1.5.0` 实际 | 处置 |
|---|---|---|---|
| 1 | 礼物分类表名 `gift_category`（后台稿执行说明） | 项目约定表名复数 | 用 **`gift_categories`**（D-1） |
| 2 | 礼包跳转页 `OpsAppPage.VALUE_PACK`（后台稿 N） | 地基已加 **`OpsAppPage.STARTER_PACK`**（`B/ops/target/OpsAppPage.java`） | 一律用 `STARTER_PACK`，**不改后端枚举**；本线只做 App `ops_target.dart` 的值与跳转、后台提示文案 |
| 3 | `gift_orders` 支付方式加 `PACK_CREDIT`、金额记 0 | `GiftOrder.channel` 类型是共享枚举 `pay.domain.PayChannel`；库里 `ck_gift_orders_total CHECK (total = unit_price * quantity)`，金额 0 会被拒 | `PayChannel` **末尾追加** `PACK_CREDIT`（E-1 只追加规则）；重建 `ck_gift_orders_channel` 与 `ck_gift_orders_total`（D-17）；所有接受客户端 `PayChannel` 的非送礼入口显式拒绝 `PACK_CREDIT`（D-18） |
| 4 | 性格测试用次数解锁「同上：价格记 0、来源记权益」写 `keepsake_purchases` | `keepsake_purchases.price_idr CHECK ≥ 1`；`OrderCenterService` 把 PAID 的 keepsake 行列进「我的订单」，而 PRD 要求「用权益次数解锁**不生成订单**」 | **不写 `keepsake_purchases`**：直接走 `TailsonalityKeepsakeGranter` 的解锁 SQL，使用记录只进权益流水（D-19） |
| 5 | KTP 用次数解锁「写 `id_card_hd_purchases` 时价格记 0、来源记权益」 | 该表无价格列；`ck_id_card_hd_purchases_channel` 只允许 QRIS / PAWCOIN（V20260817_2313 注明刻意不放宽）；该表不进订单中心 | 写一行 `pay_channel = 'PACK_CREDIT'`、`payment_intent_id = NULL`；**只**把该 CHECK 放宽到加 `PACK_CREDIT` 一值（D-19） |
| 6 | 「收礼通知的跳转目标改为这一笔订单」 | `PET_GIFT_RECEIVED` 按宠物**未读合并**（`aggregate_key = GIFT:PET:{petId}`），`target_ref = petId`；旧版 App 按 `int.tryParse(targetRef)` 进 `/pets/{id}/gifts`；`notifications.variant` 只有 VARCHAR(16)，放不下 32 位订单 token | **后端通知不改**（旧版照旧）。新版 App 点击 → 新路由 `/pets/{petId}/gifts?open=latest`：主人视角有未拆 → 直接拆**最新一笔未拆**（G1），没有未拆 → 打开最新一笔的详情（H1）。与 App 稿 F 泳道「未读合并的 → 拆最新那份，其余在 F2」一致（D-9） |
| 7 | 免费礼物「`GIFT_RECEIVED` 按礼物原价、`GIFT_SENT` 不发」 | `GiftSentEvent(giftOrderId, senderId, receiverOwnerId, petId, totalAmount)`，`totalAmount = order.total`；`GrowthGiftListener` 两条都按它算 | 事件**加第 6 个字段** `boolean paidByCredit`，`totalAmount` 改为**面值** `unit_price × quantity`（对付费单与原值相同）；监听在 `paidByCredit` 时跳过 `GIFT_SENT`（D-16） |
| 8 | 举报「新增一个举报目标类型」 | 没有通用举报目标表：内容举报 `content_reports` 按帖、场所举报 `place_reports` 独立表 + 统一工单 `TicketType.PLACE_REPORT` 分支 | 照场所举报：新表 `gift_message_reports` + `TicketType.GIFT_MESSAGE_REPORT` 一个 UNION 分支 + 下架 / 驳回两动作（D-8） |
| 9 | 敏感词「复用内容审核的敏感词库」 | `B/content/moderation/KeywordRuleEngine.classify(text).l1Blocked()`（L3 白名单豁免 → L1 硬拦） | 送礼请求里调用；`l1Blocked = true` → 422 `gift-message-blocked`，在任何写库之前。**不加预检接口**（App 点「Kirim」即请求，命中不扣款）（D-6） |
| 10 | KTP「8 款模板」 | `id_cards.card_type` ∈ `IdCardType {KTP, PASSPORT, KITAS, SIM, STUDENT}`；学生卡 `card_variant` ∈ `StudentVariant {BINTANG, MALAM, CERIA, KLASIK}` | 8 款 = KTP / PASSPORT / KITAS / SIM / STUDENT_BINTANG / STUDENT_MALAM / STUDENT_CERIA / STUDENT_KLASIK。⚠️ 这里的 PASSPORT 是 KTP 卡种「Paspor」，**不是**护照快照（`pet_passport`） |
| 11 | 礼包内容「单买合计按后台当前单价实时算」 | `pricing_config` 单行：`id_hd_download_price`、`tailsonality_unlock_price`、`pet_slot_monthly_price` | 单买合计 = `id_hd × 8 + tailsonality (+ pet_slot_monthly) (+ 1000 × 5)`，服务端算（D-21） |
| 12 | 名额已满「延长最早到期的租用名额」 | `pet_slot_purchases.kind` CHECK 只有 RENT / RENEW / ADMIN_GRANT；`pet_slot_rentals.source` 只有 RENT / ADMIN_GRANT；名额不绑定具体宠物 | 两个 CHECK 重建加 `PACK_GRANT` / `PACK_EXTEND`（rental source 只加 `PACK_GRANT`）；延长复用续租的延长逻辑（D-23） |
| 13 | H5 宠物名片「Hadiah」列改礼物个数 | `B/profile/web/CardPageController` 读 `stats.giftTotalValue()` 用 `IdrCompact` 显示 | 改读 `giftCount`（Σ quantity，已送达）（D-11） |
| 14 | 送礼包装页「支付区不走 `PayChannelPicker`」 | 线上 `A/features/gift/presentation/gift_send_flow.dart` 正在用 `PayChannelPicker` | 送礼改为包装整页自带支付区；左右两张付款卡抽成共享组件 `A/shared/widgets/pay_method_cards.dart`，Story 2-2 建、Story 6-1 的 `PayChannelPicker` 复用（D-13） |
| 15 | 礼包购买「QRIS 待支付单进订单页」 | `OrderCenterService.listOrders` 已有 `includeEcommerce` / `includeKeepsake` 闸门；`mapTopupPending` 收未过期 PENDING | 新增第 6 源 + `includeStarterPack` 闸门 + `RANK_STARTER_PACK = 5`（只追加），单号前缀 `PAKET`（D-26） |
| 16 | 「到期前 7 天站内通知，不发推送」 | 地基已加 `NotificationType.PACK_CREDIT_EXPIRING`（`PushCategory` → PROMO）；`NotificationService.sendWithoutPush(...)` 已有 | 用 `sendWithoutPush`；**不改** `ck_notifications_type`、不改 `kPushDirectTypes` |
| 17 | 埋点「报自家服务器」 | 服务端有 `AnalyticsClient.capture` + `AnalyticsEventGuard.ALLOWED_EVENTS` 白名单（如 `KtpUnlockAnalyticsListener`）；App 有 `Analytics` 门面 + 白名单测试 | 服务端事件走 `AnalyticsClient` 并登记白名单；App 事件走 `Analytics` 门面并登记白名单（D-29） |
| 18 | 支付意图用途 `STARTER_PACK` | 当前树最后一次重建 `ck_payment_intents_purpose` 是 `V20261024_2133`（12 值） | 照抄 12 值 + `STARTER_PACK` = 13 值；`growth_spend_tiers` 插一行 DIGITAL（D-20） |
| 19 | 礼物图 1000×1000、Rive | App 已依赖 `rive: ^0.14.11`，`A/features/place/presentation/widgets/stamp_ceremony.dart` 已有 `assetLoader` / `dataBind` / 事件监听写法；pubspec 已声明 `assets/rive/`，无 `assets/gift/` | 照抄 stamp_ceremony 接法；pubspec 加 `assets/gift/`（D-14） |

## 2. 关键技术决定

### A 送礼优化

- **D-1 礼物分类**：新表 `gift_categories`；`gift_catalog.category_id` 可空 FK。普通礼物必须有在线分类由**服务层**保证（`GiftCatalogRules` 校验成 422），库里只加「专属礼物分类必为空」CHECK——不加「普通必填」CHECK，避免存量集成测试直插 `gift_catalog` 的夹具全红。名称 EN / ID 各自**不分大小写唯一（含已下线）**，用 `lower()` 唯一索引。只下线不删除。
- **D-2 上线迁移**：插 5 个分类（Snacks / Camilan 10、Treats / Makan enak 20、Toys / Mainan 30、Style / Gaya 40、Big gifts / Hadiah besar 50）；存量普通礼物按价格归类。PRD 要求「非五档价格的礼物由运营指定」——云端拿不到生产数据，迁移里用**区间兜底**（≤1000 → Snacks、≤5000 → Treats、≤10000 → Toys、≤25000 → Style、其余 → Big gifts），末尾断言「无 category_id 为空的普通礼物」。上线前研发查一次生产非五档价格的普通礼物交运营，运营在后台「分类」下拉里改（后台本就支持改分类）。见待拍板 Q-1。
- **D-3 目录接口**：`GET /api/v1/gifts/catalog` 响应**保留** `tiers`（按价格分组，旧版 App 用）原样不变，**新增** `categories`（按分类分组）；`exclusiveGroups` / `lockBlock` 不变。每个礼物新增 `senderExp`（送 1 个时送礼人可得的 EXP）与 `receivedFrom`（这只宠物收到过这件礼物时的送礼人，最多 3 人 + 余数）。分类组内排序沿用 `GiftCatalogService.orderFor(species)`（适用物种 → 通用 → 其他 → `sort_order` → id），**不改** `orderFor` / `GiftCatalogItem.suits` 的签名（④ 物种线会改物种判断）。
- **D-4 分类在对外接口里用数字 id**：分类是全量公开的参考数据（与地区 id 同类例外），筛选参数 `categoryId` 用 `gift_categories.id`。礼物、订单仍用 `public_token`。
- **D-5 送礼订单新列**：`gift_orders.message`（≤ 500 字符，可空）、`opened_at`（可空 = 未拆）、`message_hidden_at`（举报处理后隐藏）。迁移把**全部存量订单** `opened_at = COALESCE(delivered_at, created_at)`（「上线前的存量订单一律视为已拆」）。「未拆」按**笔**记。
- **D-6 祝福语校验**：长度按 **Unicode 码点**计（服务端 `codePointCount`、App `runes.length`），trim 后为空当作没写（存 NULL）；>500 → 422 `validation`。敏感词：`KeywordRuleEngine.classify(message).l1Blocked()` → 422 `gift-message-blocked`。两项都在幂等重放判断之后、任何写库之前（重放旧单不再校验）。
- **D-7 拆礼物不入账**：拆开只写 `opened_at`；EXP 仍由送达时的 `GiftSentEvent` 入账。页面显示的 EXP 由服务端按订单面值现算：`ownerExp = growth 参与 ? pointsForAmount(GIFT_RECEIVED, unit_price × quantity) : 0`；`senderExp` 同理用 `GIFT_SENT`，免费次数单恒 0。
- **D-8 祝福语举报**：新表 `gift_message_reports`（同人同单唯一，重复举报幂等）；统一工单加 `TicketType.GIFT_MESSAGE_REPORT`，**按订单聚合**；动作「隐藏祝福语」（全部 PENDING → ACTIONED，`gift_orders.message_hidden_at = now`）/「驳回」（→ DISMISSED）。权限沿用内容举报处置的权限码。后台不另出稿：工单行预览 = 祝福语前 60 字。被隐藏后 App 显示「Ucapan ini disembunyikan…」，礼物照常显示。
- **D-9 收礼通知跳转**：见 §1 #6。后端 `sendGiftReceived` 不改。
- **D-10 旧接口保留**：`GET /api/v1/pets/{petId}/gifts`（汇总 + 墙）、`/gifts/supporters`（粉丝榜）、`gift_pet_wall` / `gift_pet_supporters` 的写入**全部保留**给旧版 App；新版 App 不再调用。
- **D-11 去粉丝榜与总价值**：新版 App 的 Diary 统计条去掉「Hadiah」格；`ArchiveStatsResponse` / `VisitorStats` 新增 `giftCount`（旧字段 `giftTotalValue` 保留）；H5 宠物名片改显示 `giftCount`。
- **D-12 逐笔列表排序**：主人视角「未拆在前」：`(opened_at IS NULL) DESC, delivered_at DESC, id DESC`；访客视角 `delivered_at DESC, id DESC`、不下发 `opened`。只列 `status = DELIVERED`。游标 = base64url 的 `(unopenedFlag, deliveredAt 微秒, id)`，对客户端不透明。
- **D-13 两张付款卡共享组件**：`A/shared/widgets/pay_method_cards.dart`：`PayMethodCards`（左「PawCoin」右「Bayar langsung」等宽卡；选中 = 紫描边 + `AppColors.mintTint` 底 + 右下勾；PawCoin 余额不足不置灰、不可选、红字「Saldo {n} · kurang {m}」+「Isi saldo dulu →」、点卡回调去充值；直接付款卡低于门槛置灰写「Minimal Rp{n}」）。Story 2-2 建，Story 6-1 在 `PayChannelPicker` 里复用，不各写一套。
- **D-14 Rive 接入**：`assets/rive/send_gift.riv`（画板 `GiftSend`、状态机 `SendSM`；礼物图用数据绑定 `Gift.giftImage`，运行时设置不生效则 `assetLoader` 按资源名 `plushy-image` 替换；结束事件 `sendDone`，兜底 5.5 秒）、`assets/rive/receive_gift.riv`（画板 `GiftReveal`、状态机 `GiftSM`；点礼盒由 App 触发 `GiftVM.open`；礼物图 `GiftVM.giftImage`（现文件没有，传了不生效属预期）；结束事件 `revealDone`，兜底 2.5 秒）；`Fit.cover`。礼盒图 `assets/gift/gift_box_closed.webp`（先用 `ui-gift-optimization/临时素材/gift_box_closed.png` 转存，同名覆盖即换）。系统「减弱动态效果」（`MediaQuery.disableAnimations`）/ 加载失败 / 超时 → 直接显示终态 E3 / G2。快速连点礼盒只触发一次 `open`。

### B 超值礼包

- **D-15 权益次数按批记**：新表 `entitlement_credits`（一次发放一行：类型、数量、已用、到期、来源）+ `entitlement_credit_uses`（使用流水，一次使用可跨多批，每批一行）。包 `B/entitlement/`。**所有写入只经 `EntitlementCreditService`**（grant / consume），其他模块不得直写这两张表。
- **D-16 免费礼物 EXP**：见 §1 #7。`GiftDeliveryService.deliver` 发事件时 `totalAmount = unit_price × quantity`、`paidByCredit = (channel == PACK_CREDIT)`；`GrowthGiftListener` 在 `paidByCredit` 时只入 `GIFT_RECEIVED`。粉丝榜汇总 `addToSupporters` 仍按 `order.total`（免费单加 0 金额、+数量）。
- **D-17 免费礼物订单形状**：`channel = PACK_CREDIT`、`unit_price` = 下单时礼物价（必须 = 1000）、`total = 0`、`payment_intent_id = NULL`；与 PawCoin 一样**同步成交**：同一事务 插订单 → `consume(GIFT_1K, quantity, giftOrderId)` → `deliver`；不可送达 → 抛错整笔回滚（次数不扣、订单不留），因此免费礼物永远不会进「待退回」。
- **D-18 PayChannel.PACK_CREDIT 的护栏**：`PaymentIntentService.createIntent` 收到 `PACK_CREDIT` → 422；`IdCardHdService`、`KeepsakePurchaseService`、`ConsultPayService`、`PawCoinTopupService`、宠物名额租用 / 续租、AI 解锁等所有从请求体接 `PayChannel` 的入口对 `PACK_CREDIT` 显式 422（switch 里写 `case PACK_CREDIT ->`，不落 default）。KTP / 性格测试用次数解锁走**独立端点**，不复用付费端点的 `channel` 参数。
- **D-19 用次数解锁 KTP / 性格测试**：同一事务内「consume → 解锁」，按卡 / 结果幂等：已解锁 → 直接返回成功、**不扣次数**；`use_key` 已存在 → 不重复扣。KTP：`card.markHdUnlocked()` + 写 `id_card_hd_purchases`（`PACK_CREDIT`）。性格测试：执行与 `TailsonalityKeepsakeGranter.unlock` 相同的 `UPDATE … unlocked_at` + `AUTO_EQUIP`（抽一个包内可见的方法共用，不复制 SQL），不写 `keepsake_purchases`。
- **D-20 礼包支付用途**：`PaymentPurpose` 末尾加 `STARTER_PACK`；`ck_payment_intents_purpose` 13 值重建；`growth_spend_tiers` 插 `('STARTER_PACK','DIGITAL',false)`。PawCoin 扣币 `refType = "STARTER_PACK"`（消费返 EXP 由 `GrowthSpendListener` 现成处理：13.000 → 65）。用免费次数不再返。
- **D-21 礼包配置**：`pricing_config` 加 `starter_pack_tier1_price / tier2 / tier3`（默认 8000 / 11000 / 13000，≥100）与 `starter_pack_enabled`（默认 `true`，见待拍板 Q-6）。改价只影响新下单；PENDING 单按下单快照。
- **D-22 购买限制（服务端是唯一判据）**：同一用户 `pg_advisory_xact_lock(hashtext('starter_pack:' || userId))` 串行；① 停售 → 409 `starter-pack-disabled`；② 有 PENDING 单且其意图未过窗 → 409 `starter-pack-pending`（PENDING 但已过窗 → 先就地置 EXPIRED 再继续）；③ 最近一笔 PAID 的 `paid_at + 7×24h > now` → 409 `starter-pack-cooldown`；④ 档 2 / 3 且「名额已 5 个且无可延长的租用名额」→ 409 `starter-pack-slots-full`；⑤ 虚拟号 → 403。库级兜底：`uq_starter_pack_purchases_one_pending`（每人至多一笔 PENDING）。
- **D-23 名额**：发放时在 `PetSlotLock.lockUser` 锁内：`quotaOf(userId).total() < 5` → `PACK_GRANT` 新建 1 个租用名额（30 天，从发放时刻起）+ `reconcile(RENTAL_ADDED)`；否则有 ACTIVE 租用名额 → `PACK_EXTEND` 给 `expires_at` 最早的那个 +30 天（与续租同一延长逻辑，重置 `reminded_at`）；都没有 → `SKIPPED`（下单时已拦，只有「下单后升级」的竞态会到这里：记 WARN、次数照发，交人工）。不收费、不发 `PawCoinSpentEvent`。
- **D-24 到账发放走 DB 状态机**：PawCoin：一个事务内 扣币 → 置 PAID → 发放（次数 + 名额）→ `fulfilled_at`，任何失败整笔回滚（不扣钱）。QRIS：`StarterPackPaidHandler`（`@EventListener` + `MANDATORY`，照 `GiftPaidHandler`）在回调事务里**只**把购买置 PAID、写 `paid_at`、发 `StarterPackPaidEvent`；`StarterPackFulfillmentListener`（`AFTER_COMMIT` + `REQUIRES_NEW`）执行发放并写 `fulfilled_at`；`StarterPackFulfillmentRetryJob` 每 5 分钟补跑 `status = PAID AND fulfilled_at IS NULL` 且 `paid_at < now − 2 分钟` 的单。发放幂等：次数键 `PACK:{purchaseId}`（按类型唯一）、名额键 `PACK_SLOT:{purchaseId}`（`pet_slot_purchases.idem_key`）。意图过窗后才到账（购买已 EXPIRED）→ 照样置 PAID 并发放（钱到了就发货），记 INFO。
- **D-25 扣次数顺序**：KTP 某款：先扣该款 `KTP_HD:{模板}`，不够再扣 `KTP_HD:ANY`；同类按 `expires_at ASC, id ASC`（先扣最早到期）。到期判断按扣减时刻（`expires_at > now`）。每人一把 `pg_advisory_xact_lock(hashtext('entitlement:' || userId))`，锁后查 `use_key` 幂等，再 `SELECT … FOR UPDATE` 候选批、逐批 `used += n`。
- **D-26 订单中心**：`OrderType` 末尾加 `STARTER_PACK`；`listOrders(..., includeStarterPack)` 新闸门（新版 App 传 true；`type=STARTER_PACK` 不受闸门约束）；PAID 与「未过期 PENDING」入列表（PENDING = WARN 色、`statusCode = PENDING`，照 `mapTopupPending`），EXPIRED / FAILED 不入；详情 `orderDetail` 支持礼包 token。单号 `OrderDisplayNo` 前缀 `PAKET`。
- **D-27 到期提醒**：`EntitlementExpiryReminderJob` 每天 10:00 WIB（`0 0 3 * * *` UTC）：取 `used < quantity AND expires_at > now AND expires_at <= now + 7 天 AND expiry_notified_at IS NULL` 的批，按（用户, 类别 KTP_HD / TAILSONALITY / GIFT_1K）分组，每组取最早到期的 WIB 日期，`n` = 该组在该日期到期的剩余数之和，发一条 `PACK_CREDIT_EXPIRING`（`sendWithoutPush`，`deep_link_type = PACK_CREDIT_EXPIRING`，`target_ref = NULL`），标题正文按收件人语言落库；本组被扫到的批全部写 `expiry_notified_at`（每批只提醒一次）。注销 / 虚拟号跳过。
- **D-28 「Koin & Jatah Saya」数据**：`GET /api/v1/me/entitlements`（汇总）与 `/history`（流水，发放与使用两表按时间归并，游标同订单中心的「时间 + 源先后位 + id」写法）。余额仍走既有 PawCoin 接口；两路任一失败整页报错（App 侧）。
- **D-29 埋点**：服务端 `starter_pack_purchased {tier, channel, purchase_index}`（到账 / 扣币成功后，AFTER_COMMIT）、`entitlement_used {credit_type: ktp_hd|tailsonality|gift, source: pack|activity|admin}`（取本次扣到的第一批的来源）；App `starter_pack_viewed {source: me_page|hd_paywall|tailsonality_paywall|ops_popup}`（系统通知点开也记 `ops_popup`）、`starter_pack_purchase_clicked {tier, channel}`。事件名写字面量，属性只有枚举与数值。
- **D-30 后台补发**：新权限 `AdminPermissions.USER_ENTITLEMENT_GRANT = "user.entitlement_grant"`，写进 `AdminRole.OPS_MANAGER` 预授列表（注释同 `USER_PET_SLOT_ADJUST`：按 PRD 预授）与 `AdminPageCatalog` 用户页写权限列表；一次 1–3 次、原因必填 ≤ 200、`grantToken` 幂等（键 `ADMIN:{grantToken}`）、审计 `ENTITLEMENT_ADMIN_GRANT`；补发类型不含「任意款」（D-31）。
- **D-31 「任意款」只由活动发**：`EntitlementCreditService.grant` 对 `KTP_HD_ANY` 只接受 `source = ACTIVITY`，其他来源 422 `entitlement-type-not-allowed`。

## 3. 数据（迁移原文要点）

> 版本号已预分配（§7），只能用 `V20261115_02xx`。整数列 `INT`、金额 `BIGINT`，**禁止 SMALLINT**；时间 `TIMESTAMPTZ`（UTC）。新表 FK 指向会被测试 `deleteAll` 的表时一律 `ON DELETE CASCADE` / `SET NULL`。

### 3.1 `V20261115_0200__create_gift_categories.sql`（Story 1-1）

```sql
CREATE TABLE gift_categories (
    id          BIGINT       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name_en     VARCHAR(40)  NOT NULL,
    name_id     VARCHAR(40)  NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ONLINE',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by  BIGINT       NULL,                         -- admin_accounts.id，不加 FK（审计另记）
    CONSTRAINT ck_gift_categories_status CHECK (status IN ('ONLINE', 'OFFLINE')),
    CONSTRAINT ck_gift_categories_sort CHECK (sort_order BETWEEN 0 AND 9999)
);
CREATE UNIQUE INDEX uq_gift_categories_name_en ON gift_categories (lower(name_en));
CREATE UNIQUE INDEX uq_gift_categories_name_id ON gift_categories (lower(name_id));

ALTER TABLE gift_catalog ADD COLUMN category_id BIGINT NULL REFERENCES gift_categories (id);
CREATE INDEX ix_gift_catalog_category ON gift_catalog (category_id, status);
ALTER TABLE gift_catalog ADD CONSTRAINT ck_gift_catalog_exclusive_no_category
    CHECK (kind = 'NORMAL' OR category_id IS NULL);

INSERT INTO gift_categories (name_en, name_id, sort_order) VALUES
    ('Snacks', 'Camilan', 10), ('Treats', 'Makan enak', 20), ('Toys', 'Mainan', 30),
    ('Style', 'Gaya', 40), ('Big gifts', 'Hadiah besar', 50);

UPDATE gift_catalog g SET category_id = c.id
  FROM gift_categories c
 WHERE g.kind = 'NORMAL'
   AND c.name_en = CASE WHEN g.price <= 1000 THEN 'Snacks' WHEN g.price <= 5000 THEN 'Treats'
                        WHEN g.price <= 10000 THEN 'Toys' WHEN g.price <= 25000 THEN 'Style'
                        ELSE 'Big gifts' END;

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM gift_catalog WHERE kind = 'NORMAL' AND category_id IS NULL) THEN
        RAISE EXCEPTION 'gift_catalog: 存在未归类的普通礼物';
    END IF;
END $$;
```
文件头注释写明：五档价格精确命中按 PRD；非五档按区间兜底（D-2 / Q-1）；专属礼物不迁移。

### 3.2 `V20261115_0205__gift_orders_message_and_opened.sql`（Story 1-3）

```sql
ALTER TABLE gift_orders ADD COLUMN message           VARCHAR(500) NULL;
ALTER TABLE gift_orders ADD COLUMN opened_at         TIMESTAMPTZ  NULL;
ALTER TABLE gift_orders ADD COLUMN message_hidden_at TIMESTAMPTZ  NULL;
ALTER TABLE gift_orders ADD CONSTRAINT ck_gift_orders_message_len
    CHECK (message IS NULL OR char_length(message) BETWEEN 1 AND 500);
-- 存量一律视为已拆（PRD A §6，11-10）
UPDATE gift_orders SET opened_at = COALESCE(delivered_at, created_at) WHERE opened_at IS NULL;
CREATE INDEX ix_gift_orders_pet_delivered ON gift_orders (pet_id, delivered_at DESC, id DESC) WHERE status = 'DELIVERED';
CREATE INDEX ix_gift_orders_pet_unopened  ON gift_orders (pet_id) WHERE status = 'DELIVERED' AND opened_at IS NULL;
```

### 3.3 `V20261115_0210__create_gift_message_reports.sql`（Story 1-5）

```sql
CREATE TABLE gift_message_reports (
    id                BIGINT      GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    gift_order_id     BIGINT      NOT NULL REFERENCES gift_orders (id) ON DELETE CASCADE,
    reporter_user_id  BIGINT      NOT NULL,
    reason_type       VARCHAR(16) NOT NULL,
    status            VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    handled_by        BIGINT      NULL,                    -- admin_accounts.id，不加 FK
    handled_at        TIMESTAMPTZ NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_gift_message_reports_reporter UNIQUE (gift_order_id, reporter_user_id),
    CONSTRAINT ck_gift_message_reports_status CHECK (status IN ('PENDING', 'DISMISSED', 'ACTIONED')),
    CONSTRAINT ck_gift_message_reports_reason CHECK (reason_type IN
        ('ILLEGAL', 'MISINFO', 'INAPPROPRIATE', 'HARASSMENT', 'OTHER'))
);
CREATE INDEX ix_gift_message_reports_status ON gift_message_reports (status, created_at);
```

### 3.4 `V20261115_0220__create_entitlement_credits.sql`（Story 4-1，⑥ 依赖）

```sql
CREATE TABLE entitlement_credits (
    id                  BIGINT       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id             BIGINT       NOT NULL,             -- 照 keepsake_purchases 不加 FK
    credit_type         VARCHAR(32)  NOT NULL,
    quantity            INT          NOT NULL,
    used                INT          NOT NULL DEFAULT 0,
    granted_at          TIMESTAMPTZ  NOT NULL,
    expires_at          TIMESTAMPTZ  NOT NULL,
    source_type         VARCHAR(16)  NOT NULL,
    source_ref          VARCHAR(64)  NOT NULL,             -- PACK = 购买 id；ACTIVITY = 活动 id；ADMIN = 补发 grantToken
    idem_key            VARCHAR(160) NOT NULL,
    label_id            VARCHAR(80)  NULL,                 -- 来源名快照（活动名印尼语）
    label_en            VARCHAR(80)  NULL,
    operator_admin_id   BIGINT       NULL,
    reason              VARCHAR(200) NULL,
    expiry_notified_at  TIMESTAMPTZ  NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_entitlement_credits_idem UNIQUE (idem_key, credit_type),
    CONSTRAINT ck_entitlement_credits_type CHECK (credit_type IN (
        'KTP_HD:KTP', 'KTP_HD:PASSPORT', 'KTP_HD:KITAS', 'KTP_HD:SIM',
        'KTP_HD:STUDENT_BINTANG', 'KTP_HD:STUDENT_MALAM', 'KTP_HD:STUDENT_CERIA', 'KTP_HD:STUDENT_KLASIK',
        'KTP_HD:ANY', 'TAILSONALITY', 'GIFT_1K')),
    CONSTRAINT ck_entitlement_credits_quantity CHECK (quantity BETWEEN 1 AND 100),
    CONSTRAINT ck_entitlement_credits_used CHECK (used >= 0 AND used <= quantity),
    CONSTRAINT ck_entitlement_credits_window CHECK (expires_at > granted_at),
    CONSTRAINT ck_entitlement_credits_source CHECK (source_type IN ('PACK', 'ACTIVITY', 'ADMIN')),
    CONSTRAINT ck_entitlement_credits_any_activity CHECK (credit_type <> 'KTP_HD:ANY' OR source_type = 'ACTIVITY'),
    CONSTRAINT ck_entitlement_credits_admin CHECK (source_type <> 'ADMIN' OR (operator_admin_id IS NOT NULL AND reason IS NOT NULL))
);
CREATE INDEX ix_entitlement_credits_available ON entitlement_credits (user_id, credit_type, expires_at, id) WHERE used < quantity;
CREATE INDEX ix_entitlement_credits_expiry_scan ON entitlement_credits (expires_at) WHERE used < quantity AND expiry_notified_at IS NULL;
CREATE INDEX ix_entitlement_credits_user_created ON entitlement_credits (user_id, created_at DESC, id DESC);

CREATE TABLE entitlement_credit_uses (
    id           BIGINT       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    credit_id    BIGINT       NOT NULL REFERENCES entitlement_credits (id) ON DELETE CASCADE,
    credit_type  VARCHAR(32)  NOT NULL,                    -- 扣到的那批的类型快照（KTP 可能是 ANY）
    use_kind     VARCHAR(16)  NOT NULL,
    target_id    BIGINT       NOT NULL,                    -- id_cards.id / tailsonality_results.id / gift_orders.id
    use_key      VARCHAR(64)  NOT NULL,                    -- {use_kind}:{target_id}
    quantity     INT          NOT NULL,
    pet_name     VARCHAR(20)  NULL,                        -- 「用在 {宠物}」快照
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_entitlement_credit_uses_key_credit UNIQUE (use_key, credit_id),
    CONSTRAINT ck_entitlement_credit_uses_kind CHECK (use_kind IN ('KTP_HD', 'TAILSONALITY', 'GIFT')),
    CONSTRAINT ck_entitlement_credit_uses_quantity CHECK (quantity >= 1)
);
CREATE INDEX ix_entitlement_credit_uses_key ON entitlement_credit_uses (use_key);
CREATE INDEX ix_entitlement_credit_uses_user_created ON entitlement_credit_uses (user_id, created_at DESC, id DESC);
```
文件头注释：资金类权益流水，注销不删（`users` 行匿名化，次数自然作废）；状态只经 `EntitlementCreditService`。

### 3.5 `V20261115_0225__starter_pack_purchases_and_purpose.sql`（Story 4-2）

```sql
-- 🔴 全量重建共享 CHECK：照抄当前树最后一条重建它的 V20261024_2133（12 值），末尾加 STARTER_PACK = 13 值。
--    开工当天 grep 全部迁移 + git fetch 核对远端分支，确认没有更晚的重建；有则以最晚那条为准再加。
ALTER TABLE payment_intents DROP CONSTRAINT ck_payment_intents_purpose;
ALTER TABLE payment_intents ADD CONSTRAINT ck_payment_intents_purpose
    CHECK (purpose IN ('VET_CONSULT', 'PAWCOIN_TOPUP', 'AI_UNLOCK', 'ID_HD', 'SHOP_ORDER',
                       'TAILSONALITY', 'PASSPORT_SNAP', 'BOARDING_PASS', 'TS_MATCH',
                       'GIFT', 'PET_SLOT_RENT', 'MERCHANT_PO',
                       'STARTER_PACK'));
INSERT INTO growth_spend_tiers (purpose, tier, locked) VALUES ('STARTER_PACK', 'DIGITAL', false);

ALTER TABLE pricing_config ADD COLUMN starter_pack_tier1_price BIGINT  NOT NULL DEFAULT 8000;
ALTER TABLE pricing_config ADD COLUMN starter_pack_tier2_price BIGINT  NOT NULL DEFAULT 11000;
ALTER TABLE pricing_config ADD COLUMN starter_pack_tier3_price BIGINT  NOT NULL DEFAULT 13000;
ALTER TABLE pricing_config ADD COLUMN starter_pack_enabled     BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE pricing_config ADD CONSTRAINT ck_pricing_starter_pack_min CHECK (
    starter_pack_tier1_price >= 100 AND starter_pack_tier2_price >= 100 AND starter_pack_tier3_price >= 100);

CREATE TABLE starter_pack_purchases (
    id                 BIGINT       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    public_token       VARCHAR(32)  NOT NULL,
    user_id            BIGINT       NOT NULL,              -- 不加 FK（资金流水，注销匿名化不删）
    tier               INT          NOT NULL,
    price              BIGINT       NOT NULL,              -- 下单快照
    separate_total     BIGINT       NOT NULL,              -- 下单时「单买合计」快照（详情展示用）
    pay_channel        VARCHAR(16)  NOT NULL,
    payment_intent_id  BIGINT       NULL,
    status             VARCHAR(16)  NOT NULL,
    idem_key           VARCHAR(160) NOT NULL,              -- STARTER_PACK:{userId}:{requestId}
    pay_expires_at     TIMESTAMPTZ  NULL,                  -- QRIS 付款窗截止（下单 + 60 分钟）
    purchase_index     INT          NULL,                  -- 第几次成功购买（PAID 时写）
    credits_expire_at  TIMESTAMPTZ  NULL,
    slot_outcome       VARCHAR(16)  NULL,
    slot_rental_id     BIGINT       NULL,
    slot_expires_at    TIMESTAMPTZ  NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    paid_at            TIMESTAMPTZ  NULL,
    fulfilled_at       TIMESTAMPTZ  NULL,
    CONSTRAINT uq_starter_pack_purchases_public_token UNIQUE (public_token),
    CONSTRAINT uq_starter_pack_purchases_payment_intent UNIQUE (payment_intent_id),
    CONSTRAINT uq_starter_pack_purchases_idem_key UNIQUE (idem_key),
    CONSTRAINT ck_starter_pack_purchases_tier CHECK (tier BETWEEN 1 AND 3),
    CONSTRAINT ck_starter_pack_purchases_price CHECK (price >= 100),
    CONSTRAINT ck_starter_pack_purchases_channel CHECK (pay_channel IN ('PAWCOIN', 'QRIS')),
    CONSTRAINT ck_starter_pack_purchases_status CHECK (status IN ('PENDING', 'PAID', 'EXPIRED', 'FAILED')),
    CONSTRAINT ck_starter_pack_purchases_paid CHECK ((status = 'PAID') = (paid_at IS NOT NULL)),
    CONSTRAINT ck_starter_pack_purchases_slot CHECK (slot_outcome IS NULL OR slot_outcome IN ('NONE', 'GRANTED', 'EXTENDED', 'SKIPPED'))
);
CREATE UNIQUE INDEX uq_starter_pack_purchases_one_pending ON starter_pack_purchases (user_id) WHERE status = 'PENDING';
CREATE INDEX ix_starter_pack_purchases_user_paid ON starter_pack_purchases (user_id, paid_at DESC) WHERE status = 'PAID';
CREATE INDEX ix_starter_pack_purchases_unfulfilled ON starter_pack_purchases (paid_at) WHERE status = 'PAID' AND fulfilled_at IS NULL;
CREATE INDEX ix_starter_pack_purchases_user_created ON starter_pack_purchases (user_id, created_at DESC, id DESC);
```

### 3.6 `V20261115_0230__pet_slot_pack_kinds.sql`（Story 4-2）

```sql
-- 照抄 V20261018_1601 的全集再加值（当前树无其它迁移改过这两条约束；开工当天再 grep 一次）。
ALTER TABLE pet_slot_purchases DROP CONSTRAINT ck_pet_slot_purchases_kind;
ALTER TABLE pet_slot_purchases ADD CONSTRAINT ck_pet_slot_purchases_kind
    CHECK (kind IN ('RENT', 'RENEW', 'ADMIN_GRANT', 'PACK_GRANT', 'PACK_EXTEND'));
ALTER TABLE pet_slot_rentals DROP CONSTRAINT ck_pet_slot_rentals_source;
ALTER TABLE pet_slot_rentals ADD CONSTRAINT ck_pet_slot_rentals_source
    CHECK (source IN ('RENT', 'ADMIN_GRANT', 'PACK_GRANT'));
ALTER TABLE pet_slot_purchases ADD COLUMN starter_pack_purchase_id BIGINT NULL
    REFERENCES starter_pack_purchases (id) ON DELETE SET NULL;      -- 后台「档 n」显示用
```

### 3.7 `V20261115_0235__pack_credit_channel.sql`（Story 4-3）

```sql
ALTER TABLE gift_orders DROP CONSTRAINT ck_gift_orders_channel;
ALTER TABLE gift_orders ADD CONSTRAINT ck_gift_orders_channel CHECK (channel IN ('PAWCOIN', 'QRIS', 'PACK_CREDIT'));
ALTER TABLE gift_orders DROP CONSTRAINT ck_gift_orders_total;
ALTER TABLE gift_orders ADD CONSTRAINT ck_gift_orders_total CHECK (
    (channel = 'PACK_CREDIT' AND total = 0 AND unit_price = 1000 AND payment_intent_id IS NULL)
    OR (channel <> 'PACK_CREDIT' AND total = unit_price * quantity));
-- V20260817_2313 的「窄 CHECK 纵深防御」只放宽这一个值：免费次数不是支付渠道混用，仍是单一来源。
ALTER TABLE id_card_hd_purchases DROP CONSTRAINT ck_id_card_hd_purchases_channel;
ALTER TABLE id_card_hd_purchases ADD CONSTRAINT ck_id_card_hd_purchases_channel
    CHECK (pay_channel IN ('QRIS', 'PAWCOIN', 'PACK_CREDIT'));
```

## 4. API 契约

> 全部 `role=USER`，查看者只取 JWT `sub`。错误一律 RFC 9457 ProblemDetail，`type` 末段即下表错误码（登记进 `ErrorTypes`，连字符风格）。金额 IDR `long`。时间 ISO-8601 UTC。

### 4.1 送礼（A）

| 接口 | 说明 |
|---|---|
| `GET /api/v1/gifts/catalog?petId=` | 旧字段全保留。新增 `categories: [{categoryId, nameEn, nameId, gifts: [GiftItem]}]`（只含有在线礼物的在线分类，按 `sort_order, id`）；`GiftItem` 新增 `senderExp: int`、`receivedFrom: {senders: [{nickname, deleted}], more: int} \| null`（按该送礼人最近一次送这件礼物的时间倒序，最多 3 人，`more` = 其余人数；送礼人被查看者拉黑的照常算）|
| `POST /api/v1/pets/{petId}/gifts` | 请求新增 `message: string?`；`channel` 新增 `PACK_CREDIT`（B，Story 4-3）。响应新增 `senderExp: int`（仅 `delivered=true` 时有意义）。新错误：422 `gift-message-blocked`；B 再加 409 `entitlement-insufficient`、422 `gift-credit-not-applicable`（礼物价 ≠ 1000）|
| `GET /api/v1/pets/{petId}/gift-orders?filter=ALL\|UNOPENED&categoryId=&cursor=` | 每页 30。`items: [GiftOrderCard]`，`nextCursor`，主人视角另有 `unopenedCount`；首页（无 cursor）另带 `categories: [{categoryId, nameEn, nameId}]`（全部在线分类，App 筛选胶囊）与 `pet`（**复用** `PetGiftShowcaseResponse.PetView` 类型与组装方式，`viewerIsOwner` 一并给；④ 物种线给 `PetView` 加字段时这里自动带上）。访客传 `UNOPENED` 按 `ALL` 处理 |
| `GET /api/v1/pets/{petId}/gift-orders/preview` | Diary 礼物行：`giftCount`（Σ quantity）、主人视角 `unopenedCount`、`recent`（最多 10 张卡，排序同列表）|
| `GET /api/v1/gift-orders/{orderToken}` | 详情（H1 / H2）：可见性同礼物页（`GiftShowcaseAccess.requireVisible`），只认 DELIVERED，否则 404 |
| `POST /api/v1/pets/{petId}/gift-orders/open` | 仅主人（否则 403）。body `{orderTokens: [≤ 50]}` 或 `{all: true}`（全部未拆，最多 200 笔，按 `delivered_at ASC` 取）。幂等：已拆的照样返回、`alreadyOpened = true`。响应 `{items: [GiftOrderDetail], totalOwnerExp}`，`items` 按 `delivered_at DESC`。**不入账** |
| `POST /api/v1/gift-orders/{orderToken}/message-reports` | body `{reasonType}`（`ReportReason` 五值）；204。无祝福语 / 已隐藏 / 举报人是送礼人本人 → 422 `gift-message-not-reportable`；限流 10 次 / 10 分钟 / 用户 |

`GiftOrderCard`：`orderToken, giftNameEn, giftNameId, giftIconUrl, quantity, receivedAt, sender {nickname, avatarUrl, deleted}, opened (仅主人视角，访客为 null), hasMessage`。

`GiftOrderDetail`：`orderToken, petId, petName, giftNameEn, giftNameId, giftIconUrl, quantity, receivedAt, opened (仅主人), alreadyOpened (仅 open 接口), message (无 / 已隐藏为 null), messageHidden, sender {userId (未注销时), nickname, avatarUrl, deleted, petNames: [≤3]}, ownerExp (仅主人视角), canReport`。`canReport = 有祝福语 && 未隐藏 && 查看者 ≠ 送礼人`。

### 4.2 超值礼包（B）

| 接口 | 说明 |
|---|---|
| `GET /api/v1/starter-pack` | `{enabled, defaultTier, maxSaving, balance, tiers: [{tier, price, separateTotal, saving, contents: [KTP_HD_8, TAILSONALITY_1, PET_SLOT_30D, GIFT_1K_5], slotState: GRANT\|EXTEND\|FULL\|null}], cooldown: {until, daysLeft} \| null, pending: {purchaseToken, tier, price, payExpiresAt} \| null}`。虚拟号 → `enabled=false`。`defaultTier` = 3，档 3 `FULL` 时为 1。`daysLeft = ceil((until − now) / 24h)` |
| `POST /api/v1/starter-pack/purchases` | body `{tier, channel: PAWCOIN\|QRIS, requestId}`；PawCoin → `{status: PAID, purchaseToken}`；QRIS → `{status: PENDING, purchaseToken, intent, qrPayload}`。错误 409 `starter-pack-disabled` / `starter-pack-pending` / `starter-pack-cooldown` / `starter-pack-slots-full` / `pawcoin-insufficient`；422 `validation`。同 `requestId` 重放返回同一单 |
| `GET /api/v1/me/starter-pack-purchases/{token}` | `{purchaseToken, tier, price, channel, status, fulfilled, paidAt, creditsExpireAt, slot: {outcome, expiresAt, petName} \| null, contents, intent?, qrPayload?}`；PENDING 时带意图与二维码（「继续支付」用），过窗 → 就地置 EXPIRED |
| `GET /api/v1/me/entitlements` | `{totalRemaining, types: [{family: KTP_HD\|TAILSONALITY\|GIFT_1K, remaining, earliestExpiry, expiringSoon, templates: [{template: KTP\|PASSPORT\|KITAS\|SIM\|STUDENT_BINTANG\|STUDENT_MALAM\|STUDENT_CERIA\|STUDENT_KLASIK\|ANY, remaining, earliestExpiry}]}]}`。只列剩余 > 0；`expiringSoon` = 最早到期 ≤ 7 天 |
| `GET /api/v1/me/entitlements/history?cursor=` | 每页 20：`items: [{kind: GRANT\|USE, creditType, quantity, at, source?: PACK\|ACTIVITY\|ADMIN, packTier?, labelId?, labelEn?, useKind?: KTP_HD\|TAILSONALITY\|GIFT, petName?}]`，`nextCursor` |
| `POST /api/v1/pet-profiles/me/id-cards/{cardId}/hd-credit-unlock` | 无 body。成功 `HdPurchaseResponse.granted()` 形状 + `remaining`（该款 + ANY 剩余合计）；已解锁 → 成功且不扣；无可用次数 → 409 `entitlement-insufficient`；非本人卡 → 404 |
| `POST /api/v1/pet-profiles/me/tailsonality/results/{token}/credit-unlock` | 同上（性格测试结果）|
| `GET /api/v1/me/orders?includeStarterPack=true` | 订单中心第 6 源（D-26）。订单卡 `orderType = STARTER_PACK`，`statusCode` PAID / PENDING；详情 `GET /api/v1/me/orders/{token}` 另带 `starterPack: {tier, contents, creditsExpireAt, slot}` |

## 5. 对 ⑥ 运营活动规则引擎的契约：发放权益次数

> Story 4-1 实现，合进 `dev_1.5.0` 后 ⑥ 即可开工。**签名、包名、幂等语义冻结**，后续 story 只能加方法，不能改这里列出的任何东西。

```java
package com.tailtopia.entitlement.domain;

/** 权益类型。wire 值即 entitlement_credits.credit_type。 */
public enum CreditType {
    KTP_HD_KTP("KTP_HD:KTP"), KTP_HD_PASSPORT("KTP_HD:PASSPORT"), KTP_HD_KITAS("KTP_HD:KITAS"), KTP_HD_SIM("KTP_HD:SIM"),
    KTP_HD_STUDENT_BINTANG("KTP_HD:STUDENT_BINTANG"), KTP_HD_STUDENT_MALAM("KTP_HD:STUDENT_MALAM"),
    KTP_HD_STUDENT_CERIA("KTP_HD:STUDENT_CERIA"), KTP_HD_STUDENT_KLASIK("KTP_HD:STUDENT_KLASIK"),
    KTP_HD_ANY("KTP_HD:ANY"), TAILSONALITY("TAILSONALITY"), GIFT_1K("GIFT_1K");
    public String wire();
    public CreditFamily family();                                    // KTP_HD / TAILSONALITY / GIFT_1K
    public static Optional<CreditType> fromWire(String wire);
    public static CreditType ktpHdOf(String cardType, String cardVariant); // id_cards → 8 款之一；非法 → IllegalArgumentException
    public static List<CreditType> ktpTemplates();                   // 8 款（不含 ANY），礼包发放用
}

public enum CreditFamily { KTP_HD, TAILSONALITY, GIFT_1K }
public enum CreditSource { PACK, ACTIVITY, ADMIN }
```

```java
package com.tailtopia.entitlement.service;

public record GrantCommand(long userId, CreditType type, int quantity, CreditSource source, String sourceRef,
        String idemKey, String labelId, String labelEn, Long operatorAdminId, String reason) {
    /** ⑥ 用这个：sourceRef = 活动 id，label = 活动名（印尼语 / 英语快照，≤ 80）。 */
    public static GrantCommand activity(long userId, CreditType type, int quantity, long activityId, String idemKey,
            String nameId, String nameEn);
}

public record GrantResult(long creditId, Instant expiresAt, boolean replayed) { }

@Service
public class EntitlementCreditService {
    /**
     * 发放一批次数。
     * - 事务：Propagation.REQUIRED（并入调用方事务；⑥「先写待发 → 发奖 → 改已发」同一事务内调用即可）。
     * - 有效期：到账起 3 个月 = granted_at（当前时刻）按 Asia/Jakarta plusMonths(3) 的同一时刻；V1.5.0 不可配置。
     * - 幂等：(idemKey, type) 唯一。同键同类型重放 → 不新建、返回原批（replayed = true），不比对数量（数量不同记 WARN）。
     *   同一个 idemKey 可以发多种类型（⑥ 一份奖励里 KTP + 性格测试共用一个键）。
     *   实现：INSERT … ON CONFLICT (idem_key, credit_type) DO NOTHING RETURNING id；没返回则按键查回。
     * - 校验（失败抛 AppException.validation，带码）：quantity 1..100（entitlement-quantity-invalid）；
     *   idemKey 非空且 ≤ 160、sourceRef 非空且 ≤ 64（validation）；用户不存在 / 已注销 / 注销受理中 / 虚拟号
     *   （entitlement-user-inactive）；KTP_HD_ANY 且 source ≠ ACTIVITY（entitlement-type-not-allowed）；
     *   ADMIN 必须带 operatorAdminId 与 reason。
     * - 不发通知、不发埋点、不写审计（这些归调用方：⑥ 自己发 OPS_ACTIVITY_REWARDED；后台补发由 5-2 写审计）。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public GrantResult grant(GrantCommand cmd);

    /** 本线内部用：扣次数（MANDATORY，见 D-25）。⑥ 不调用。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ConsumeResult consume(ConsumeCommand cmd);

    /** 汇总（「Koin & Jatah Saya」、商城胶囊、「我的」页）。 */
    @Transactional(readOnly = true)
    public CreditSummary summary(long userId);
}
```

⑥ 侧要点（写进 ⑥ 的规划即可，本线不实现）：
- 幂等键沿用 ⑥ PRD §6 的 `ACT:{活动ID}:POST:{帖子ID}` 等原样传入 `idemKey`；重新发放用同一键，不会重复到账。
- 活动「KTP 高清解锁 · 任意款」→ `CreditType.KTP_HD_ANY`；指定模板 → 对应 8 款之一。
- 「Koin & Jatah Saya」免费次数记录里活动行显示 `Event · {labelId/labelEn}`（App 按语言取），由本线 Story 6-3 实现。
- 活动发次数的站内通知 `OPS_ACTIVITY_REWARDED` 点开进 `/me/pawcoin`，由 ⑥ 实现（地基已有该类型）。
- 次数**不扣回**（⑥ PRD §3.4）：本线不提供扣减 / 作废接口。

## 6. 旧版 App 兼容（≤ 1.4.x）

| 改动 | 旧版表现 | 保证方式 |
|---|---|---|
| 目录按分类 | 照旧用 `tiers` 按价格分组 | `tiers` 字段不动；测试断言旧字段逐字一致 |
| 汇总 / 粉丝榜 | 照旧显示（含新版用户送的免费礼物、按原价计入墙） | 两个旧接口与两张汇总表写入保留 |
| 送礼请求 | 不带 `message`；不会发 `PACK_CREDIT` | `message` 可选；`PACK_CREDIT` 只有新版发 |
| 收礼通知 | `targetRef = petId` 照旧进礼物页 | 后端通知不改（D-9）|
| 我的送礼记录 | 免费礼物行 `channel = PACK_CREDIT`、金额 0（旧版按原样显示字符串与 Rp0）| 可接受；新版显示「Pakai jatah gratis」|
| PawCoin 流水 | 礼包扣币行 `refType = STARTER_PACK` 落到通用「消费」标签 | 无改动 |
| 订单中心 | 看不到礼包单 | `includeStarterPack` 闸门 |
| 跳转页 `STARTER_PACK` | 解析为空、不跳转 | 地基已保证 |
| KTP / 性格测试 | 只能付费解锁（次数在旧版不可用）| 次数解锁走新端点 |
| 名额 `PACK_GRANT` | 名额列表来源统一显示「Sewa」 | App 现有兜底 |
| H5 名片 | 网页，随后端一起变成个数 | 后端渲染 |

**前后端同批**：新版 App 依赖本线全部新接口，后端须先于 App 上线；后端单独上线对旧版无影响。

## 7. 迁移清单（预分配，日期 20261115，HHMM 0200–0259，不得另取）

| 版本 | 文件 | Story |
|---|---|---|
| `V20261115_0200` | `__create_gift_categories.sql` | 1-1 |
| `V20261115_0205` | `__gift_orders_message_and_opened.sql` | 1-3 |
| `V20261115_0210` | `__create_gift_message_reports.sql` | 1-5 |
| `V20261115_0220` | `__create_entitlement_credits.sql` | 4-1（最先做）|
| `V20261115_0225` | `__starter_pack_purchases_and_purpose.sql` | 4-2 |
| `V20261115_0230` | `__pet_slot_pack_kinds.sql` | 4-2 |
| `V20261115_0235` | `__pack_credit_channel.sql` | 4-3 |
| `V20261115_0240` ~ `_0259` | 备用（实现中发现需补列时按序取，回写本表与 sprint-status）| — |

本线**不改** `ck_notifications_type`（地基所有）。本线**负责重建**：`ck_payment_intents_purpose`（0225）、`ck_pet_slot_purchases_kind` / `ck_pet_slot_rentals_source`（0230）、`ck_gift_orders_channel` / `ck_gift_orders_total` / `ck_id_card_hd_purchases_channel`（0235），一律 DROP + ADD 重列全集，值取当前树最后一条定义它的迁移。

## 8. 跨线交界

- **地基（Story 0.1，已在 dev）**：`NotificationType.PACK_CREDIT_EXPIRING`、`ck_notifications_type`、`OpsAppPage.STARTER_PACK`（含后台四语标签）、`AppVersionGate`、`RetentionPushCalendar` —— 只用，不改。
- **④ 物种（在本线之后合）**：会改送礼面板的物种显示 / 排序（收礼宠物「Kucing · Persia」、`GiftCatalogService.orderFor`、`GiftCatalogItem.suits` / `for_cat` 等）与后台跳转提示文案（加「宠物类型」页）。本线：不重构这几处、不改签名；分类分组只在外层包一层。后台 `admin.opsPopups.form.targetHint` / `admin.opsNotify.form.targetHint` 按 PRD B §8 原文写（6 个页面），④ 合并时再追加。
- **⑥ 运营活动（在本线之后起）**：只调 §5 `EntitlementCreditService.grant`。PawCoin 流水「Event · 活动名」归 ⑥。
- **③ 首日 / 推送**：无交界（本线不加推送、不改 `kPushDirectTypes`）。

## 9. 验证层级约定

- **L0**（云端可跑）：`./mvnw -B clean package -DskipTests` + 纯单元测试（不起 Spring / DB / Redis）；`flutter analyze` + `flutter test`；迁移按 `PetSlotMigrationTextTest` 的写法做**文本测试**（断言 CHECK 全集、DROP + ADD、版本号）。
- **L1**（本地 Docker）：`*IntegrationTest` / `ApiIntegrationTest` 子类、Flyway 真跑、并发 / 幂等、订单中心归并、定时任务。
- **L2**（本地真机 / 模拟器 / 真凭证）：Rive 动画视觉、UI 还原度 ≥ 95%、QRIS 沙箱付款、通知点开跳转。

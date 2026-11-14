# Story 2.4: App 送礼礼物页逐笔列表与 Diary 礼物行（A1、A4 ~ A7、F1 ~ F4）

Status: review

> 版本 v1.5.0 · 会话 ② gift-pack · 分支 `feat/1.5.0-gift-pack`
> 口径优先级：`decision-log.md`（11-02 去粉丝榜 / 访客可看未拆；11-12 访客 Diary 页按钮）> PRD A §3.4 ~ §3.6（FR-190 ~ FR-192、FR-225）> 架构 D-10 ~ D-12、§4.1 > App 稿 A / F 泳道。
> 前置：1-4。
> 范围层：**只 App**。拆礼物归 2-5；详情抽屉归 2-6（本 story 点卡片先调 2-6 的入口函数，2-6 未完成前可临时空实现）。

## Story

As a 宠物主人 / 访客,
I want 礼物页一份份列出礼物、Diary 页有礼物行、送礼和关注按钮并排,
so that 收到的礼物看得见、送礼入口顺手。

## Acceptance Criteria

### AC1 — 数据层 `[L0]`
`gift_repository.dart` 新增：`fetchPetGiftOrders(petId, {filter, categoryId, cursor})`、`fetchPetGiftPreview(petId)`；模型 `GiftOrderCard`（`opened` 可空：访客为 null）、`GiftOrderPage`（`items`、`nextCursor`、`unopenedCount?`、`categories?`、首页 `pet` + `viewerIsOwner`——宠物解析复用现有 `gift_showcase.dart` 里的宠物模型）、`GiftPreview`。新版**不再调用** `/pets/{id}/gifts` 与 `/supporters`（旧 provider 与 `GiftSupportersController` 可删，删前 grep 确认无其他引用）。单测覆盖访客 / 主人两种 JSON。

### AC2 — 礼物页（访客 A4 ~ A6，主人 F2 ~ F4） `[L0]` `[L2]`
1. 替代线上 `gift_page.dart` 的「汇总卡 + Koleksi hadiah 礼物墙 + Pendukung 粉丝榜」三块（全部去掉）。
2. 顶栏「Hadiah {petName}」（复用 `giftPageTitle`）；主人视角有未拆时右侧「Buka Semua」（新文案，无未拆时隐藏）→ 进 2-5 批量拆。
3. 筛选胶囊横滑：「Semua」/ 主人视角才有「Belum dibuka」（新文案）/ 后台分类（首页响应 `categories`，按语言取名、按后端顺序）。
4. 3 列逐份礼物：图标 + 礼物名 +「dari {nama}」；主人视角未拆的显示礼盒图（`kGiftBoxClosedAsset`）+「Masih terbungkus」+ 右上「Baru」角标（白字，不被裁）排最前（后端已排）；访客看到的都是礼物本身。
5. 访客底部吸底「Kirim Hadiah」（复用 `giftSendButton`）→ 2-1 选礼物页；主人无送礼按钮。
6. 空态：访客 A5 `giftPageEmptyTitle` + `giftPageEmptyVisitorHint` + 送礼按钮；主人 F3 `giftPageEmptyTitle` + `giftPageEmptyOwnerHint`，无按钮；F4 筛选未拆为空同 F3 空态。加载失败 A6：`giftLoadFailed` + `commonRetry`。
7. 点卡片：主人点未拆 → 2-5 单个拆（G1）；主人点已拆 / 访客点任意 → 2-6 详情抽屉（H1 / H2）。
8. 路由：`GiftPage.routeFor(petId)` 保持 `/pets/:id/gifts`；新增可选 query `open=latest`（2-5 实现其行为：主人视角有未拆 → 直接进 G1 拆最新一笔未拆，否则打开最新一笔详情）。旧 query `wall=expanded` 忽略。

### AC3 — Diary 礼物行（F1 主人 / A1 访客） `[L0]` `[L2]`
1. 位置：🏆 Pencapaian 进度卡下方（主人：进度卡与 Linimasa 之间）。标题「Hadiah {petName} ›」（复用 `giftPageTitle`）；主人右侧「{n} hadiah」（复用 `giftDetailCount`，`n = giftCount`）。
2. 横滑图标条（`recent`）：右下送礼人小头像；主人视角未拆的带「Baru」角标（礼物条加内边距防裁）；访客都显示礼物本身。没有礼物时只留标题、不显示图标条。点标题 / 图标 → 礼物页。
3. 统计条去掉「Hadiah」格（剩 Diary / Milestone / Pengikut 三格），主人 / 访客两处都改。

### AC4 — 送礼与关注按钮并排（A1 / A7） `[L0]` `[L2]`
1. 他人宠物 Diary Tab：标题行右侧「Kirim Hadiah」胶囊（`GiftSendPill`）去掉；宠物信息卡内与「Ikuti」并排：各占一半、间距 8、高 40、圆角 12；左 `PetFollowButton`（profileFull），右「Kirim Hadiah」+ 礼物图标（`giftSendButton`）。
2. 未关注：「Ikuti」实心紫、「Kirim Hadiah」浅紫底 `#EFE6FF` / 字 `#6C48AE`；已关注（A7）：「Mengikuti」白底描边（线上已关注态）、「Kirim Hadiah」升为实心紫。一次只有一个实心主按钮。
3. Diary 详情页礼物区（`GiftDetailZone`）、多宠物选择（A2 / A3）线上不改。

### AC5 — 文案 `[L0]`
新增：`Belum dibuka` / `Unopened`、`Masih terbungkus` / `Still wrapped`、`Baru` / `New`、`Buka Semua` / `Open All`（逐字取 App 稿新文案表）。其余复用（见上）。

## Tasks / Subtasks

- [x] 仓库 + 模型 + 单测（AC1）
- [x] 礼物页重写（AC2）
- [x] Diary 礼物行与统计条（AC3）
- [x] 按钮并排（AC4）
- [x] ARB（AC5）

## Dev Notes

- 背景页代码：`A/features/profile/presentation/visitor_archive_view.dart`、`widgets/diary_header.dart`（readOnly）、`widgets/pet_info_card.dart`、主人侧 `growth_archive_page.dart` 的 `_ArchiveBody`；送礼入口 `A/features/gift/presentation/widgets/gift_entry.dart`（`GiftSendPill`、`GiftDetailZone`）；关注按钮 `A/features/social/presentation/widgets/pet_follow_button.dart`。
- ④ 物种线之后会改 Diary 页头的物种显示：本 story 只动按钮行、礼物行、统计格，不改物种 / 品种文字的拼法。
- 列表分页用 `nextCursor`，加载更多照 `gift_orders_page.dart` 的 `_LoadMoreFooter` 写法。

## 验证层级

- L0：`flutter analyze`；`flutter test test/features/gift/ test/features/profile/`
- L2（本地）：A1、A4 ~ A7、F1 ~ F4 还原度 ≥ 95%

## Dev Agent Record

### Completion Notes

- **L1/L2 待本地验收**：A1、A4 ~ A7、F1 ~ F4 逐屏还原度（模拟器 + 真后端，含「Baru」角标不被裁、3 列卡片、按钮并排两种关注态、Diary 礼物行横滑）。
- L0：`flutter analyze` 无 error / warning（存量弃用 info 不计）；`flutter test test/gift test/profile test/notify test/analytics` 全绿（全量跑过一轮，本线引起的 5 条失败已修：埋点对账、收礼通知深链）。测试放 `test/gift/`、`test/profile/`（仓库现有目录），非 story 写的 `test/features/*`。
- 数据层：`fetchPetGiftOrders(petId, {filter, categoryId, cursor})`、`fetchPetGiftPreview(petId)`；新模型 `gift_order_browse.dart`（`GiftOrderCard` / `GiftOrderPage` / `GiftPreview` 等，`opened` 访客为 null）；单测 `gift_order_browse_test.dart` 覆盖访客 / 主人 JSON。新页不再调 `/pets/{id}/gifts` 与 `/supporters`。**旧 provider（`petGiftShowcaseProvider`、`giftSupportersProvider` / `GiftSupportersController`）保留不删**：`gift_entry.dart`（`GiftDetailZone`，AC4.3 线上不改）仍引用 showcase。
- 礼物页 `gift_page.dart` 重写：顶栏 `giftPageTitle` + 主人有未拆时「Buka Semua」；筛选胶囊（Semua / 主人 Belum dibuka / 后台分类）；3 列卡片（主人未拆 = 礼盒 + Masih terbungkus + Baru，排序靠后端）；访客吸底「Kirim Hadiah」；空态 A5 / F3 / F4、失败 A6；分页 `nextCursor` 加载更多（翻页按 `orderToken` 去重）。
- 点卡片 / Buka Semua 调 `gift_order_actions.dart` 的 `openGiftOrders` / `showGiftOrderDetail`：**本 story 为空实现，2-5 / 2-6 填实**。
- 路由：`routeFor(petId, {openLatest})` 新增 `?open=latest`（行为在 2-5 实现）；旧 `wall=expanded` 忽略。`PET_GIFT_RECEIVED` 通知深链已改为 `?open=latest`（同步覆盖 2-5 AC3.1 的路由部分）。
- Diary 礼物行 `widgets/gift_diary_row.dart`（`GiftDiaryRow`）：主人放进度卡与 Linimasa 之间，访客放进度卡下方；标题「Hadiah {petName} ›」+ 主人「{n} hadiah」；横滑 `recent`（送礼人小头像、主人未拆 Baru）；无礼物只留标题。统计条去掉「Hadiah」格（主人 / 访客两处），`pet_info_card.dart` 的 `_giftCol` 已删；`giftTotalValue` / `onOpenGifts` 参数保留（不破调用方签名，已无渲染）。
- 按钮并排 `GiftFollowAndSendRow`（同在 `gift_diary_row.dart`）：他人宠物信息卡内「Ikuti」+「Kirim Hadiah」各半、间距 8、高 40、圆角 12；一次只有一个实心主按钮；标题行 `GiftSendPill` 去掉。
- 埋点：PRD A §4 本线不加埋点。1.4.0 的 `gift_wall_opened` 保留（礼物页首次加载报一次）；`gift_sender_list_opened` 原挂在已删的「Pendukung」列表，改为逐笔列表首次加载到非空时报一次（口径最接近的保守做法，**待产品确认**是否废弃该事件）。
- 文案：`giftFilterUnopened`「Belum dibuka / Unopened」、`giftStillWrapped`「Masih terbungkus / Still wrapped」、`giftNewBadge`「Baru / New」、`giftOpenAll`「Buka Semua / Open All」。

### File List

- `petgo_app/lib/features/gift/domain/gift_order_browse.dart`（新）
- `petgo_app/lib/features/gift/presentation/gift_order_actions.dart`（新，2-5 / 2-6 入口占位）
- `petgo_app/lib/features/gift/presentation/widgets/gift_diary_row.dart`（新）
- `petgo_app/lib/features/gift/presentation/gift_page.dart`（重写）
- `petgo_app/lib/features/gift/data/gift_repository.dart`、`lib/core/network/api_paths.dart`、`lib/core/router/app_router.dart`、`lib/core/router/deep_link_routes.dart`（改）
- `petgo_app/lib/features/profile/presentation/growth_archive_page.dart`、`visitor_archive_view.dart`、`widgets/pet_info_card.dart`（改）
- `petgo_app/lib/l10n/app_en.arb`、`app_id.arb`（改）
- `petgo_app/test/gift/gift_order_browse_test.dart`（新）；`test/gift/gift_page_test.dart`、`gift_entry_visibility_test.dart`、`test/notify/deep_link_routes_test.dart`、`gift_notification_test.dart`、`test/profile/growth_archive_r2_test.dart`、`growth_archive_state_branch_test.dart`、`pet_info_card_follower_stat_test.dart`、`pet_info_card_gift_stat_test.dart`、`visitor_archive_view_test.dart`（改）

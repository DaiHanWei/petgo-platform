# Story 2.4: App 送礼礼物页逐笔列表与 Diary 礼物行（A1、A4 ~ A7、F1 ~ F4）

Status: ready-for-dev

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

- [ ] 仓库 + 模型 + 单测（AC1）
- [ ] 礼物页重写（AC2）
- [ ] Diary 礼物行与统计条（AC3）
- [ ] 按钮并排（AC4）
- [ ] ARB（AC5）

## Dev Notes

- 背景页代码：`A/features/profile/presentation/visitor_archive_view.dart`、`widgets/diary_header.dart`（readOnly）、`widgets/pet_info_card.dart`、主人侧 `growth_archive_page.dart` 的 `_ArchiveBody`；送礼入口 `A/features/gift/presentation/widgets/gift_entry.dart`（`GiftSendPill`、`GiftDetailZone`）；关注按钮 `A/features/social/presentation/widgets/pet_follow_button.dart`。
- ④ 物种线之后会改 Diary 页头的物种显示：本 story 只动按钮行、礼物行、统计格，不改物种 / 品种文字的拼法。
- 列表分页用 `nextCursor`，加载更多照 `gift_orders_page.dart` 的 `_LoadMoreFooter` 写法。

## 验证层级

- L0：`flutter analyze`；`flutter test test/features/gift/ test/features/profile/`
- L2（本地）：A1、A4 ~ A7、F1 ~ F4 还原度 ≥ 95%

## Dev Agent Record

### Completion Notes

### File List

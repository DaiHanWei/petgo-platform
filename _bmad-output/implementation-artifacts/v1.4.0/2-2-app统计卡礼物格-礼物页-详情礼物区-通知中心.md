# Story 2.2: App——统计卡礼物格、礼物页、详情礼物区、通知中心

Status: review

> 版本 v1.4.0 · 主题 gift · 分支 `feat/1.4.0-gift`
> 口径优先级：`decision-log.md` ③ 节 > `送礼/1-4-0送礼prd.md` > `architecture-v1.4.0-gift-delta.md` > UI 稿 `送礼/ui-1-4-0-gift-integrated-v1.html`（均在 `_bmad-output/planning-artifacts/v1.4.0/`）。
> 前置：**Story 2.1**（`giftTotalValue`、`GET /api/v1/pets/{petId}/gifts`、`/gifts/supporters`、详情 `giftPets`、Java 缩写用例表）、**Story 1.2**（`PET_GIFT_RECEIVED` 合并通知的 title / actorName / targetRef、`GIFT_UNDELIVERED_REFUND` 的 body）、**Story 1.3**（`features/gift/` 目录、送礼面板入口函数、「Kirim Hadiah」按钮与其 arb 键、`gift_` 埋点前缀登记）。
> 范围层：**只 App**。UI 稿 A1 / A2（统计格）、A3 ~ A6（详情礼物区）、E1 ~ E7、G1 ~ G4。送礼记录页（F 组）在 Story 2.3。
> 🔴 与 ①② 并行：A4 多宠礼物区、A7 选宠物、礼物墙随宠物切换、DiaryHeader 与 ② 切换器合并 → **对齐 Story 4.1**。本 story 只做单宠。不得引用 ② 的 Dart 类。

## Story

As a 宠物主人 / 访客,
I want 在 Diary Tab 看到礼物总价值、点进礼物页看礼物墙和粉丝榜、在详情页看到礼物区,
so that 礼物被看见。

## Acceptance Criteria

**AC1 — 金额缩写公共工具 `lib/shared/format/idr_compact.dart`** `[L0]`（PRD §3.2、UI 稿「定稿后修订」第 2 条）
1. 新建目录 `lib/shared/format/`（现不存在）与 `idr_compact.dart`：`String formatIdrCompact(int amount)`（整串，如 `Rp1,5rb`）+ `({String number, String unit}) idrCompactParts(int amount)`（供分字号渲染）。纯整数运算，不用 `double`；**与语言无关**（逗号小数 + `rb` / `jt`，与 `formatIdrAmount` 不随语言变化同口径，`pay_channel_picker.dart`:11-19）。
2. 规则与 Java `IdrCompact`（Story 2.1 AC7）**完全相同**；测试 `test/shared/format/idr_compact_test.dart` **逐行照抄 2.1 AC7 的用例表**（-5 → Rp0、0 → Rp0、999 → Rp999、1000 → Rp1rb、1500 → Rp1,5rb、1999 → Rp1,9rb、53000 → Rp53rb、188000 → Rp188rb、188500 → Rp188,5rb、999999 → Rp999,9rb、1000000 → Rp1jt、1050000 → Rp1jt、1200000 → Rp1,2jt、1290000 → Rp1,2jt、1999999 → Rp1,9jt、25000000 → Rp25jt、1234567890 → Rp1234,5jt；`parts` 四例同 2.1），文件头注释写明对端路径 `petgo-backend/src/test/java/com/tailtopia/shared/format/IdrCompactTest.java`，改一边必须改另一边。
3. **不复用** `toko_page_v2.dart`:1048 的私有 `_compactIdr`：它是文件私有、用 `toStringAsFixed`（四舍五入）、`rb` 档丢小数（1500 → `1rb`）、不带 `Rp`，与本规则不符。本 story **不改**它（顶栏余额缩写另有口径，见其注释 :1044-1047）。
4. 完整金额（礼物页汇总大字「Rp53.000」、粉丝累计金额「Rp100.000」）一律 `'Rp${formatIdrAmount(v)}'`，不缩写。

**AC2 — Diary Tab 统计卡中间格（主人 / 访客共用）** `[L0]` `[L2]`（FR-149.1、PRD AC11、UI 稿 A1 / A2）
1. `ArchiveStats`（`archive_stats.dart`:2-41）加 `final int giftTotalValue`（`fromJson` 缺键按 0）；`consultCount` 字段保留（`pet_card_page.dart`:44 的 `/card/preview` 旧页还在读，不在本 story 范围）。
2. `PetInfoCard`（`pet_info_card.dart`:14）：中间格（:166 `_statCol(consultCount, l10n.petInfoStatConsult)`）改为礼物格——数值 `idrCompactParts(giftTotalValue)` 渲染成 `Text.rich`：「Rp」与「rb / jt」字号为数字的 0.6 倍（数字 17 → 单位约 10.2），颜色沿用三格统一的 `AppColors.mint`；标签新键 `giftStatLabel`；`giftTotalValue == null` 时仍显示占位「·」（沿用 `_statCol` 现状 :182）。构造参数 `consultCount` → `giftTotalValue`，新增 `VoidCallback? onOpenGifts`：非 null 时整格可点（`InkWell`，热区 ≥ 44）并在标签后加紫色「›」（UI 稿 A1「第三步在标签后加紫色 ›」）；null 时不可点、无「›」。Key：`ValueKey('petInfoGiftStat')`。
3. `DiaryHeader`（`diary_header.dart`:30）：参数 `consultCount`（:35 / :70）→ `giftTotalValue`，新增 `onOpenGifts`，透传给 `PetInfoCard`（:132-138）。
4. 三个调用方：
   - 主人 `growth_archive_page.dart`:447：`giftTotalValue: stats?.giftTotalValue`，`onOpenGifts: () => context.push(GiftPage.routeFor(widget.profile.id))`（A2 → E2）。
   - 站内访客 `visitor_archive_view.dart`:105-115：`giftTotalValue: stats?.giftTotalValue`；`widget.scope.isInApp` 时 `onOpenGifts` → `GiftPage.routeFor(widget.scope.petId!)`（A1 → E1）；分享 token 态（`ArchiveScope.visitor(token)`，`archive_scope.dart`:31，**无 petId**）见 AC2.6。
   - 游客示例 `diary_guest_page.dart`:86-95：`DiaryDemoData`（`diary_demo_data.dart`:43）新增 `giftTotalValue = 188000` 常量替换 `consultCount` 的传参（示例态显示 Rp188rb），`onOpenGifts` 不传（游客示例不可点）。
5. Given 宠物收到总价值 Rp53.000，When 主人或访客打开 Diary Tab，Then 统计卡为「Diary · Rp53rb · Milestone」三格，不再出现 Consultations / Konsultasi；点中间格进礼物页（AC11）。
6. **分享 token 态**：若 Story 1.3 已为该态拿到 petId（为了 A1「分享链接进入时同样显示送礼按钮」，T0 核对），礼物格同样可点；否则该态礼物格**只显示数值、不可点**，在 Completion Notes「待确认」写明（不擅自给公开投影加 petId）。

**AC3 — 礼物页（墙 + 粉丝榜同页）`/pets/:petId/gifts`** `[L0]` `[L2]`（FR-149.2、decision-log ③ Q1 / Q2、PRD AC11 / AC12 / AC12a、UI 稿 E1 ~ E7）
1. 路由：`GiftPage.routePattern = '/pets/:petId/gifts'`，**独立顶层 `GoRoute`**，注册在 `VisitorArchiveView.inAppRoutePattern`（`app_router.dart`:1084-1091）旁。⚠️ 加路由测试证明 `/pets/5/gifts` 落礼物页、不会被 `/pets/:petId` 当成 `petId = '5/gifts'`（那条 builder 里是 `int.parse`，会崩）。与访客视图一样**不进** `_controlledLocations`（`app_router.dart`:158-165），登录门控在入口处 `requireLogin`（`auth_guard.dart`:15）+ 服务端。支持 query `?wall=expanded`（通知深链用，AC6）。
2. 数据：`features/gift/data/` 加 `getPetGifts(petId)` / `getSupporters(petId, cursor)`（若 1.3 已建 `GiftRepository` 则加在其上）；provider 用 `FutureProvider.family(..., isAutoDispose: true)`（理由同 `timeline_repository.dart`:216-224 注释：family 默认 keep-alive 会让换账号后仍吃旧缓存、绕过服务端拉黑守卫）；粉丝榜分页用 Notifier（照钱包页加载更多的写法）。
3. 页面结构（E1 访客视角，自上而下）：AppBar「Hadiah {petName}」（`giftPageTitle`）→ 汇总卡（宠物头像 56 + 完整金额「Rp188.000」大字 + `giftPageSummary`「21 hadiah dari 9 pendukung」小字）→ 「Koleksi hadiah · 12 jenis」礼物墙：4 列网格，每格图标 + 按语言取 `nameId` / `nameEn` + 右上紫色小胶囊「×N」；**默认只露两行（8 格）**，超过时底部「Lihat semua ▾」展开全部（展开后按钮消失）→「Pendukung · N orang」粉丝榜：排名圆（前三名实色圆 金 `AppColors.gold` #F6A609 / 银 #A7A7A7 / 铜 #C98A5A + 白字，第 4 名起灰底灰字；**不用 🥇🥈🥉 emoji**）、头像 40、昵称、该人礼物「[图标]×n · [图标]×n」（`gifts` 最多 3 种 +「+moreKinds」）、右侧累计金额 `Rp{formatIdrAmount(totalAmount)}`（14 号、等宽数字 `FontFeature.tabularFigures()`）→ 访客视角**吸底**「Kirim Hadiah」主按钮（点击打开 1.3 的送礼面板，`source = gift_page`）。
4. 主人视角（E2，`viewerIsOwner == true`）：同 E1，**无吸底按钮**；榜单由服务端已剔除其隐藏的送礼人（客户端不做任何过滤）。
5. 注销送礼人：`deleted == true` → 灰色 `LetterAvatar` 默认头像 + `l10n.feedDeletedUser`（「Pengguna terhapus」，现有键 `app_id.arb`:256），不可点（AC12）。
6. 状态：E3 访客无礼物 / E4 主人无礼物（`giftCount == 0`，`EmptyState`（`empty_state.dart`:11）80 圆底 + `Icons.redeem_rounded`，标题 `giftPageEmptyTitle`，副文案访客 `giftPageEmptyVisitorHint` / 主人 `giftPageEmptyOwnerHint`；E3 吸底按钮照常、E4 无）；E5 加载中居中转圈；E6 加载失败 `Icons.cloud_off` + `detailNetworkError`「Gagal memuat, silakan coba lagi」+ `commonRetry`「Coba lagi」（均现有键）；E7 粉丝榜加载更多失败：列表底部行内「Gagal memuat · Coba lagi」（`giftLoadMoreFailed` + `commonRetry`），点击只重试下一页、已加载行保留。
7. 服务端 403（我拉黑了主人）/ 404（宠物已删、或主人拉黑了我）→ 一律 E6 形态（不区分原因，不提示「你被拉黑」）。
8. Given 宠物收到 Rp53.000，When 进礼物页，Then 汇总卡「Rp53.000」+「4 hadiah dari N pendukung」；墙 🎂×1 在 🦴×3 前（AC11）；粉丝榜每行有累计金额、前三名特殊样式（AC12）。

**AC4 — Diary 详情礼物区** `[L0]` `[L2]`（FR-149.3、PRD AC14 单宠部分、decision-log ③ Q3、UI 稿 A3 / A5 / A6）
1. `ContentDetail`（`content_detail.dart`:8，`fromJson` :134）加 `final List<GiftPetSummary> giftPets`（缺键 → 空表）。
2. `content_detail_page.dart`：礼物区插在场所条（:313-317 `_DetailCheckinPlaceStrip`）之后、分隔线（:319 `Divider`）之前；`giftPets` 为空不渲染（Moment / Tips / 无关联宠物）。作者区（:368 `_authorRow`）与图片区（:249 `_ImageCarousel(...)` 调用、:579 类定义）**不动**。
3. 结构（A3）：浅紫底卡（`AppColors.mintTint2` #F8F6FF，圆角 16）→ 小标题 `giftZoneTitle.toUpperCase()`（「HADIAH」；T0：1.3 已建该键并已在 `GiftDetailZone` 渲染，不另建 `giftDetailSectionTitle`）→ 每只宠物一张白色小卡：头像 32 + 名字 + `giftDetailCount`「24 hadiah」+ `wallPreview` 最多 3 个叠放小圆片图标 +「+N」（N = `kindCount − wallPreview.length`，0 不显示）+「›」；点该行 → `GiftPage.routeFor(petId)`（游客先 `requireLogin`）。
4. A5（该宠物 0 礼物）：副标题 `giftDetailEmpty`「Belum ada hadiah. Jadi yang pertama!」，无图标、无「›」，**仍可点进 E3 / E4 空态**（UI 稿 A5「实际也可点进 E3 空态，第三步定」→ 按可点实现，统一行为）。
5. 「Kirim Hadiah」按钮位于礼物区卡片底部、整宽、**次级样式**（白底紫字 + `AppColors.dashedViolet` #C2B0EC 描边，UI 稿 A3 第三步视觉）——按钮本身与显隐规则（`isAuthor` 不显示、A6）**属 Story 1.3**；若 1.3 已在别处放了按钮，本 story 把它挪进礼物区卡片底部，不改其行为与埋点。
6. A6（自己的 Diary）：礼物区照常展示、点行进 E2，无「Kirim Hadiah」。
7. Given 关联 1 只宠物的他人 Diary 详情，When 查看，Then 礼物区显示该宠物头像、名字、「N hadiah」、前 3 种图标、「›」与「Kirim Hadiah」；点行进该宠物礼物页（AC14 单宠部分）。

**AC5 — 通知中心 G1 ~ G3** `[L0]` `[L2]`（FR-149.5、UI 稿 G1 ~ G3）
1. `NotificationItem.serverCopyTypes`（`notification_item.dart`:25-30）加 `'PET_GIFT_RECEIVED'`——收礼通知标题由 1.2 后端拼整句（「Andi mengirim 🦴×5 untuk Mochi」/ 合并「Andi dan 2 lainnya mengirim hadiah untuk Mochi」），App 只把 `actorName` 加粗（沿用 `_serverTitle`，`notification_center_page.dart`:1005-1022）。无 actorName 时走本地兜底 `giftNotifyReceivedTitle` / `giftNotifyReceivedBody`。
2. `_iconStyle`（`notification_center_page.dart`:743）：`'PET_GIFT_RECEIVED' => (Icons.redeem_rounded, AppColors.gold, AppColors.goldTint)`（#F6A609，同 `PET_BIRTHDAY` :764）；`'GIFT_UNDELIVERED_REFUND' => (Icons.account_balance_wallet_rounded, AppColors.mint, AppColors.cream2)`（紫色，**不用** `REFUND_REJECTED` 的红色）。
3. `_typeLabel`（:872）/ `_typeBody`（:911）对 `'GIFT_UNDELIVERED_REFUND'` **读后端 title / body**（1.2 AC 已约定：后端按收件人语言渲染「Hadiah tidak terkirim」与含宠物名、退回金额、原因的正文后落库，与 G4 推送同一句）；任一为空时分别回落本地 `giftNotifyRefundTitle` / `giftNotifyRefundBodyFallback`。这是继 `OPS_ANNOUNCEMENT`（:905 / :959）之后又一个读后端串的类型，在 `_typeLabel` / `_typeBody` 与 :1189 的注释里补一句理由（正文带三个运行时参数，App 无从本地拼）。
4. 正文超两行时沿用现有「Lihat semua」展开（:1170-1183 的测量逻辑，不改）。
5. 埋点 `_notifTypeForAnalytics`（:270-277）不改（默认 `type.toLowerCase()` 即可）。

**AC6 — 通知 / 推送深链（G1 ~ G4）** `[L0]` `[L2]`（FR-149.5「点通知跳 Diary Tab 并展开礼物墙」、decision-log ③ Q5「退回通知进送礼记录」）
1. `DeepLinkRoutes.pushPayloadToLocation`（`deep_link_routes.dart`:62）在 id 寻址段加：`'PET_GIFT_RECEIVED'` → `int.tryParse(targetRef)` 成功则 `'/pets/$id/gifts?wall=expanded'`，否则 `notificationsCenter`（照 `USER_FOLLOWED` :156-158 的防崩写法）。1.2 story 已定 `targetRef = String.valueOf(petId)`（宠物没有可外发的非分享 token；`cardToken` 按 B1-D1 不得下发）——T0 核对 1.2 实际提交，若不同按 1.2 改解析。
2. 固定目标段加：`'GIFT_UNDELIVERED_REFUND'` → `'/me/gift-orders'`（1.2 的 targetRef 是订单 `public_token`，本版本落列表页、不用它定位单行；送礼记录页与路由由 **Story 2.3** 落地；本 story 只加映射与纯函数测试，点击到页的验证在 2.3）。
3. 「进 Diary Tab 并展开礼物墙」：通知中心点击（`_onTap`，:289-370）对 `PET_GIFT_RECEIVED` 特判——先 `context.go(DeepLinkRoutes.growthArchive)`（`/profile`，Tab 根只能 go，:13-24），**下一帧**再 `push` 礼物页（照 `_goDeepLinkWith` 的 post-frame 写法，`app_router.dart`:285-303），返回即回到 Diary Tab。系统推送冷 / 热启动走既有 `_goDeepLinkWith`：`deepLinkNeedsBaseRoute`（`app_router.dart`:218-219）对 `/pets/...` 为 true → 底座取落地矩阵（有宠物主人即 `/profile`）再叠礼物页，无需改。
4. 礼物页收到 `?wall=expanded` 时礼物墙初始即展开全部。
5. Given 主人收到收礼通知（单条 / 合并），When 点击，Then 落在该宠物礼物页（E2）且墙已展开，返回到 Diary Tab；Given 送礼人收到退回通知，When 点击，Then 进送礼记录（2.3 完成后验证）。

**AC7 — 埋点** `[L0]`（PRD §4、FR-AN）
1. `gift_wall_opened`：礼物页首次拿到数据时报一次（放 `initState` 后的首个 data 回调，不放 `build`，理由同 `visitor_archive_view.dart`:72-80），属性 `{is_owner}`。
2. `gift_sender_list_opened`：粉丝榜区块**首次滚入视口**时报一次（用页面 `ScrollController` + 区块 `GlobalKey` 判位置，不引新依赖；首屏即可见则首帧报），属性 `{is_owner}`。
3. 事件名前缀 `gift_` 与词尾 `_opened` 须过守卫：`test/analytics/v112_events_test.dart` 的 `allowedPrefixes`（:216 起，`gift_` 应已由 1.3 登记，未登记则本 story 加并写理由注释）与 `allowedSuffixes`（:346，`_opened` 已在 :383）；属性名 `is_owner` 加进 `propNames`（:491 起）。不删守卫、不放宽正则。属性不得含宠物名 / 昵称（`Analytics._piiKeys`，`analytics.dart`:42）。

**AC8 — 文案（新增 arb，EN / ID 两份都加；子前缀 `gift*`，已 grep：两份 arb 现无任何 `gift*` 键）** `[L0]`

| key | EN | ID | 用处 |
|---|---|---|---|
| `giftStatLabel` | Gifts | Hadiah | 统计卡中间格标签 |
| `giftPageTitle` | {petName}'s gifts | Hadiah {petName} | 礼物页 AppBar |
| `giftPageSummary` | {giftCount, plural, =1{1 gift} other{{giftCount} gifts}} from {supporterCount, plural, =1{1 supporter} other{{supporterCount} supporters}} | {giftCount} hadiah dari {supporterCount} pendukung | 汇总卡小字 |
| `giftWallTitle` | Gift collection | Koleksi hadiah | 墙标题 |
| `giftWallKinds` | {count, plural, =1{1 kind} other{{count} kinds}} | {count} jenis | 墙标题右侧 |
| `giftWallShowAll` | See all | Lihat semua | 展开 |
| `giftSupportersTitle` | Supporters | Pendukung | 榜标题 |
| `giftSupportersCount` | {count, plural, =1{1 person} other{{count} people}} | {count} orang | 榜标题右侧 |
| `giftPageEmptyTitle` | {petName} hasn't received any gifts yet | {petName} belum menerima hadiah | E3 / E4 |
| `giftPageEmptyVisitorHint` | Be the first to send {petName} a gift. | Jadi yang pertama kirim hadiah untuk {petName}. | E3 |
| `giftPageEmptyOwnerHint` | Gifts from friends will show up here. | Hadiah dari teman akan muncul di sini. | E4 |
| `giftLoadMoreFailed` | Couldn't load | Gagal memuat | E7（后接现有 `commonRetry`） |
| ~~`giftDetailSectionTitle`~~ | — | — | T0：不建；复用 1.3 已有 `giftZoneTitle`（Gifts / Hadiah，同值同用处） |
| `giftDetailCount` | {count, plural, =1{1 gift} other{{count} gifts}} | {count} hadiah | 详情宠物行 |
| `giftDetailEmpty` | No gifts yet. Be the first! | Belum ada hadiah. Jadi yang pertama! | A5 |
| `giftNotifyReceivedTitle` | New gift | Hadiah baru | 收礼通知无 actorName 兜底 |
| `giftNotifyReceivedBody` | Someone sent your pet a gift. | Ada yang mengirim hadiah untuk hewanmu. | 同上 |
| `giftNotifyRefundTitle` | Gift not delivered | Hadiah tidak terkirim | G3 标题 |
| `giftNotifyRefundBodyFallback` | Your gift couldn't be delivered. The PawCoin has been returned to your balance. | Hadiahmu tidak terkirim. PawCoin sudah dikembalikan ke saldomu. | G3 正文兜底 |

- 复用现有键：`feedDeletedUser`、`detailNetworkError`、`commonRetry`；「Kirim Hadiah」按钮用 **1.3 新增的键**（T0 核对其真实名，不另建）。
- 各键补 `@key` 描述与占位符类型（照 `app_en.arb` 现有写法）；运行 `flutter gen-l10n`（或项目既有生成方式）后提交生成文件。
- `petInfoStatConsult` 键保留不删（他处可能引用，删键不在本 story 范围）。

**AC9 — 回归与质量** `[L0]`
1. `flutter analyze` 零新增告警；相关测试绿。**不对整文件跑 `dart format`**，手工对齐缩进。
2. 会被按设计打破的既有测试：`test/profile/growth_archive_r2_test.dart`:80（`expect(find.text('Consultations'), findsOneWidget)` → 改为断言礼物格标签与缩写值）——更新断言，不删不 skip。`ArchiveStats(...)` 的构造在 `growth_archive_test.dart`:45、`visitor_archive_view_test.dart`:61 / :81、`growth_archive_r2_test.dart`:33 因新字段有默认值可不改。
3. 不写 golden；视觉对照 UI 稿留 L2。

## Tasks / Subtasks

- [x] **T0 开工核对**：2.1 实际 DTO 字段名；1.2 的 `PET_GIFT_RECEIVED` targetRef（预期数字 petId）与 `GIFT_UNDELIVERED_REFUND` 的 title / body / targetRef（预期订单 token）；1.3 的 `features/gift/` 结构、送礼面板打开函数签名、按钮 arb 键、`gift_` 前缀是否已登记、分享 token 态是否拿到 petId。有出入先改本文件并在 Completion Notes 记一句
- [x] **T1** `lib/shared/format/idr_compact.dart` + 测试（AC1）
- [x] **T2** 统计格：`ArchiveStats`、`PetInfoCard`、`DiaryHeader`、三个调用方、`DiaryDemoData`（AC2）
- [x] **T3** 礼物页：数据层 + provider + `GiftPage`（E1 ~ E7）+ 路由（AC3）
- [x] **T4** 详情礼物区：`ContentDetail` 模型 + `content_detail_page.dart` 插入（AC4）
- [x] **T5** 通知中心 + 深链（AC5 / AC6）
- [x] **T6** 埋点 + 守卫登记（AC7）；arb 两份（AC8）
- [ ] **T7** `flutter analyze` + 相关测试；Android 模拟器演示 E1 与统计卡（L2，留本地）

## Dev Notes

### 现状（改动前必读，2026-10-18 核 `dev_1.4.0` @ 688f9bd5）

| 文件 | 现状 | 本 story 改什么 | 必须保持 |
|---|---|---|---|
| `petgo_app/lib/shared/format/` | **不存在，需新建** | `idr_compact.dart` | — |
| `lib/shared/widgets/pay_channel_picker.dart` | `formatIdrAmount(int)` 千分位点分隔（:11-19） | 复用于完整金额 | — |
| `lib/shared/utils/compact_count.dart` | `formatCompactCount` K / M 计数缩写，小数点随语言（:10-19） | 不复用（那是人数计数，不是卢比） | — |
| `lib/features/shop/presentation/toko_page_v2.dart` | 私有 `_compactIdr`（:1048-1055），四舍五入、rb 档无小数、无 Rp | **不复用、不改** | 顶栏余额口径 |
| `lib/features/profile/domain/archive_stats.dart` | 6 字段 + `fromJson`（:2-41） | 加 `giftTotalValue` | `consultCount` |
| `lib/features/profile/presentation/widgets/pet_info_card.dart` | `PetInfoCard`（:14）三格 `_statCol(happyCount…)` / `_statCol(consultCount, l10n.petInfoStatConsult)`（:166）/ `_statCol(milestoneCount…)`；`_statCol` 无 onTap（:177-192） | 中间格换礼物格（可点 + 分字号） | 两侧两格样式与占位「·」 |
| `lib/features/profile/presentation/widgets/diary_header.dart` | `DiaryHeader`（:30）参数 `consultCount`（:35 / :70），透传 `PetInfoCard`（:132-138）；`readOnly` 访客只读（:64） | 参数改名 + `onOpenGifts` | `readOnly` 语义 |
| `lib/features/profile/presentation/growth_archive_page.dart` | 主人视图 `DiaryHeader(...)`（:447-474），`stats` 来自 `archiveStatsProvider`（:428）；`widget.profile.id` 可用（`pet_profile.dart`:24） | 传值 + 跳礼物页 | 其余回调 |
| `lib/features/profile/presentation/visitor_archive_view.dart` | `DiaryHeader(readOnly: true, consultCount: stats?.consultCount, …)`（:105-115）；`scope.isInApp` 才有 petId（`archive_scope.dart`:34-50）；`diary_visitor_viewed` 埋点放 initState（:72-80） | 传值 + 站内态跳礼物页 | 只读、无分享按钮 |
| `lib/features/profile/presentation/diary_guest_page.dart` | 游客示例 `DiaryHeader(consultCount: DiaryDemoData.consultCount, …)`（:86-95） | 改传示例礼物值 | 入口引建档 |
| `lib/features/profile/data/timeline_repository.dart` | `archiveStatsProvider`（:192）、`visitorStatsProvider` autoDispose family（:236-239，注释 :216-224） | 不改 | — |
| `lib/core/router/app_router.dart` | `/pets/:petId` 站内访客路由（:1084-1091，不进受控前缀）；`_controlledLocations`（:158-165）；`deepLinkNeedsBaseRoute`（:218-219）；`_goDeepLinkWith`（:285-303） | 加礼物页顶层路由 | 访客路由与受控表 |
| `lib/features/content/domain/content_detail.dart` | `ContentDetail`（:8）、`fromJson`（:134-）、`checkinPlace`（:129） | 加 `giftPets` | 既有解析 |
| `lib/features/content/presentation/content_detail_page.dart`（866 行） | 顺序：作者行（:241 调用 / :368 `_authorRow` 定义，注销名 `feedDeletedUser` :369）→ 图片（:249 `_ImageCarousel(...)`，类 :579）→ 正文 → 装饰标签 → 场所条（:313-317）→ Divider（:319）→ KOMENTAR | 礼物区插在 :317 之后、:319 之前 | 图片出血、其余顺序 |
| `lib/features/notify/domain/notification_item.dart` | `serverCopyTypes` 四类（:25-30）；`hasServerCopy`（:69） | 加 `PET_GIFT_RECEIVED` | 其余类型 |
| `lib/features/notify/presentation/notification_center_page.dart` | `_onTap`（:289-370，Tab 根用 go :361-366）；`_iconStyle`（:743，`PET_BIRTHDAY` 金色 :764）；`_typeLabel`（:872）；`_typeBody`（:911）；`_serverTitle`（:1005-1022）；只读后端串的例外仅 `OPS_ANNOUNCEMENT`（:905 / :959、注释 :1189） | 两个新类型的图标、标题、正文；收礼特判跳转 | 其余类型 |
| `lib/core/router/deep_link_routes.dart`（178 行） | `pushPayloadToLocation`（:62）固定目标段（:65-）、id 寻址段（:132-163），`USER_FOLLOWED` 防崩（:156-158）；`shellTabRoots`（:24） | 加两个类型 | 兜底落通知中心 |
| `lib/core/theme/colors.dart` | `gold` #F6A609（:46）、`goldTint`（:57）、`mintTint2` #F8F6FF（:24）、`dashedViolet` #C2B0EC（:41）；**无** #A7A7A7 / #C98A5A | 新增 `rankSilver` / `rankBronze` 两个 token | 既有色值 |
| `lib/l10n/app_en.arb` / `app_id.arb` | 无任何 `gift*` 键；`feedDeletedUser`（id :256 / en :389）、`detailNetworkError`（id :260）、`commonRetry`（id :2114） | 加 AC8 的键 | — |
| `test/analytics/v112_events_test.dart` | 前缀白名单 `allowedPrefixes`（:216 起）、词尾 `allowedSuffixes`（:346）、属性 `propNames`（:491 起） | 登记 `gift_`（若 1.3 未做）、`is_owner` | 规则本身 |

### T0 核对结果（2026-10-18，对照 1.2 = 0feee5e6、1.3 = 6b9a645c、2.1 = dcfe4531；优先于上表行号）

- 2.1 实际 DTO：`GET /api/v1/pets/{petId}/gifts` → `PetGiftShowcaseResponse{pet{petId,name,avatarUrl,petType}, viewerIsOwner, totalValue, giftCount, supporterCount, wall[GiftWallItemView{nameEn,nameId,iconUrl,unitPrice,count}]}`；`/gifts/supporters?cursor=` → `GiftSupporterPage{items, nextCursor?}`，`GiftSupporterRow{rank, nickname?, avatarUrl?, deleted, totalAmount, giftCount, gifts[{iconUrl,nameEn,nameId,count}], moreKinds}`；详情 `giftPets[GiftPetSummaryView{petId,name,avatarUrl,totalValue,giftCount,kindCount,wallPreview[GiftWallItemView]}]`；统计 `giftTotalValue`（`ArchiveStatsResponse` / `VisitorStats` 同名）。墙「N jenis」= `wall.length`。
- 1.2：`PET_GIFT_RECEIVED.targetRef = String.valueOf(petId)`（一致）；`GIFT_UNDELIVERED_REFUND` 后端渲染 title / body（一致）。
- 1.3：`features/gift/{data/gift_repository.dart, domain/, presentation/{gift_send_flow.dart, gift_panel_sheet.dart, widgets/gift_entry.dart}}`；`runGiftSendFlow({context, ref, recipient: GiftRecipientRef, source, flightTargetKey?, onDelivered?})`；按钮键 `giftSendButton`；详情页礼物区 1.3 已建 `GiftDetailZone(postId)`（标题 `giftZoneTitle`、次级按钮 `ValueKey('giftDetailSendButton')`，显隐 `_showGiftZone`）→ 本 story 在其内加宠物行、按钮保持在卡片底部；`source` 常量只有 `diary_tab` / `content_detail`，本 story 加 `giftSourceGiftPage = 'gift_page'`。`gift_` 前缀 1.3 已登记（`v112_events_test.dart`:348），`_opened` 在 :388，`propNames` 在 :497。分享 token 态 1.3 **未**拿到 petId（1.3「待确认 1」）→ AC2.6 走「只显示数值、不可点」。
- 行号更正：`pet_info_card.dart` 中间格 :171；`diary_header.dart` `consultCount` :35 / :75、透传 :140；`growth_archive_page.dart` :450；`visitor_archive_view.dart` `DiaryHeader(...)` :108-123；`content_detail_page.dart` 场所条 :320-323、1.3 礼物区 :326、Divider :328。

### 实现提示

- 礼物名一律按 `Localizations.localeOf(context).languageCode == 'id' ? nameId : nameEn` 取，**不渲染后端拼好的显示串**（NFR5）；收礼通知标题是 V1.3.4 既有「后端文案条目」例外，退回通知正文是本 story 新增例外（AC5.3）。
- 礼物图标 `iconUrl` 用 `AppImage`（`shared/widgets/app_image.dart`）加载；加载失败显示通用 `Icons.redeem_rounded` 占位，不崩。
- 粉丝榜第 1 页随礼物页一起请求（两个 provider 并行），墙与榜各自独立的加载 / 失败态：墙失败 = 整页 E6；榜首页失败 = 榜区块内行内重试（与 E7 同组件）。
- 统计格可点区域 + 「›」：访客视图 `readOnly: true` 只影响编辑铅笔与入口网格，**不影响**礼物格可点（UI 稿 A1 要求访客可点）。
- 新 provider 都是 autoDispose family，不必登记到 `resetUserScopedCaches`（换账号缓存重置只管 keep-alive 的用户维度 provider）。

### 架构约束

- AD-13：礼物页（E 组）、DiaryHeader 中间格换「礼物总价值」、详情页礼物区（A3 ~ A6）、通知中心（G 组）。
- AD-10：主人与访客同一接口、同一数值；缩写规则与 H5 一致（两份实现同一组用例）。
- decision-log ③：礼物页一页（墙在上默认两行可展开 + 粉丝榜分页）；粉丝榜显示累计金额；访客礼物页底部固定「送礼」、主人无；详情缩略点击进礼物页；退回通知进送礼记录；排名不用奖牌 emoji；通知礼物图标色 #F6A609。
- cloud-rules-gift.md：不写 golden；不整文件 `dart format`；埋点守卫不放宽。

### 与前后 story 的边界（本 story 不做）

- 不做送礼面板、选渠道、支付、动效、Toast，也不定义「Kirim Hadiah」按钮的行为（1.3）；本 story 只把按钮放进礼物页吸底与详情礼物区的位置。
- 不做送礼记录页、钱包页入口、钱包流水文案（2.3）；`/me/gift-orders` 的映射本 story 加，页面 2.3 加。
- 不改后端（2.1 / 1.2）；通知标题整句与合并文案由 1.2 后端生成。
- 不做 A4 多宠礼物区、A7、礼物墙随宠物切换、DiaryHeader 与 ② 切换器合并、锁区块跳等级页（对齐 Story 4.1）。

### 测试

**验证层级**
- **L0（云端可跑）**：
  - `test/shared/format/idr_compact_test.dart`：2.1 AC7 用例表逐行 + `parts` 四例。
  - `test/profile/pet_info_card_gift_stat_test.dart`：中间格显示 `Rp53rb`（单位与数字分两个 `TextSpan`、单位字号 = 数字 × 0.6）、标签 `Hadiah`、无 `Konsultasi`；`onOpenGifts` 非 null 可点且有「›」、null 不可点无「›」；`giftTotalValue` null 显示「·」。
  - `test/profile/growth_archive_r2_test.dart`（更新 :80）、`test/profile/visitor_archive_view_test.dart`（补：站内态点礼物格 push `/pets/{id}/gifts`；分享 token 态按 AC2.6 结论断言）。
  - `test/gift/gift_page_test.dart`（fake repository）：E1 访客有吸底按钮 / E2 主人无；墙 >8 种时默认 8 格 +「Lihat semua」、点后全部、`?wall=expanded` 初始全部；排名前三实色圆、第 4 名灰；`deleted` 行显示「Pengguna terhapus」；累计金额 `Rp100.000`；E3 / E4 文案与按钮有无；E5 转圈；E6（含 403 / 404 同形态）；E7 行内重试只补下一页；`gift_wall_opened` 报一次带 `is_owner`、`gift_sender_list_opened` 只报一次。
  - `test/gift/gift_route_test.dart`：`/pets/5/gifts` 解析到礼物页、`/pets/5` 仍是访客页（不崩）。
  - `test/content/content_detail_gift_area_test.dart`：有 `giftPets` → 礼物区在场所条之后、评论标题之前；0 礼物显示 A5 副标题且可点；空表不渲染；作者本人无「Kirim Hadiah」；「+N」计算。
  - `test/notify/deep_link_routes_test.dart`（补）：`PET_GIFT_RECEIVED` + `'12'` → `/pets/12/gifts?wall=expanded`、非数字 → 通知中心；`GIFT_UNDELIVERED_REFUND` → `/me/gift-orders`。
  - `test/notify/notification_center_v134_test.dart` 或新建 `test/notify/gift_notification_test.dart`：收礼条目金色礼物图标、后端标题人名加粗；退回条目紫色钱包图标、后端 title / body 优先、为空走兜底；点收礼条目先落 `/profile` 再叠礼物页。
  - `test/analytics/v112_events_test.dart`：前缀 / 属性登记后全绿。
- **L2（本地）**：Android 模拟器演示统计卡（A1 / A2）与礼物页 E1，对照 UI 稿做还原度检测（≥ 95%）；真机点收礼推送冷启动落礼物页、返回到 Diary Tab。
- 云端只跑 L0；Completion Notes 标「L2 待本地验收」。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.0/epics-v1.4.0-gift.md#Story 2.2]
- [Source: _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-gift-delta.md#AD-10、AD-12、AD-13、§0.3 第 9 条、§0.6、§6.2]
- [Source: _bmad-output/planning-artifacts/v1.4.0/送礼/1-4-0送礼prd.md#3.2 礼物展示、§4 埋点]
- [Source: _bmad-output/planning-artifacts/v1.4.0/decision-log.md#③ 送礼（Q1 ~ Q5、第四步核对、定稿后修订）]
- [Source: _bmad-output/planning-artifacts/v1.4.0/送礼/ui-1-4-0-gift-integrated-v1.html#A1 ~ A6、E1 ~ E7、G1 ~ G4、顶部「定稿后修订」]
- [Source: _bmad-output/implementation-artifacts/v1.4.0/2-1-礼物展示与统计后端.md#AC7 用例表]

### 拍板（2026-10-18，Dai；优先于本文件正文与「待确认」）

- P-5：退回通知直接显示后端 title / body（同运营通知做法）。
- P-6：封号主人宠物的礼物页可进入、可送礼。

## Dev Agent Record

### Agent Model Used

Claude (cloud dev agent)

### Debug Log References

- `cd petgo_app && flutter gen-l10n` ✅；`flutter analyze` ✅ No issues found（基线 0 → 0）。
- 新增 / 更新的相关测试单跑全绿；`flutter test` 全量 ✅ `+3543 ~1: All tests passed!`（~1 为既有 skip，非本 story）。
- `bash scripts/ci/check-flyway-versions.sh origin/main` → OK（本 story 无迁移、无后端改动，未跑 mvn）。
- 测试踩点：粉丝榜「滚入视口」判定在滚动回调里做时区块可能这一帧才建出来 → 滚动回调里另挂一次 post-frame 复判（`gift_page_test` 的滚入用例锁住）。

### Completion Notes List

- **T0 开工核对（对照 1.2 = 0feee5e6、1.3 = 6b9a645c、2.1 = dcfe4531）**：2.1 DTO / 1.2 targetRef 与本文件一致；**改了本文件四处**：① 新增「T0 核对结果」小节（实际 DTO 字段名、1.3 实际文件 / 函数签名 / 按钮键、行号更正）；② 详情礼物区小标题改用 1.3 已建的 `giftZoneTitle`（同值同用处），AC4.3 与 AC8 表中 `giftDetailSectionTitle` 划掉不建；③ 1.3 已在详情页建 `GiftDetailZone`（标题 + 次级按钮），本 story 在其内加宠物行、按钮保持在卡片底部，不改其行为与埋点；④ `source = gift_page` 常量 1.3 未建 → 本 story 在 `gift_send_flow.dart` 加 `giftSourceGiftPage`。前序对外契约未改。
- AC1：`lib/shared/format/idr_compact.dart`（`formatIdrCompact` / `idrCompactParts`，纯整数、向下截断、与语言无关）；测试逐行照抄 2.1 AC7 用例表 17 行 + parts 4 例，文件头写明对端 Java 路径。`toko_page_v2._compactIdr` 未动。
- AC2：`ArchiveStats.giftTotalValue`（缺键 0，`consultCount` 保留）；`PetInfoCard` 中间格换礼物格（`Text.rich` 三段 span，单位 0.6 倍，颜色 mint；`onOpenGifts` 非 null → `InkWell`（Key `petInfoGiftStat`）热区 ≥ 44 + 紫色「›」）；`DiaryHeader` 参数 `consultCount` → `giftTotalValue` + `onOpenGifts`；主人 → `GiftPage.routeFor(profile.id)`；站内访客 → `GiftPage.routeFor(scope.petId)`；游客示例 `DiaryDemoData.giftTotalValue = 188000`（Rp188rb）、不可点。**分享 token 态无 petId → 只显示数值、不可点**（AC2.6，见待确认 1）。
- AC3：`GiftPage`（`/pets/:petId/gifts` 独立顶层路由，注册在访客路由前；`int.tryParse` 失败 → petId 0 → 直接 E6 不发请求；不进受控前缀）；数据 `GiftRepository.getPetGifts / getSupporters` + `petGiftShowcaseProvider`（autoDispose family、`retry: null`）+ `GiftSupportersController`（autoDispose family Notifier，加载更多照 `PostLikersController` / 钱包页写法，build 代际防旧结果回写）。E1 ~ E7 全部实现；墙默认 8 格 +「Lihat semua ▾」、`?wall=expanded` 初始全开；排名圆金 / 银 / 铜（新增 token `AppColors.rankSilver` #A7A7A7 / `rankBronze` #C98A5A）+ 第 4 名起 `line2` 灰底灰字；注销者 `LetterAvatar(deleted)` +「Pengguna terhapus」；金额 `Rp{formatIdrAmount}` 14 号等宽；403 / 404 / 其它错误一律 E6；榜首页失败 = 区块内行内重试，下一页失败 = E7 行内重试（只补下一页）。首页不足一屏时 post-frame 自动补一次加载更多。访客吸底按钮调 `runGiftSendFlow(recipient: pet(petId), source: gift_page)`，送达后失效汇总与榜。
- AC4：`ContentDetail.giftPets`（`GiftPetSummary.listFromJson`，缺键空表；`withFollowEpoch` 透传）；礼物区改为 `giftPets` 非空才渲染（自己的 Diary A6 也展示），「Kirim Hadiah」显隐仍走 1.3 `_showGiftZone`；卡片 `mintTint2` 圆角 16、白色宠物行（头像 32、名字、`giftDetailCount` / A5 `giftDetailEmpty`、3 个叠放小圆片、「+N」、「›」）；点行 `requireLogin` 后 push 礼物页（A5 也可点）。作者区 / 图片区未动。
- AC5：`serverCopyTypes` 加 `PET_GIFT_RECEIVED`（沿用 `_serverTitle` 加粗 actorName；无 actorName → `giftNotifyReceivedTitle / Body`）；`_iconStyle` 收礼金色 `redeem_rounded`、退回紫色钱包；`GIFT_UNDELIVERED_REFUND` 在 `_typeLabel / _typeBody` 读后端 title / body（P-5），空则分别回落 `giftNotifyRefundTitle / giftNotifyRefundBodyFallback`，并在两处与 :1189 注释补理由。展开逻辑、`_notifTypeForAnalytics` 未改。
- AC6：`pushPayloadToLocation`：`PET_GIFT_RECEIVED` + 数字 targetRef → `/pets/{id}/gifts?wall=expanded`（非数字 → 通知中心）；`GIFT_UNDELIVERED_REFUND` → `DeepLinkRoutes.giftOrders = '/me/gift-orders'`（页面 / 路由由 2.3 落地）。通知中心点收礼条目：`go('/profile')` 后 post-frame `push` 礼物页（返回即 Diary Tab）。系统推送冷 / 热启动走既有 `_goDeepLinkWith`，未改。
- AC7：`gift_wall_opened`（initState 里 `listenManual` 首个 data 报一次）/ `gift_sender_list_opened`（`ScrollController` + 区块 `GlobalKey` 判进入视口，首屏可见首帧报，只报一次），属性均 `{is_owner}`；`v112_events_test` 的 `propNames` 加 `is_owner`（`gift_` 前缀 1.3 已登记、`_opened` 已在词尾表），`v140_gift_events_test` 对账表补两条。守卫未删、正则未放宽。
- AC8：两份 arb 各加 18 键（AC8 表减去复用的 `giftZoneTitle`），EN 带 `@key` 描述与占位符类型；复用 `feedDeletedUser` / `detailNetworkError` / `commonRetry` / `giftSendButton`；`petInfoStatConsult` 保留。
- AC9：被按设计打破的既有测试已更新断言（未删未 skip）：`growth_archive_r2_test`（Consultations → `Rp53rb` +「Gifts ›」）、`visitor_archive_view_test`（`'3'` → `Rp188rb`、分享 token 态不可点；新增站内态点礼物格 push 用例）、`gift_entry_visibility_test`（1.3 详情礼物区用例补 `giftPets`、源码顺序断言改匹配 `GiftDetailZone(`）。无 golden、未整文件 `dart format`、无新依赖、无后端改动。

#### L1/L2 待本地验收

- L1：无（本 story 只 App，无后端改动）。
- L2（Android 模拟器 + stag 后端）：A1 / A2 统计卡（Rp53rb 分字号、紫色「›」、点进 E1 / E2）与礼物页 E1 对照 UI 稿还原度 ≥ 95%；E2 ~ E7 各态目视；详情礼物区 A3 / A5 / A6 视觉（浅紫卡、叠放图标、次级按钮）；通知中心 G1 / G3 视觉。
- L2：真机点收礼推送冷启动 / 热启动 → 落礼物页（墙展开），返回到 Diary Tab；点通知中心收礼条目同样行为。
- L2：退回通知点击进送礼记录（`/me/gift-orders` 页面由 Story 2.3 落地，2.3 完成后验证；在此之前点击会落 go_router 未匹配页）。
- L2：游客在详情页点宠物行 → 登录强弹窗（L0 只覆盖已登录路径）。

#### 待确认

1. **分享 token 态（`/pet/:token`）礼物格不可点**（AC2.6）：1.3 未为该态拿到 petId（1.3 待确认 1），本 story 不擅自给公开投影加 petId → 只显示数值。若产品要可点，需后端在分享投影下发可寻址标识（petId 或新 token）。
2. **退回通知在 2.3 前点击无落地页**：`/me/gift-orders` 路由由 2.3 新增；本 story 只做映射（按本文件 AC6.2）。
3. **详情礼物区显示条件改为「有 `giftPets` 即显示」**：作者已注销的他人 Diary 若后端仍下发 `giftPets`，礼物区照常显示宠物行、无「Kirim Hadiah」（按钮仍走 1.3 规则）；如需整区隐藏再定。
4. **礼物页加载中 / 失败态 AppBar 标题**用「Hadiah」（`giftStatLabel`）：UI 稿 E5 / E6 画的是「Hadiah Mochi」，但拿到数据前 App 不知道宠物名（路由只带 petId）；如需完全一致，可让入口经 `extra` 传宠物名，待定。
5. **E3 / E4 空态**只显示空态组件（不显示汇总卡 / 墙 / 榜），与 UI 稿 E3 一致。
6. **墙格图标回退**沿用 1.3 `giftIcon` 的 `Icons.redeem`（本文件写 `redeem_rounded`），同一视觉族，保持 gift 模块内一致。
7. 所有新 EN 文案为本文件给定值；ID 取 UI 稿 / 本文件原文。

### File List

**新增（petgo_app/）**
- `lib/shared/format/idr_compact.dart`
- `lib/features/gift/domain/gift_showcase.dart`
- `lib/features/gift/presentation/gift_page.dart`
- `lib/features/gift/presentation/gift_supporters_controller.dart`
- `test/shared/format/idr_compact_test.dart`
- `test/profile/pet_info_card_gift_stat_test.dart`
- `test/gift/gift_page_test.dart`
- `test/gift/gift_route_test.dart`
- `test/content/content_detail_gift_area_test.dart`
- `test/notify/gift_notification_test.dart`

**修改（petgo_app/）**
- `lib/core/network/api_paths.dart`（`petGiftSupporters`）
- `lib/core/router/app_router.dart`（礼物页顶层路由）
- `lib/core/router/deep_link_routes.dart`（`PET_GIFT_RECEIVED` / `GIFT_UNDELIVERED_REFUND`、`giftOrders`）
- `lib/core/theme/colors.dart`（`rankSilver` / `rankBronze`）
- `lib/features/content/domain/content_detail.dart`（`giftPets`）
- `lib/features/content/presentation/content_detail_page.dart`（礼物区显示条件 + 传参）
- `lib/features/gift/data/gift_repository.dart`（`getPetGifts` / `getSupporters` / `petGiftShowcaseProvider`）
- `lib/features/gift/presentation/gift_send_flow.dart`（`giftSourceGiftPage`）
- `lib/features/gift/presentation/widgets/gift_entry.dart`（`GiftDetailZone` 宠物行）
- `lib/features/notify/domain/notification_item.dart`（`serverCopyTypes`）
- `lib/features/notify/presentation/notification_center_page.dart`（图标、标题 / 正文、收礼点击）
- `lib/features/profile/domain/archive_stats.dart`、`domain/diary_demo_data.dart`
- `lib/features/profile/presentation/diary_guest_page.dart`、`growth_archive_page.dart`、`visitor_archive_view.dart`、`widgets/diary_header.dart`、`widgets/pet_info_card.dart`
- `lib/l10n/app_en.arb` / `lib/l10n/app_id.arb`（+18 键）
- `test/analytics/v112_events_test.dart`（`propNames` + `is_owner`）、`test/analytics/v140_gift_events_test.dart`（对账 +2）
- `test/gift/gift_entry_visibility_test.dart`、`test/notify/deep_link_routes_test.dart`、`test/profile/growth_archive_r2_test.dart`、`test/profile/visitor_archive_view_test.dart`

**修改（实现产物）**
- `_bmad-output/implementation-artifacts/v1.4.0/2-2-app统计卡礼物格-礼物页-详情礼物区-通知中心.md`（T0 修订 + 本记录）
- `_bmad-output/implementation-artifacts/v1.4.0/sprint-status-v1.4.0-gift.yaml`（2-2 → review）

### Change Log

- 2026-10-18：T0 核对后修订本文件（T0 核对结果小节、`giftZoneTitle` 复用、1.3 `GiftDetailZone` 扩展、`gift_page` source 常量）；实现 App 金额缩写工具、统计卡礼物格、礼物页 E1 ~ E7 + 路由、详情礼物区、通知中心 G1 ~ G3 与深链、埋点与文案；L0 全绿（analyze 0 issue、flutter test 全量 +3543 ~1）；状态 → review（L2 待本地验收）。
- 2026-10-18（code-review 复审）：修 `GiftSupportersController.loadMore` 在刷新（送达后 invalidate）进行中仍用旧游标续页、旧页可能晚到盖掉新首页 → 加 `state.isLoading` 守卫 + `gift_page_test` 回归用例；L0 复跑全绿。

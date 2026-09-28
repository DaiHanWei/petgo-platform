# V1.3.2 batch-a 代码核对报告（2026-09-28）

> PRD / 内容设计 / UI 稿里「现状如此」的说法逐条对 `feat/1.3.2-batch-a-app-feature`（= `dev_1.3.2` @ c69bf99d）核实的结果。**拆 epics / 写 story 前必读。**
> 路径：`APP` = `petgo_app/lib`，`BE` = `petgo-backend/src/main`，`TEST` = 各自 test 目录。

## 0. 会被本版本「按设计打破」的既有测试（改代码时要一并改，别当回归）

| 测试 | 钉的是什么 |
|---|---|
| `petgo_app/test/profile/pet_insights_test.dart:91-126` | 聚合页源码不得出现 passport / tailsonality / comingSoon；InkWell 恰好 2 个 |
| `petgo_app/test/user_profile/public_profile_pet_test.dart:151-157` | `profilePetTailsonality` key 与 "Tailsonality" 文本 findsNothing |
| `petgo_app/test/user_profile/public_profile_posts_test.dart:277-313` | 公开主页源码（小写化后）禁 milestone / passport / checkin / share / follow / visitor / bio——**小标实现的标识符也不能含这些词**；FR-118.7 反向清单本身仍要守 |
| `petgo-backend/.../onboarding/OnboardingMarkTest.java:117, :134` | `OnboardingMarkKey.values()` hasSize(1)；`tailsonality_intro` 必须被拒 |
| `petgo_app/test/profile/milestone_celebration_test.dart:32-42` | 三级同一全屏页、无 `milestoneChestTap`（本版本不做分级仪式，D-11，此测试继续成立） |

## 1. Tailsonality（FR-117）

- **聚合页** `APP/features/profile/presentation/pet_insights_page.dart`：`IntrinsicHeight > Row > 2×Expanded(InsightEntryCard)`；年龄卡非猫狗时 `onTap:null` + Opacity .45 原地置灰。组件 `widgets/insight_entry_card.dart`（`inkKey/onTap/icon/title/sub`）。无任何占位（:36-39 注释明说）。
- **引导蒙层**：`growth_archive_page.dart:309-392`，`CoachmarkOverlay`；「已看过」**按账号存后端** `user_onboarding_marks(user_id, mark_key) UNIQUE`，接口 `/api/v1/me/onboarding-marks`，key 白名单枚举 `BE/.../onboarding/domain/OnboardingMarkKey.java`（现仅 `KTP_MOVED`）。加第二次触发 = 加枚举值（无需迁移）+ Dart 常量 + 复制显示逻辑。
- **公开主页小标**：`public_profile_page.dart` `_PetSection`(:650-760) **没有预留位置**；数据模型 `PublicProfilePetResponse.java` / `public_profile_pet_repository.dart` 需加字段。PRD「FR-118 已预留小标位置」不成立。
- **支付**：`APP/shared/widgets/qr_payment_sheet.dart` **只管 QRIS**；PawCoin 在其之前的选渠道抽屉 `id_card/hd_paywall_sheet.dart` 里选（`HdPayChannel {qris, pawcoin}`）。后端范式 `IdCardHdService.purchaseCard`：PAWCOIN 走 `wallet.debit(..., idempotencyKey)`，QRIS 走 `paymentIntents.createIntent(PaymentPurpose.ID_HD)`。`id_card_hd_purchases` 现为购买流水（V92 已去掉 UNIQUE(user_id)，实体注释过期），解锁态落在 `id_cards.hd_unlocked`。
- **定价配置**：单行表 `pricing_config`（id=1），**加一项 = 加一列 + 迁移 + 表单字段**（非 KV）。已有 `passport_page_unlock_price`、`passport_boarding_unlock_price`（V20260909_1843，CHECK ≥1）。后台卡 `POST /admin/config/ktp-pricing`（`AdminConfigController:266-289`，`AdminConfigService.updateKtpPricing` 校验 1..1e8，写 `config_change_logs`）。**缺 Tailsonality 一列**（D-2）。
- **分享奖励**：`share/ShareRewardService.tryReward` 为全局闸门；渠道**不是注册表**，每个渠道各自一套 service + 流水表 + `pawcoin_config` 两列（奖励额 / 日上限）+ 后台表单参数。先例：KTP（`id_card_share_rewards` UNIQUE(pet_profile_id)）、年龄卡（`age_card_share_rewards`）。代码里无「AB-3M」字样。
- **分享卡基建**：`APP/shared/card_render/card_render_pipeline.dart`（任意 widget → PNG）、`card_canvas.dart`（story 1080×1920 / square）、`card_watermark.dart`、`card_export.dart`（`showSheet` 存相册/分享）。模板 `features/content/presentation/share_card/share_card_template.dart`：只有**品牌段 15% 在 9:16 上固定**，上半 85% 按内容伸缩；二维码现指 `/c/{token}?src=qr`。预览页 `share_card_preview_page.dart` 有 `SegmentedButton` `shareCardRatioToggle`。`ShareCardEntry` 绑定帖子（`ContentDetail`），**不通用**。
- **下载落地页** `GET /get`（`LegalPageController:71`，permitAll）在线，App 内无引用；`card_link.dart:16-18` 注释提示需重建 `petDownloadUrl`。
- **ImageLightbox** `APP/shared/media/image_lightbox.dart`：`open(context, urls, initialIndex, heroTagPrefix, source)`，**只收 URL**，不支持本地渲染的 PNG（结果卡大图需扩展或另传 bytes）。
- **发帖页** `publish_compose_page.dart`：入口是 `PublishComposePage.open(context, {preset, presetEventDate, milestoneCode})`（**不是 `show()`**），**无 `initialText` / 初始图片参数**；`PublishController.addImage(Uint8List)`、`setText`，上限 9 图 / 1000 字；provider autoDispose 无草稿。预填需新增参数。
- **Diary 时间线**：后端**查询时拼装、从不落库**（AD-2），`TimelineService.fetchMerged` 五源 + `TimelineClassifier.classify`。`TimelineItemType` = HAPPY_MOMENT / HAPPY_MOMENT_MILESTONE / MILESTONE_BANNER / HEALTH_RECORD / ID_CARD_ISSUED，前后端同名为契约。**旧客户端遇未知类型回落成照片卡**（`timeline_item.dart:139-143`）——新增 banner 类型对老版本 App 会显示成照片卡，需评估（可按客户端版本号下发，或接受）。
- **物种**：`PetType` = CAT / DOG / OTHER（建档后不可改），`breed` 自由文本。
- **埋点**：App `core/analytics/analytics.dart`（PostHog）；**App 端会丢弃 `breed`、`name`、`title/label/text/...` 等键及 >64 字符的值**；后端 `AnalyticsEventGuard.java` 有**事件名白名单**，服务端新事件须登记。
- 现有 tailsonality / personality / mbti 实现：**无**，只有注释与反向测试。

## 2. 场所打卡 + 护照（FR-112 §8 / FR-120）

- **places 表**（`V20260909_1749` + `V20260918_2110`）：`public_token`、`name`、`place_type`、`tags JSONB`、`city`、`address_text`、`lat/lng NUMERIC(9,6)`、`status` ∈ ACTIVE / DELISTED / MERGED、`merged_into_id`、`deleted_at`。计数列已全部删除、改实时算。
- **类型枚举** CAFE / RESTAURANT / PARK / MALL / HOTEL / PET_SERVICE / OTHER，与 7 款默认章 **1:1 对应**。
- **`place_checkins` 表已存在但是空壳**：`id, place_id, user_id, checked_at`，**无 pet 关联、无帖子关联、无唯一约束**；App 端无实体 / 接口 / 页面（`PlaceCheckin` 只在 admin 包）。
- **后台场所管理**：Thymeleaf + HTMX，`BE/.../admin/places/web/AdminPlaceController.java`（`/admin/places`，权限 `place.manage`），已有编辑 / 下架 / 恢复 / **合并**（`PlaceMergeService`：一个事务内迁照片、评论、打卡到保留方，发 `PlaceMergedEvent`）。**没有「并章、次数相加」的监听器**，合并后同一用户同一场所可能有两条当日打卡——本版本要补。**无专属章上传字段**（AB-18B 新增）。**新建场所必须带照片（D-5）需加校验**。打卡计数在后台被开关 `admin.places.checkin-visible`（默认 false）藏着。
- **场所详情页** `APP/features/place/presentation/place_detail_page.dart`：:41 注释「没有打卡按钮（⑧ 在批次 B2）」，且有契约测试钉 DTO 无打卡字段。评论输入条在 body Column 底部（为键盘避让），打卡按钮不能吸底。距离由服务端按坐标（客户端取整约 110m）算，`distanceMeters` 为 null 时整行不显示。
- **定位**：`features/place/data/location_service.dart`（permission_handler + geolocator，whenInUse、中精度、5s 超时、10 分钟内缓存、不记坐标日志），`place_location_controller.dart` 有 `mustGoToSettings / openSettings`，已有「去设置」横幅先例。**无任何地理围栏 / 距离校验逻辑**。
- **KTP / 护照号**：`id_cards` **按 user_id 存，没有 pet 外键**；每张卡都带 `card_no` 与 `passport_no`。`CardNumberService`：护照号 `TT+SP+P+YY+XXXXX` 12 位，XXXXX 为**按 WIB 年**的全局计数器 `passport_no_counters`（不分物种），物种码 DOG=01 / CAT=02 / 其他 00。D-6「沿用 KTP 护照号」要走「账号 → 唯一宠物」这层对应。
- **content_posts**：`pet_id` 单值可空，**无 place / checkin 关联**，需加。帖子详情 `features/content/presentation/content_detail_page.dart`，场所条自然位置在正文后、分隔线前（:283-307），与 UI 稿 C8 一致。
- **里程碑「去发布」回填**（Story 8.4）：`publish_compose_page.dart:360-371` 发布成功后 best-effort 调 `milestoneRepository.checkIn(code, postId)`。打卡发帖可照抄此模式、加并行参数，不能直接复用（写死了里程碑）。
- **多宠**：`pet_profiles` 仍有 **`UNIQUE(owner_id)`**（一账号一宠），App 无「当前选中宠物」状态。PRD 要求打卡↔宠物多对多：**建关联表，界面按单宠**。
- **命名撞车**：「Paspor」已用于 Diary 头部与 KTP 护照样式卡；「checkin」已用于里程碑打卡（`MilestoneCheckInService`）。新代码命名需区分（建议 `place_visit` / `passport_stamp` 或明确前缀）。

## 3. 里程碑视觉（FR-111）

- **清单** `BE/.../profile/domain/MilestoneCatalog.java`：猫 31 / 狗 31 / 通用 16，共 78 code，与 PRD 一致。按语义去重：三物种共有 15 + 猫狗共有 8 + 通用独有 1（G-M2）+ 猫独有 8 + 狗独有 8 = **40 枚**（G-M2 复用疫苗图则 39）。同语义在通用套里后缀不同（如 Camilan = C-S8 / D-S8 / **G-S6**）——**插画映射必须按完整 code 或语义键，禁止按后缀**。
- **现状徽章全是内联的 `Icons.emoji_events_rounded`**，六处各写一份、无公共组件：
  1. 庆祝页 `widgets/milestone_celebration.dart` `_badge()`（:308-326）——**大徽章恒为紫色渐变，不是级别色**（PRD「渐变大徽章 + 级别色」不准确）；级别小标与 KOLEKSI 圆点用 `_levelColor`（L 金 / M 紫 / S 绿）
  2. 列表页徽章墙 `milestone_list_page.dart` `_Badge`（:532-602）
  3. 列表底抽屉 `_showBadgeSheet`（:702-722）
  4. Diary 时间线里程碑条 `widgets/timeline_item_tile.dart:212-330`（🏆 emoji；配色 L 紫 / M 浅紫 / S 金，与列表页不一致）
  5. 通知中心 `notify/presentation/notification_center_page.dart:666-670`
  6. H5 `/m` 分享页 `BE/resources/templates/milestone_share.html`（CSS 紫圆 + 🏆 文本）
- 资源约定可仿 `assets/age_card/`（按物种 × 序号的 webp + Dart 映射表）→ 建议 `assets/milestone/` + code→asset 映射 + **一个公共徽章组件替换六处**。H5 端静态资源走 `BE/resources/static/brand/`（`/brand/**` 公开）或 OSS CDN；注意 `wordmark_brand.svg` 被模板引用但文件不存在。
- **H5 分享页只拿到级别串**：`milestone_shares.collection_levels` 存的是 "SSMLS" 这类字符串，模型不传 code → KOLEKSI 圆点要逐条显示徽章需**改表 / DTO**；旧分享无 code（C-10）。
- **三处文案同步**：`MilestoneCatalog.titleZh/titleId` + App `domain/milestone_titles.dart`（Dart 常量表，不在 ARB），跨库测试 `MilestoneCatalogI18nTest.java`。另有第四份 `domain/milestone_celebration_copy.dart`（75 条，C-S16 / D-S16 / G-S9 缺失回落标题）。`titleId` 实际只被 `/p` 档案 H5 用，`/m` 用的是客户端分享时写入的本地化文案。
- 补庆祝（`celebrated_at` 在 `milestone_completions`）、同操作多条只弹最高级：**均已上线**，与 PRD 一致。
- 代码注释「三级动效 S 半屏 / M 全屏 / L 开宝箱」：**从未实现**，注释过期。
- **访客视图没有徽章墙**（`visitor_archive_view.dart` 只有计数进度条与时间线条目；公开主页有测试禁止徽章墙）——PRD「访客视图徽章墙会自动切换」不成立，也无需适配。

## 4. 约定提醒

- Flyway 新迁移一律时间戳号 `V<yyyyMMdd_HHmm>__<snake>.sql`；最新为 `V20260925_1330__init_user_active_days.sql`。
- places schema 归属后台分支（`V20260918_2110` 注释），App 侧只读写。

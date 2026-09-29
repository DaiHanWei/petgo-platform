# Story 1.6: Diary 打卡条目

Status: ready-for-dev

## Story

As a 打过卡的用户,
I want 在宠物的 Diary 里看到「去过哪里」,
so that 成长时间线里也记着一起出门的日子。

## Acceptance Criteria

**AC1 — 能力参数 `supports`** `[L1]` `[L0]`
1. `GET /api/v1/pet-profiles/me/timeline`、`/me/day`、`/me/calendar` 三处都接受可选多值参数 `supports`（`@RequestParam(value = "supports", required = false) List<String>`，兼容 `supports=a&supports=b` 与 `supports=a,b` 两种写法；未知值忽略、大小写敏感按小写 wire 比对）。本 story 定义取值 `place_checkin`；Story 3.3 会追加 `tailsonality`。
2. 解析为 NEW `profile.service.TimelineCapabilities`（不可变 `Set<Capability>`，`Capability` 枚举带 wire 值）；`TimelineService` 三个公开方法各加带 `TimelineCapabilities` 的重载，**原签名保留并委托 `TimelineCapabilities.none()`**（既有调用点与测试零改动）。
3. **未声明 `place_checkin` 时三处都不下发新类型**：时间线 / 日详情不出 `PLACE_CHECKIN_BANNER`，日历不因打卡产生新格子、`DayCell` 不带新字段——老 App 请求的响应与本 story 前**逐字段一致**（集成测试：同一数据、带与不带 `supports` 各请求一次，不带时与基线快照相等）。
4. 前后端 `TimelineItemType` **同名追加** `PLACE_CHECKIN_BANNER`（后端枚举末尾；App `TimelineItemType.placeCheckinBanner('PLACE_CHECKIN_BANNER')`）；查询时拼装、**不落库**（AD-2 / AD-9 既有约束）。

**AC2 — 时间线打卡条目** `[L1]` `[L0]`
1. 新数据源（`fetchMerged` 源⑥）：该宠物（`place_checkin_pets.pet_profile_id = 当前宠物`）的打卡，经 place 包只读口 `PlaceCheckinTimelineQuery.findForPetBefore(petId, anchor.createdAtUpperBound(), fetch)` 按 `checked_at DESC` 取；**单键上界**写法与里程碑源④一致（打卡没有独立事件日期），取满 `fetch` 时照源④更新 `allKnown` / `floor`（地板用**去重前**的原始列表末条）。
2. 条目：`kind = "PLACE_CHECKIN"`（新常量 `TimelineItemResponse.PLACE_CHECKIN`）、`itemType = PLACE_CHECKIN_BANNER`、`date = checked_at`、`eventDate = checked_at 的 UTC 日期`（显式给出，使 App 的 `displayDate` 与服务端 `effectiveDate()` 同一天；AD-9「有效日期与排序键 = `checked_at` 的 UTC 日期」）、新字段 `checkinPlace { placeToken, name, status }`（`status` ∈ `ACTIVE | UNAVAILABLE`，复用 1.2 `PlaceAvailability`）。**不下发** checkin 自增 id、坐标、`visit_date`。
3. **二选一去重**：该打卡存在一条关联帖（`content_posts.place_checkin_id = 该打卡`）且该帖满足**作者自看时间线的同一过滤口径**（`author_id = 本人`、`pet_id = 当前宠物`、`type = GROWTH_MOMENT`、`deleted_at IS NULL`——与 `ContentPostRepository.findGrowthMomentsBeforeAnchor` 的 WHERE 一致）→ 不出打卡条目、只出帖子。判定经 content 只读口 `ContentService.findCheckinIdsWithTimelinePost(authorId, petId, Collection<Long> checkinIds) → Set<Long>` **一批查一次**，与分页无关（见「关键设计点」对 AD-9 措辞的解释）。
4. 关联帖被删（`deleted_at` 置值）或关联被断开后，下一次请求条目**恢复**（实时计算，天然成立；集成测试钉住：发帖 → 无条目；删帖 → 有条目）。非 GROWTH_MOMENT 的关联帖（Moment / Edukasi）**不抑制**条目。
5. 不改 `TimelineClassifier.classify` 的签名（四参，`TimelineClassifierTest` 大量直接调用）：打卡条目在 `classify` 之后、`merged.sort(TIMELINE_ORDER)` 之前追加。`attachDecorationTags` 只看 `postId`，打卡条目 `postId = null` 不受影响。
6. `TimelineItemResponse` 新增工厂 `placeCheckinBanner(Instant checkedAt, String placeToken, String placeName, String placeStatus)`（内部算 `eventDate = checkedAt 的 UTC 日`）与字段 `checkinPlace`（NEW 嵌套 record `TimelineItemResponse.CheckinPlace(placeToken, name, status)`，record 末尾追加；`withDecorationTags` 同步透传新字段）；`TimelineItemResponseContractTest`：`itemTypeVocabulary` 的 `containsExactly` 末尾加 `"PLACE_CHECKIN_BANNER"`、`ALL_FIELDS` 加 `checkinPlace`、`allFactoriesStayWithinDeclaredFieldSet` 加新工厂样本，并新增「打卡条目不含 postId / imageUrls / 坐标类键」一例。

**AC3 — 日详情与日历** `[L1]` `[L0]`
1. `getDayDetail`（带 `place_checkin`）：追加该宠物 `checked_at` 的 UTC 日期 = `date` 的打卡条目，同样按 AC2.3 去重；大类序号 `dayDetailCategory` 新增 3（排在健康记录之后），类内按 `date` 正序。
2. `getCalendarMonth`（带 `place_checkin`）：该月有打卡（UTC 日）且**未被去重**的日子 → `DayCell` 新字段 `Boolean hasPlaceCheckin = true`；只有打卡的日子新建格子（其余维度 false / null / 0）；未声明能力时该字段为 null（`non_null` 省略）且不新建格子。
3. App `archive_calendar.dart` 的整格标记优先级（L232-257，只显一个）在「④ 只有结构化健康记录」之后、「⑤ 无记录」之前插入「只有打卡 → 线性定位图标」；`calendar_cell_priority_test.dart` 补一例且原有各级断言不变。

**AC4 — App 模型、组件与点击** `[L2]` `[L0]`
1. `timeline_item.dart`：枚举加值；`TimelineItem` 加 `checkinPlace`（`CheckinPlaceRef`，Story 1.5 在 `features/place/domain/checkin_place_ref.dart` 新建；时间线 wire 键为 `placeToken`，与帖子详情的 `token` 不同，`fromJson` 各按各自契约解析）；`_parseKind` 认 `PLACE_CHECKIN`（不认也无妨——`resolvedType` 优先用 `itemType`）。
2. `timeline_item_tile.dart` `build` 的穷举 `switch (item.resolvedType)`（L58-66）新增分支 `_placeCheckinBanner`：复用类③ banner 的通栏样式（L246 `_milestoneBanner` 的圆角 / 内边距 / 左 34px 日期槽不变）+ **专属底色**（新 token，放 `core/theme/colors.dart`，与里程碑 S/M/L 三色、身份证紫都区分开）+ 线性定位图标 + 场所名（单行省略）；key `timelineCheckinBanner`。**不用 📍 emoji**（UI 稿图标规则）。
3. 点击语义由调用方注入（组件自身不持有跳转，L22 注释的既有约定）：`growth_archive_page.dart` `_realTapFor`（L760-800 的穷举 switch）加分支——`ACTIVE` → `PlaceDetailPage.routeFor(placeToken, from: kPlaceDetailFromDiary)`；`UNAVAILABLE` → `showAppToast(context, l10n.placeUnavailableTitle)`（「Tempat tidak ditemukan」，UI 稿 C9）；两者都先 `_reportItemTap(item)`（`item_type` 自动为 `PLACE_CHECKIN_BANNER`）。`place_detail_page.dart` 加 `kPlaceDetailFromDiary = 'diary'` 并入 `_knownFrom`。
4. `day_detail_page.dart` `_tile`（L58-67）现按 `kind` 二分（healthEvent / 其余一律当快乐时刻）——**必须**先判 `resolvedType == placeCheckinBanner` 并渲染同一个 `TimelineItemTile`（或抽出的 banner 子组件）+ 同样的点击语义，否则打卡条目会被当成 `postId == null` 的照片卡。
5. 游客示例时间线（`diary_guest_page.dart` L99）与真实时间线（`growth_archive_page.dart` L887）、访客（`visitor_archive_view.dart` L215）**同一个** `TimelineItemTile`，新分支对三处天然可用；`DiaryDemoData`（UI 稿 A1 权威 9 条）**本 story 不加示例条目**。因此 `test/profile/timeline_five_class_render_test.dart` L130 `expect(demoTypes, containsAll(TimelineItemType.values))` 须改为「除 `placeCheckinBanner` 外全覆盖」并写明原因；另在同文件加一例直接渲染打卡条目（ACTIVE / UNAVAILABLE 各一）。`diary_guest_state_test.dart` L250 `items.length == 9` 不变。
6. `DioTimelineRepository`（`timeline_repository.dart` L51-104）：**仅作者态**（`scope.isVisitor == false`）的 `getTimeline` / `getCalendar` / `getDay` 请求带 `supports: ['place_checkin']`（常量集中在一处 `kTimelineSupports`，Story 3.3 往里加）；访客态请求**不带**。`TimelineRepository` 抽象签名不变（测试 fake 零改动）。

**AC5 — 访客态不下发** `[L1]` `[L0]`
`VisitorProjectionService`（`profile/visitor/`）只取 Diary 一个源（L155-170），天然不含打卡：
1. 集成测试（照 `VisitorProjectionIntegrationTest`）：某宠物有未发帖的打卡，经分享 token 时间线 / 某天详情 / 日历与站内访客时间线四个入口，响应中**无** `PLACE_CHECKIN_BANNER`、无 `checkinPlace` 键、日历无新增格子。
2. 源码守卫测试：`VisitorProjectionService.java` 不出现 `PLACE_CHECKIN`、`PlaceCheckinTimelineQuery`、`supports`（防后来者为了「复用」把作者态能力参数接进访客层）。

**AC6 — 多宠落点** `[L1]`
条目按 `place_checkin_pets` 落到**打卡所选宠物**（本版本 = 当前唯一宠物）：集成测试构造一次打卡关联该宠物 → 该宠物时间线有条目；删档重建新宠物后，新宠物时间线无旧打卡（1.1 删档已清理，且查询按 `pet_profile_id` 过滤双保险）。

## Tasks / Subtasks

- [ ] **T1 后端：能力参数**（AC1）
  - [ ] `TimelineCapabilities` + `ProfileApiController` 三端点加参（L133-161）
  - [ ] `TimelineService` 三方法重载；`TimelineItemType` 追加枚举值
- [ ] **T2 后端：place 只读口**（AC2.1、AC3）
  - [ ] NEW `place/service/PlaceCheckinTimelineQuery`：`findForPetBefore(petId, upperBound, limit)`、`findForPetOnUtcDate(petId, date)`、`findForPetInUtcRange(petId, fromInclusive, toExclusive)`；返回 `PlaceCheckinTimelineView(checkinId, checkedAt, placeToken, placeName, placeStatus)`（NEW record，`place/dto`；`checkinId` 只在后端内部用于去重，不外露）
  - [ ] 原生 SQL：`place_checkin_pets cp ⋈ place_checkins c ⋈ places p ON p.id = c.place_id`（当前场所，合并后是保留方）；UTC 日界用 `checked_at >= :dayStartUtc AND checked_at < :nextDayStartUtc`（不要在 SQL 里做时区敏感的 date 转换，`ContentService` L640-645 注释同一理由）
- [ ] **T3 后端：content 去重读口**（AC2.3）
  - [ ] `ContentPostRepository` JPQL：`select distinct p.placeCheckinId from ContentPost p where p.authorId = :a and p.petId = :pet and p.type = GROWTH_MOMENT and p.deletedAt is null and p.placeCheckinId in :ids`
  - [ ] `ContentService.findCheckinIdsWithTimelinePost`（空集合短路返回）
- [ ] **T4 后端：三处拼装**（AC2、AC3）
  - [ ] `fetchMerged` 源⑥ + floor；`getDayDetail`；`getCalendarMonth` + `DayCell.hasPlaceCheckin`（record 末尾追加，所有 `new DayCell(...)` 调用点补参——`getCalendarMonth` 内 5 处）
  - [ ] `TimelineItemResponse` 新字段 / 工厂 / 常量；`withDecorationTags` 透传
  - [ ] 测试：`TimelineServiceTest`（构造器 L59 注入新依赖；新增：无 supports 不出、有 supports 出、GROWTH_MOMENT 关联帖去重、Moment 关联帖不去重、删帖恢复、跨页不重不漏——打卡与帖子分处两页时仍只出现一次、同日多源排序）；`TimelineItemResponseContractTest`（AC2.6）
- [ ] **T5 后端：访客守卫**（AC5）+ 多宠落点（AC6）集成测试
- [ ] **T6 App：模型 + 仓库**（AC4.1、AC4.6）
  - [ ] 枚举 / 字段 / `fromJson`；`CalendarDayCell.hasPlaceCheckin`（`bool`，缺键 false）
  - [ ] `kTimelineSupports` + 三个作者态请求带参；`test/profile/timeline_pagination_test.dart` 若断言 query 参数，按新参数更新
- [ ] **T7 App：组件与点击**（AC4.2-4.5、AC3.3）
  - [ ] `_placeCheckinBanner` + 颜色 token；`_realTapFor` 分支；`day_detail_page` `_tile` 分支；日历标记
  - [ ] 更新 `timeline_five_class_render_test.dart` L130；新增渲染 / 点击测试（ACTIVE 跳场所详情、UNAVAILABLE toast 不跳）
- [ ] **T8 l10n**（见下表）
- [ ] **T9 联调**（L1 / L2：只打卡 → Diary 出条目；顺手发帖 → 只出帖子；删帖 → 条目回来；下架场所 → 点击提示；老版本 App 验 RC-6）

## Dev Notes

> ⚠️ **前置 story 尚未实现**：Story 1.1-1.5 尚未落代码。开工前先对照其实际代码核对本文件引用的类名 / 接口 / 字段（`place_checkin_pets` / `place_checkins.checked_at`、`PlaceAvailability`、`content_posts.place_checkin_id` 与 `ContentPost.placeCheckinId`、`CheckinPlaceRef`、l10n `placeUnavailableTitle`、`kPlaceDetailFromPassport` / `kPlaceDetailFromPost` 的写法），有出入先改本文件。

### 必读：会被本 story 改到的现有代码

| 文件 | 现状 | 本 story 改什么 | 必须保留 |
|---|---|---|---|
| `petgo-backend/.../profile/service/TimelineService.java` | `getTimeline` L107-144（同日不跨页、批次放大到 `MAX_FETCH=500`、游标取内部序末条后再 `withinDayAscending`）；`fetchMerged` L198-299 五源（Diary 复合锚点 / 问诊 / 健康记录 / 里程碑单键上界 / 身份证）→ `TimelineClassifier.classify` → `sort(TIMELINE_ORDER)`；`getCalendarMonth` L395-457；`getDayDetail` L467-492（**只有三源**：Diary / 问诊 / 健康记录，没有里程碑与身份证）；`dayDetailCategory` L517 | 源⑥、日详情、日历、重载 | AD-1 三条分页规则；游标在重排**前**计算；各源「取满即降可信前缀」的地板逻辑 |
| `.../profile/service/TimelineClassifier.java` | `classify(contents, healthItems, milestones, idCardIssues)` L87，四参 | **不改** | 签名与五步优先级 |
| `.../profile/dto/TimelineItemType.java` | 5 值，枚举名即线格式；类注释「词表由 App 侧定义，必须同名同值」 | 末尾加 `PLACE_CHECKIN_BANNER` | 既有 5 值顺序 |
| `.../profile/dto/TimelineItemResponse.java` | 17 字段 record + 6 个工厂 + `withDecorationTags`；`effectiveDate()` = `eventDate` 或 `date` 的 UTC 日；`non_null` | 加字段 `checkinPlace`、工厂、`PLACE_CHECKIN` 常量 | 既有工厂的参数与输出键集 |
| `.../profile/dto/CalendarMonthResponse.java` | `DayCell(day, firstImageUrl, hasHappyMoment, hasHealthEvent, healthRecordType, healthRecordCount)`；类注释写明日历优先级与时间线**刻意不对齐** | 末尾加 `Boolean hasPlaceCheckin` | 前五维语义 |
| `.../profile/web/ProfileApiController.java` | L133-161 三端点，`currentUserId(jwt)` | 各加 `supports` 参数 | 路径与既有参数 |
| `.../profile/visitor/VisitorProjectionService.java` | L141-185：只调 `contentService.findRecentGrowthMomentsByEventDate`，类型恒 `HAPPY_MOMENT` | **不改**；加守卫测试 | 只取 Diary 一个源 |
| `src/test/.../profile/dto/TimelineItemResponseContractTest.java` | L48-58 `containsExactly` 五值；L67-86 工厂样本 | AC2.6 | 其余断言 |
| `src/test/.../profile/service/TimelineServiceTest.java` | L59 手工 `new TimelineService(...)` | 补新依赖 mock | 既有用例 |
| `petgo_app/lib/features/profile/domain/timeline_item.dart` | 枚举 L17-49；`resolvedType` L139（优先 `itemType`，缺失按 `kind` 兜底）；`displayDate = eventDate ?? date`；`fromJson` L169 | 加枚举值 / 字段 / 解析 | 兜底逻辑与 `openable` fail-closed |
| `.../profile/presentation/widgets/timeline_item_tile.dart` | L58-66 穷举 switch（加值不改这里编译即报错）；L90 34px 日期槽；L246 类③ banner | 新分支 | 组件不持有跳转（L22） |
| `.../profile/presentation/growth_archive_page.dart` | `_realTapFor` L760-800 穷举 switch；L887 渲染；`_reportItemTap` 上报 `item_type` | 新分支 | 既有五类跳转 |
| `.../profile/presentation/day_detail_page.dart` | `_tile` L58-67 按 `kind` 二分；访客态 `_tapFor` 私密 toast | 先判打卡类型 | 访客私密条目不跳转 |
| `.../profile/data/timeline_repository.dart` | L51-104：访客 / 作者两套 URL；`queryParameters` 分别 `{limit, cursor}` / `{year, month}` / `{date}` | 作者态加 `supports` | 访客态请求不变；站内访客无日历 / 日详情（`token!` fail-fast） |
| `.../profile/presentation/widgets/archive_calendar.dart` | L232-257 整格标记五级优先级 | 插「只有打卡」一级 | 其余级别顺序 |
| `petgo_app/test/profile/timeline_five_class_render_test.dart` | L130 断言示例覆盖**全部**枚举值 | AC4.5 | 其余断言 |

### 可直接复用

| 要做的事 | 用这个 | 位置 |
|---|---|---|
| 单键上界取数 + 地板 | 里程碑源④写法 | `TimelineService.java` L254-262 |
| 锚点换算 | `TimelineAnchor.createdAtUpperBound()` | `profile/service/TimelineAnchor.java` L49 |
| 能力闸先例（老 App 不传则不下发） | `includeEcommerce` | `order/web/OrderController.java` L32-45；App `order_repository.dart` L22-32 |
| 场所可用性 | `PlaceAvailability`（1.2） | `place/domain/` |
| 场所引用模型 | `CheckinPlaceRef`（1.5） | `features/place/domain/checkin_place_ref.dart` |
| 不可用提示 | `l10n.placeUnavailableTitle`（1.3） + `showAppToast` | `shared/widgets/app_toast.dart` |
| banner 视觉 | `_milestoneBanner` | `timeline_item_tile.dart` L246 |

### 关键设计点

- **去重按「会不会出现在这只宠物的时间线里」，不按「是否在同一页」**：AD-9 原文是「仅当该打卡的某条关联帖子出现在同一次时间线结果里时不出 banner」。若字面按「同一页响应」判：帖子的事件日期可以早于 / 晚于打卡的 UTC 日（用户改了事件日期、或 WIB 凌晨打卡），两者会落在不同页——帖子在第 1 页、打卡在第 2 页时，第 2 页看不到那条帖子就会再出 banner，同一件事占两条，正是 AD-9 要防的。因此实现为：用与作者态 Diary 源**完全相同的过滤条件**判断关联帖是否存在（它存在 ⇔ 它一定会出现在这条时间线的某一页），与分页解耦。该解释已在「文档矛盾」里报给 SM，如有异议先改本段。
- **为什么 GROWTH_MOMENT 才去重**：Moment / Edukasi 帖不进 Diary 时间线，若也去重，打卡在 Diary 里就彻底消失（AD-9 Prevents 第二条）。
- **`eventDate` 显式给 UTC 日**：里程碑 banner 的 `eventDate` 是 null、靠 `effectiveDate()` 回退；打卡若也给 null，App 的 `displayDate` 会拿 `checked_at` 这个 UTC 时刻去算本地日，WIB 00:00-07:00 打卡会在 App 上显示成后一天、服务端却归在前一天。显式给日期让两端同一天。
- **`visit_date`（WIB）只管唯一约束**：日期展示与排序一律 UTC（与既有五源一致，AD-9 明写）。不要为了「对用户更直观」改成 WIB——会与帖子、里程碑的日期口径打架。
- **老 App 的回落**：`timeline_item.dart` L139-143 的兜底会把未知类型渲染成照片卡；所以能力参数是**必需**的，不是锦上添花（代码核对报告 §1 已点名这个风险）。日历同理：老 App 收到一个没有任何标记的格子会显示成空日但可点。
- **不给示例时间线加打卡条目**：`DiaryDemoData` 以 UI 稿 A1 为权威（9 条），而 A1 没有打卡条目；改示例数据会连带 `diary_guest_state_test` 的条数断言。组件复用（AC 要求）已由同一 `TimelineItemTile` 保证。

### l10n 新 key（en + id，两文件同 key）

| key | id | en |
|---|---|---|
| `timelineCheckinSemantics` | Check-in di {place} | Checked in at {place} |

条目正文就是场所名本身（用户数据，不进 ARB）；日期走左侧日期槽既有格式。上面这个键只用于 `Semantics(label:)` 读屏。不可用提示复用 `placeUnavailableTitle`（1.3）。

### 验证层级

L0：AC1.4 / AC2.6 契约测试、AC4 widget 与渲染测试、AC5.2 源码守卫、AC3.3 日历优先级 · L1：AC1.3 基线一致、AC2.1-2.4、AC3.1-3.2、AC5.1、AC6 · L2：AC4 视觉与点击、RC-6 老版本 App 回归

### Project Structure Notes

- 后端：拼装仍全部在 `profile.service.TimelineService`；打卡数据经 `place` 读口、帖子关联经 `content` 读口取，**profile 不直接 join `place_*` / `content_posts`**（`TimelineService` 类注释的模块边界）。
- App：只动 `features/profile/`（模型、组件、页面、仓库）与 `core/theme/colors.dart`（一个新颜色 token），`CheckinPlaceRef` 复用 1.5 的。
- Story 3.3（Tailsonality banner）会沿用本 story 的 `TimelineCapabilities` 与 `kTimelineSupports`，**不要**把能力判断写死成 `boolean includeCheckins`。

### References

- [Source: _bmad-output/planning-artifacts/v1.3.2/epics-v1.3.2-batch-a.md#Story 1.6]
- [Source: _bmad-output/planning-artifacts/v1.3.2/architecture-v1.3.2-batch-a-delta.md#AD-9, AD-10]
- [Source: _bmad-output/planning-artifacts/v1.3.2/PRD-v1.3.2-batch-a.md#3.4 联动 Diary、多宠归属]
- [Source: _bmad-output/planning-artifacts/v1.3.2/ui-v1.3.2-batch-a.html#C7, C9（图标规则：不用 emoji）]
- [Source: _bmad-output/planning-artifacts/v1.3.2/代码核对报告-batch-a.md#1（Diary 时间线 / 老客户端回落照片卡）]
- [Source: _bmad-output/planning-artifacts/v1.3.2/epics-v1.3.2-batch-a.md#发版检查单 RC-6]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created

### File List

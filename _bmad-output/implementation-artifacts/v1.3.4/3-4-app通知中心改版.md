# Story 3.4: App 通知中心改版

Status: review

> 版本 v1.3.4 · 主题 follow · 分支 `feat/1.3.4-follow-notify`
> 口径优先级：`决策日志-follow.md` > `PRD-v1.3.4-follow.md` > `architecture-v1.3.4-follow-delta.md` > `ui-v1.3.4-follow.html`（均在 `_bmad-output/planning-artifacts/v1.3.4/`）
> 范围：**只改 App**（`petgo_app`）。依赖：Story 3.3（列表返回 `title / body / actorName / actorCount / actorUserId / actorToken / actorIFollow`）、Story 3.2（`USER_FOLLOWED` / `PLACE_COMMENTED`）、Story 1.3 / 2.1（`FollowButton`、`followStateProvider`、`followRepositoryProvider`）。
> UI：`ui-v1.3.4-follow.html` D1 ~ D8。注意 D 泳道开头的说明文字「未读只用浅紫底、去掉圆点」是旧稿残留，以 PRD AC2 与 UI 稿顶部核对结论为准：**保留圆点，移到图标右上角**。

## Story

As a 用户,
I want 通知按时间分段、写清是谁、可以直接回关,
so that 通知值得点开。

## Acceptance Criteria

**AC1 — 五段分组（FR-141.3，UI D1）** `[L0]`
1. 新增纯函数（如 `features/notify/domain/notification_grouping.dart`：`NotificationGroup groupOf(DateTime? createdAt, DateTime now)`，枚举 `today / yesterday / last7Days / last30Days / earlier`），页面只调它，不在 widget 里写日期判断。
2. 判定按**手机本地时间的自然日**：先 `createdAt.toLocal()`，`dayDiff = 本地日期(now) − 本地日期(createdAt)`（按年月日相减，不按 24 小时）：
   - `dayDiff <= 0` → Hari ini / Today（含 `createdAt == null`，与现状一致；时钟偏差导致的「未来时间」也归今天）；
   - `1` → Kemarin / Yesterday；
   - `2 ~ 7` → 7 hari terakhir / Last 7 days；
   - `8 ~ 30` → 30 hari terakhir / Last 30 days；
   - `> 30` → Sebelumnya / Earlier。
   （边界取自 UI 稿 D1 示例：今天 9 Okt 时 2 Okt 在「7 hari terakhir」；30 天边界按同理推，见「待确认 1」。）
3. 分组标题按上述顺序显示，空组不显示；组内保持服务端顺序（不重排）。翻页加载的条目按同一函数并入对应分组。
4. l10n：沿用 `notifyGroupToday`（Hari ini / Today）、`notifyGroupEarlier`（Sebelumnya / Earlier），新增 `notifyGroupYesterday`（Kemarin / Yesterday）、`notifyGroupLast7Days`（7 hari terakhir / Last 7 days）、`notifyGroupLast30Days`（30 hari terakhir / Last 30 days），`app_id.arb` 与 `app_en.arb` 同步并 `flutter gen-l10n`。分组标题样式（`_groupLabel`：11 号、w700、muted、字距 0.6）不变。

**AC2 — 时间显示（FR-141.3）** `[L0]`
1. 今天：沿用「Baru saja / N menit lalu / N jam lalu」（`notifyTimeJustNow / MinutesAgo / HoursAgo`）。
2. 非今天：`d MMM, HH:mm`（如「8 Okt, 15:57」），月份随 App 语言；**格式化前先 `toLocal()`**。
3. 「是否今天」与 AC1 用同一函数，不各判一次。

**AC3 — 后端拼好文案的条目（FR-141.5 / 141.13，UI D1 / D4 / D5）** `[L0]`
1. `NotificationItem`（`features/notify/domain/notification_item.dart`）新增 `actorName`（String?）、`actorCount`（int，缺省 0）、`actorUserId`（int?）、`actorToken`（String?）、`actorIFollow`（bool?），`fromJson` 缺键容错（旧后端返回不崩）。
2. 判据：`actorName` 非空 **且** 类型为 `CONTENT_LIKED` / `CONTENT_COMMENTED` / `USER_FOLLOWED` / `PLACE_COMMENTED` → 「后端文案条目」。只有这类条目读 `title` / `body`；其余条目（含 `actorName` 为空的存量点赞 / 评论）**一律走现有 `_typeLabel` / `_typeBody` 本地文案，不渲染后端 `title` / `body`**（存量行的库值是中文兜底串）。
3. 标题：`title` 全文，常规字重（w400），其中**只有** `actorName` 加粗（w700）；`actorName` 在 `title` 里找不到时整句常规字重显示，不崩。字号 13.5、颜色 `ink` 不变。系统类条目标题仍整体 w700（UI 稿第三步说明）。
4. 正文：`body` 非空时单行显示、超出省略号（评论 / 回复原文；评论已删除时后端给「Komentar telah dihapus」，照常显示）；这类条目**不显示**「展开 / 收起」按钮。`body` 为空（点赞、新粉丝、场所评论）→ 不留正文行和对应间距。
5. 不显示任何头像、帖子图、场所图（PRD AC3）。
6. 后端文案条目不再依赖 `_typeLabel` / `_typeBody`；这两个方法里 `CONTENT_LIKED` / `CONTENT_COMMENTED` 的旧文案**保留**（存量用，UI D4「Suka baru / Seseorang menyukai postingan Anda」）。

**AC4 — 未读样式（FR-141.2，UI D1 / D3）** `[L0]`
1. 未读行底色 `AppColors.cream2`（#F8F2FF）、已读 `AppColors.card`，不变。
2. 未读紫点（8×8、`AppColors.mint`）从条目最右侧（`notification_center_page.dart:989-1001`）**移到 40×40 类型图标的右上角**（叠在图标块角上，不占右侧宽度）；已读不显示。给圆点加 key `notificationUnreadDot_<token>`，测试按 key 断言。
3. 点开某条 → 该行白底、圆点消失（沿用 `_locallyReadTokens`）；「全部已读」入口、显示条件、不二次确认、本地即时置灰全部沿用（`_markAllRead` :218-237）。

**AC5 — 两种新类型图标（FR-141.1，UI D8）** `[L0]`
1. `_iconStyle` 新增：`USER_FOLLOWED` → `Icons.person_add_rounded`，前景 `AppColors.mint`（#845EC9），底 `AppColors.violet100`（#EFE6FF）；`PLACE_COMMENTED` → `Icons.location_on_rounded`，前景 `AppColors.checkinIcon`（#1B7F8C），底 `AppColors.checkinTint`（#E6F5F7）。规格沿用 40×40、圆角 11、图标 20。
2. 其余类型图标与配色不变。

**AC6 — 新粉丝回关按钮（FR-141.10，UI D1 / D2 / D3）** `[L0]`
1. 条件：`USER_FOLLOWED` 后端文案条目、`actorToken` 与 `actorUserId` 非空、按「服务端初值 `actorIFollow` + `followStateProvider` 覆盖」得出**我还没关注对方**（或本按钮就是刚点关注的那个位置，见第 3 条）→ 条目最右侧显示 `FollowButton`（`FollowButtonStyle.listCompact`：高 30、圆角 8、12 号字、最小宽 88；Ikuti Balik 浅紫底紫字）。`actorIFollow == true` → 不显示按钮（UI D1「Rumah Meong」）。
2. 文案固定「Ikuti Balik / Follow Back」（服务端初值 `followsMe = true`，因为这是对方关注我的通知；见「待确认 3」）。
3. 这是「非常驻 Mengikuti」位置（AD-7 / FR-140.3）：点击直接调关注接口（`PUT /api/v1/users/{actorToken}/follow`），**不进主页、不触发整行点击**；成功后本按钮变「Mengikuti」（白底灰框）可立即再点取关；同一用户在其它位置（Feed 卡、详情、列表、另一条新粉丝通知）的按钮立即隐藏。本按钮的 surface key 用 `notification:<deepLinkToken>`，保证同一人的两条新粉丝通知是两个不同位置。
4. 未读行（浅紫底）上按钮的 idle 底色改用白色（UI 稿核对结论「未读行内用白底」）；已读行用默认浅紫。若 `FollowButton` 没有改底色的参数，加一个可选参数（默认不变，不影响 Story 1.3 / 2.x 的调用点）。
5. 失败 / 429：沿用 `FollowButton` 的回滚 + toast（「Gagal mengikuti, coba lagi」/「Terlalu sering. Coba lagi nanti.」）。
6. 「刷新后隐藏」：通知中心重新加载首页（再次进入页面、错误重试）成功后，清除本页这些 surface 的固定态（按 `followStateProvider` 提供的清除方式），新数据 `actorIFollow = true` 的条目不再显示按钮。本 story 不新增下拉刷新（见「待确认 4」）。
7. 点回关按钮**不**标记该通知已读、**不**上报 `app_notification_item_tapped`（只有点整行才算点条目）。

**AC7 — 点击跳转（FR-141.6，UI D5）** `[L0]`
1. `DeepLinkRoutes.pushPayloadToLocation`（`core/router/deep_link_routes.dart`）新增两种类型（系统推送点击与列表点击共用，必须写在这里）：
   - `PLACE_COMMENTED`：`targetRef`（场所 token）非空 → `PlaceDetailPage.routeFor(targetRef)`（`/places/<token>`）；空 → 通知中心兜底；
   - `USER_FOLLOWED`：`targetRef` 能 `int.tryParse` → `'${PublicProfilePage.routeBase}/<id>'`（`/users/<id>`）；否则通知中心兜底（绝不拼出 `/users/abc` 让 `int.parse` 崩）。
2. 列表点击 `USER_FOLLOWED`：用 `item.actorUserId`（存在时）作为跳转 id 传给 `NotificationDeepLink.open`（AD-12），缺失时退回 `item.targetRef`。推送点击只有 `targetRef`，依赖 Story 3.2 把 `targetRef` 设为关注者的数字 id（见「待确认 2」）。
3. 其余类型落点不变：点赞 → `/content/<id>`；评论 / 回复 → `/content/<id>?focus=comments`（沿用 `commentAnchor`）；成就（`MILESTONE_NODE` / `MILESTONE_SM_NODE`，深链类型都是 `MILESTONE_NODE`）→ 里程碑页；举报已处理（`targetRef = null`）→ 不跳转（沿用：落点等于通知中心时直接 return）。
4. 帖子已删：点赞 / 评论类条目点击时，跳转前先用 `ref.read(detailRepositoryProvider).getDetail(postId)` 探一次：返回 `ContentLoadErrorKind.gone`（404）→ `showAppToast(context, l10n.detailGoneTitle)`（「Konten ini sudah tidak ada」，复用现有键）并**不跳转**；其它错误（断网 / 403）→ 照常跳转，由详情页自己的错误态处理。探测期间不重复响应连点（与现有 `_markingAllRead` 同类的忙碌标记即可）。见「待确认 5」。
5. 标记已读、角标重算照旧走 `NotificationDeepLink.open`；探测 gone 时也照常标记已读。

**AC8 — 埋点（FR-141.16 / AD-13）** `[L0]`
1. `app_notification_item_tapped` 在现有属性（`notif_type`、里程碑 `level`）之外，对后端文案条目加 `actor_count`（= `item.actorCount`）；其它条目不带该属性。两种新类型的 `notif_type` 按 `_notifTypeForAnalytics` 现有默认规则取小写（`user_followed` / `place_commented`），不另定新值。
2. 点「Ikuti Balik」→ 上报 `notification_follow_back_tapped`（无属性）；关注成功由 `FollowButton` 按既有规则上报 `user_followed {target_user_id, source: 'notification', is_follow_back: true}`，取关上报 `user_unfollowed {…, source: 'notification'}`——`source` 通过 `FollowButton` 的来源参数传入，本页不重复上报。
3. `test/analytics/v112_events_test.dart` 的 `allowedPrefixes` **现在没有 `notification_`**：按该文件惯例加入 `'notification_'` 并写注释（模块是「通知中心」，与已有的 `notify_`（推送权限撤销检测点）刻意分开）。`user_` 已在表中。

**AC9 — 其余系统通知与回归** `[L0]`
1. 其余类型（问诊、订单、客服、生命周期、审核、里程碑、提及……）文案、图标、展开 / 收起按钮、点击行为不变，只跟随新分组、新时间格式、新圆点位置（FR-141.15）。
2. 推送开启引导条、空态（D6）、加载失败态（D7）、翻页加载更多与失败重试、无上一页时的返回兜底按钮全部不变。
3. 既有测试按新分组更新后全部通过：`test/notify/notification_center_test.dart`、`mention_notification_test.dart`、`milestone_sm_notification_test.dart`、`deep_link_routes_test.dart`、`push_payload_test.dart`、`notification_badge_refresh_test.dart`、`push_notification_center_banner_test.dart`。
4. `flutter analyze` 零警告；`flutter test` 全绿（含 `test/l10n/` 文案规则测试）。

## Tasks / Subtasks

- [x] Task 1：数据模型（AC3.1）
  - [x] 1.1 `NotificationItem` 5 个新字段 + `fromJson` 容错；`test/notify/` 补解析单测（缺键 / 有值）
- [x] Task 2：分组与时间（AC1 / AC2）
  - [x] 2.1 `notification_grouping.dart` 纯函数 + 单测（跨午夜、UTC 输入、30 / 31 天边界）
  - [x] 2.2 `_notificationList` 改五段；`_relativeTime` 先 `toLocal()`
  - [x] 2.3 三个新 l10n key（id / en）+ `flutter gen-l10n`
- [x] Task 3：条目渲染（AC3 / AC4 / AC5）
  - [x] 3.1 后端文案条目分支：标题富文本加粗昵称、单行正文、无展开按钮
  - [x] 3.2 圆点移到图标右上角
  - [x] 3.3 两种新类型图标
- [x] Task 4：回关按钮（AC6）
  - [x] 4.1 条目右侧接 `FollowButton(listCompact)`，surface key、白底参数、来源 `notification`
  - [x] 4.2 首页重载成功后清本页固定态
- [x] Task 5：跳转（AC7）
  - [x] 5.1 `pushPayloadToLocation` 两种新类型 + `deep_link_routes_test` 用例
  - [x] 5.2 列表点 `USER_FOLLOWED` 用 `actorUserId`
  - [x] 5.3 点赞 / 评论类帖子已删探测 + toast
- [x] Task 6：埋点（AC8）
  - [x] 6.1 `actor_count`、`notification_follow_back_tapped`、`FollowButton` 来源
  - [x] 6.2 `v112_events_test` 登记 `notification_` 前缀
- [x] Task 7：回归与测试（AC9）

## Dev Notes

### 现状（改动前必读）

`petgo_app/lib/features/notify/presentation/notification_center_page.dart`（1009 行）：

- 列表：`_notificationList`（:473-537）只分两组——`isToday`（:479-483）用 `d.year/month/day` 与 `DateTime.now()` 比较，其余全归 `notifyGroupEarlier`。**隐患**：`createdAt` 由 `DateTime.tryParse('…Z')` 解析（`notification_item.dart:33`），是 **UTC** 的 `DateTime`，代码里从未 `toLocal()`，所以「今天」判的是 UTC 日期、非今天的时间也按 UTC 显示（在 WIB 早上 0~7 点的通知会被分到 Earlier、时间差 7 小时）。本 story 按 PRD「按手机本地时间」一并修正。
- 时间：`_NotificationTileState._relativeTime`（:846-859）今天内相对时间、否则 `DateFormat('d MMM, HH:mm', locale)`——格式已是「8 Okt, 15:57」，只缺 `toLocal()`。注意 `shared/utils/date_format.dart:45` 的 `formatDayMonthTime` 是 `d MMM HH:mm`（无逗号），**不要**换成它。
- 条目：`_NotificationTile`（:582）→ `build`（:863）：`Material(color: unread ? cream2 : card)`（:871）；40×40 图标块（:885-902，里程碑显示徽章）；标题 `_typeLabel`（:751-785，13.5 / w700，:924-931）；正文 `_typeBody`（:788-837，最多 2 行 + 溢出时显示展开 / 收起，:905-986，测量与渲染共用 `_kBodyStyle`）；未读紫点在最右（:989-1001）。代码注释明写「**不渲染后端 title/body**」（:923）——本 story 只对后端文案条目放开。
- 配色 `_iconStyle`（:640-749），未知类型兜底 `Icons.notifications_rounded`；`_typeLabel` 未知类型兜底 `notifyTypeSystem`。
- 点击 `_onTap`（:262-296）：先报 `app_notification_item_tapped {notif_type, level?}`（:265-271，`_notifTypeForAnalytics` :243-250），本地置已读，再 `NotificationDeepLink.open(type: item.deepLinkType, token, targetRef, commentAnchor: deepLinkType == 'CONTENT_COMMENTED')`；落点等于通知中心时直接 return（举报已处理就是这样「无动作」的）；Tab 根用 `go`、其余 `push`。
- 首页加载 `_loadFirstPage`（:151-167）、翻页 `_loadMore`（:186-212）、全部已读 `_markAllRead`（:218-237）。页面**没有下拉刷新**。
- 跳转口径：`core/router/deep_link_routes.dart` `pushPayloadToLocation`（:62-157），id 寻址类缺 `targetRef` 落通知中心；`features/notify/domain/notification_deep_link.dart`（`open` / `openFromPush` 共用 `_open`，先标记已读再算 location）；推送载荷 `core/push/push_service.dart:19-38`（只有 `type / token / targetRef`），点击走 `openFromPush`（:231）。
- 路由：场所详情 `PlaceDetailPage.routeFor(token)`（`features/place/presentation/place_detail_page.dart:117`）；他人主页 `PublicProfilePage.routeBase = '/users'`（`features/user_profile/presentation/public_profile_page.dart:132`，`app_router.dart:1078-1084` 里 `int.parse`）。
- 帖子已删：详情仓库 `features/content/data/detail_repository.dart` 404 → `ContentLoadErrorKind.gone`（:13 / :161）；详情页 gone 态标题 `l10n.detailGoneTitle`（id「Konten ini sudah tidak ada」，`app_id.arb:257`）。toast 统一 `shared/widgets/app_toast.dart` 的 `showAppToast`。
- 颜色 `core/theme/colors.dart`：`mint` #845EC9（:19）、`violet100` #EFE6FF（:25）、`cream2` #F8F2FF（:31）、`checkinTint` #E6F5F7（:59）、`checkinIcon` #1B7F8C（:61）。
- l10n：`notifyGroupToday` / `notifyGroupEarlier` 已存在（`app_id.arb:837-838`、`app_en.arb:1025-1026`）。
- 埋点前缀登记：`test/analytics/v112_events_test.dart` `allowedPrefixes`（:216 起）有 `app_`、`notify_`、`user_`，**没有** `notification_`。

### 与 Epic 1 / 2 的对接（按实际落地的 API 调用）

- `FollowButton`（`features/social/presentation/widgets/follow_button.dart`）：用 `FollowButtonStyle.listCompact`；需要传入的有用户 id、用户 token、服务端初值（`iFollow = actorIFollow`、`followsMe = true`）、是否常驻（否）、surface key、埋点来源（`notification`）、点击回调（用于本页上报 `notification_follow_back_tapped`）。参数名以 Story 1.3 / 2.1 实际为准；缺「idle 底色」与「点击回调」参数时在本 story 加可选参数，默认行为不变。
- `followStateProvider`：显示与否必须读「初值 + 覆盖」，不要在本页自己存一份关注状态；固定态清除用它提供的方法（AD-7「刷新数据后覆盖清除」）。
- 回关按钮放在 `InkWell` 行内：按钮自身的手势优先，确保不会同时触发 `_onTap`（widget test 断言点按钮后没有跳转、没有标记已读）。

### 架构约束

- AD-12：五段按手机本地时间；非今天「8 Okt, 15:57」；圆点在图标右上角；两种新图标；有后端文案的类型读 `title / body` 并只加粗 `actorName`；新粉丝回关按钮读 AD-7 状态、点击不进主页；场所评论跳场所详情；`USER_FOLLOWED` 进对方主页（`actorUserId`）；`deep_link_routes` 补两种类型。
- AD-7：全局关注状态与「本按钮保留、其余隐藏」。
- 用户可见字符串一律走 ARB（`petgo_app/CLAUDE.md` i18n 纪律）；后端下发的 `title / body` 本身已按 `Accept-Language` 拼好（Story 3.3），App 不再拼接。
- 推送文案由后端拼（Story 3.1 / 3.2），App 推送展示不改。

### 本 story 不做

- 不加下拉刷新、分类 Tab、按类型推送开关（FR-141.15 / PRD 边界）。
- 不改后端；不改 `FollowButton` 已有外观规则（只加可选参数）；不改其它页面的关注入口。
- 不给存量通知补触发人；不改系统类通知的文案与图标。

### 测试

- 单测：`test/notify/notification_grouping_test.dart`（新建）——固定 `now`，覆盖 dayDiff 0 / 1 / 2 / 7 / 8 / 30 / 31、`createdAt == null`、未来时间、**UTC 输入在本地跨日**（如 `2026-10-11T18:00:00Z` 在 UTC+7 的 `now = 2026-10-12 08:00` 下属于「今天」）。时区相关断言用 `toLocal()` 后再比较，或在测试里构造本地时间，避免 CI 时区导致不稳定。
- `test/notify/notification_center_test.dart` 更新 / 新增：五段标题出现顺序；后端文案条目标题加粗昵称（找 `RichText` 的加粗 span）、正文单行无展开按钮；存量点赞（`actorName == null`，`title` 是中文）仍显示「New like」且界面无中文；圆点 key 在图标区域内；新类型图标；新粉丝按钮显示 / `actorIFollow = true` 不显示 / 点击不跳转不标记已读并上报 `notification_follow_back_tapped`；点 `USER_FOLLOWED` 行跳 `/users/<actorUserId>`；帖子 gone → toast 不跳转。关注接口与 `followStateProvider` 用 override 打桩。
- `test/notify/deep_link_routes_test.dart`：`PLACE_COMMENTED` → `/places/<token>`、空 → 兜底；`USER_FOLLOWED` → `/users/<id>`、非数字 / 空 → 兜底。
- `test/analytics/v112_events_test.dart`：登记 `notification_` 后全绿。
- 跑 `flutter analyze`、`flutter test`；L2（真机看浅紫底上的白底按钮、圆点位置、锁屏推送与列表同文案、点推送跳转）留本地。

### References

- [Source: _bmad-output/planning-artifacts/v1.3.4/epics-v1.3.4-follow.md#Story 3.4]
- [Source: _bmad-output/planning-artifacts/v1.3.4/architecture-v1.3.4-follow-delta.md#AD-7 / AD-12 / AD-13]
- [Source: _bmad-output/planning-artifacts/v1.3.4/PRD-v1.3.4-follow.md#3.2 通知中心优化（已确认项 / 验收标准 AC1 ~ AC8）/ #4 数据埋点]
- [Source: _bmad-output/planning-artifacts/v1.3.4/ui-v1.3.4-follow.html#D1 ~ D8 / 顶部核对结论 / 新增印尼语文案]
- [Source: _bmad-output/planning-artifacts/v1.3.4/决策日志-follow.md#D-6]

### 待确认项裁定（2026-10-12，操作者 Hex；详见 `planning-artifacts/v1.3.4/决策日志-follow.md` D-8~D-11 / T-x）

> **以下裁定优先于本文件正文与 Completion Notes 中的「待确认」与暂定做法**；标 ⚠️ 的条目与正文暂定做法不同，实现时以此为准。

- T-40 分组边界：相差 2–7 天 / 8–30 天 / 超过 30 天（确认）。
- T-34 推送点击 `USER_FOLLOWED` 用 `targetRef`（关注者数字 userId）走现有主页路由（确认）。
- ⚠️ T-41 回关按钮按全局三态规则：对方仍关注我 →「Ikuti Balik」，否则「Ikuti」，已关注则不显示；用 Story 3.3 新增的 `actorFollowsMe`。
- T-42 不新增下拉刷新，重新进入 / 失败重试即刷新（确认）。
- T-43 帖子已删按 UI 稿 D5 用 toast，跳转前探测一次帖子详情（确认）。
- ⚠️ T-36 触发人全部被过滤的条目后端已不返回，App 无需兜底展示（「系统通知」兜底不会因此出现）。
- T-21 从通知进主页 `from = avatar`，场所详情按现有缺省，不新增取值（确认）。

## Dev Agent Record

### Agent Model Used

Claude（云端 headless session，bmad-dev-story 流程手工执行）

### Debug Log References

- App `flutter analyze` 零告警；`flutter test` 2843 个全绿（含新增 `notification_grouping_test`、`notification_center_v134_test`、`deep_link_routes_test` 两例、`test/l10n/` 文案规则）。后端未改，纯单测 3079 全绿。

### Completion Notes List

**开工核对（对照 1.3 / 2.1 / 3.3 实际代码）**

- `FollowButton` 实际参数：`userId / userToken / initial / style / source / surfaceKey / snapshotEpoch / persistent`；缺「idle 底色」与「点击回调」→ 按 Dev Notes 新增可选参数 `idleBackground`（仅 listCompact 的 Ikuti / Ikuti Balik 态）与 `onTapped(ValueChanged<bool> wantFollow)`（已登录点下那一刻、请求前），默认行为不变，1.3 / 2.x 调用点未动。
- 3.3 实际字段：除 story 写的 5 个外还有 ⚠️ T-41 `actorFollowsMe`，模型一并解析（共 6 个）。
- 「刷新后覆盖清除」用 Story 2.1 的版本号机制：每页发请求前记 `followStateProvider.currentEpoch`（按 deepLinkToken 存），按钮传 `snapshotEpoch`；重拉首页后旧的「本按钮保留 Mengikuti」对新快照自动失效（AC6.6）。

**实现摘要**

- `domain/notification_grouping.dart`：`enum NotificationGroup {today, yesterday, last7Days, last30Days, earlier}` + `groupOf(createdAt, now)`（先 `toLocal()`，按本地自然日相差天数：≤0 / 1 / 2–7 / 8–30 / >30，T-40）；页面只调它。时间显示「是否今天」同用 `groupOf`，非今天 `d MMM, HH:mm`（先 `toLocal()`）。l10n 新增 `notifyGroupYesterday / Last7Days / Last30Days`（id / en）。
- `NotificationItem`：`actorName / actorCount / actorUserId / actorToken / actorIFollow / actorFollowsMe` + `hasServerCopy`（四种互动类型且 `actorName` 非空），`fromJson` 缺键容错。
- 条目：后端文案条目标题 `Text.rich` 常规字重、只加粗 `actorName`（找不到则整句常规）；正文非空时单行省略、无展开按钮，空则不留正文行；其余条目（含存量点赞 / 评论）仍走 `_typeLabel / _typeBody`。未读紫点移到 40×40 图标块右上角（key `notificationUnreadDot_<token>`）。新图标 `USER_FOLLOWED`（person_add，mint / violet100）、`PLACE_COMMENTED`（location_on，checkinIcon / checkinTint）。
- 回关：`USER_FOLLOWED` 后端文案条目右侧 `FollowButton(listCompact, 非常驻, source: 'notification', surfaceKey: 'notification:<deepLinkToken>')`，初值 `FollowState(iFollow: actorIFollow, followsMe: actorFollowsMe ?? true)`，按全局三态（⚠️ T-41：对方仍关注我「Ikuti Balik」，否则「Ikuti」，已关注不显示）；未读行白底、已读行浅紫。按钮吃掉点击：不跳转、不标已读、不报 `app_notification_item_tapped`；点「关注」时报 `notification_follow_back_tapped`，关注 / 取关结果由 `FollowButton` 报 `user_followed / user_unfollowed {source: notification}`。
- 跳转：`DeepLinkRoutes.pushPayloadToLocation` 加 `PLACE_COMMENTED → /places/<token>`、`USER_FOLLOWED → /users/<id>`（非数字 / 空 → 通知中心）；列表点新粉丝用 `actorUserId`。点赞 / 评论类跳转前探一次帖子详情：`gone` → toast `detailGoneTitle`、不跳转（已读照常标）；其它错误照常跳；探测期间忽略连点。
- 埋点：`app_notification_item_tapped` 对后端文案条目加 `actor_count`；`v112_events_test` 登记前缀 `notification_`（注释说明与 `notify_` 分开）。

**与 story 文字的出入**

- AC6.2「文案固定 Ikuti Balik」→ 按 ⚠️ T-41 三态显示。
- AC9 / 待确认 6：⚠️ T-36 后端已不返回「触发人全被过滤」的条目，App 不做兜底。
- core 的 `deep_link_routes.dart` 不 import feature 页面，新落点写字面量（与 `PlaceDetailPage.routeFor`、`PublicProfilePage.routeBase` 同形，测试里用这两个常量断言）。
- 既有测试按设计更新：`milestone_sm_notification_test` 的点赞点击用例因新增「帖子探测」需要打桩详情仓库（统一返回网络错误 = 照常跳转），断言未改。

**待本地验收**

- **L2 待本地验收**：UI 稿 D1 ~ D8 真机视觉（五段分组标题、浅紫未读底 + 图标右上角圆点、标题只加粗昵称、单行正文、两种新图标配色、未读行内白底回关按钮）；锁屏推送与列表同文案；点推送跳转（新粉丝进主页、场所评论进场所详情）；帖子已删 toast。
- 本 story 只改 App，无 L1 项。L1/L2 待本地验收。

**待确认**

- 探测帖子是否已删会让点赞 / 评论类通知点击多一次详情请求（T-43 已确认接受）。
- 回关按钮的显示依赖本页快照的 `actorIFollow`；在别的页面关注了该用户后回到已打开的通知中心，按全局覆盖立即隐藏（AD-7 非常驻规则）。
- 原 Completion Notes 的待确认 1~7 已被裁定 T-40 / T-34 / T-41 / T-42 / T-43 / T-36 / T-21 覆盖，按裁定实现。


- 待确认 1：「7 hari terakhir」「30 hari terakhir」的天数边界。PRD / UI 只给了名称；本 story 按 UI 稿 D1 示例（今天 9 Okt，2 Okt 在 7 天组）取「相差 2 ~ 7 天 / 8 ~ 30 天 / 超过 30 天」。
- 待确认 2（与 Story 3.2 对接）：系统推送点击 `USER_FOLLOWED` 只拿得到 `targetRef`，要进对方主页需要 3.2 把 `targetRef` 设为关注者的数字 id（与点赞通知 `targetRef = 帖子 id` 同口径，只下发给本人）。3.2 若用了别的值，本 story 的推送跳转需相应调整。
- 待确认 3：回关按钮固定显示「Ikuti Balik」。若对方发完通知后又取关了我，按钮文案严格说应是「Ikuti」；后端 3.3 只返回 `actorIFollow`、没有对方是否仍关注我。是否要后端再加 `actorFollowsMe`（批量查询本来就同时拿得到）。
- 待确认 4：「刷新后按钮隐藏」——通知中心目前没有下拉刷新，本 story 把「重新进入页面 / 失败重试」当作刷新，不新增下拉刷新。
- 待确认 5：帖子已删用 toast（UI D5）需要在跳转前多请求一次帖子详情；现状是进入详情页后整页显示同一句「Konten ini sudah tidak ada」。若接受现状，可去掉探测请求。
- 待确认 6：后端对「触发人全部被过滤」的条目原样返回（见 Story 3.3 待确认 2），此时 `USER_FOLLOWED` / `PLACE_COMMENTED` 没有旧文案，会显示「系统通知」兜底标题与正文。
- 待确认 7：进他人主页的 `user_profile_viewed.from` 目前只有 `avatar / mention / place`；从通知进主页按默认值（`avatar`）上报，是否新增 `notification` 取值。场所详情的 `from` 同理按缺省（记 `other`）。
- L2 待本地验收（真机视觉、推送跳转）。

### File List

- petgo_app/lib/features/notify/domain/notification_grouping.dart（新）
- petgo_app/lib/features/notify/domain/notification_item.dart（改）
- petgo_app/lib/features/notify/presentation/notification_center_page.dart（改）
- petgo_app/lib/features/social/presentation/widgets/follow_button.dart（改：可选 `idleBackground`、`onTapped`）
- petgo_app/lib/core/router/deep_link_routes.dart（改）
- petgo_app/lib/l10n/app_en.arb、app_id.arb（改）
- 测试：test/notify/notification_grouping_test.dart（新）、notification_center_v134_test.dart（新）、deep_link_routes_test.dart（改）、milestone_sm_notification_test.dart（改）；test/analytics/v112_events_test.dart（改：登记 `notification_`）

# Story 1.3: App 通知设置页与设置页入口

Status: ready-for-dev

> 版本 v1.4.0 · 主题 push-collect · 分支 `feat/1.4.0-push-collect`
> 口径优先级：`decision-log.md` ④ 节 > `推送开关与收藏/1-4-0推送开关与收藏prd.md` > `architecture-v1.4.0-push-collect-delta.md` > UI 稿 `推送开关与收藏/ui-1-4-0-push-collect-integrated-v1.html`（均在 `_bmad-output/planning-artifacts/v1.4.0/`）。跨分支契约以 `CROSS-STORY-DECISIONS.md`「2026-10-18 V1.4.0 四条线共用地基」节为准。
> 前置：**Story 1.1**（`GET / PATCH /api/v1/me/push-settings` 已上线到本分支：GET 返回 `{categories:[{code,enabled,lockable}]}` 固定 9 项；PATCH body `{categories:{CODE:bool}}` 部分更新、返回完整设置；`ORDER_ACCOUNT:false` → 422 `push-category-locked`；未知 code → 400）。**Story 1.2**（闸门）与本 story 无代码依赖。
> 范围层：**仅 App**（`petgo_app/`）。UI 稿 A1 ~ A2、B1 ~ B6。**无迁移、无后端改动**。
> 🔴 与 ①②③ 并行：本 story 只碰设置页与新通知设置页；不引用 ③ 的 `PET_GIFT_RECEIVED` 图标（本分支 `_iconStyle` 里还没有，见 Dev Notes「拍板点」③）。

## Story

As a 用户,
I want 在设置里进入通知设置、按分类切换推送，并看到系统通知权限状态,
so that 我能自己决定收哪些推送。

## Acceptance Criteria

**AC1 — 设置页「Notifikasi」改为入口行（A1 / A2）** `[L0]` `[L2]`（AD-14、PRD AC5a、§6.5 ①）
1. `settings_page.dart` AKUN 分组第二行（现 :169-170 `_toggleRow(l10n.notificationCenterTitle, _notif, _onNotifToggle, key: ValueKey('meNotifToggle'))`）改为 `_navRow(l10n.notificationCenterTitle, …, onTap: …, key: const ValueKey('meNotifEntry'))`，位置不变；同组「Edit Profil / Bahasa / Nomor HP」与其余三组共 **10 行完全不动**（文案、顺序、key、样式）。
2. `_navRow`（:276-302）新增两个可选参数 `Color? valueColor`、`bool valueDot = false`：`valueColor` 为 null 时右值保持现样式 `fontSize 14 / AppColors.muted`（:294）；`valueDot = true` 时在右值文字**前**画 6×6 圆点（颜色同 `valueColor`）、间距 5。其他调用点不传 → 外观零变化。
3. 右值按系统权限（`_notif`）：
   | 系统权限 | 右值 | 样式 |
   |---|---|---|
   | 已开启 | `pushSettingsValueOn`「Aktif」 | 14 号 muted（同「Bahasa」右值） |
   | 已关闭 | `pushSettingsSystemOff`「Notifikasi sistem nonaktif」 | 14 号 **`AppColors.popRed`（#F0425A）** + 前置 6px 红点（`ValueKey('meNotifOffDot')`） |
   | 尚未读到（首帧） | 不显示右值，只留箭头 | — |
   `_notif` 由 `bool` 改为 `bool?`（初值 null），避免进页首帧先闪「nonaktif」再变「Aktif」。
4. 点击 → `context.push(PushSettingsPage.routePath)`，返回后 `.then((_) => _refreshNotifStatus())` 再刷一次右值（用户可能在通知设置页里去系统设置开了权限）。
5. Given 系统权限开启 / 关闭，When 打开设置页，Then 「Notifikasi」右值分别为「Aktif」/ 红色「Notifikasi sistem nonaktif」+ 红点，点击进通知设置页（PRD AC5a）。

**AC2 — 移除旧的「开关 = 申请 / 引导」逻辑，保留状态刷新** `[L0]`（AD-14、decision-log ④ 第二步告知项「设置页通知改导航行后不再从这里申请系统权限」）
1. **删除** `_onNotifToggle`（:93-114）与 `_showOpenSettingsDialog`（:116-134，含 `ValueKey('notifOpenSettingsDialog')` 对话框）。首次申请权限仍由现有软引导（`pushSoftGuide*` / `PushPermissionGate`）负责；「已关闭 → 去系统设置」改由通知设置页顶部状态卡负责（AC6）。
2. **保留** `_refreshNotifStatus`（:79-91）原样：`isPushPermissionGranted()` → `PushPermissionChangeReporter.record(granted, fromScreen: 'settings_page')` → `setState` → `granted` 时 `pushServiceProvider.syncRegistration(isVet: …)`；`WidgetsBindingObserver` 注册（:58）、`initState` 首查（:59）、`resumed` 刷新（:75-77）都保留。
3. 删除后 `settings_page.dart` 不再引用 `requestPushPermission` / `openPushSettings`；`import '../../notify/data/push_permission_providers.dart'`（:19）因 `isPushPermissionGranted` 仍需保留。`flutter analyze` 零 unused 警告。
4. 文案键 `pushEnableGuideTitle` / `pushEnableGuideBody` / `pushSoftGuideLater` / `mediaOpenSettings` **不删**（`push_enable_guide.dart`、`notification_center_page.dart`、`milestone_list_page.dart`、`place_checkin_button.dart` 等仍在用）。
5. 为可测，`SettingsPage` 加可选构造参数 `isNotificationGrantedForTest`（`Future<bool> Function()?`），`_refreshNotifStatus` 用 `(widget.isNotificationGrantedForTest ?? isPushPermissionGranted)()`——写法照 `notification_center_page.dart`:43-47 / :111-112。`const SettingsPage()` 现有调用点（`app_router.dart`:1011、三个测试文件）不受影响。

**AC3 — 数据层与 `pushSettingsProvider`** `[L0]`（AD-4、AD-14、Story 1.1 契约）
1. `ApiPaths`（`core/network/api_paths.dart`，`me` 在 :126）新增 `static const String mePushSettings = '$base/me/push-settings';`，放在 `meTailsonalityOwnerType`（:194）附近并带一行注释（「V1.4.0 ④ Story 1.3：推送分类开关，GET / PATCH，仅 role=USER」）。
2. 领域模型 `features/notify/domain/push_settings.dart`：
   - `PushCategoryCode` 常量集（9 个字符串，顺序 = AD-1：`LIKE, COMMENT, MENTION, FOLLOWER, GIFT, PET_REMINDER, ACHIEVEMENT, PROMO, ORDER_ACCOUNT`），`ORDER_ACCOUNT` 标为 App 侧锁定。
   - `PushSettings`（不可变）：`Map<String, bool> enabled` + `Set<String> locked`；`fromJson`：遍历 `categories` 数组，**未知 code 忽略**；9 个已知 code 缺失 → 视为 `enabled = true`（服务端「无行即开启」口径）；`lockable == false` 的 code 进 `locked`，且 `ORDER_ACCOUNT` 无论服务端怎么给都进 `locked`（双保险，PRD AC3）；`copyWith(code, enabled)`。
3. `features/notify/data/push_settings_repository.dart`：`fetch()` → `dio.get(ApiPaths.mePushSettings)`；`update(String code, bool enabled)` → `dio.patch(ApiPaths.mePushSettings, data: {'categories': {code: enabled}})`，解析返回的完整设置。不自己处理 401（拦截器负责）。Provider 写法照 `tailsonality_owner_type_repository.dart`:32-48。
4. `pushSettingsProvider = AsyncNotifierProvider.autoDispose<PushSettingsNotifier, PushSettings>`：`build()` = `fetch()`（**每次进页都重拉**，满足 PRD AC4「手机 A 改、手机 B 打开看到」）；`retry()` = `ref.invalidateSelf()`。
5. 🔴 在 `lib/app.dart` 的 `resetUserScopedCaches`（:355-390）末尾、`consultRefreshProvider` 那行（:389）之前加 `ref.invalidate(pushSettingsProvider); // V1.4.0 ④：推送分类设置（账号级，换账号不得沿用上一账号的开关）`；并在守卫 `test/app/user_scoped_cache_registry_test.dart` 的 `_mustReset`（:13-33）末尾加 `// V1.4.0 push-collect` + `'pushSettingsProvider',`。

**AC4 — 路由 `/me/settings/notifications`** `[L0]`
1. `PushSettingsPage.routePath = '/me/settings/notifications'`；在 `app_router.dart` 的 `/me/settings`（:1011）下一行注册顶层 `GoRoute(path: PushSettingsPage.routePath, builder: (c, s) => const PushSettingsPage())`，注释写明「V1.4.0 ④ Story 1.3，受控（/me 前缀）」。
2. 落在 `_controlledLocations` 的 `/me` 前缀下（:158-165，前缀匹配自动受控），游客深链 → `/home`，**不另登记**；顶层路由 push 时隐藏 Tab Bar（与 `/me/settings` 一致）。

**AC5 — 通知设置页布局（B1 / B2）** `[L0]` `[L2]`（AD-14、PRD §3.1「通知设置页」、§6.5 ②）
1. 新页 `features/notify/presentation/push_settings_page.dart`：`Scaffold(backgroundColor: AppColors.base)`，AppBar 标题 `notificationCenterTitle`「Notifikasi」，返回按钮照设置页 `_backBtn`（`settings_page.dart`:244-249：`chevron_left_rounded` 28，`canPop ? pop : go('/me/settings')`）；body `SafeArea(top:false) > ListView(padding: EdgeInsets.fromLTRB(20, 8, 20, 28))`（同设置页 :158）。
2. 视觉组件**照抄设置页常量**（不改 `settings_page.dart` 外观，也不把私有方法搬成公共组件——两处各自持有，避免顺手改动设置页）：分组标题 11 / w700 / muted / letterSpacing 0.6、`EdgeInsets.only(left: 4, bottom: 10)`（:251-259）；白卡圆角 16 + `BoxShadow(0x0D2B2A27, (0,3), blur 12)`（:261-271）；组间距 22。
3. 自上而下：状态卡（AC6）→ 4 组 9 行 → 底部说明：
   | 组标题 | 行（标题 / 说明） | 图标（36 圆底、19 号实心） | 图标色 / 圆底色 |
   |---|---|---|---|
   | `pushSettingsGroupInteraction` INTERAKSI | `LIKE` Suka / Seseorang menyukai postinganmu | `Icons.favorite_rounded` | `coral` / `coralTint` |
   | | `COMMENT` Komentar & balasan / Komentar di postingan atau tempat, dan balasan untukmu | `Icons.mode_comment_rounded` | `mint` / `cream2` |
   | | `MENTION` Sebutan (@) / Seseorang menyebutmu di postingan atau komentar | `Icons.alternate_email_rounded` | `mint` / `cream2` |
   | | `FOLLOWER` Pengikut baru / Seseorang mulai mengikutimu | `Icons.person_add_rounded` | `mint` / `cream2` |
   | | `GIFT` Hadiah / Hewanmu menerima hadiah | `Icons.redeem_rounded` | `gold` / `goldTint` |
   | `pushSettingsGroupPet` HEWAN & PENCAPAIAN | `PET_REMINDER` Pengingat hewan / Ulang tahun dan hari jadi kebersamaan | `Icons.cake_rounded` | `gold` / `goldTint` |
   | | `ACHIEVEMENT` Tonggak & level / Tonggak baru dan naik level | `Icons.emoji_events_rounded` | `triageGreen` / `momenBadgeBg` |
   | `pushSettingsGroupPromo` INFO & PROMO | `PROMO` Promo & aktivitas / Pengumuman, promo, dan survei layanan | `Icons.campaign_rounded` | `mint` / `cream2` |
   | `pushSettingsGroupAlways` SELALU AKTIF | `ORDER_ACCOUNT` Pesanan & akun / Pesanan, pembayaran, dan keamanan akun | `Icons.receipt_long_rounded` | `ink2` / `Color(0xFFF4F3F7)` |
4. 行（UI 稿 `.nrow`）：`Padding(horizontal 16, vertical 11)`，`Row[图标 36 圆, 12, Expanded(Column[标题 15 / w500 / ink, 2, 说明 12 / muted / height 1.4]), 开关或胶囊]`；行间分隔 `Divider(height 1, thickness 1, color: AppColors.line2, indent: 64, endIndent: 16)`（分隔线从文字列起，不压图标——与设置页 `_divider` 的 indent 16 不同，UI 稿 `.nrow+.nrow` margin-left 64）。
5. 开关：`Switch`，参数照 `_toggleRow`（:412-418：`activeThumbColor: AppColors.onAccent`、`activeTrackColor: AppColors.mint`）+ 关态按 UI 稿 B2 `.sw`（`inactiveTrackColor: AppColors.line`、`inactiveThumbColor: AppColors.card`、`trackOutlineColor: WidgetStatePropertyAll(Colors.transparent)`）；Key `ValueKey('pushSettingsSwitch_$code')`；过渡用 Switch 自带动画，不加额外动效（UI 稿 B2 说明）。
6. 「Pesanan & akun」（`locked` 内的 code）**不放开关**：标题色改 `AppColors.ink2`；右侧胶囊 `Container(padding: h9 v4, radius 999, color: Color(0xFFF4F3F7))` 内 `Icon(Icons.lock_outline_rounded, 12, muted)` + 3 + `pushSettingsAlwaysOn`「Selalu aktif」（11 / w600 / muted）；Key `ValueKey('pushSettingsLocked_ORDER_ACCOUNT')`；整行不可点（PRD AC3）。
7. 底部说明（`.nfoot`）：最后一张卡下方 12（卡 22 间距减 10），`Padding(left 4)`，`Row(crossAxisAlignment: start)[Icon(Icons.info_outline_rounded, 14, muted), 6, Expanded(Text(pushSettingsFooter, 12 / muted / height 1.5))]`。
8. Given 进入通知设置页且服务端全开，Then 4 组 9 行，前 8 行开关为开、第 9 行为「🔒 Selalu aktif」胶囊无开关，底部一行说明（B1）；Given 服务端返回 `ACHIEVEMENT` / `PROMO` 为关，Then 这两行开关为关、其余为开（B2）。

**AC6 — 顶部系统权限状态卡（B1 / B3）** `[L0]` `[L2]`（PRD AC5、§6.5 ②）
1. 已开启（B1，`ValueKey('pushSettingsStatusOn')`）：`Container(padding: h16 v14, radius 16, color: AppColors.mintTint, 无阴影)`，`Row(center)[40 白圆 + Icon(Icons.notifications_active_rounded, 22, mint), 12, Expanded(Text(pushSettingsSystemOn, 13.5 / w600 / ink / height 1.45))]`。
2. 已关闭（B3，`ValueKey('pushSettingsStatusOff')`）：底色 `AppColors.coralTint`、`crossAxisAlignment: start`；白圆内 `Icon(Icons.notifications_off_rounded, 22, AppColors.popRed)`；文字列 = `pushSettingsSystemOff`（13.5 / w600 / ink）+ 2 + `pushSettingsSystemOffHint`（12 / muted）；文字列下方、左缩进 52 对齐文字、上距 4：按钮 `mediaOpenSettings`「Buka Pengaturan」（高 36、水平内边距 14、圆角 12、`AppColors.mint` 底、白字 12.5 / w700、阴影 `0 6 16 rgba(132,94,201,.28)`；Key `ValueKey('pushSettingsOpenSystem')`）→ 调 `openPushSettings()`（`push_permission_providers.dart`:45）。
3. 权限在 `initState` 读一次；页面 `with WidgetsBindingObserver`，`AppLifecycleState.resumed` 时重读并 `setState`——即「从系统设置返回后自动刷新」。重读逻辑与设置页 `_refreshNotifStatus` 同三步：`PushPermissionChangeReporter.record(granted, fromScreen: 'push_settings_page')`、`setState`、`granted` 时 `syncRegistration(isVet: authControllerProvider.isVet)`（用户现在从这里去开权限，不补注册就要等下次冷启动，见设置页 :86-90 注释）。
4. 权限未读到前不显示状态卡（占位高度由骨架或空 `SizedBox` 承担，不闪错误态）。可测：构造参数 `isNotificationGrantedForTest` / `openSettingsForTest`（同 `notification_center_page.dart`:46-47）。
5. **系统权限关闭时 9 个开关照常可操作**（PRD 2026-10-13 拍板、B3），保存行为与开启时完全一致。
6. Given 系统权限关闭，When 打开通知设置页，Then 顶部浅红卡 + 说明 +「Buka Pengaturan」，点击调 `openPushSettings`，返回 App 后状态卡按新权限刷新（PRD AC5）；9 个开关仍可切换并保存。

**AC7 — 切换即保存：乐观更新 + 失败回弹（B4）** `[L0]` `[L2]`（PRD「切换即保存…失败时开关回弹并 Toast」、§6.5 ②）
1. 点开关 → 立即把该行改成新值（乐观），同时调 `update(code, value)`；**无确认按钮**。
2. 成功 → 该行以返回值中**同一 code** 的值为准（不整体替换状态，避免覆盖另一行仍在途的乐观值）；上报 `push_category_toggled`（AC9）。
3. 失败（任何异常，含网络、4xx、5xx）→ 该行回弹到切换前的值 + `showAppToast(context, l10n.pushSettingsSaveFailed)`「Gagal menyimpan, coba lagi」（`app_toast.dart`:35）；不上报埋点。
4. 同一 code 请求在途时再点该开关 → 忽略（`Set<String> _inFlight`，照 `LikeButton` `_inFlight` 防连点 `like_button.dart`:91）；不同 code 可并行。开关不置灰（置灰会被当成坏了，UI 稿 B1 说明同理）。
5. 页面已退出时请求返回 → 不再 `setState` / 不弹 Toast（`mounted` 守卫）。
6. Given 切换「Suka」为关，When 保存失败，Then 开关弹回开启 + Toast「Gagal menyimpan, coba lagi」（B4）；When 保存成功，Then 保持关闭，再次进页（重拉）仍为关闭。

**AC8 — 加载中骨架屏（B5）与加载失败（B6）** `[L0]` `[L2]`（§6.5 ②）
1. `pushSettingsProvider` 加载中 → 骨架屏（`ValueKey('pushSettingsSkeleton')`）：状态卡形（40 圆 + 12 高 50% 宽条）+ 一个组标题占位 + 一张卡内 **5 行**占位（36 圆、12 高标题条、10 高说明条、46×28 胶囊）；静态灰块、无 shimmer、无第三方包（范式同 `blocked_list_skeleton.dart`:6-10）；占位色 `Color(0xFFF1EEF7)`（UI 稿 `.sk`）。按真实布局占位，加载完不跳动。
2. 加载失败 → 整页居中 `EmptyState(icon: Icons.cloud_off_rounded, title: l10n.pushSettingsLoadFailed, actionLabel: l10n.commonRetry, onAction: retry)`（`empty_state.dart`:11；同 ③ 2.2 E6 加载失败样式），点「Coba lagi」重拉；**失败态不显示状态卡与任何开关**（B6 只有云图标 + 标题 + 按钮）。
3. Given 首次加载中，Then 只见骨架；Given 加载失败，Then 云图标 +「Gagal memuat pengaturan」+「Coba lagi」，点后重新加载成功显示 9 行。

**AC9 — 埋点** `[L0]`（PRD §4 FR-150、AD-17、P-8）
1. `push_settings_viewed{system_permission: 'on' | 'off'}`：每次进入页面、**首次读到系统权限后**报一次（resume 刷新不重报）。
2. `push_category_toggled{category, enabled}`：仅在**服务端确认保存成功后**报（失败回弹不报，与 `user_followed` 同口径）；`category` 取 code 小写（`like` / `comment` / `mention` / `follower` / `gift` / `pet_reminder` / `achievement` / `promo`）、`enabled` 为 bool。
3. 命名守卫 `test/analytics/v112_events_test.dart`：前缀 `'push_'` 已在 `allowedPrefixes`（:239），词尾 `_viewed` / `_toggled` 已在 `allowedSuffixes`（:347）→ **守卫无需改动**；属性键 `system_permission` / `category` / `enabled` 不命中 `Analytics.scrub` 的 PII / 自由文本键，也不命中 `v140_events_inventory_test.dart` 的 NFR-5 全局体检（:75-109）。
4. **新建 `test/analytics/v140_push_collect_events_test.dart`**（④ 线埋点清单，写法照 `v140_gift_events_test.dart` / `v140_events_inventory_test.dart`:53 的清单对账）：常量 `pushCollectInventory`（PRD 口径名 → 实际名）先放 `'push_settings_viewed'` / `'push_category_toggled'` 两条 + 一条「④ 清单逐条在 lib 里有上报点」测试；收藏 6 个事件由 2.3 / 2.4 追加到同一常量。**不改** `v140_events_inventory_test.dart`（Toko 线旧清单），其 NFR-5 全局属性体检照常要绿。

**AC10 — 文案（新增 arb，EN / ID 两份；子前缀 `pushSettings*`，已 grep 确认 0 命中）** `[L0]`（PRD §6.5 ⑦、NFR5）

| key | EN | ID | 用处 |
|---|---|---|---|
| `pushSettingsValueOn` | On | Aktif | A1 设置页右值（**不复用 `vetTabActive`**，兽医页签语义不同） |
| `pushSettingsSystemOff` | System notifications off | Notifikasi sistem nonaktif | A2 右值、B3 状态卡标题 |
| `pushSettingsSystemOn` | System notifications on | Notifikasi sistem aktif | B1 状态卡 |
| `pushSettingsSystemOffHint` | Once turned on, notifications follow the settings below. | Setelah diaktifkan, notifikasi dikirim sesuai pengaturan di bawah. | B3 状态卡说明 |
| `pushSettingsGroupInteraction` | INTERACTIONS | INTERAKSI | 组标题 |
| `pushSettingsGroupPet` | PETS & ACHIEVEMENTS | HEWAN & PENCAPAIAN | 组标题 |
| `pushSettingsGroupPromo` | NEWS & PROMOS | INFO & PROMO | 组标题 |
| `pushSettingsGroupAlways` | ALWAYS ON | SELALU AKTIF | 组标题 |
| `pushSettingsLikeTitle` | Likes | Suka | 行标题 |
| `pushSettingsLikeDesc` | Someone liked your post | Seseorang menyukai postinganmu | 行说明 |
| `pushSettingsCommentTitle` | Comments & replies | Komentar & balasan | |
| `pushSettingsCommentDesc` | Comments on your posts or places, and replies to you | Komentar di postingan atau tempat, dan balasan untukmu | |
| `pushSettingsMentionTitle` | Mentions (@) | Sebutan (@) | |
| `pushSettingsMentionDesc` | Someone mentioned you in a post or comment | Seseorang menyebutmu di postingan atau komentar | |
| `pushSettingsFollowerTitle` | New followers | Pengikut baru | |
| `pushSettingsFollowerDesc` | Someone started following you | Seseorang mulai mengikutimu | |
| `pushSettingsGiftTitle` | Gifts | Hadiah | |
| `pushSettingsGiftDesc` | Your pet received a gift | Hewanmu menerima hadiah | |
| `pushSettingsPetReminderTitle` | Pet reminders | Pengingat hewan | |
| `pushSettingsPetReminderDesc` | Birthdays and companionship anniversaries | Ulang tahun dan hari jadi kebersamaan | |
| `pushSettingsAchievementTitle` | Milestones & levels | Tonggak & level | |
| `pushSettingsAchievementDesc` | New milestones and level-ups | Tonggak baru dan naik level | |
| `pushSettingsPromoTitle` | Promos & events | Promo & aktivitas | |
| `pushSettingsPromoDesc` | Announcements, promos and service surveys | Pengumuman, promo, dan survei layanan | |
| `pushSettingsOrderAccountTitle` | Orders & account | Pesanan & akun | |
| `pushSettingsOrderAccountDesc` | Orders, payments and account security | Pesanan, pembayaran, dan keamanan akun | |
| `pushSettingsAlwaysOn` | Always on | Selalu aktif | 锁定胶囊 |
| `pushSettingsFooter` | Muted categories still show up in your in-app Notifications. | Notifikasi yang dimatikan tetap masuk ke kotak Notifikasi di aplikasi. | 底部说明 |
| `pushSettingsLoadFailed` | Couldn't load settings | Gagal memuat pengaturan | B6 |
| `pushSettingsSaveFailed` | Couldn't save, try again | Gagal menyimpan, coba lagi | B4 Toast |

- **沿用现有键**：`notificationCenterTitle`（Notifikasi / Notifications，设置页行标题 + 新页 AppBar）、`mediaOpenSettings`（Buka Pengaturan / Open Settings）、`commonRetry`（Coba lagi / Try again）、`settingsTitle`（返回目标页标题，不新增）。
- EN 值不得与 ID 相同（`test/l10n/microcopy_rules_test.dart`「D-3：英文基线不得混入印尼语」）——上表已逐条区分（例：组标题 EN 用 `NEWS & PROMOS`，不用与 ID 同形的 `INFO & PROMO`）；两份 key 完全对齐（同文件 J2）。
- 「Gagal menyimpan, coba lagi」与现有 `meNicknameSaveFailed` / `phoneSaveNetworkError` 同值，但语义属别的模块，**新建** `pushSettingsSaveFailed`，不跨模块复用。
- 改完 arb 跑 `flutter gen-l10n`。

**AC11 — 回归** `[L0]`：`flutter analyze` 零警告；`flutter test` 中 `test/me/`、`test/notify/`、`test/analytics/`、`test/app/user_scoped_cache_registry_test.dart`、`test/l10n/microcopy_rules_test.dart` 全绿；既有用例被新规则打破时**更新断言表达新规则，不删不 skip**；不写 golden；不整文件 `dart format`（手工对齐缩进）。

## Tasks / Subtasks

- [ ] **T0 开工核对**：拉 1.1 实际提交，核对 `GET / PATCH /api/v1/me/push-settings` 的路径、请求体 `{categories:{CODE:bool}}`、响应 `{categories:[{code,enabled,lockable}]}`、9 个 code 字面量与顺序；`settings_page.dart` 行号与本文件「现状」表一致。有出入先改本文件并在 Completion Notes 记一句
- [ ] **T1 数据层**：`ApiPaths.mePushSettings`、`domain/push_settings.dart`、`data/push_settings_repository.dart`、`pushSettingsProvider`；登记 `resetUserScopedCaches` + `_mustReset`（AC3）
- [ ] **T2 通知设置页**：页面 + 路由 + 状态卡 + 4 组 9 行 + 乐观保存 + 骨架 / 失败态（AC4 ~ AC8）
- [ ] **T3 设置页**：`_navRow` 加 `valueColor` / `valueDot`；「Notifikasi」改入口；删 `_onNotifToggle` / `_showOpenSettingsDialog`；`_notif` 改 `bool?`；加 `isNotificationGrantedForTest`（AC1 / AC2）
- [ ] **T4 埋点**：两个事件 + 新建 `test/analytics/v140_push_collect_events_test.dart` 登记（AC9）
- [ ] **T5 arb**：EN / ID 30 个键 + `flutter gen-l10n`（AC10）
- [ ] **T6 测试**：见 Dev Notes「测试」L0 列表；`flutter analyze` + 指定测试绿；Completion Notes 标「L2 待本地验收」

## Dev Notes

### 现状（改动前必读，2026-10-18 核 `dev_1.4.0` @ ba55e1c5）

| 文件 | 现状 | 本 story 改什么 | 必须保持 |
|---|---|---|---|
| `petgo_app/lib/features/me/presentation/settings_page.dart` | `_SettingsPageState with WidgetsBindingObserver`（:39）；`bool _notif = false`（:45，真相源是系统权限）；`initState` 加观察者 + `_refreshNotifStatus()`（:58-59）；`resumed` 刷新（:75-77）；`_refreshNotifStatus`（:79-91：`isPushPermissionGranted` → `PushPermissionChangeReporter.record(fromScreen:'settings_page')` → `setState` → `syncRegistration`）；`_onNotifToggle`（:93-114）；`_showOpenSettingsDialog`（:116-134，`ValueKey('notifOpenSettingsDialog')`）；AKUN 第二行 `_toggleRow(notificationCenterTitle, _notif, _onNotifToggle, key: 'meNotifToggle')`（:169-170）；`_sectionTitle`（:251-259）/ `_card`（:261-271）/ `_divider`（:273-274，indent 16）/ `_navRow`（:276-302，右值 14 muted :294）/ `_toggleRow`（:392-422，Switch :412-418） | 见 AC1 / AC2 | 其余 10 行、四个分组、`_refreshNotifStatus` 全部逻辑 |
| `lib/features/notify/data/push_permission_providers.dart` | `isPushPermissionGranted()`（:38）、`requestPushPermission()`（:41-42）、`openPushSettings()`（:45） | 不改；新页调 :38 与 :45 | — |
| `lib/features/notify/domain/push_permission_change_reporter.dart` | `record(granted, {fromScreen})`（:36），事件 `notify_push_permission_toggled`（:30），只在跃迁时上报 | 不改；新页传 `fromScreen: 'push_settings_page'` | 受控字面量，不传 UI 文案 |
| `lib/core/push/push_service.dart` | `syncRegistration({required bool isVet})`（:113） | 不改；新页 resume 且已授权时调用 | — |
| `lib/features/notify/presentation/notification_center_page.dart` | 测试注入参数 `isNotificationGrantedForTest` / `openSettingsForTest`（:43-47，用法 :111-112 / :155）；`_iconStyle`（:743-869）：`CONTENT_LIKED` 红心 coral/coralTint（:749）、`CONTENT_COMMENTED` mint/cream2（:754）、`PET_BIRTHDAY` cake gold/goldTint（:764）、`MILESTONE_NODE` 奖杯 triageGreen/momenBadgeBg（:770）、`CONTENT_MENTIONED` alternate_email mint/cream2（:846）、`USER_FOLLOWED` person_add mint/**violet100**（:852）、`OPS_ANNOUNCEMENT` campaign_**outlined** mint/**violet100**（:863）；**无 `PET_GIFT_RECEIVED`**（③ 分支加） | 不改；新页图标照 AC5 表 | — |
| `lib/core/network/api_paths.dart` | `base = '/api/v1'`（:5）、`me`（:126）、`meTailsonalityOwnerType`（:194） | 加 `mePushSettings` | — |
| `lib/features/tailsonality/data/tailsonality_owner_type_repository.dart` | 账号级设置 GET/PUT 的 Repository + `AsyncNotifierProvider`（:8-48），已登记 reset（`app.dart`:388） | 照写 | — |
| `lib/core/router/app_router.dart` | `/me/settings` → `SettingsPage`（:1011）；`_controlledLocations` 含 `/me`（:158-165，前缀自动受控） | 加 `/me/settings/notifications` | 其余路由 |
| `lib/app.dart` | `resetUserScopedCaches`（:355-390），末行 `consultRefreshProvider` bump（:389） | 加 `ref.invalidate(pushSettingsProvider)` | 既有登记 |
| `test/app/user_scoped_cache_registry_test.dart` | `_mustReset`（:13-33），断言 `ref.invalidate(name)` 文本在函数体内（:40-44） | 加 `'pushSettingsProvider'` | — |
| `lib/shared/widgets/app_toast.dart` | `showAppToast(context, message, {duration, top, actionLabel, onAction})`（:35） | 不改（本 story 不用 action） | — |
| `lib/shared/widgets/empty_state.dart` | `EmptyState(title, message, icon, actionLabel, onAction, …)`（:11），按钮 `ValueKey('emptyStateAction')` | 复用于 B6 | — |
| `lib/features/social/presentation/blocked_list_skeleton.dart` | 静态灰块骨架范式（:6-10，无 shimmer） | 照写一份页内骨架 | — |
| `lib/core/theme/colors.dart` | `mint` #845EC9（:19）、`mintTint` #F8F2FF（:23）、`cream2`（:31）、`ink2` #544864（:36）、`muted`（:37）、`line` #E6E6E6（:38）、`line2`（:39）、`popRed` / `coral` #F0425A（:44-45）、`gold`（:46）、`coralTint`（:49）、`goldTint`（:57）、`momenBadgeBg`（:69）、`triageGreen`（:156）；**无 #F4F3F7、#F1EEF7** | 不改 colors.dart；两色以页内私有常量写，注释「UI 稿 lk2 / sk」 | — |
| `test/analytics/v112_events_test.dart` | `allowedPrefixes`（:217）已含 `'push_'`（:239）；`allowedSuffixes`（:346）已含 `_viewed` / `_toggled`（:347） | 不改 | 不放宽正则 |
| `test/analytics/v140_events_inventory_test.dart` | Toko 线旧清单（:23-51）+ NFR-5 全局体检（:75） | **不改**；新建 `test/analytics/v140_push_collect_events_test.dart`（AC9.4） | 既有条目、体检照常绿 |
| `test/me/settings_page_test.dart` | 仅 1 个用例：断言 `meLanguage` / `meLogout` / `meDeleteAccount` 存在（:9-26） | 追加用例（见「测试」） | 原用例不改即绿 |
| `lib/l10n/app_en.arb` / `app_id.arb` | `notificationCenterTitle`（en :983 / id :755）、`mediaOpenSettings`（en :114 / id :78）、`commonRetry`（en :3201 / id :2114）、`vetTabActive`（en :672 / id :454）；`pushSettings*` 0 命中 | 加 AC10 的 30 个键 | 既有文案 |

### 拍板点（按建议实现，Completion Notes 复述）

- **① 新页图标颜色以 UI 稿为准**：PRD §6.5 ② 说 `Sebutan` / `Pengikut` / `Promo` 在 `_iconStyle` 里没有，实际有（:846 / :852 / :863），且 `USER_FOLLOWED`、`OPS_ANNOUNCEMENT` 的圆底是 `violet100`、Promo 是描边 `campaign_outlined`。UI 稿 B1 三者都画成 `cream2` 底 + 实心图标 → 按 UI 稿（`cream2` + `*_rounded`）；符号与通知中心一致，满足「同一个符号」的用意。
- **② `push_category_toggled` 只在保存成功后报**，`category` 用小写 code（与仓库属性值小写口径一致）；失败回弹不报，避免看板把失败次数算成关闭次数。
- **③ 「Hadiah」图标先用 `Icons.redeem_rounded` gold / goldTint（UI 稿 B1）**；③ 合入后若通知中心给 `PET_GIFT_RECEIVED` 定了别的图标，在对齐 story 3.1 统一（架构 §6）。
- **④ 设置页右值首帧不显示**（`_notif` 改 `bool?`）：避免进页面先闪红色「nonaktif」。
- **⑤ 通知设置页 resume 时也做 `PushPermissionChangeReporter.record` + `syncRegistration`**：开权限的入口从设置页挪到了这里，补注册逻辑跟着挪；`fromScreen` 新增受控字面量 `push_settings_page`。
- **⑥ 埋点清单单独成文件**：`v140_events_inventory_test.dart` 是 Toko 线旧清单（③ 送礼线另建了 `v140_gift_events_test.dart`），本线照送礼做法新建 `test/analytics/v140_push_collect_events_test.dart`，由本 story（④ 第一条 App story）建，2.3 / 2.4 追加（规划复核 2026-10-18 统一口径）。

### 实现提示

- 页面状态：`ConsumerStatefulWidget` + `with WidgetsBindingObserver`；`bool? _granted`；`Map<String, bool> _optimistic`（覆盖 provider 值）+ `Set<String> _inFlight`。渲染值 = `_optimistic[code] ?? settings.enabled[code] ?? true`。成功后把该 code 的服务端值写回 notifier（`state = AsyncData(old.copyWith(code, v))`）并清 `_optimistic[code]`；失败只清 `_optimistic[code]` + Toast。
- 分组与 code → (标题 key, 说明 key, 图标, 前景, 底色) 用页内 `const` 列表表达，**顺序与 AD-1 一致**；未知 code 不渲染、已知 code 缺失按开启渲染。
- 不在 App 里判断「能不能关」以外的业务规则；`ORDER_ACCOUNT` 永远不发 PATCH。
- 设置页 `_navRow` 改签名时只加可选参数，现有 8 处调用（:163 / :172 / :178 / :193 / :196 / :202 / :216 / :219）不动。
- 新文件放 `features/notify/{domain,data,presentation}/`（AD-14、§0.6）。

### 架构约束

- AD-4：分组与说明文案归 App，不渲染后端显示串（NFR5）；接口只认 code。
- AD-14：设置页只把「Notifikasi」从 `_toggleRow` 改 `_navRow(value…)`，删 `_onNotifToggle` / `_showOpenSettingsDialog`，保留 `_refreshNotifStatus`；新页样式照设置页同一套、不改设置页外观；乐观更新 + 失败回弹；骨架 / 失败重试；`resumed` 刷新权限卡；`pushSettingsProvider` 登记 `resetUserScopedCaches`。
- AD-17 / P-8：埋点保留 PRD 原名。
- 不引新依赖、不加本地缓存（设置只存服务端）；不改 `NotificationType` / 地基契约。

### 与前后 story 的边界（本 story 不做）

- **1.1**：后端表、`PushCategory` 映射、`GET / PATCH /api/v1/me/push-settings`、注销清理——本 story 只调用，接口不符先回 T0 改本文件，不改 1.1 的对外契约。
- **1.2**：推送闸门（`NotificationService` / `OpsNotificationDispatcher`）——App 无任何闸门逻辑。
- **2.x**：收藏按钮、Disimpan Tab、`showAppToast` 的「Lihat」动作、其余 6 个收藏埋点——不在本 story。
- **3.1（对齐分支）**：③ 合入后「Hadiah」「Tonggak & level」图标与通知中心新类型对齐；① 的升级通知走「成就与等级」。
- 不做免打扰时段、不做通知中心按分类筛选、不改通知中心显示逻辑（PRD 不做项）；不动首次申请权限的软引导。

### 测试

**验证层级**
- **L0（云端可跑，纯 widget / unit；不写 golden）**：
  - `test/notify/push_settings_test.dart`（新，纯单元）：`PushSettings.fromJson` —— 9 项全开；部分关闭；未知 code 忽略；缺失 code 视为开启；`ORDER_ACCOUNT` 在服务端 `lockable` 缺失 / 为 true 时仍进 `locked`；`copyWith` 只改一项。
  - `test/notify/push_settings_page_test.dart`（新；override `pushSettingsRepositoryProvider` 为 fake，注入 `isNotificationGrantedForTest` / `openSettingsForTest`，`Analytics.debugCaptureSink` 收事件，locale `id`）：
    - B1：4 个组标题（INTERAKSI / HEWAN & PENCAPAIAN / INFO & PROMO / SELALU AKTIF）按序出现；8 个 `pushSettingsSwitch_*` 均为开；`pushSettingsSwitch_ORDER_ACCOUNT` **不存在**、`pushSettingsLocked_ORDER_ACCOUNT` 存在且含「Selalu aktif」；底部说明文本存在；`pushSettingsStatusOn` 存在。
    - B2：fake 返回 `ACHIEVEMENT` / `PROMO` 关 → 两个 Switch `value == false`，其余 true。
    - B3：权限 false → `pushSettingsStatusOff` + 说明 +「Buka Pengaturan」；点后 `openSettingsForTest` 被调用 1 次；开关仍可切换并调用 fake `update`。
    - resume 刷新：权限桩由 false 改 true 后 `tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed)` → 状态卡变 `pushSettingsStatusOn`。
    - B4：fake `update` 抛错 → 点「Suka」后先变关、`pump` 后弹回开 + 出现「Gagal menyimpan, coba lagi」；无 `push_category_toggled` 事件。
    - 成功：点「Promo」→ fake 收到 `('PROMO', false)`；Switch 保持关；事件 `push_category_toggled {category: 'promo', enabled: false}` 恰一次。
    - 在途防连点：fake `update` 用 `Completer` 挂起，连点同一开关两次 → fake 只被调 1 次。
    - B5：fake `fetch` 挂起 → `pushSettingsSkeleton` 存在、无任何 Switch。
    - B6：fake `fetch` 抛错 → 「Gagal memuat pengaturan」+ `emptyStateAction`；无状态卡、无 Switch；点重试且 fake 改为成功 → 9 行出现。
    - 埋点：进页后 `push_settings_viewed {system_permission: 'on'}` 恰一次（权限 false 时为 `'off'`）；resume 刷新不重报。
  - `test/me/settings_page_test.dart`（追加用例，**原用例不改**；用 `GoRouter` 承接 push，注入 `isNotificationGrantedForTest`）：
    - 权限 true → `meNotifEntry` 行内含「Aktif」、无 `meNotifOffDot`；`find.byKey(ValueKey('meNotifToggle'))` 与 `find.byType(Switch)` 只剩「Profil Hewan (publik)」1 个。
    - 权限 false → 含「Notifikasi sistem nonaktif」且该 `Text` 颜色 == `AppColors.popRed`、`meNotifOffDot` 存在。
    - 点 `meNotifEntry` → 路由落到 `/me/settings/notifications`；全程不出现 `notifOpenSettingsDialog`。
    - 「Edit Profil / Bahasa / Nomor HP / Profil Hewan / Kebijakan Privasi / Syarat & Ketentuan / Daftar Blokir / Periksa pembaruan / Keluar / Hapus akun」10 行 key 均在。
    - ⚠️ 核实结论：现有 `test/` 下**没有任何断言引用 `meNotifToggle` 或 `notifOpenSettingsDialog`**（grep 0 命中），`settings_page_test.dart` / `settings_check_update_test.dart` / `language_settings_test.dart` 的既有断言不会被本 story 打破；如实现后仍有红，按「更新断言表达新规则」处理。
  - `test/app/user_scoped_cache_registry_test.dart`：`_mustReset` 加 `pushSettingsProvider` 后绿。
  - `test/analytics/v112_events_test.dart`：不改，两个新事件过前缀 / 词尾守卫。
  - `test/analytics/v140_push_collect_events_test.dart`（新建）两条对账绿；`v140_events_inventory_test.dart` 的 NFR-5 体检照常绿。
  - `test/l10n/microcopy_rules_test.dart`：J2 key 对齐、D-3 EN 不混印尼语。
  - `flutter analyze` 零警告。
- **L1**：无（纯 App；后端接口的 L1 在 1.1）。
- **L2（本地，Android 模拟器 + 真后端）**：A1 / A2（系统设置里关通知后返回设置页右值变红）、B1 / B2 / B3（点「Buka Pengaturan」→ 系统设置开启 → 返回卡片变紫）、B4（断网切换回弹 + Toast）、B5 / B6；同账号两台设备改设置互相可见（PRD AC4）。逐屏对照 UI 稿 A1–A2、B1–B6，**还原度 ≥ 95%**。
- 云端只跑 L0；Completion Notes 标「L2 待本地验收」。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.0/epics-v1.4.0-push-collect.md#Story 1.3]
- [Source: _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-push-collect-delta.md#§0.3 第 9 条、AD-4、AD-14、AD-17、P-8]
- [Source: _bmad-output/planning-artifacts/v1.4.0/推送开关与收藏/1-4-0推送开关与收藏prd.md#3.1 通知设置页 / AC3 / AC4 / AC5 / AC5a、§4 FR-150、§6.5 ① ② ⑦]
- [Source: _bmad-output/planning-artifacts/v1.4.0/decision-log.md#④ 推送开关与收藏（第二步告知项、第三步两处偏离已确认、第四步移交授权）]
- [Source: _bmad-output/planning-artifacts/v1.4.0/推送开关与收藏/ui-1-4-0-push-collect-integrated-v1.html#A1 ~ A2、B1 ~ B6]
- [Source: _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md#2026-10-18 V1.4.0 四条线共用地基]
- [Source: _bmad-output/implementation-artifacts/v1.4.0/cloud-rules-push-collect.md]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

### Change Log

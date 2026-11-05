# Story 1.4: 宠物访客页追更与失效修复（App，A1–A4、A14、A15）

Status: review

> 版本 v1.4.2 · 主题 social-topic · 分支 `feat/1.4.2-social-topic`
> 口径优先级：`决策日志-social-topic.md`（R-2）> PRD §3.1「入口与按钮 / 主人一侧 / 关注关系（失效页修复）」、AC1 / AC2 / AC13、§4、§7 > 架构增量 D-8、D-23、§1.2 第 3 / 9 条、P-9 ~ P-12 > **UI 定稿 `追更话题/ui-1-4-2-social-topic-integrated-v1.html` 的 A1–A4、A14、A15 每屏下方说明**（含文件、行号、数值；数值照说明里写的代码出处取，**不从截图量像素**；标「原有模块照线上」的不改样式）。
> 前置：**Story 1.1**（后端接口与字段）。后端未就绪时可按本文件「接口契约」先写 mock 测试，联调留到 1.1 合入后。
> 范围层：**只 App**（`petgo_app/`）。关注列表 / 关注流 / 通知 / 推送设置归 1.5。

## Story

As a 访问他人宠物的用户,
I want 在宠物访客页一键追更、看到追更人数，页面失效时能返回或重试,
so that 追更顺手、不会卡在死页面。

## 接口契约（来自 1.1，实现前核对后端实际 JSON）

- `POST` / `DELETE /api/v1/me/pet-follows/{petId}` → `{ "iFollow": bool, "followerCount": int }`；429 超频；403 `blocked-user`（我拉黑了主人）；404（宠物 / 主人失效）；422（自己的宠物）。
- `GET /api/v1/pets/{petId}/visitor/profile` 新增 `iFollow`、`iFollowOwner`、`ownerToken`（**只站内入口有**；分享 token 路径 `/public/...` 不下发，缺键按 null）。
- `…/visitor/stats` 与作者态 `/pet-profiles/me/stats` 新增 `followerCount`（同一个 App 模型 `ArchiveStats`）。

## Acceptance Criteria

> 层级：`[L0]` `flutter analyze` / `flutter test`（云端可跑）· `[L1]` 连本地 / stag 后端接口联调 · `[L2]` Android 模拟器视觉（本地）。

### AC1 — A1 / A2 追更按钮 `[L0 widget]` `[L2]`（PRD AC1 / AC2、UI A1 / A2）
1. 他人宠物**站内**访客页（`/pets/:petId`，`ArchiveScope.inApp`），信息卡内、统计条下方放整宽按钮：未追更「Ikuti」（紫色实心）/ 已追更「Mengikuti」（白底 + 1.5 `AppColors.line` 灰框、`ink` 字）；高 40、圆角 12、14 / w700 —— **直接复用 `FollowButton._profileFull` 的视觉**（`follow_button.dart`:261-299），文案复用 arb 现有 `followActionFollow` / `followActionFollowing`。
2. 点 Ikuti → 乐观更新为 Mengikuti、Pengikut +1（A2，128 → 129）；再点 Mengikuti → **立即取消、无二次确认**、回 A1、计数 −1。请求在途时再点忽略（不显示 loading，避免闪烁，同 `FollowButton._onTap` 的 `_inFlight`）。成功后以响应 `followerCount` 为准写回。
3. 失败（A3）：按钮与计数**回滚**到点击前，**顶部** toast —— 追更失败 `followFailed`「Gagal mengikuti, coba lagi」、取消失败 `followUnfollowFailed`；`showAppToast(context, …, top: true)`（`shared/widgets/app_toast.dart`）。
4. 超频（429 或 ProblemDetail `typeSlug == 'rate-limited'`）：**底部** toast `followRateLimited`「Terlalu sering. Coba lagi nanti.」，按钮回滚（与 1.3.4 规则一致，UI A3 说明「与 A3 同屏、只是提示在底部」）。
5. 游客点 Ikuti → `requireLogin(ref, context, entrySource: 'pet_follow')` 拉起登录，**登录后不自动追更**，不发请求、不埋点。
6. **不显示按钮的情况**：自己的宠物（与 `_giftPill()` 同一判定：`petProfileProvider` 加载中 / 失败 / 是自己的 → 不显示）；分享链接 `/pet/{token}` 落地态（`!scope.isInApp`）；名片 H5、Feed 卡片、帖子详情一律没有追更按钮（PRD AC2，本 story 不在这些地方加任何东西）。
7. 成功后失效：本宠物 `visitorStatsProvider(scope)`（Pengikut 数）、我自己的 `publicProfileProvider(myId)`（「Hewan N」胶囊）、我的追更宠物列表 provider（1.5 建；本 story 若先于 1.5 实现，预留 `ref.invalidate` 调用点并在 1.5 接上）。请求返回时页面可能已关 → **先取 `ProviderScope.containerOf(context, listen: false)` 再 await**（照 `FollowButton._onTap`:313 的写法）。
8. 同一只宠物在多处出现时状态一致：新建 `petFollowStateProvider`（`Notifier<Map<int petId, PetFollowOverride>>` 覆盖表 —— 值为 `{iFollow, followerCount?}`，人数随乐观 ±1 / 成功写回一并覆盖，乐观写 / 回滚 / 成功写回），访客页按钮、1.5 的 Hewan 列表与推荐卡共用；**登记到 `app.dart` 的 `resetUserScopedCaches`**（换账号必须清，否则 B 看到 A 的追更态）。

### AC2 — 统计条第 4 格「N Pengikut」 `[L0 widget]` `[L2]`（PRD AC1、UI A1 / A4）
1. `PetInfoCard` 统计条在礼物格、Milestone 格之后加第 4 格「{N} Pengikut」：数字 16 / w700 `ink`、**加 `FontFeature.tabularFigures()`**（乐观 +1 不跳宽）；标签复用 arb 现有 `followStatFollowers`「Pengikut」（11 号 `ink2`）；**不可点**。
2. 计数格式「同 1.3.4」：`formatCompactCount(n, Localizations.localeOf(context))`（`shared/utils/compact_count.dart`，1234 → `1,2K`（id）/ `1.2K`（en））。
3. 4 格时每格左右内边距 **4**（3 格是 8，`pet_info_card.dart` `_statCol`:200-215 / `_giftCol`:239-241）；礼物格数值外包 `FittedBox(fit: BoxFit.scaleDown)`（360dp 小屏「Rp999,9rb」放不下时等比缩小、不截断）。
4. 何时 4 格：`followerCount != null`（新后端两路 stats 都会下发）。缺字段（旧后端 / 游客示例页 `diary_guest_page.dart`）→ 维持现状 3 格、内边距 8，不出空格。
5. 主人自己的 Diary Tab（A4，`growth_archive_page.dart`:540 起的 `DiaryHeader`）同样显示第 4 格，**只显示人数、无按钮、无名单、不可点**；只读宠物、切宠物过渡态同样显示该格；主人 Diary Tab 页头**不合并、不改**。

### AC3 — 访客页页头合并为一行 `[L0 widget]` `[L2]`（PRD「访客页页头合并」、UI A1 ①）
1. 线上：`visitor_archive_view.dart` 先渲染 `_backRow`（:181-196，40×40）、间隔 12、再是 `DiaryHeader` 标题行「Diary {name}」+ Kirim Hadiah 胶囊。改为**同一行**：左返回键（热区 44×44，`Icons.chevron_left_rounded` 28 `ink`，点击逻辑照 `_backRow`：`canPop` 就 pop、否则 `go('/home')`）+ 标题「Diary {name}」19 / w700（从返回区之后起排、单行省略）+ 右侧「Kirim Hadiah」胶囊（外层 44 高）；整行 44 高、内容垂直居中；行上距 8（ListView 顶部内边距不变）、行到信息卡 14 不变。节省 52px（96 → 44）。
2. 实现：给 `DiaryHeader` 加 `Widget? leading` 参数（插在标题行 `Row` 最前）；**主人 Diary Tab 不传，零影响**。访客页传返回键并删掉 ListView 里的 `_backRow` 子项（返回键 `ValueKey('visitorBackButton')` 保留在新位置，既有测试据此找它）。
3. 分享链接 `/pet/{token}` 落地态同样合并；其「由 {昵称} 分享」来源横幅（`_banner`）移到**合并行下方**。
4. 非 token 值照说明出处（标题 19 `diary_header.dart`:141；胶囊 12.5 / 内边距 11·14 `gift_entry.dart`:69,84；胶囊图标与文字间距 4 `gift_entry.dart`:81；头像无图 emoji 26 `pet_info_card.dart`:64 —— 代码已是 26，无需改）。

### AC4 — A14 失效页（服务端「不存在」）`[L0 widget]` `[L2]`（PRD AC13、UI A14）
1. 线上 `_Gone`（:307-323）只有居中一句 `growthLoadFailed`「…tarik untuk coba lagi」：没有返回箭头、页面也不能下拉。改为：左上补返回箭头（复用 AC3 的返回键组件）+ 通用空态 `EmptyState`（`shared/widgets/empty_state.dart`）：图标 `Icons.search_off_rounded` 48 `muted`、标题复用 `detailGoneTitle`「Konten ini sudah tidak ada」（`AppTypography.title` 18 / 1.3 w600）、左右内边距 24（`AppSpacing.xl`）、在返回行以下区域垂直居中；**不放任何按钮**（失效重试无用）。无新文案。
2. 触发：接口明确返回 404（宠物已删 / 主人注销 / 主人封号 / 对方拉黑我 / 链接失效 —— **四种原因共用这一屏、文案不区分**，保留线上安全口径）。另：403 `blocked-user`（我拉黑了主人）同样落 A14（见「待确认」3）。
3. 追更者从 Hewan 列表点进「主人被封号」的宠物会落到这里（PRD AC11），返回后在列表可点 Mengikuti 取消。

### AC5 — A15 加载失败（没拿到响应）`[L0 widget]` `[L2]`（PRD AC13、UI A15）
1. 断网 / 超时 / 5xx / 其它没拿到明确业务结论的错误 → 本屏：结构同 A14（返回箭头 + `EmptyState`），图标 `Icons.cloud_off_rounded`、标题复用 `detailNetworkError`「Gagal memuat, silakan coba lagi」、主按钮复用 `commonRetry`「Coba lagi」（按 `app_theme.dart` 的 `filledButtonTheme`：高 46、圆角 12、14.5 / w700、左右 24；标题到按钮 16 `AppSpacing.lg`）。
2. 点「Coba lagi」→ `ref.invalidate(visitorProfileProvider(scope))`（及 stats / timeline 两个 provider），回到现有居中转圈；恢复网络后能正常加载。
3. 错误分类在数据层做：`DioTimelineRepository.getInAppVisitorProfile` / `getVisitorProfile`（`timeline_repository.dart`:146-155）捕获 `DioException` 抛出带类型的错误（`gone` / `network`），照 `features/content/data/detail_repository.dart` 的 `_classify`（:158-166：404 → gone、403 → forbidden、其余 → network）；页面按类型分两屏。**禁止**把请求错误画成空态。

### AC6 — 埋点 `[L0]`（PRD §4、R-2、P-9）
1. 追更 / 取消**服务端确认成功后**各报一次（失败、游客拦截不报）：
   - `pet_follow` / `pet_unfollow`：`{ pet_id: <petId>, owner_id: <ownerToken>, source: 'pet_visitor', is_following_owner: <iFollowOwner> }`。
   - `owner_id` 用访客 profile 的 `ownerToken`（R-2：不上报自增 id）；`ownerToken` / `iFollowOwner` 缺失（旧后端）时省略该属性，不报 null。
2. 事件名写字面量（P-9，守卫正则扫源码）。⚠️ `test/analytics/v112_events_test.dart` 的命名守卫要求「前缀在表内 + 词尾在 `allowedSuffixes`」：前缀 `pet_` 已在表；但词尾 `_follow` / `_unfollow` **不在** `allowedSuffixes`（表里只有 `_followed` / `_unfollowed`，:471-472）。照守卫文件里「名字由 PRD §4 定死，扩表、不改名」的先例，在 `allowedSuffixes` 追加 `'_follow'`、`'_unfollow'` 并写注释（来源 V1.4.2 social-topic PRD §4 `pet_follow` / `pet_unfollow`）；**不改事件名**（见「待确认」2）。
3. `source` 取值本 story 只用 `pet_visitor`；`follow_list` / `pet_recommendation` 归 1.5。

### AC7 — 文案 `[L0]`
1. 本 story **无新增 arb 键**：按钮、标签、toast、失效两屏全部复用现有键（`followActionFollow`、`followActionFollowing`、`followStatFollowers`、`followFailed`、`followUnfollowFailed`、`followRateLimited`、`detailGoneTitle`、`detailNetworkError`、`commonRetry`）。严禁源码硬编码用户可见字符串。
2. 若实现中发现必须新增键：`app_id.arb` / `app_en.arb` 同步写，跑 `flutter gen-l10n`，不改生成的 `app_localizations*.dart`。

### AC8 — 还原度与回归 `[L0]` `[L2]`
1. `flutter analyze` 零警告；`flutter test` 全绿（现有 `visitor_archive_view_test`、`visitor_wire_contract_test`、`visitor_deeplink_routing_test`、`visitor_route_gating_test`、`pet_info_card_gift_stat_test`、`follow_button_test` 只补不删）。
2. **还原度 ≥ 95%**（L2）：Android 模拟器逐屏对照 UI 稿 A1 / A2 / A3（含超频底部 toast 态）/ A4 / A14 / A15 打分，结果记入 Completion Notes；「原有模块照线上」区域不按稿改样式。

## Tasks / Subtasks

- [x] **T0 开工核对**：分支；1.1 后端字段是否已在本分支（未在则先按契约写 mock 测试）；Dev Notes「现状」行号未漂移
- [x] **T1 数据层**（AC1、AC5.3）：`core/network/api_paths.dart` 注册 `mePetFollow(int petId)`；`features/social/data/pet_follow_repository.dart`（follow / unfollow → `PetFollowResult`）；`features/social/domain/pet_follow_state.dart`（`petFollowStateProvider`）；`VisitorProfile` 加 `iFollow` / `iFollowOwner` / `ownerToken`；`ArchiveStats` 加 `int? followerCount`；访客档案取数抛分类错误
- [x] **T2 按钮组件**（AC1、AC6）：`features/social/presentation/widgets/pet_follow_button.dart`（`PetFollowButtonStyle.profileFull` 本 story、`listCompact` 留给 1.5 —— 可一次做完两种外观）；把 `FollowButton` 的 `_profileFull` / `_listCompact` 外观抽成同文件可复用的私有→库内函数（只抽外观、点击逻辑不动），保证两种按钮像素一致
- [x] **T3 信息卡与页头**（AC2、AC3）：`PetInfoCard` 加 `followerCount`、`bottomAction`；`DiaryHeader` 加 `leading`、透传 `followerCount` / `bottomAction`；`growth_archive_page.dart` 透传 `followerCount`；`visitor_archive_view.dart` 合并页头、挂按钮
- [x] **T4 失效两屏**（AC4、AC5）：重写 `_Gone` 为 `_VisitorGone` / `_VisitorLoadFailed`（或一个带 kind 的组件）
- [x] **T5 缓存与埋点**（AC1.8、AC6）：`resetUserScopedCaches` 登记；`v112_events_test.dart` 词尾扩表
- [x] **T6 测试**（见「测试」）
- [x] **T7** `flutter analyze` + `flutter test`；Completion Notes 标「**L1/L2 待本地验收**」（L2 还原度打分在本地模拟器做）

## Dev Notes

### 现状（改动前必读，2026-11-04 核 `dev_1.4.2` @ c496e3c3）

路径前缀 `A/` = `petgo_app/lib/`。

| 文件 | 现状 | 本 story 改什么 | 必须保持 |
|---|---|---|---|
| `A/features/profile/presentation/visitor_archive_view.dart` | 两种来源共用（分享 `/pet/{token}` 与站内 `/pets/{petId}`）；`build`（:89-144）：ListView 子项 `_backRow`（:105）→ 横幅（仅分享）→ `DiaryHeader(readOnly: true, titleAction: _giftPill(), …)`；`_giftPill()`（:151-165）自己的宠物 / 档案加载中不显示；`error:` 一律 `_Gone`（:101、:307-323）；时间线点击分流（:291-299） | 页头合并、横幅下移、挂追更按钮、失效分两屏 | 「不要为站内再复制一个页面」；私密条目 toast；`diary_visitor_viewed` 埋点只在 initState 报 |
| `A/features/profile/presentation/widgets/diary_header.dart` | 标题行（:133-154）`Row[Expanded(Text 19/w700), titleAction, 编辑铅笔]`；`showTitleRow`（作者态有切换器时传 false）；`belowTitle` 插槽；`PetInfoCard(...)`（:161 起） | 加 `leading`（标题行最前）；透传 `followerCount`、`infoCardBottom`（按钮） | `readOnly` 整块移除入口网格的语义；作者态外观 |
| `A/features/profile/presentation/widgets/pet_info_card.dart` | 三格 `_statCol`（:200-215，内边距 h8 v6）/ `_giftCol`（:218-275）；头像 emoji 已是 26（:64） | 第 4 格、4 格内边距 4、礼物格 `FittedBox`、`bottomAction` 插槽（统计条下方 12） | 3 格时外观逐像素不变 |
| `A/features/profile/presentation/growth_archive_page.dart` | 作者态 `DiaryHeader(...)`（:540 起，传 `happyCount` / `giftTotalValue` / `milestoneCompleted` …） | 透传 `followerCount: stats?.followerCount` | 页头不合并 |
| `A/features/profile/presentation/diary_guest_page.dart` | 游客示例页也用 `DiaryHeader` / `PetInfoCard` | 不传 `followerCount`（维持 3 格） | — |
| `A/features/profile/data/timeline_repository.dart` | `getInAppVisitorProfile` / `getVisitorProfile`（:146-155）直接 `dio.get`，异常原样抛；`visitorProfileProvider` / `visitorStatsProvider` / `visitorTimelineProvider`（:247-265，autoDispose family） | 档案取数抛分类错误 | autoDispose（拉黑守卫每次都会被调用的理由见 :240-246 注释） |
| `A/features/profile/domain/archive_stats.dart` / `visitor_profile.dart` | `ArchiveStats.fromJson`（:38-46，缺键给 0）；`VisitorProfile` | 加字段：`followerCount` **缺键 = null**（不要给 0，否则旧后端也会显示 4 格「0 Pengikut」） | 其余字段缺键口径 |
| `A/features/social/presentation/widgets/follow_button.dart` | 用户关注按钮；外观 `_profileFull`（:261-299）、`_listCompact`（:173-215）；点击 `_onTap`（:301-359）：游客 `requireLogin` → 乐观覆盖 → 请求 → 成功写回 + 埋点 + 失效 → 失败回滚 + 顶部 / 底部 toast；**先取 container 再 await**（:313）；记点击时账号、换号丢弃（:316-317） | 抽出外观函数供宠物按钮复用 | 用户关注全部行为 |
| `A/features/content/data/detail_repository.dart` | `_classify`（:158-166）404 → gone / 403 → forbidden / 其余 → network | 照写分类 | — |
| `A/features/content/presentation/content_detail_page.dart` | `_errorBody`（:104-126）gone / network 两态 + `EmptyState` | 参照（但访客页 A14 **无按钮**、A15 按钮是「Coba lagi」重试，与详情页「Kembali ke Sosial」不同，UI 说明已注明） | — |
| `A/shared/widgets/empty_state.dart` | `EmptyState(title, message?, icon?, actionLabel?, onAction?, …)` | 复用 | — |
| `A/shared/utils/compact_count.dart` | `formatCompactCount(int, Locale)`（≥1000 → K，向下截断，id 用逗号） | 复用 | — |
| `A/app.dart` | `resetUserScopedCaches`（:362 起）登记账号维度 provider | 加 `petFollowStateProvider` | — |
| `petgo_app/test/analytics/v112_events_test.dart` | 前缀表含 `'pet_'`（:359）；`allowedSuffixes` 含 `'_followed'` / `'_unfollowed'`（:471-472） | 追加 `'_follow'`、`'_unfollow'` + 注释 | 其余规则 |

### UI 关键数值（A1–A4、A14、A15 说明原文摘要，出处即代码行）

- 页头：整行 44 高；返回热区 44×44、`chevron_left_rounded` 28 `ink`；标题 19 / w700（`diary_header.dart`:141）；Kirim Hadiah 胶囊外层 44（`gift_entry.dart`:65）、12.5 / 内边距 11·14（:69,84）、图标与文字间距 4（:81）；行上距 8、行到信息卡 14。
- 信息卡：内边距 14、头像与文字间距 14（`pet_info_card.dart`:98,133）；简介上距 3（:161）；礼物单位 9.6 = 16 × 0.6（:227）；4 格每格左右内边距 4；Pengikut 数字 tabular-nums。
- 追更按钮：整宽 × 40、圆角 12、14 / w700（`follow_button.dart`:266）；Ikuti `AppColors.mint` 实心白字；Mengikuti `AppColors.surface` 底 + 1.5 `AppColors.line` 框 + `ink` 字；按钮在信息卡内、统计条下方，上下 12 间距内无其它可点元素（热区 40 高沿用 `_profileFull`，为统一不加高）。
- toast：顶部距离 = 状态栏 + 16、14 / 1.3、内边距 11 / 16、圆角 12（`app_toast.dart`，无改动）。
- 其它卡：卡间距 11、Pencapaian 卡 11 / 15、进度条上距 7（`diary_header.dart`:173,324,353）；时间线标题 12.5 / 副标题 10.5 / 卡内 9·10（`timeline_item_tile.dart`:155,159）；月份标题上下 10 / 8（`growth_archive_page.dart`:800）—— 均为原有模块，照线上。
- A4（主人 Diary Tab）额外出处：「Diary saja」10.5 / 内边距 3·8·4（`growth_archive_page.dart`:706,715）；分享 / 编辑按钮 38 圆角 11（`diary_header.dart`:397-412）；入口行 10·12 / 分隔左缩进 54（`diary_header.dart`:216,253）—— 原有模块照线上。
- A14 / A15：`EmptyState` 标题 `AppTypography.title` 18 / 1.3 w600、左右 24（`AppSpacing.xl`）、图标 48 `muted`、图标到标题 12；A15 按钮高 46 / 圆角 12 / 14.5 w700（`app_theme.dart`:79）/ 左右 24、标题到按钮 16。

### 拍板点（按建议实现，Completion Notes 复述）

- **① 新建 `PetFollowButton`，不改 `FollowButton` 的点击逻辑**：用户关注按钮的覆盖表按 `userId` 键、接口按 user token；宠物按 `petId`。只把两种外观抽成共用函数，避免像素漂移。
- **② `followerCount` 缺键 = null → 3 格**：防旧后端 / 游客示例页误显「0 Pengikut」。
- **③ 403（我拉黑了主人）落 A14**：服务端对「我拉黑了他」返回 403 `blocked-user`；该场景从他人主页本就进不来（主页先 403），深链进入时给「内容已不存在」比给「加载失败 + 重试」合理（重试必然再 403）。
- **④ 追更埋点只在成功后报**：同 `user_followed`（D-6 口径）。

### 实现提示

- `PetInfoCard` 4 格建议写法：`final four = followerCount != null; final hp = four ? 4.0 : 8.0;` 三个既有格与新格都用 `hp`；礼物格 `value` 外包 `FittedBox(fit: BoxFit.scaleDown, child: value)`（只在 4 格时包，3 格保持现状以免像素变化）。
- `bottomAction` 放在统计条 `Container` 之后：`if (bottomAction != null) ...[const SizedBox(height: 12), bottomAction!]`。
- 页头 `leading`：`Row(children: [if (leading != null) leading!, Expanded(标题), …])`；访客页返回键 `SizedBox(width: 44, height: 44, child: IconButton(...))`，`constraints: BoxConstraints.tightFor(width: 44, height: 44)`。合并后的标题行由 `DiaryHeader` 画，访客页 ListView 第一个子项直接是 `DiaryHeader`。
- `PetFollowButton` 点击流程照抄 `FollowButton._onTap`，但：覆盖表用 `petFollowStateProvider`；埋点属性见 AC6；失败分支额外把 403 / 404 当作普通失败（顶部 toast），404 时可调 `onNotFound` 让页面重取（宠物刚失效）。
- 错误分类示意：
  ```dart
  enum VisitorLoadErrorKind { gone, network }
  class VisitorLoadError implements Exception { const VisitorLoadError(this.kind); final VisitorLoadErrorKind kind; }
  // 404 / 403 → gone；其余（含 null response）→ network
  ```
- **不要整文件 `dart format`**（存量代码未格式化，会带出几百行无关 diff）：手工对齐缩进，只跑 `flutter analyze`。
- 新文案（若有）两份 arb 同步写后 **必须** `flutter gen-l10n`，否则编译失败。

### 架构约束

- D-8：按钮只在站内访客页；自己的宠物不显示；`iFollow` / `followerCount` / `ownerToken` / `iFollowOwner` 来自后端。
- D-23 / P-11：本 story 不涉功能开关。
- P-4：追更进 `features/social`（`data/pet_follow_repository.dart`、`domain/pet_follow_state.dart`、`presentation/widgets/pet_follow_button.dart`）；访客页改动在 `features/profile`。
- P-9：事件名字面量、属性严格按 PRD §4；P-10：本 story 无新 key（若新增前缀 `petFollow*`）；P-12：widget 测试覆盖稿中每种状态。
- 护栏：App 不渲染后端显示串（`detail` 原文不上屏）；不放开 `textScaler` 上限；无新依赖。

### 测试

**L0（云端可跑）**
- `test/profile/visitor_archive_view_test.dart`（补）：
  - 站内他人宠物：渲染 Ikuti、统计条 4 格且 Pengikut 显示 `128`；点击 → Mengikuti、`129`（mock repo 成功）；再点 → Ikuti、`128`、**无确认弹窗**；
  - 失败 → 回滚 + 顶部 toast `followFailed`；取消失败 → `followUnfollowFailed`；429 → 底部 toast `followRateLimited`、按钮回滚；在途重复点击只发一次请求；
  - 游客：点击触发登录、不发请求；
  - 自己的宠物 / 分享 token 态：无按钮；
  - 页头：返回键（`visitorBackButton`）与标题、送礼胶囊同一行（同一 `Row` 祖先 / 同一 y 中心）；分享 token 态横幅在合并行之下；返回键 canPop → pop、否则 go `/home`；
  - A14：provider 抛 `gone` → 返回箭头 + `detailGoneTitle`、**无按钮**；A15：抛 `network` → `detailNetworkError` + `commonRetry`，点击后重新请求（provider 被重建）。
- `test/profile/pet_info_card_follower_stat_test.dart`（新）：`followerCount: null` → 3 格、内边距 8（与改前一致）；`followerCount: 9999` → `9,9K`（id）/ `9.9K`（en）；`followerCount: 128` → 4 格、tabular 字体特性存在；礼物格 4 格时有 `FittedBox`。
- `test/social/pet_follow_button_test.dart`（新）：两态外观（高 40 / 圆角 12 / 颜色）、乐观更新与回滚、`petFollowStateProvider` 跨实例同步、埋点 `pet_follow` / `pet_unfollow` 属性（`owner_id` = token、缺 token 时无该键）、失败不报埋点。
- `test/profile/visitor_wire_contract_test.dart`（补）：`VisitorProfile.fromJson` 读新三字段、缺键为 null；`ArchiveStats.fromJson` 缺 `followerCount` → null。
- `test/analytics/v112_events_test.dart`：扩表后全绿（`pet_follow` / `pet_unfollow` 通过）。
- 跑：`flutter analyze && flutter test`。

**L1（本地，连 stag 或本地后端）**：真实追更 / 取消、计数与主人 Diary Tab 一致；断网打开访客页落 A15、恢复后「Coba lagi」可加载；删宠后打开落 A14。

**L2（本地 Android 模拟器）**：A1 / A2 / A3（含超频底部 toast）/ A4 / A14 / A15 逐屏截图对照 UI 稿打分，**还原度 ≥ 95%**；记入 Completion Notes。

> 云端只能跑 L0：Completion Notes 必须写「**L1/L2 待本地验收**」，story 不得标 done。

### Previous Story Intelligence（Story 1.1 ~ 1.3）

- 1.1：追更 403 = `blocked-user`（不是关注侧的 409）；分享 token 公开路径不下发 `ownerToken` / `iFollow*`；计数后端只给原数。
- 1.2 / 1.3：与本 story 无直接代码交集。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.2/epics-v1.4.2-social-topic.md#Story 1.4]
- [Source: _bmad-output/planning-artifacts/v1.4.2/追更话题/ui-1-4-2-social-topic-integrated-v1.html#A1、A2、A3、A4、A14、A15 每屏说明；研发执行说明]
- [Source: _bmad-output/planning-artifacts/v1.4.2/追更话题/1-4-2追更宠物·里程碑·话题prd.md#3.1 入口与按钮 / 主人一侧 / 关注关系（失效页）；AC1、AC2、AC11、AC13；§4 FR-153；§7]
- [Source: _bmad-output/planning-artifacts/v1.4.2/architecture-v1.4.2-social-topic-delta.md#§1.2 第 3 / 9 条、D-8、D-23、P-4、P-9 ~ P-12、§5.2]
- [Source: _bmad-output/planning-artifacts/v1.4.2/决策日志-social-topic.md#R-2]
- [Source: petgo_app/CLAUDE.md#i18n、测试、易踩点]

## 待确认 / 发现的规划冲突

1. **PRD「≥10,000 显示 1.2K」与 1.3.4 实际格式不符**：PRD 写「计数格式同 1.3.4（≥10,000 显示 1.2K）」，而 1.3.4 的 `formatCompactCount` 从 1,000 起缩写（1234 → `1,2K`）；且「10,000」本应显示 `10K` 而非 `1.2K`，PRD 示例自相矛盾。本 story 按「同 1.3.4」用 `formatCompactCount`。
2. **埋点名 `pet_follow` / `pet_unfollow` 不符合 App 埋点命名守卫**：守卫要求动作词尾在白名单（过去式），用户关注用的是 `user_followed` / `user_unfollowed`。本 story 按守卫文件里「名字由 PRD 定死，扩表、不改名」的先例扩词尾白名单；若产品愿意改名为 `pet_followed` / `pet_unfollowed`，可免扩表 —— 请主会话确认。
3. **「我拉黑了主人」后打开访客页落哪一屏规划未写**：服务端返回 403 `blocked-user`；UI 只定义了 A14（不存在）与 A15（加载失败）。本 story 落 A14（拍板点 ③）。
4. **UI 稿说明里的头像 emoji「28→26」已是线上值**：`pet_info_card.dart`:64 现为 26，无需改动；照代码。

## Dev Agent Record

### Agent Model Used

Claude

### Debug Log References

- 第 0 步自检：remote `origin` → github.com/DaiHanWei/petgo-platform；`feat/1.4.2-social-topic` 与 origin 同步；`git push --dry-run` 通过；Flutter 3.47.9 / Java 21.0.12 可用。
- 名字核对（对照 1.1 提交 389b99e0）：`POST|DELETE /api/v1/me/pet-follows/{petId}` → `PetFollowResultResponse(iFollow, followerCount)`；`VisitorProfileResponse` 新增 `iFollow` / `iFollowOwner` / `ownerToken`（公开路径 null → NON_NULL 省略）；`ArchiveStatsResponse` / `VisitorStats` 新增 `followerCount`；403 = `blocked-user`、404、422、429 `rate-limited` —— 与本 story「接口契约」一致，无需改契约。
- `flutter analyze`：No issues found。`flutter test`（全量）：+4162 ~1（1 条为存量 skip），All tests passed。
- 复审修复后复跑：`flutter analyze` No issues found；`flutter test`（全量）+4170 ~1，All tests passed。

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created
- **L1/L2 待本地验收**：
  - L1（连 stag / 本地后端）：真实追更 / 取消、Pengikut 计数与主人 Diary Tab 一致；断网打开访客页落 A15、恢复后「Coba lagi」可加载；删宠后打开落 A14；我拉黑主人后深链打开落 A14。（本 story 只动 App，无后端集成测试类。）
  - L2（Android 模拟器）：A1 / A2 / A3（含超频底部 toast 态）/ A4 / A14 / A15 逐屏对照 UI 稿打分（还原度 ≥ 95%）、360dp 小屏礼物格 `FittedBox` 缩放效果 —— 均未做，留本地。
  - L2 待本地确认（复审记录，不改）：统计条 3 格 → 4 格切换时的跳动（stats 到达前 3 格、到达后 4 格）。
- 名字 / 实现修订（对 story 文件的改动）：
  - AC1.8 `petFollowStateProvider` 值类型由 `bool` 改为 `PetFollowOverride{iFollow, followerCount?, epoch}`：访客页 Pengikut 格要随乐观 ±1 与成功写回同步，单靠 bool 无法表达人数；成功后同时失效 stats，覆盖里的人数与重取值一致。
  - 新增 key：按钮 `petFollowButton`（listCompact 为 `petFollowList_{petId}`）、第 4 格 `petInfoFollowerStat` / `petInfoFollowerCount`、A15 屏 `visitorLoadFailed`（A14 沿用 `visitorGone`）。
  - `DiaryHeader` 新参数：`leading`、`followerCount`、`infoCardBottom`（→ `PetInfoCard.bottomAction`）。
- 拍板点复述：① 新建 `PetFollowButton`，`FollowButton` 的 `_profileFull` / `_listCompact` 外观抽成库级函数 `followProfileFullButton` / `followListCompactButton`，用户关注点击逻辑未动；② `followerCount` 缺键 = null → 3 格；③ 403 `blocked-user` 落 A14；④ 追更埋点只在服务端确认成功后报。
- **复审修复（2026-11-04）**：
  - [CONFIRMED] 覆盖表整会话盖住服务端 → 仿 `FollowStateNotifier` 加版本号：`PetFollowOverride.epoch`（全局计数器 `_petFollowEpochCounter`，换号不归零）；`visitorProfileProvider` / `visitorStatsProvider` 发请求前读 `currentEpoch` 贴进快照（`VisitorProfile.followEpoch` / `ArchiveStats.followEpoch`，客户端字段、不来自 JSON）；覆盖只在 `epoch > snapshotEpoch` 时生效（纯函数 `resolvePetFollow` / `resolvePetFollowerCount`）。效果：同会话再进宠物页，新 stats 134 显示 134、新 profile `iFollow=false` 显示 Ikuti；追更成功后 stats 重取，以服务端人数为准，按钮态仍由覆盖保持（profile 不重取）。`PetFollowButton` 新增 `followSnapshotEpoch` / `countSnapshotEpoch`。
  - (a) 埋点拆成 `if / else` 两处字面量 `Analytics.capture('pet_follow', …)` / `('pet_unfollow', …)`；`v112_events_test.dart` 新增断言 `eventNamesInSource()` 含这两个名字（已通过）。
  - (b) 「是不是自己的宠物」同时比对 `petProfileProvider`（当前宠物）与 `petListProvider`（我名下全部宠物）；任一加载中 / 失败按不显示；追更按钮与送礼胶囊共用 `_othersInAppPetId()`。`gift_entry_visibility_test.dart` 的 pump 补 `petListProvider` 覆盖与一帧（宠物列表在档案就绪分支首次被 watch），并补多宠 / 加载中两条。
- 额外：`visitorProfileProvider` 加 `retry: (_, _) => null`（同 bug 20261006-583 的处理）—— Riverpod 3 默认重试期间是 `AsyncLoading`，A14 / A15 要转圈几十秒才出；失败停失败态、重试由 A15 按钮触发。
- 追更成功失效：`visitorStatsProvider`（整族）、`publicProfileProvider(我)`；1.5 的追更宠物列表 provider 在 `pet_follow_button.dart` 留了 `TODO(v1.4.2 Story 1.5)` 调用点。
- 埋点：`pet_follow` / `pet_unfollow` 字面量；属性 `pet_id`、`owner_id`（= `ownerToken`，R-2）、`source: 'pet_visitor'`、`is_following_owner`（= `iFollowOwner`），后两者 / `owner_id` 缺失时省略。`v112_events_test.dart` 的 `allowedSuffixes` 追加 `_follow` / `_unfollow`（附注释）。
- 文案：无新增 arb 键（全部复用），未跑 gen-l10n（无 ARB 改动）。
- 分享 token 落地态：stats 新后端同样下发 `followerCount` → 也显示第 4 格（只人数），无追更按钮。

#### 待确认

1. 埋点名 `pet_follow` / `pet_unfollow` 与守卫过去式惯例不一致（见上文「待确认」2），按 PRD 定死扩词尾表实现；如改名需同步改代码与守卫。
2. 403 `blocked-user` 落 A14（见上文「待确认」3）。
3. 访客页 `iFollowOwner` 取页面加载时快照；若用户在本次浏览期间去主人主页关注 / 取关后回来再追更，埋点 `is_following_owner` 可能是旧值（profile 不重取）。按最保守做法不跨覆盖表推断。

### File List

- petgo_app/lib/app.dart（`resetUserScopedCaches` 登记 `petFollowStateProvider`）
- petgo_app/lib/core/network/api_paths.dart（`mePetFollow`）
- petgo_app/lib/features/social/data/pet_follow_repository.dart（新）
- petgo_app/lib/features/social/domain/pet_follow_state.dart（新）
- petgo_app/lib/features/social/presentation/widgets/pet_follow_button.dart（新）
- petgo_app/lib/features/social/presentation/widgets/follow_button.dart（外观抽函数）
- petgo_app/lib/features/profile/data/timeline_repository.dart（访客档案分类错误、retry null、快照贴追更版本号）
- petgo_app/lib/features/profile/domain/archive_stats.dart（`followerCount`、`followEpoch`）
- petgo_app/lib/features/profile/domain/visitor_profile.dart（三新字段、`followEpoch`、`VisitorLoadError`）
- petgo_app/lib/features/profile/presentation/visitor_archive_view.dart（页头合并、追更按钮、A14 / A15）
- petgo_app/lib/features/profile/presentation/widgets/diary_header.dart（`leading` / `followerCount` / `infoCardBottom`）
- petgo_app/lib/features/profile/presentation/widgets/pet_info_card.dart（第 4 格、`bottomAction`、礼物格 FittedBox）
- petgo_app/lib/features/profile/presentation/growth_archive_page.dart（A4 透传 `followerCount`）
- petgo_app/test/social/pet_follow_button_test.dart（新）
- petgo_app/test/profile/pet_info_card_follower_stat_test.dart（新）
- petgo_app/test/profile/visitor_archive_view_test.dart（补）
- petgo_app/test/profile/visitor_wire_contract_test.dart（补）
- petgo_app/test/analytics/v112_events_test.dart（词尾扩表）
- petgo_app/test/app/user_scoped_cache_registry_test.dart（登记 `petFollowStateProvider`）
- petgo_app/test/gift/gift_entry_visibility_test.dart（复审：petListProvider 覆盖 + 多宠 / 加载中用例）

### Change Log

- 2026-11-04：复审修复 —— 追更覆盖加版本号（服务端新快照接管）、埋点拆字面量、自己的宠物判定加宠物列表；L0 复跑全绿。
- 2026-11-04：Story 1.4 实现（App）—— 访客页追更按钮 + Pengikut 第 4 格 + 页头合并 + 失效两屏（A14 / A15）+ 埋点；L0 全绿，L1/L2 待本地验收。

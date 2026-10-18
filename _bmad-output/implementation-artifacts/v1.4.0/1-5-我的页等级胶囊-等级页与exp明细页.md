# Story 1.5: 「我的」页等级胶囊、等级页与 EXP 明细页（App）

Status: ready-for-dev

> 版本 v1.4.0 · 主题 tailrent-level · 分支 `feat/1.4.0-tailrent-level`
> 口径优先级：`decision-log.md` ① 节 > `Tailrent等级/1-4-0Tailrent等级prd.md` > `architecture-v1.4.0-tailrent-level-delta.md` > UI 稿 `Tailrent等级/ui-1-4-0-tailrent-level-integrated-v1.html`（A6、B1 ~ B8，定稿 2026-10-14；均在 `_bmad-output/planning-artifacts/v1.4.0/`）。
> 前置：**Story 1.4**（`GET /api/v1/me/growth`、`GET /api/v1/me/growth/ledger?cursor=`、`/me` 的 `growth` 摘要；字段名以 1.4 实现为准）。后端 1.1 ~ 1.3 提供数据。
> 范围层：**只 App**（`petgo_app/`）。不改后端。

## Story

As a 用户,
I want 在「我的」页看到自己的等级胶囊，点进去看等级页和完整明细,
so that 我知道自己在哪一级、离下一级还差多少、EXP 是怎么来的。

## Acceptance Criteria

**AC1 — 「我的」页等级胶囊（A6）** `[L0]` `[L2]`（FR-144.2、UX-DR3）
1. Given 真实用户打开「我的」页，When 页面加载，Then 昵称下方、签名上方显示等级胶囊：段位浅色底圆角胶囊，内含段位色实心小胶囊「Lv{n}」（白字、tabular 数字）+ 段位名 + `chevron_right`（UI 稿 `.lvchip`：外 padding 4/10/4/6、圆角 999、12/w600；内 11/w700、padding 2/7）。头像右下相机角标位置不变；「我的」页仍不显示运营标签；本 story **不给「我的」页头像加框**（Epic 3 Story 3.2）。
2. 点胶囊 → `context.push('/me/growth')` 并上报 `growth_level_page_viewed`（`source: 'me_page'`）。
3. 数据源：`/me` 的 `growth` 摘要（Story 1.4）。`growth == null`（不参与 / 旧后端 / 登录响应还没被 `/me` 刷新）→ **不显示胶囊**，不占位、不闪「Lv1」。
4. 胶囊有 `ValueKey('meLevelCapsule')`，Semantics 标签读出「Lv{n} {段位名}」。

**AC2 — 等级页首屏（B1 / B3）** `[L0]` `[L2]`（FR-144.3、UX-DR4）
1. 新页 `features/growth/presentation/level_page.dart`，路由 `/me/growth`（挂 `/me` 前缀 → 自动在 `_controlledLocations` 内，游客重定向）。全局 AppBar 样式（44 高、返回 chevron、标题左对齐 16/w700），标题 `growthLevelPageTitle`（「Tailrent Level」）。
2. 等级卡（段位浅色底）：头像 84（`InitialAvatar`，本 story 不画框不画角标）→ 「Lv{n}」大号 → 段位色圆点 + 「{段位名} · Lv{from}–{to}」（STAR 为「Lv{from}+」）→ 进度条 → 「{a} / {b} EXP · Kurang {n} EXP ke Lv{x}」。
3. 进度条：轨道 12 高圆角，淡染下一级色；填充从「当前级色」渐变到「下一级色」，只露出走过的一段；两端「Lv{n}」实心胶囊 /「Lv{n+1}」虚线胶囊。比例 = `(lifetimeTotal − currentThreshold) / (nextThreshold − currentThreshold)`。
4. 满级（`nextThreshold == null`，B3）：进度条满格、右侧显示「Level maksimal」，左侧显示「{lifetimeTotal} EXP」照常累积。
5. 「Keuntungan level」小节（小节标题沿用 12/w700/大写/`AppColors.muted`）：按 `tierBenefits` 逐段位一组，组头「{段位名} Lv{from}–{to}」；已解锁（当前等级 ≥ fromLevel）每项绿勾 `check_circle` + 组尾「Terbuka」，未解锁置灰 + `lock` + 组尾「Terbuka di Lv{fromLevel}」。**不出现任何优惠券**（AC20；App 只渲染后端下发的权益代码，未知代码忽略不显示）。
6. **头像框一排（C 组）本 story 不渲染**（不放占位、不放不可点的假控件），Story 3.3 接入；位置预留在等级卡与权益之间。
7. 数字按当前语言格式化千分位（印尼语点号：`1.420`）：`NumberFormat.decimalPattern(locale)`。

**AC3 — 等级页下滑（B2）** `[L0]` `[L2]`（AC17）
1. 「Cara dapat EXP」小节，右上「Reset 00.00 WIB」。每条规则一行：图标 + 名称 + 今日进度 + 右侧大号分值。
2. 行的组成（照 UI 稿 B2 自上而下）：`DAILY_LOGIN`（`login`）、`POST_PUBLISHED`（`edit_note`）、`COMMENT_POSTED`（`mode_comment`）、`LIKE_GIVEN`（`favorite`）、`PLACE_CHECKIN`（`location_on`）各一行，进度「Hari ini {todayCount}/{dailyCap}」或满额「Penuh hari ini」，分值「+{points} EXP」；被动三项合成**一行**「Disukai · dikomentari · disimpan」（`volunteer_activism`），进度取 `passiveGroup`「Hari ini {todayEarned}/{dailyCap}」，分值「+1 · +3 · +2 EXP」（按三条规则实际 `points` 拼）；里程碑三项合成一行「Tonggak S · M · L」（分值「+5 · +15 · +25 EXP」）；`GIFT_SENT` / `GIFT_RECEIVED` / `SPEND_DIGITAL` / `SPEND_SHOP` 各一行，分值「+{points} EXP / Rp{unitAmount}」，进度位写「Tanpa batas harian」。后端未下发（停用）的规则不显示；合成行在其中任一规则下发时显示、只拼下发的那几条。
3. 今日进度小条：主动规则按次数比例、被动行按 EXP 比例；`maxedToday` 时显示「Penuh hari ini」（AC17）。
4. 「Riwayat EXP」小节：最近 3 条（`recentLedger`），条目组件与 B6 **同一个**；右上「Lihat semua ›」→ `context.push('/me/growth/history')`。无流水时整节显示一行空态文案（复用 `growthExpEmptyTitle`），不显示「Lihat semua」。

**AC4 — 加载中 / 失败（B4 / B5）** `[L0]`
- Given 等级页加载中或失败，When 网络慢或出错，Then 加载中显示居中 `CircularProgressIndicator`（B4：个人页类页面沿用转圈，不做骨架）；失败用 `EmptyState`（图标 48 + 标题「Gagal memuat level」+ 说明复用 `feedErrorBody` + 按钮「Coba lagi」复用既有「Coba lagi」键）→ 点击重试（B5）。
- 不参与（`participating == false`）：显示同一失败态的标题换为 `growthLevelLoadFailed`，不给重试按钮（正常路径进不来，防御性）。

**AC5 — EXP 明细页（B6 ~ B8）** `[L0]` `[L2]`
1. 新页 `features/growth/presentation/exp_ledger_page.dart`，路由 `/me/growth/history`，标题「Riwayat EXP」。
2. 分组：复用 `features/notify/domain/notification_grouping.dart` 的 `groupOf(createdAt, now, toLocal: …)` 与 `notifyGroup*` 文案（Hari ini / Kemarin / 7 hari terakhir / 30 hari terakhir / Sebelumnya），**按 WIB 自然日**：`toLocal` 传「UTC + 7 小时」的换算（`now` 同样换算），不用手机本地时区；空组不显示。
3. 条目组件 `GrowthLedgerTile`（B2 预览与 B6 共用）：左 36 圆底图标（入账浅紫底、扣回浅红底）、名称 + 时间、右侧增减值（加绿减红，「+{n} EXP」/「−{n} EXP」）。时间：今天 / 昨天组显示 WIB `HH.mm`，更早显示 `d MMM`（印尼语如「1 Okt」）。
4. 名称映射（按代码本地化，**不渲染后端显示串**；只有 `note` 原样）：

   | 条件 | 名称 | 图标 |
   |---|---|---|
   | `delta < 0` 且 `reason ∈ {REFUND, UNLIKE, UNCOLLECT, TAKEDOWN}` | 「Dikurangi · {原因}」（refund / batal suka / batal simpan / konten diturunkan） | REFUND `currency_exchange` · UNLIKE `heart_broken` · UNCOLLECT `bookmark_remove` · TAKEDOWN `remove_circle` |
   | `ruleCode = ADMIN_ADJUST` | 「Penyesuaian admin」（正负都是） | `admin_panel_settings` |
   | `ruleCode = OPS_CAMPAIGN` | 「Event · {note}」（note 为空时只显示「Event」） | `celebration` |
   | `ruleCode = LAUNCH_BACKFILL` | 「Bonus perjalananmu sejauh ini」 | `history` |
   | `DAILY_LOGIN` | 「Login harian」 | `login` |
   | `POST_PUBLISHED` | 「Posting {Diary / Momen / Tips}」（按 `detailCode` = 内容类型；null 时「Posting」） | `edit_note` |
   | `COMMENT_POSTED` / `LIKE_GIVEN` / `PLACE_CHECKIN` | 规则名（同 B2） | 同 B2 |
   | `LIKE_RECEIVED` / `COMMENT_RECEIVED` / `COLLECT_RECEIVED` | 「Postinganmu disukai / dikomentari / disimpan」 | `favorite` / `mode_comment` / `bookmark` |
   | `MILESTONE_S/M/L` | 「Tonggak S / M / L」 | `emoji_events` |
   | `GIFT_SENT` / `GIFT_RECEIVED` | 「Kirim hadiah」/「Terima hadiah」 | `redeem` |
   | `SPEND_DIGITAL` | 「Layanan digital · {用途短名}」（`detailCode` 未知时只显示「Layanan digital」） | `payments` |
   | `SPEND_SHOP` | 「Belanja Toko」 | `shopping_bag` |
   | 未知代码（新规则、旧 App） | `delta > 0` 显示「EXP」通用名，不崩 | `bolt` |

5. 分页：首屏 `GET /me/growth/ledger`，滚到底按 `nextCursor` 加载下一页；`nextCursor == null` 不再请求。
6. 空态（B7）：`EmptyState` 带 80 圆底图标（`iconBackground`，同通知中心空态），标题「Belum ada riwayat EXP」、说明「Login, posting, dan berinteraksi untuk mulai mengumpulkan EXP.」。
7. 首屏失败：同 B5 形态，标题换「Gagal memuat riwayat EXP」+ 重试。加载更多失败（B8）：列表底部一行可点「Gagal memuat lagi · Ketuk untuk coba lagi」（复用 `notifyLoadMoreRetry`），点击重试同一游标，已加载的条目不丢。

**AC6 — 数据层与模型** `[L0]`（§6.3）
1. `features/growth/data/growth_repository.dart`：`GrowthRepository`（dio，照 `PawCoinRepository`）`fetchPage()`、`fetchLedger({String? cursor})`；`ApiPaths` 加 `meGrowth = '$base/me/growth'`、`meGrowthLedger = '$meGrowth/ledger'`。
2. `features/growth/domain/`：`GrowthSummary`（等级页整包）、`GrowthBrief`（`/me` 摘要：level / tier / frame / lifetimeTotal / nextThreshold）、`GrowthLedgerEntry`、`GrowthLedgerPage`、`GrowthTier` 枚举（未知值 → null）。`fromJson` 全部容忍缺字段（新字段可空、旧后端不崩）。
3. `UserProfile`（`login_response.dart`）加 `GrowthBrief? growth`：`fromJson` 与 `copyWith` 同步；`MeCounts`（`me_counts_provider.dart`）带上 `growth`，胶囊读 `meCountsProvider(profile?.id)` 的值并回落 `auth.profile?.growth`（与四格同一取数路径，不新增 `/me` 请求）。
4. Provider：`growthPageProvider`（autoDispose）、明细页列表状态由页面持有或 `growthLedgerProvider`（autoDispose）。**凡是常驻的用户维度 provider 必须登记** `app.dart` 的 `resetUserScopedCaches` 与 `test/app/user_scoped_cache_registry_test.dart` 的 `_mustReset`（autoDispose 且只在页面内使用的可不登记，Completion Notes 说明判断）。

**AC7 — 段位色与每级色（§5.6）** `[L0]`
1. 只在 `core/theme/colors.dart` 定义：段位色 4 个 `tierPemula #66748C`、`tierSahabat #0A7FA3`、`tierPenjaga #2B4FBF`、`tierBintangStart #4A2E9E` / `tierBintangEnd #B5307F`（Bintang 为渐变，纯色场景取 Start）；段位浅色底 4 个 `tierPemulaTint #EEF1F5`、`tierSahabatTint #E1F2F8`、`tierPenjagaTint #E7ECFB`、`tierBintangTint #EFE9FB`；逐级色 20 个（UI 稿 A1 色条）：
   - Pemula Lv1–4：`#C5CCD8 #9CA4B3 #737D8D #4A5568`
   - Sahabat Lv5–9：`#8ED3EA #6CB5CD #4A96B0 #287892 #065A75`
   - Penjaga Lv10–14：`#A8B8F2 #8597DA #6276C2 #3F56A9 #1C3591`
   - Bintang Lv15–20：`#B9A6EE #B38CD3 #AD72B8 #A6589E #A03E83 #9A2468`
2. `growthLevelColor(int level, List<GrowthLevelDef> levels)`（放 `features/growth/domain/` 或 `core/theme/`）：Lv1–20 取上表；后台追加的 Lv21+ 取所属段位的最后一个逐级色（不插值出新色，见 Dev Notes「与架构的出入」）。段位由 `levels` 里的 `tier` 决定，不写死等级区间。
3. 组件、页面里**禁止**硬编码 `Color(0x…)`（`colors.dart` 文件头护栏）。

**AC8 — 文案（EN / ID 全部来自 arb）** `[L0]`（NFR7）
1. `app_id.arb` / `app_en.arb` 新增下表全部键，跑 `flutter gen-l10n`（或项目既有生成方式）更新 `app_localizations*.dart`。
2. 🔴 **键名前缀约定**：现有 27 个 `growth*` 键属于 Diary「成长档案」（`growthArchive*`、`growthLoadFailed`、`growthLoadRetry`、`growthViewTimeline` …），**不得复用、不得混用**。等级线新键一律用子前缀：`growthLevel*`（等级页 / 胶囊 / 进度）、`growthTier*`（段位名）、`growthRule*`（规则名）、`growthExp*`（EXP 明细 / 原因）、`growthBenefit*`（权益）、`growthFrame*`（头像框，Epic 3）、`growthCelebration*` / `growthBackfill*`（Epic 2）。新增前 grep 两个 arb 确认无重名。
3. 复用既有键（不新增）：`feedErrorBody`（失败说明）、`notifyLoadMoreRetry`（加载更多失败）、`notifyGroupToday / Yesterday / Last7Days / Last30Days / Earlier`（分组）、「Coba lagi」（`feedRetry` 或 `notificationLoadRetry`，二选一，与通知中心一致）。UI 稿写的 `commonRetry` **代码里不存在**，不要新建。

**AC9 — 埋点** `[L0]`（FR-AN、V-3）
1. 点胶囊进等级页时 `Analytics.capture('growth_level_page_viewed', {'source': 'me_page'})`；`source` 只取 `me_page`（`celebration` / `notification` 由 Story 2.3 发）。
2. `test/analytics/v112_events_test.dart` 的 `allowedPrefixes`（:217 起）**新增 `'growth_'`** 并按该文件惯例写注释（模块是「Tailrent Level 等级体系」，跨我的页 / 庆祝页 / 通知三处入口；新模块入表，不是为遗留事件放宽规则）；`_viewed` 已在 `allowedSuffixes`（:346 起）。

**AC10 — 质量门槛** `[L0]` `[L2]`
1. `flutter analyze` 0 issue；相关 `flutter test` 绿（含 `test/me/me_page_test.dart` 既有用例）。**不写 golden 截图测试**；**不对整文件跑 `dart format`**（存量代码未格式化，只手工对齐新改行）。
2. 完成后在 **Android 模拟器**打开「我的」页与等级页、明细页给用户看，并按 UI 稿 A6 / B1 ~ B8 逐屏做还原度对照（目标 ≥ 95%）——L2，云端做不了，标「L2 待本地验收」。

## Tasks / Subtasks

- [ ] **T0 开工核对**：确认 Story 1.4 已合入，按其实际 JSON 字段名写 `fromJson`（有出入以后端为准、Completion Notes 记）；核对 Dev Notes「现状」行号
- [ ] **T1 颜色**（AC7）：`colors.dart` 加常量 + `growthLevelColor`
- [ ] **T2 arb**（AC8）：两份 arb 加键 + 生成；grep 防重名
- [ ] **T3 数据层**（AC6）：模型、`GrowthRepository`、`ApiPaths`、`UserProfile.growth`、`MeCounts.growth`、provider（+ 必要时登记换账号清理）
- [ ] **T4 「我的」页胶囊**（AC1）+ 埋点（AC9）
- [ ] **T5 等级页**（AC2 ~ AC4）：等级卡、进度条组件 `GrowthProgressBar`、权益、规则、最近 3 条
- [ ] **T6 明细页**（AC5）：`GrowthLedgerTile`、WIB 分组、分页、空态、失败与加载更多失败
- [ ] **T7 路由**：`app_router.dart` 加 `/me/growth`、`/me/growth/history`（放在 `/me/settings`、`/me/pawcoin` 附近）
- [ ] **T8 测试**（见「测试」）；`flutter analyze`；Completion Notes 标「L2 待本地验收」

## Dev Notes

### 现状（改动前必读，2026-10-18 核 `dev_1.4.0` @ 81005da7）

| 文件 | 现状 | 本 story 改什么 | 必须保持 |
|---|---|---|---|
| `petgo_app/lib/features/me/presentation/me_page.dart` | `MePage.build`（:37-109）ListView：`_ProfileHeadCard` → `_MeStats` → Edit Profil → Hewanku → Aktivitas → Postinganku；`_ProfileHeadCard`（:435-497）：`InitialAvatar` radius 42（:453）+ 右下相机角标（:455-466）→ 昵称 Text（:468-474）→ 签名（:479-492）；`_MeStats`（:134 起）读 `meCountsProvider(profile?.id)`（:142） | 昵称与签名之间插胶囊（需要 `WidgetRef` → `_ProfileHeadCard` 改 `ConsumerWidget` 或由 `MePage` 传 growth 进去） | 相机角标、点头像换头像、不显示邮箱 / 运营标签 |
| `…/features/me/data/me_counts_provider.dart` | `MeCounts`（postCount / likeCount / followingCount / followerCount）+ `fromProfile`；`meCountsProvider` 按 userId family、`retry: null`；已登记 `resetUserScopedCaches`（`app.dart:368`） | `MeCounts` 加 `growth` | family 分实例、失败回落 |
| `…/features/auth/domain/login_response.dart` | `UserProfile`（:33 起）字段到 `followerCount`（:100）；`fromJson`（:102）；`copyWith`（:124） | 加 `GrowthBrief? growth` | 其余字段解析 |
| `…/features/auth/data/me_repository.dart` | `getMe` / PATCH 均返回 `UserProfile.fromJson`（:32、:57-60） | 不改（字段随 fromJson 带上） | — |
| `…/core/router/app_router.dart` | `_controlledLocations` 含 `/me`（:158-161，前缀匹配 :235）；`/me/settings`（:1011）、`/me/pawcoin`（:1013）、`/me/orders`（:1018） | 加两条 GoRoute | 受控路由名单不改 |
| `…/core/network/api_paths.dart` | `me`（:126）、`mePawcoin`（:135） | 加 `meGrowth` / `meGrowthLedger` | — |
| `…/core/theme/colors.dart`（181 行） | 文件头护栏「禁止业务文件硬编码颜色」（:6）；无任何段位色 | 加段位色、浅底、逐级色 | 既有常量 |
| `…/features/pawcoin/data/pawcoin_repository.dart` | dio 分页仓储范例（`fetch({cursor, limit})`） | 照写 | — |
| `…/features/notify/domain/notification_grouping.dart` | `enum NotificationGroup`（:2）、`groupOf(createdAt, now, {toLocal})`（:13）、`localDayDiff`（:25） | 复用，传 WIB `toLocal` | — |
| `…/features/notify/presentation/notification_center_page.dart` | 分组渲染与标签映射范例（:565-590）、加载更多失败行 | 参照 | — |
| `…/shared/widgets/empty_state.dart` | `EmptyState(title, message, icon, actionLabel, onAction, iconBackground …)`（:11-42） | 复用 | — |
| `…/shared/widgets/initial_avatar.dart` | `InitialAvatar`（:11） | 等级页头像复用，不改组件（框 / 角标在 Epic 3 的共享装饰层） | — |
| `…/core/analytics/analytics.dart` | `Analytics.capture(event, props)`（:163）先 `scrub` 再上报 | 调用 | — |
| `…/app.dart` | `resetUserScopedCaches`（:355 起） | 视 provider 生命周期登记 | — |
| `petgo_app/lib/l10n/app_id.arb`（2609 行）/ `app_en.arb` | 已有 `growthArchive*` 等 27 个 Diary 键；`feedErrorBody`（id :1027）、`notifyGroup*`（id :840-844）、`notifyLoadMoreRetry`（id :846）；**无** `commonRetry` | 加新键 | 不改旧键 |
| `petgo_app/test/analytics/v112_events_test.dart` | 事件名守卫：`allowedPrefixes`（:217）无 `growth_`；`allowedSuffixes`（:346）含 `_viewed` | 加 `'growth_'` | — |
| `petgo_app/test/me/me_page_test.dart`、`test/me/me_counts_test.dart` | 既有用例 | 补胶囊用例 | 既有断言 |

### 新增 arb 文案（ID / EN；★ = UI 稿文案表里有；☆ = 屏内出现但表里没给 EN 或表里省略，**暂定，交付时提醒运营复核**）

| 键 | ID | EN | 来源 |
|---|---|---|---|
| `growthLevelPageTitle` | Tailrent Level | Tailrent Level | ★ |
| `growthLevelShort` | Lv{level} | Lv{level} | ★（胶囊 / 进度条端点） |
| `growthLevelTierRange` | {tier} · Lv{from}–{to} | {tier} · Lv{from}–{to} | ★（B1 屏内） |
| `growthLevelTierRangeOpen` | {tier} · Lv{from}+ | {tier} · Lv{from}+ | ★（B3 屏内） |
| `growthLevelProgress` | {a} / {b} EXP | {a} / {b} EXP | ★ |
| `growthLevelToNext` | Kurang {n} EXP ke Lv{x} | {n} EXP to Lv{x} | ★ |
| `growthLevelMax` | Level maksimal | Max level | ★ |
| `growthLevelTotalExp` | {n} EXP | {n} EXP | ☆（B3「45.210 EXP」） |
| `growthLevelUnlocksAt` | Terbuka di Lv{n} | Unlocks at Lv{n} | ★（权益与 Epic 3 头像框共用） |
| `growthLevelLoadFailed` | Gagal memuat level | Couldn't load level | ★ |
| `growthTierNewbie` | Pemula | Newbie | ★ |
| `growthTierBuddy` | Sahabat | Buddy | ★ |
| `growthTierGuardian` | Penjaga | Guardian | ★ |
| `growthTierStar` | Bintang | Star | ★ |
| `growthBenefitSectionTitle` | Keuntungan level | Level perks | ★ |
| `growthBenefitLevelBadge` | Lencana level di avatar | Level badge on avatar | ★ |
| `growthBenefitPetSlot` | +1 slot hewan | +1 pet slot | ★ |
| `growthBenefitFrameBasic` | Bingkai avatar Dasar | Basic frame | ★ |
| `growthBenefitFrameUpgraded` | Bingkai avatar Lanjutan | Advanced frame | ★ |
| `growthBenefitFrameAnimated` | Bingkai avatar Animasi | Animated frame | ★ |
| `growthBenefitGiftsBatch1` | Hadiah eksklusif (batch 1) | Exclusive gifts (batch 1) | ★ |
| `growthBenefitGiftsBatch2` | Hadiah eksklusif (batch 2) | Exclusive gifts (batch 2) | ★ |
| `growthBenefitFeedBoost` | Postingan lebih sering tampil | Posts shown more often | ★ |
| `growthBenefitUnlocked` | Terbuka | Unlocked | ★ |
| `growthRuleSectionTitle` | Cara dapat EXP | How to earn EXP | ★ |
| `growthRuleResetHint` | Reset 00.00 WIB | Resets at 00:00 WIB | ☆（EN 暂定） |
| `growthRuleDailyLogin` | Login harian | Daily login | ★ |
| `growthRulePost` | Posting (Diary, Momen, Tips) | Post | ★ |
| `growthRuleComment` | Komentar | Comment | ★ |
| `growthRuleLike` | Menyukai postingan | Like a post | ★ |
| `growthRuleCheckin` | Check-in tempat | Place check-in | ★ |
| `growthRulePassive` | Disukai · dikomentari · disimpan | Liked · commented · saved | ★ |
| `growthRuleMilestone` | Tonggak S · M · L | Milestone S · M · L | ★ |
| `growthRuleGiftSent` | Kirim hadiah | Send gift | ★ |
| `growthRuleGiftReceived` | Terima hadiah | Receive gift | ★ |
| `growthRuleSpendDigital` | Layanan digital | Digital services | ★ |
| `growthRuleSpendShop` | Belanja Toko | Toko shopping | ★ |
| `growthRuleTodayProgress` | Hari ini {a}/{b} | Today {a}/{b} | ★ |
| `growthRuleMaxedToday` | Penuh hari ini | Maxed today | ★ |
| `growthRuleNoDailyLimit` | Tanpa batas harian | No daily limit | ☆（B2 说明里有 ID，EN 暂定） |
| `growthRulePoints` | +{n} EXP | +{n} EXP | ★（屏内） |
| `growthRulePointsPerAmount` | +{points} EXP / Rp{amount} | +{points} EXP / Rp{amount} | ☆（稿内无此行样式，暂定） |
| `growthExpSectionTitle` | Riwayat EXP | EXP history | ★ |
| `growthExpHistoryTitle` | Riwayat EXP | EXP History | ★（明细页标题） |
| `growthExpSeeAll` | Lihat semua | See all | ★ |
| `growthExpDeltaMinus` | −{n} EXP | −{n} EXP | ★（屏内） |
| `growthExpDeducted` | Dikurangi · {reason} | Deducted · {reason} | ★ |
| `growthExpReasonRefund` | refund | refund | ★ |
| `growthExpReasonUnlike` | batal suka | unlike | ★ |
| `growthExpReasonTakedown` | konten diturunkan | content removed | ★ |
| `growthExpReasonUncollect` | batal simpan | unsave | ☆（表里没有，ID / EN 都暂定） |
| `growthExpAdminAdjust` | Penyesuaian admin | Admin adjustment | ★ |
| `growthExpEvent` | Event · {name} | Event · {name} | ★ |
| `growthExpEventNoName` | Event | Event | ☆ |
| `growthExpBackfill` | Bonus perjalananmu sejauh ini | Your journey so far | ★ |
| `growthExpLikeReceived` | Postinganmu disukai | Your post was liked | ☆（B6 屏内有 ID，EN 暂定） |
| `growthExpCommentReceived` | Postinganmu dikomentari | Your post got a comment | ☆（暂定） |
| `growthExpCollectReceived` | Postinganmu disimpan | Your post was saved | ☆（暂定） |
| `growthExpPostTyped` | Posting {type} | Post · {type} | ☆（B6「Posting Diary」，EN 暂定） |
| `growthExpPostTypeDiary` / `…Moment` / `…Tips` | Diary / Momen / Tips | Diary / Moment / Tips | ☆ |
| `growthExpSpendDetail` | {rule} · {detail} | {rule} · {detail} | ★（B6「Layanan digital · KTP」） |
| `growthExpPurposeIdHd` / `VetConsult` / `AiUnlock` / `Tailsonality` / `PassportSnap` / `BoardingPass` / `TsMatch` / `PetSlotRent` | KTP / Konsultasi / Cek Gejala / Tailsonality / Paspor / Boarding Pass / Tailsonality Match / Slot hewan | KTP / Consultation / Symptom check / Tailsonality / Passport / Boarding pass / Tailsonality match / Pet slot | ☆（除 KTP 外均暂定；未知用途不显示 detail） |
| `growthExpMilestone` | Tonggak {level} | Milestone {level} | ☆ |
| `growthExpGeneric` | EXP | EXP | ☆（未知代码兜底） |
| `growthExpEmptyTitle` | Belum ada riwayat EXP | No EXP history yet | ★ |
| `growthExpEmptyBody` | Login, posting, dan berinteraksi untuk mulai mengumpulkan EXP. | Log in, post and interact to start earning EXP. | ★ ID / ☆ EN（表里 EN 写成「…」） |
| `growthExpLoadFailed` | Gagal memuat riwayat EXP | Couldn't load EXP history | ★ |

- 文案表里属于 Epic 2 / 3 的键（`growthFrame*` 头像框一排与切框 toast、`growthCelebration*` 庆祝、`growthBackfill*` 存量弹窗、通知「Naik ke Lv{n}!」）**本 story 不加**，由对应 story 加。
- 带占位符的键在 arb 里写 `@key.placeholders`（`level` / `n` / `a` / `b` / `x` 为 `int` 或按格式化后的 `String` 传入——千分位已格式化的数字用 `String`）。

### 与架构 / UI 稿的出入（实现照下面，已在交付回复中列出待拍板）

- **逐级色**：架构 §5.6 写「每级颜色由 `growthLevelColor(level, levels)` 插值」，UI 稿 A1 给了 20 个明确色值且说「新增令牌 … 20 级逐级色，需加进 colors.dart」。本 story 以 UI 稿的 20 个常量为准，函数只负责查表与 Lv21+ 兜底。
- **明细分组**：UI 稿 B6 说明写「沿用通知中心 notifyGroup*（五段）」，屏内只画了三段；按五段实现。
- **明细时间**：屏内「Sebelumnya」组里既有「08.00」又有「1 Okt」，口径不一；按「今天 / 昨天 `HH.mm`，更早 `d MMM`」实现。
- **B2 无上限规则行**：屏内只画到被动合计那一行；里程碑 / 送礼 / 消费行的样式是按说明「不限次的写 Tanpa batas harian」推出来的。

### 架构约束

- AD-16 / §5.6：App 不渲染后端显示串（`note` 例外）；段位色只在 `colors.dart`；每级色只用于等级页进度条，角标 / 段位名 / 胶囊一律用段位色。
- NFR7：EN / ID 全部新键；NFR8：段位色白字对比 ≥ 4.5:1（UI 稿已核，`test/a11y/contrast_test.dart` 若有颜色登记惯例，照惯例补段位色）。
- NFR9：别人的成长值明细不对外——他人主页**不加**任何入口。
- 「我的」页属 `IndexedStack` 常驻页：胶囊数据跟随 `meCountsProvider`（已按 userId 分实例 + 已登记换账号清理），不另起常驻 provider。

### 与前后 story 的边界（本 story 不做）

- 不做头像框一排 C1 ~ C5 与 `PUT /frame`（Story 3.3）、头像角标 / 头像框绘制（Story 3.2，含「我的」页头像戴框）。
- 不做升级庆祝 D 组、存量汇总弹窗 E1、通知中心升级条目 F1、`StartupPopupLedger` 新枚举、其余 4 个埋点（Story 2.3）。
- 不改后端；后端字段缺失按可空处理。

### 测试

**验证层级**
- **L0（云端可跑：`flutter analyze` + `flutter test`）**：
  - `test/growth/growth_models_test.dart`：`GrowthSummary` / `GrowthBrief` / `GrowthLedgerPage` 的 `fromJson`（满级 null、未知 tier / ruleCode / benefit 容忍）。
  - `test/growth/growth_level_color_test.dart`：Lv1 / Lv4 / Lv5 / Lv20 取表；Lv21（STAR 追加）取 Bintang 末色；tier 由 levels 决定。
  - `test/growth/level_page_test.dart`（widget，fake repository）：B1 文案「1.420 / 1.800 EXP」「Kurang 380 EXP ke Lv8」「Sahabat · Lv5–9」；满级「Level maksimal」；权益已解锁 / 未解锁「Terbuka di Lv10」、不出现优惠券；规则行「Hari ini 1/3」「Penuh hari ini」、被动行「Hari ini 18/50」与「+1 · +3 · +2 EXP」；最近 3 条 +「Lihat semua」跳 `/me/growth/history`；加载中转圈；失败态点「Coba lagi」重新请求；**不渲染头像框一排**。
  - `test/growth/exp_ledger_page_test.dart`：WIB 分组（固定 `now` 与 UTC 16:30 / 17:30 两条流水落在不同自然日）；名称映射表逐行（扣回原因、运营调整、活动名原样、存量补算、Posting Diary、Layanan digital · KTP、未知代码兜底）；加减号与颜色；分页触底加载第二页、`nextCursor = null` 不再请求；空态；加载更多失败行点击重试同一游标且不丢已加载条目。
  - `test/me/me_page_test.dart` 补：有 growth 显示胶囊（`meLevelCapsule`、「Lv7」「Sahabat」）、点击跳 `/me/growth` 并经 `Analytics.debugCaptureSink` 断言 `growth_level_page_viewed {source: me_page}`；growth 为 null 不显示；相机角标仍在。
  - `test/analytics/v112_events_test.dart`（改 `allowedPrefixes`）与 `test/app/user_scoped_cache_registry_test.dart`（如有新增常驻 provider）保持绿。
- **L1**：无（不涉及后端运行）。
- **L2（本地 Android 模拟器 + 真后端，云端做不了）**：「我的」页胶囊、等级页 B1 / B2 / B3、加载失败 B5（断网）、明细页 B6 / B7 / B8 逐屏对照 UI 稿，还原度 ≥ 95%；印尼语 / 英语各看一遍千分位与文案。标「L2 待本地验收」。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.0/epics-v1.4.0-tailrent-level.md#Story 1.5、UX-DR3 ~ UX-DR5]
- [Source: _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-tailrent-level-delta.md#AD-16、§5.1（App 命名）、§5.6、§6.3、§7 V-3]
- [Source: _bmad-output/planning-artifacts/v1.4.0/Tailrent等级/1-4-0Tailrent等级prd.md#3.2 等级标识 / 等级页 / 段位权益 / AC17 / AC20、§4 埋点]
- [Source: _bmad-output/planning-artifacts/v1.4.0/Tailrent等级/ui-1-4-0-tailrent-level-integrated-v1.html#A1（色值）/ A6 / B1 ~ B8 / 顶部「新增印尼语文案」表]
- [Source: _bmad-output/implementation-artifacts/v1.4.0/1-4-等级页与exp明细接口.md]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

### Change Log

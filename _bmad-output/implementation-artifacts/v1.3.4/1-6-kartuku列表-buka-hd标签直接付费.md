# Story 1.6: Kartuku 列表——Buka HD 标签直接付费

Status: ready-for-dev

> 版本 v1.3.4 · 主题 ktp · 分支 `feat/1.3.4-ktp-revamp`
> 口径优先级：`决策日志-ktp.md` > `PRD-v1.3.4-ktp.md` > `architecture-v1.3.4-ktp-delta.md` > `ui-v1.3.4-ktp.html`（均在 `_bmad-output/planning-artifacts/v1.3.4/`）
> 前置：Story 1.2（`IdCardStyle` 注册表）、Story 1.5（列表页双 Tab 骨架，本 story 填 Kartuku Tab 的内容）。本 story 只改 App，不动后端。

## Story

As a 用户,
I want 在已建的卡列表里一眼看出哪些还没解锁 HD、点一下就能付费,
so that 我不用进详情页也能解锁。

## Acceptance Criteria

**AC1 — Kartuku 有卡态（UI 稿 B3）** `[L0]` `[L2]`
1. 顶部说明句沿用 `idCardListSubtitle`（id「Setiap kartu adalah snapshot tersimpan…」/ en「Each card is a saved snapshot…」，现有 key，文案不改）。
2. 每行（白底、14 圆角、14 内边距，沿用现有 `_cardTile` 尺寸）：
   - 行首 46×46、12 圆角、`AppColors.mintTint` 底 + `AppColors.mint` 色**线性证件图标**（24px），取代现有 🪪 emoji（`id_card_page.dart:144`）；图形以 UI 稿 B3 的 SVG 为准，可用 Material outlined 图标近似（如 `Icons.badge_outlined`）；
   - 宠物名（15 / w700，单行省略）；
   - 「{卡种名} · No. {编号}」（12 / `ink2`，单行省略）：卡种名取 `IdCardStyle.fromCard(card)` 的 l10n 名称（8 款各自名称：KTP / Paspor / Kartu Izin Tinggal / SIM / Pelajar Bintang / Malam / Ceria / Klasik），**删除** `:123-127` 的三分支 switch；编号 = `card_no` **原样**（SIM / 居住证不做格式化，FR-142E.7），为空时 `serialId` 左补零 4 位，`serialId` 也为空显示 `----`（保持 `:119-121` 现口径）；编号部分用等宽数字（`FontFeature.tabularFigures()`）；
   - 「Dibuat {日期}」沿用 `idCardCreatedLabel` + `formatDayMonthYear`（`createdAt` 为空不显示，同现状）；
   - 行尾 `chevron_right`。
3. 顺序 = 接口返回顺序（后端 `findByUserIdOrderByCreatedAtDesc` 已是倒序），App 不再排序。
4. 底部**吸底**主按钮「Buat Kartu Baru」/「Create New Card」（品牌紫 `AppColors.mint` 实心、带 `+` 图标、带文字，`ValueKey('idCardCreateNewCta')`）：点击**切到 Pilih Kartu Tab**（不再 `push('/profile/id-cards/create')`）。
5. **删除**列表顶部的「Kartu Baru」入口行 `_createEntry`（`id_card_page.dart:85-115`）及其调用（`:39`）。

**AC2 — 「Buka HD」标签（FR-142B.8）** `[L0]`
1. `hdUnlocked == false` 的行，在文字列与 chevron 之间挂紫色胶囊标签：`AppColors.mint` 底、白字 10.5 / w700、全圆角、左侧 12px 锁形线性图标（UI 稿 `.buka`）；文字用 `l10n.idCardUnlockHd`（见 Dev Notes「文案边界」）。`ValueKey('idCardBukaHd_${card.id}')`。
2. `hdUnlocked == true` 的行**不挂任何标签**——删除现有「✓ HD」徽标（`id_card_page.dart:169-184`）。`idCardHdUnlockedBadge` 若全仓再无引用，从 `app_en.arb` / `app_id.arb` 删除并重新生成 l10n。
3. 标签**独立热区 ≥ 44×44**（视觉胶囊可小于 44，外包 `SizedBox`/`ConstrainedBox(minWidth: 44, minHeight: 44)` + 独立 `InkWell`/`GestureDetector(behavior: opaque)`），点击不冒泡到整行 `InkWell`。

**AC3 — 点标签直接弹付费抽屉（FR-142B.9，UI 稿 E5）** `[L0]` `[L2]`
1. 点「Buka HD」标签 → 调共享函数 `openIdCardHdPaywall(context, ref, card, entry: IdCardPaywallEntry.listBadge)`（AC5），**不进详情页**；抽屉叠在列表上，内容与现有 E1 完全相同（`HdPaywallSheet`，头部「{宠物名} · No. {原样 card_no}」，价格读 `idCardHdPriceProvider` = `pricing_config.id_hd_download_price`，8 款同价、客户端不硬编码）。
2. 关闭抽屉（下滑 / 点遮罩，未选渠道）→ 留在列表，不刷新、无 toast。
3. PawCoin 当场成功 → 留在列表、`idCardListProvider` 刷新、该卡标签消失 + toast `idCardHdUnlockedToast`。
4. QRIS：沿用现状进 `showQrPaymentSheet`（等待支付 / Cancel Payment）；轮询到账 → 关闭二维码页后同 3（留在列表、刷新、标签消失、toast）；用户关掉二维码页未到账 → 留在列表、刷新列表、无成功 toast。
5. 余额不足（409）→ toast `idCardHdInsufficientBalance`；其他失败 → `idCardHdPurchaseError`；后端返回既非 unlocked 也无 payload → `idCardHdQrisPending`（三条均沿用现有文案与判定）。断网价格失败态由 `HdPaywallSheet` / `PayChannelPicker` 自带（E3，沿用）。
6. 付费流程进行中，同一列表上的「Buka HD」标签不可再次触发（防连点重复下单）。
7. 点行其余位置（含宠物名、编号、chevron、行首图标）→ `context.push('/profile/id-cards/${card.id}')` 进卡详情（同现状）。

**AC4 — 空态 / 加载 / 失败（UI 稿 B2 / B4 / B5）** `[L0]`
1. 空态（B2）：72×72 紫浅底圆形（`mintTint` 底 + 1px `lineViolet` 内描边）+ 34px 线性证件图标（`mint` 色），取代 🪪（`:58`）；标题 `idCardListEmptyTitle`、说明 `idCardListEmptyBody`（文案不改）；吸底主按钮「Buat Kartu Baru」（与 AC1.4 同一组件、同 key），点击切到 Pilih Kartu Tab。
2. 加载中（B4）：居中 `CircularProgressIndicator`（沿用）。
3. 失败（B5）：替换现有纯文字 `_centered(l10n.idCardLoadError)`（`:25`）为——72×72 白底灰描边圆形 + 断网线性图标（`muted` 色，如 `Icons.cloud_off_outlined`）+ `idCardLoadError` 文案 +「Coba lagi」**描边**按钮（`commonRetry`，高 44，`mint` 字、1.5px `lineViolet` 描边，`ValueKey('idCardListRetry')`）；点击 `ref.invalidate(idCardListProvider)` 重新拉取，重拉期间显示 B4 转圈（见 Dev Notes「重试时的加载态」）。
4. 付费成功后的列表刷新**不得**闪成整页转圈（保留旧数据直到新数据回来）。

**AC5 — 付费共享函数 `openIdCardHdPaywall`（AD-10）** `[L0]`
1. 新文件 `petgo_app/lib/features/profile/presentation/id_card/id_card_hd_flow.dart`，公开 API（名字照跨 story 约定，**不得另起**）：
   ```dart
   enum IdCardPaywallEntry { create, detail, listBadge } // wire: create / detail / list_badge
   Future<IdCardHdFlowResult> openIdCardHdPaywall(
       BuildContext context, WidgetRef ref, IdCard card, {required IdCardPaywallEntry entry});
   ```
   `IdCardPaywallEntry` 带 `String get wire`；`IdCardHdFlowResult` 至少能区分：`dismissed`（抽屉未选渠道关闭）、`unlocked`（PawCoin 当场成功或 QRIS 轮询到账）、`notCompleted`（QRIS 二维码页关闭未到账 / pending 文案分支）、`failed`（请求失败）。
2. 函数内完成（合并 `id_card_create_page.dart:358-419` 与 `id_card_detail_page.dart:297-378` 两份逻辑，取二者之并）：
   - 读 PawCoin 余额：`(await ref.read(pawCoinProvider.future)).balance`，失败按 0；
   - 弹窗期间 `ref.listenManual(idCardHdPriceProvider, …)` 保活定价（autoDispose），关窗后读 `priceIdr` 再 `close()`；
   - 埋点 `ktp_unlock_paywall_shown` → 弹 `HdPaywallSheet`（参数同现状：`petName` / `serialId` / `cardNo` / `avatarUrl` / `balance`，**抽屉组件本身不改**）；
   - 返回 null → 上报 `id_card_paywall_dismissed` → 返回 `dismissed`；
   - 选定渠道 → `ktp_unlock_started` → `purchaseHdForCard(card.id, channel)`；
   - `unlocked`：`invalidate(idCardDetailProvider(card.id))`、`invalidate(idCardListProvider)`、`invalidate(pawCoinProvider)`（沿用详情页 bug 20260806 的余额失效，建卡页现漏了这步，借此收口）、toast `idCardHdUnlockedToast` → 返回 `unlocked`；
   - 有 `payload`：`showQrPaymentSheet(pollPaid: refresh(idCardDetailProvider(card.id)).hdUnlocked)`；到账 → `Analytics.capturePurchase(amountIdr: priceIdr, purpose: 'ID_HD', orderRef: res.paymentRef)`（投放归因，仅 QRIS）+ 同上失效 + toast → `unlocked`；未到账 → `invalidate(idCardListProvider)` → `notCompleted`；
   - 无 payload → toast `idCardHdQrisPending` → `notCompleted`；
   - `DioException` → `KtpUnlockAnalytics.failedFromError(...)` + 409/其他 toast → `failed`；其他异常 → `idCardHdPurchaseError` → `failed`；
   - 所有 `await` 之后先查 `context.mounted`；toast 用 `ScaffoldMessenger.of(context)`。
3. **落点不在函数里**：函数只负责「打开 + 付费 + 失效 + toast + 埋点」，不 `pop` / `push` / 导出；调用方按返回值决定落点。列表（本 story）= 什么都不做（留在列表）；`detail`（导出 HD）与 `create`（`pushReplacement` 到新卡详情）由 Story 1.8 在接入时处理。
4. 本 story **只在列表接入**；`id_card_create_page.dart` 的 `_openPayment` 与 `id_card_detail_page.dart` 的 `_openHdPaywall` / `_purchaseHd` **保持原样不动**（切换到共享函数是 Story 1.8 的范围）。

**AC6 — 埋点（PRD §4、AD-12）** `[L0]`
1. `KtpUnlockAnalytics`（`id_card/ktp_unlock_analytics.dart`）新增 `static const String entryListBadge = 'list_badge'`；`paywallShown` / `started` / `failedFromError` 增加**可选**命名参数 `String? cardType`、`String? cardVariant`，非空时作为 `card_type` / `card_variant` 属性上报（null 不带键，同 `price_idr` 写法）。可选参数保证建卡 / 详情两处旧调用不改也能编译、行为不变。
2. 共享函数内三个 `ktp_unlock_*` 事件一律带 `entry = entry.wire`、`card_type = IdCardStyle.fromCard(card).cardType`、`card_variant = …cardVariant`（学生卡外为 null → 不带键）。
3. 新事件 `id_card_buka_hd_tapped{card_type, entry}`：`entry != create` 时在共享函数开头上报（列表标签点击 = `list_badge`；Story 1.8 详情按钮 = `detail`；建卡后自动弹出不报）。
4. 新事件 `id_card_paywall_dismissed{card_type, entry}`：付费抽屉返回 null（未选渠道关闭）时上报，本 story 为 `entry = list_badge`。
5. 🔴 仍**不在 App 端上报 `ktp_unlock_succeeded`**（成功只由服务端报，`ktp_unlock_analytics_test.dart:77` 守护；该测试改为同时扫描 `id_card_hd_flow.dart`）。
6. 新事件名过 `test/analytics/v112_events_test.dart` 的命名规则：前缀 `id_card_` 已登记；后缀 `_click` / `_close` 不在 `allowedSuffixes` 里——见 Dev Notes「埋点命名与 v112 规则冲突」，按其中默认做法处理并保证测试绿。

**AC7 — 测试与回归** `[L0]`
1. `test/profile/id_card_page_test.dart` 按新结构重写（现有两个用例断言「建卡入口行 key」与「已解锁 HD 徽标」，均会被本 story 打破），覆盖：
   - 有卡：说明句、卡种名取注册表（KTP / Paspor / 一款新卡种 / 学生卡配色各一行）、`card_no` 原样（SIM 卡不被格式化）、无 `card_no` 的旧卡显示 `No. 0012`；
   - 未解锁行有 `idCardBukaHd_<id>`、已解锁行没有；页面不再有「HD」徽标；
   - 吸底 `idCardCreateNewCta` 存在，点击后切到 Pilih Kartu（以 Story 1.5 的 Tab 断言方式为准）；
   - 点标签 → 出现 `HdPaywallSheet`，且**没有**路由到详情；下滑关闭 → 仍在列表；
   - 假仓库 `purchaseHdForCard` 返回 `unlocked: true` → 列表重拉后该行标签消失、出现 `idCardHdUnlockedToast` 文案；
   - 点行其余位置 → 跳详情（用 `GoRouter` 测试壳或断言 `push` 目标）；
   - 空态：圆形图标 + 标题 + `idCardCreateNewCta`；失败态：`idCardLoadError` + `idCardListRetry`，点击后仓库 `listCards` 被再次调用、成功后渲染列表。
   - 测试里 `pawCoinProvider` 需 override（避免真 dio）；`hdPrice` 走假仓库。
2. 新增 `test/profile/id_card_hd_flow_test.dart`：`IdCardPaywallEntry.wire` 三值；挂 `Analytics.debugCaptureSink` 断言列表入口一次完整流程的事件序列与属性——`id_card_buka_hd_tapped{card_type, entry: list_badge}` → `ktp_unlock_paywall_shown{entry: list_badge, card_type, …}`；关闭 → `id_card_paywall_dismissed{card_type, entry: list_badge}`；选 PawCoin → `ktp_unlock_started{entry: list_badge, method: PAWCOIN, card_type, …}`；学生卡带 `card_variant`、非学生卡不带该键；`entry = create` 时不报 `id_card_buka_hd_tapped`。
3. `test/profile/ktp_unlock_analytics_test.dart`：补 `entryListBadge` 与 `card_type` / `card_variant` 可选属性用例；「两个付费入口都接了…」用例保持对建卡 / 详情两页的断言**不变且通过**（两页本 story 不改），另加一条断言 `id_card_hd_flow.dart` 接了 `paywallShown(` / `started(` / `failedFromError(` 且 entry 来自参数；「App 端没有 `ktp_unlock_succeeded`」扫描范围加上 `id_card_hd_flow.dart`。
4. `test/keepsake/keepsake_contract_test.dart` 的「KTP HdPaywallSheet 回归」组**原样通过**（抽屉不改）。
5. `test/analytics/v112_events_test.dart`、`v116_events_test.dart` 通过。
6. `flutter analyze` 零告警、`flutter test` 全绿（L0）；L2 真机走一遍 B2 / B3 / B5 / E5（PawCoin 与 QRIS 各一次）。

## Tasks / Subtasks

- [ ] Task 1：共享付费函数（AC5、AC6）
  - [ ] 1.1 新建 `id_card/id_card_hd_flow.dart`：`IdCardPaywallEntry`（含 `wire`）、`IdCardHdFlowResult`、`openIdCardHdPaywall`
  - [ ] 1.2 `KtpUnlockAnalytics` 加 `entryListBadge` 与 `cardType` / `cardVariant` 可选参数
  - [ ] 1.3 `id_card_buka_hd_tapped` / `id_card_paywall_dismissed` 上报（放置位置见 Dev Notes「埋点放哪」）
  - [ ] 1.4 处理 v112 后缀规则（Dev Notes「埋点命名与 v112 规则冲突」）
- [ ] Task 2：Kartuku 有卡态（AC1、AC2、AC3）
  - [ ] 2.1 行首证件图标、卡种名取注册表、编号等宽、删 HD 徽标、删 `_createEntry`
  - [ ] 2.2 「Buka HD」标签 + 独立 44 热区 + 防重入
  - [ ] 2.3 吸底「Buat Kartu Baru」→ 切 Pilih Kartu
  - [ ] 2.4 l10n：新增 `idCardCreateNewCta`（en / id），删除无引用的 `idCardCreateEntry` / `idCardHdUnlockedBadge`，`flutter gen-l10n`
- [ ] Task 3：空态 / 加载 / 失败（AC4）
- [ ] Task 4：测试（AC7）
  - [ ] 4.1 重写 `id_card_page_test.dart`
  - [ ] 4.2 新增 `id_card_hd_flow_test.dart`
  - [ ] 4.3 更新 `ktp_unlock_analytics_test.dart`
  - [ ] 4.4 `flutter analyze` + `flutter test` 全绿

## Dev Notes

### 现状（改动前必读）

- **列表页** `petgo_app/lib/features/profile/presentation/id_card_page.dart`（`ConsumerWidget`，Story 1.5 会改成双 Tab，本 story 改的是 Kartuku Tab 里的内容）：
  - `:19-27` `ref.watch(idCardListProvider).when(...)`：`:24` 加载转圈；**`:25` 失败只渲染一行 `idCardLoadError` 文字，无法重试**；`:26` 空 → `_empty`，否则 `_list`。
  - `:31-48` `_list`：说明句 `idCardListSubtitle` → `_createEntry`（顶部「Kartu Baru」入口行）→ 逐卡 `_cardTile`。
  - `:50-83` `_empty`：🪪 emoji（`:58`）+ 空态标题 / 说明 + 绿色 `FilledButton.icon`「Kartu Baru」，`push('/profile/id-cards/create')`；与 `_createEntry` 共用 `ValueKey('idCardCreateEntry')`。
  - `:85-115` `_createEntry`：紫浅底入口行，同样 push 建卡页。
  - `:117-192` `_cardTile`：`:119-121` 编号口径（`cardNo` 优先，否则 `serialId` 补零 4 位，都无 `----`）——**保持**；`:123-127` 卡种名只认三种（`PASSPORT` / `STUDENT` / 其余归 KTP）——**改为注册表**；`:144` 行首 🪪；`:169-184` 已解锁时挂「✓ HD」徽标（逻辑与本 story 相反）；整行一个 `InkWell` → `push('/profile/id-cards/${card.id}')`。
- **建卡页付费** `id_card_create_page.dart:358-419` `_openPayment`：余额 → 保活定价 → `paywallShown(entryCreate)` → `HdPaywallSheet` → `started` → `purchaseHdForCard`；PawCoin 成功只 `invalidate(idCardListProvider)` + toast（**未失效 `pawCoinProvider`**）；QRIS 到账只 `capturePurchase` + 失效列表（**无成功 toast**）；`:354` 无论结果都 `context.pop()` 回列表。
- **详情页付费** `id_card_detail_page.dart:297-330` `_openHdPaywall` + `:332-378` `_purchaseHd`：同样的打开流程（`entryDetail`）；成功失效详情 / 列表 / `pawCoinProvider`、toast，**并自动 `_exportHd()`**；QRIS 到账同样 toast + 导出；有 `_hdBusy` 防重入。
- 两份逻辑除落点（建卡回列表 / 详情导出）外的差异都是遗漏，共享函数取并集（失效余额、QRIS 到账 toast 都做）。
- **抽屉** `id_card/hd_paywall_sheet.dart`：`HdPaywallSheet({petName, serialId, cardNo, avatarUrl, required balance})`，返回 `HdPayChannel?`；价格 `ref.watch(idCardHdPriceProvider)`、失败重试由 `PayChannelPicker` 自带；头部编号口径与列表 `:119` 相同（原样 `cardNo`）。**本 story 不改**。
- **仓库** `data/id_card_repository.dart`：`idCardListProvider`（`:168`，`FutureProvider`，非 autoDispose）、`idCardDetailProvider`（`:173`，family）、`idCardHdPriceProvider`（`:154`，autoDispose）、`purchaseHdForCard(cardId, channel)` → `HdPurchaseResult{unlocked, payload, paymentRef}`。后端列表 `findByUserIdOrderByCreatedAtDesc` 已倒序。
- **二维码** `shared/widgets/qr_payment_sheet.dart:36` `Future<bool> showQrPaymentSheet(context, {required payload, required pollPaid, orderRef, onAborted})`：到账 true、用户取消 false。
- **余额** `features/pawcoin/presentation/pawcoin_controller.dart:81` `pawCoinProvider`（`AsyncNotifierProvider`），`.future` 取 `PawCoinState.balance`。
- **埋点** `id_card/ktp_unlock_analytics.dart`：只有 `entryCreate` / `entryDetail`；三个方法无卡种属性；类注释写明「成功只由服务端报」。`core/analytics/analytics.dart` 的 scrub 黑名单不含 `card_type` / `card_variant` / `entry`，可直接上报。
- **路由** `core/router/app_router.dart:836-838`：`/profile/id-cards/create`、`/profile/id-cards/:id`。
- **l10n**（`lib/l10n/app_en.arb` / `app_id.arb`）：`idCardLoadError`（id「Gagal memuat kartu. Coba lagi.」）、`commonRetry`（id「Coba lagi」/ en「Try again」）、`idCardListSubtitle`、`idCardListEmptyTitle` / `Body`、`idCardCreatedLabel`、`idCardSerialLabel`、`idCardHdUnlockedToast`、`idCardHdQrisPending`、`idCardHdInsufficientBalance`、`idCardHdPurchaseError` 均已存在；`idCardCreateEntry`（id「Kartu Baru」/ en「New ID Card」）与 `idCardHdUnlockedBadge`（「HD」）只被 `id_card_page.dart` 引用；`idCardUnlockHd` 现值 id「Buka unduhan HD」/ en「Unlock HD download」。
- **测试位置**：`test/profile/id_card_page_test.dart`（`_FakeIdCardRepo` 可复用，两个用例会被打破）、`test/profile/ktp_unlock_analytics_test.dart`（`:85` 用例按源码扫描建卡 / 详情两页的 entry 计数——本 story 不改两页，它应继续通过）、`test/keepsake/keepsake_contract_test.dart:58` 抽屉回归组、`test/analytics/v112_events_test.dart`（前缀 `:277` 已有 `id_card_`；后缀白名单 `:330` 起）。

### 架构约束

- AD-6：卡种名、`card_type` / `card_variant` 一律从 `IdCardStyle`（`domain/id_card_style.dart`，Story 1.2）取，不在页面里写 switch。
- AD-10：付费打开与成功处理只有一份实现（`openIdCardHdPaywall`），落点由调用方按 entry 决定；列表 `list_badge` = 留在列表、成功刷新 + toast。
- AD-12 / D-7：`ktp_unlock_*` 补 `card_type` / `card_variant`；成功事件只由服务端报（服务端补属性是 Story 1.8）。
- NFR3 / FR-142F.7：价格只来自 `pricing_config`，客户端不写死。
- 不改 `HdPaywallSheet` / `PayChannelPicker` / `showQrPaymentSheet`（UX-DR8「抽屉内容不改」）。

### 文案边界

- 「Buka HD」标签复用 `idCardUnlockHd`。该 key 改值（id「Buka HD」/ en「Unlock HD」）按跨 story 约定归 **Story 1.8**；若 1.6 先于 1.8 单独提测，标签会暂显「Buka unduhan HD」——见「待确认」。
- 「Buat Kartu Baru」新增 key `idCardCreateNewCta`：id「Buat Kartu Baru」/ en「Create New Card」（epics「新增文案」+ D-11）。不复用 `idCardCreateEntry`（值是「Kartu Baru」/「New ID Card」，与定稿文案不同）。

### 与 Story 1.5 的接口

- 1.5 负责 Tab 骨架、默认 Pilih Kartu、`id_card_tab_viewed`。本 story 的 Kartuku 内容需要一个「切到 Pilih Kartu」的回调（如 Tab 内容组件接收 `VoidCallback onCreateNew`，由页面传入 `tabController.animateTo(0)`）；以 1.5 落地的结构为准，不另造路由。
- 从详情页返回后停在哪个 Tab 由 1.5 保证，本 story 不处理。

### 埋点放哪

`id_card_buka_hd_tapped` / `id_card_paywall_dismissed` 属 `id_card_` 模块（与 `ktp_` 付费漏斗刻意分开，见 `v112_events_test.dart:318` 注释），**不要**塞进 `KtpUnlockAnalytics`。若 Story 1.5 已建身份证模块埋点 helper（承载 `id_card_tab_viewed` / `id_card_style_tapped`），加到那里；否则在 `id_card_hd_flow.dart` 内写两个私有上报函数，Story 1.8 复用同一文件即可。

### 埋点命名与 v112 规则冲突

`v112_events_test.dart` 要求事件名以 `allowedSuffixes` 中的动词过去式结尾；`id_card_buka_hd_tapped` 的 `_click`、`id_card_paywall_dismissed` 的 `_close` 都不在表里（同表已有语义相同的 `_tapped` / `_dismissed`，如 `toko_payment_sheet_dismissed`）。PRD §4 末尾写「具体落入埋点方案时与现有命名对齐」，epics 则直接写了这两个名字。**默认做法**：按 epics 原名实现，在 `allowedSuffixes` 加 `_click` / `_close`（若 Story 1.5 已为 `id_card_style_tapped` 加过 `_click` 则只加 `_close`），注释写「名字由 PRD §4 定死，扩表、不改名」（沿用该文件先例）。是否改名为 `id_card_buka_hd_tapped` / `id_card_paywall_dismissed` 已列「待确认」，改名只需改共享函数里两处字符串与测试。

### 重试时的加载态

`idCardListProvider` 非 autoDispose；`AsyncValue.when` 默认在「刷新」时保留上一个状态（错误态刷新会继续显示错误页）。重试按钮触发的重拉要显示转圈（B4）：可对错误态判断 `isLoading` 或用 `skipLoadingOnRefresh: false`；但付费成功后的静默刷新要保留旧列表（AC4.4），两者不要用同一个开关一刀切。

### 本 story 不做

- 不改建卡页 `_openPayment`、详情页 `_openHdPaywall` / `_purchaseHd`，不改「建卡后 pushReplacement 到详情」与「详情页按钮文案」——都在 **Story 1.8**（届时两处切到 `openIdCardHdPaywall`，并更新 `ktp_unlock_analytics_test.dart:85` 的源码扫描用例）。
- 不改 `idCardUnlockHd` 的值（Story 1.8）。
- 不做 Tab 骨架、样式网格、`new` 角标、`id_card_tab_viewed`（Story 1.5）。
- 不改付费抽屉内容、价格、QRIS 等待页、服务端 `ktp_unlock_succeeded` 属性（Story 1.8）。
- 不做迷你卡面缩略图（PRD 已取消）。
- 不改后端。

### 测试

- L0：`cd petgo_app && flutter analyze && flutter test`（重点 `test/profile/id_card_page_test.dart`、新增 `test/profile/id_card_hd_flow_test.dart`、`test/profile/ktp_unlock_analytics_test.dart`、`test/keepsake/keepsake_contract_test.dart`、`test/analytics/v112_events_test.dart`）。
- 测试壳：`ProviderScope(overrides: [idCardRepositoryProvider.overrideWithValue(fake), pawCoinProvider.overrideWith(...)])`；`hdPrice()` 同步返回避免 timer 残留（现有假仓库已这么做）；点行跳详情需 `MaterialApp.router` + 测试用 `GoRouter`。
- L2：真机 / 模拟器验 B2 / B3 / B5 视觉、标签热区与整行点击互不干扰、PawCoin 成功后标签消失 + toast、QRIS 到账后同样、关闭二维码页后列表刷新。

### References

- [Source: _bmad-output/planning-artifacts/v1.3.4/epics-v1.3.4-ktp.md#Story 1.6]
- [Source: _bmad-output/planning-artifacts/v1.3.4/PRD-v1.3.4-ktp.md#3.2 Created Tab / #3.6 FR-142F / #4 数据埋点需求]
- [Source: _bmad-output/planning-artifacts/v1.3.4/architecture-v1.3.4-ktp-delta.md#AD-6 / AD-10 / AD-12]
- [Source: _bmad-output/planning-artifacts/v1.3.4/决策日志-ktp.md#D-7 / D-11]
- [Source: _bmad-output/planning-artifacts/v1.3.4/ui-v1.3.4-ktp.html#B2 / B3 / B4 / B5 / E5]

## 待确认项裁定（2026-10-12，权威，覆盖本文件上文的对应写法）

> 依据 `_bmad-output/planning-artifacts/v1.3.4/决策日志-ktp.md` D-13 ~ D-19 与技术裁定 T-1 ~ T-22。Completion Notes 里原列的「待确认」均已按此关闭。

- D-13：事件改名为 `id_card_buka_hd_tapped` / `id_card_paywall_dismissed`，**不放宽**命名白名单
- T-7：`idCardUnlockHd` 改值（id「Buka HD」/ en「Unlock HD」）在本 story 完成
- T-8：`id_card_paywall_dismissed` 仅抽屉未选支付方式关闭时报，列表入口 `entry = list_badge`
- T-21：QRIS 未付关闭二维码只刷新列表
- D-14：从 Kartuku 进详情再返回仍停在 Kartuku

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

- 待确认：`idCardUnlockHd` 改值（→「Buka HD」/「Unlock HD」）按约定在 Story 1.8；本 story 的列表标签复用该 key，若 1.6 单独提测会暂显「Buka unduhan HD」。是否把改值提前到 1.6（1.8 只验收详情页）？
- 待确认：事件名 `id_card_buka_hd_tapped` / `id_card_paywall_dismissed` 不符合 `v112_events_test` 的后缀规则（已有同义 `_tapped` / `_dismissed`）。默认按 epics 原名并扩后缀白名单；或按 PRD §4「与现有命名对齐」改为 `id_card_buka_hd_tapped` / `id_card_paywall_dismissed`（Story 1.5 的 `id_card_tab_viewed` / `id_card_style_tapped` 同类问题）。
- 待确认：PRD §4 `id_card_paywall_dismissed` 的 `entry` 只列 create / detail，epics Story 1.6 要求列表入口也报 `entry = list_badge`；本 story 按 epics 报。
- 待确认：`id_card_paywall_dismissed` 的「未付款关闭」本 story 只指付费抽屉未选渠道就关；选了 QRIS 后关掉二维码页未到账**不**报该事件（PRD 未定义）。
- 待确认：QRIS 二维码页关闭未到账时，本 story 刷新列表但不提示；建卡页现状同此、详情页现状亦无提示，沿用。

### File List

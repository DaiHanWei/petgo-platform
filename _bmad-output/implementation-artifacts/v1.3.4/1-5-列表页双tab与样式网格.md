# Story 1.5: 身份证列表页双 Tab 与「Create new」样式网格

Status: ready-for-dev

> 版本 v1.3.4 · 主题 ktp · 分支 `feat/1.3.4-ktp-revamp`
> 口径优先级：`决策日志-ktp.md` > `PRD-v1.3.4-ktp.md` > `architecture-v1.3.4-ktp-delta.md` > `ui-v1.3.4-ktp.html`（均在 `_bmad-output/planning-artifacts/v1.3.4/`）
> 前置：Story 1.2（`IdCardStyle` 注册表、4 款学生卡卡面）、Story 1.3（SIM / 居住证卡面）已完成。

## Story

As a 用户,
I want 一进身份证页就看到 8 款卡的样子并选一款,
so that 我知道能做什么卡、直接去做。

## Acceptance Criteria

**AC1 — 双 Tab 骨架与默认 Tab** `[L0]`
1. `IdCardPage`（`petgo_app/lib/features/profile/presentation/id_card_page.dart`）改为 `ConsumerStatefulWidget`，应用栏标题仍 `idCardTitle`；应用栏下方两个 Tab：「Pilih Kartu」/「Kartuku」（en：**Create new** / **My Cards**，D-11）。新 l10n key：`idCardTabStyles`（Pilih Kartu / Create new）、`idCardTabMyCards`（Kartuku / My Cards），`app_id.arb` 与 `app_en.arb` 同时加，跑 `flutter gen-l10n`。
2. Tab 外观按 UI 稿 B1：高 46、底色 `AppColors.cream`、底部 1px `AppColors.line` 分隔线；标签 14 / w600，选中 `AppColors.ink` + 2.5px `AppColors.mint` 下划线，未选 `AppColors.muted`；两 Tab 平分宽度。Tab key：`idCardTab_style`、`idCardTab_created`。
3. 每次进入页面（从聚合页 `PetInsightsRoutes.idCard`、从时间线「发了一张身份证」条目、旧路径 `/profile/id-card` 重定向、任何其他入口）都停在 **Pilih Kartu**，不记忆上次的 Tab（FR-142B.1）。
4. 从卡详情页返回列表页时停在 **Pilih Kartu**：在 Kartuku 点卡进入详情（`/profile/id-cards/:id`），`await` 该次 push 返回后若页面仍挂载，切回 Pilih Kartu（FR-142B.1、UI 稿 B 线说明「从详情页返回都停在 Pilih Kartu Tab」；见 Completion Notes 待确认 1）。
5. Pilih Kartu 不依赖卡列表接口：列表加载中 / 失败时网格照常显示；加载态与失败态只出现在 Kartuku Tab 内。

**AC2 — Pilih Kartu 网格** `[L0]` `[L2]`
1. Tab 内容区背景 `AppColors.cream2`，内边距 14 / 16，可纵向滚动。自上而下：标题句 `idCardStyleGridTitle`（id「Pilih dan buat kartu identitas untuk hewanmu!」/ en「Pick and create an ID card for your pet!」）→ 组标题 `idCardStyleGroupIdentity`（id「Kartu Identitas」/ en「ID Cards」）→ 4 格网格 → 组标题 `idCardStyleGroupStudent`（id「Kartu Pelajar」/ en「Student Cards」）→ 4 格网格（FR-142B.2）。
2. 组与组内顺序取自注册表，**不在页面里另写一份列表**（AD-6）：`IdCardStyle.values.where((s) => s.group == <组>)`，顺序为 identity：`ktp, passport, kitas, sim`；student：`studentBintang, studentMalam, studentCeria, studentKlasik`。单测断言该顺序。
3. 组标题 12 / w600 / `AppColors.ink2`；网格 2 列、横纵间距 10。
4. 格子：白底（`AppColors.card`）、16 圆角、内边距 8（底部 10）、轻阴影（UI 稿 `0 1px 2px rgba(46,42,69,.06), 0 4px 12px rgba(46,42,69,.04)`）；内容 = 缩略图 + 下方 8 间距 + 卡种名（13 / w600 / 居中 / 最多 2 行）。卡种名取注册表项的名称 l10n（Story 1.2 定义），不新增名称 key。
5. 缩略图区：宽撑满格子、宽高比 994:600；缩略图 8 圆角 + 1px `Color(0x14000000)` 内描边；**护照缩略图（非 16:9）按区高等高、水平居中**，两侧露格子底色（UX-DR2）。图片来自 `Image.asset(style.thumbnailAsset)`，`errorBuilder` 回落为同尺寸 `AppColors.mintTint` 圆角块（素材缺失不崩）。
6. 每格 key：`idCardStyleCell_<IdCardStyle.name>`（如 `idCardStyleCell_studentBintang`）。
7. L2：360dp 真机 id / en 两语，8 格两列不溢出、卡种名不截断（「Kartu Izin Tinggal」「Residence Permit」「Driving License」最长）。

**AC3 — 缩略图：可重复执行的渲染脚本** `[L0]`（D-2 / AD-8）
1. 新增生成脚本 `petgo_app/test/tool/id_card_style_thumbnails_test.dart`（widget test 形式）+ 包装脚本 `scripts/gen-id-card-style-thumbnails.sh`。默认 `flutter test` **跳过**该生成用例（用 `skip: !Platform.environment.containsKey('GEN_ID_CARD_THUMBS')`），只在包装脚本里设该环境变量运行。
2. 生成流程：对 8 个 `IdCardStyle` 逐个用**注册表的卡面构建函数**渲染固定示例数据（见 Dev Notes「示例数据」），放进与该卡款画布等大的 `RepaintBoundary`，`toImage` 输出 PNG 到 `petgo_app/build/id_card_style_thumbs/<snake_name>.png`；包装脚本再用 `cwebp -q 90` 转为 `petgo_app/assets/id_card/styles/<snake_name>.webp`（文件名与注册表 `thumbnailAsset` 一致：`ktp / passport / kitas / sim / student_bintang / student_malam / student_ceria / student_klasik`）。
3. 输出尺寸：16:10 系卡面宽 600px（高按画布比例，1988×1200 → 600×362）；护照（1990×1548）**高**取同一高度 362px、宽按比例。缩略图**不带水印**（UI 稿 B1 示例卡面无水印；网格是示例而非用户的卡）。
4. 脚本内先用 `FontLoader` 加载卡面实际用到的字体（至少 `Rubik`、`Poppins`，以卡面代码里的 `fontFamily` 为准），否则 widget test 会以 Ahem 方块字渲染；跑完逐张目检。
5. 产出的 8 张 `.webp` 入库；`pubspec.yaml` 的 `flutter.assets` 增加 `assets/id_card/styles/`（带注释：由脚本生成，设计日后可同名替换）。
6. 单测（常规 `flutter test` 跑）：8 个 `thumbnailAsset` 文件都存在于磁盘、互不相同。
7. 网格只显示静态图，**不**按当前宠物实时渲染（FR-142B.5）。

**AC4 — `new` 角标与截止常量** `[L0]`（D-3 / AD-11）
1. `petgo_app/lib/features/profile/domain/id_card_style.dart` 中的 `const DateTime kIdCardNewStylesBadgeUntil`（若 Story 1.2 尚未建则本 story 新建）：以 UTC 写出 WIB 时刻（例：WIB 某日 00:00 = 前一日 `DateTime.utc(y, m, d - 1, 17)`），注释首行写「**发版检查项：上线日 + 30 天（WIB）**」，并写明到达该时刻起所有用户不再显示。
2. 判定函数 `bool idCardStyleShowsNewBadge(IdCardStyle style, {DateTime? now})`：`style.isNew && (now ?? DateTime.now()).toUtc().isBefore(kIdCardNewStylesBadgeUntil)`。页面只调用它，不在页面里直接比日期。
3. 显示时格子右上角红色角标：文案 `insightNewBadge`，样式与 `InsightEntryCard` 角标一致（`AppColors.danger` 底、白字 10 / w600、圆角 8、内边距 7×2、行高 1.2），位置 `top: -5, right: 8`、骑在格子上沿、`IgnorePointer`（UI 稿 `.gnew`）。key：`idCardStyleNewBadge_<name>`。
4. 显示的恰为 `kitas / sim / studentBintang / studentMalam / studentCeria` 五款；`ktp / passport / studentKlasik` 永不显示（FR-142B.3）。
5. `docs/runbooks/` 下目前**没有**发版检查清单（已核实）；把「`kIdCardNewStylesBadgeUntil` = 上线日 + 30 天（WIB）」写进本 story Completion Notes 并在交付汇报里提示操作者（见待确认 6）。

**AC5 — 点格进入建卡页并带上卡款** `[L0]`
1. 点格 → `context.push(IdCardCreatePage.locationFor(style))`，即 `/profile/id-cards/create?style=<IdCardStyle.name>`（FR-142B.4）。
2. 路由 `/profile/id-cards/create`（`app_router.dart:836`）改为读 query：`IdCardCreatePage(initialStyle: IdCardStyle.values.asNameMap()[s.uri.queryParameters['style']] ?? IdCardStyle.ktp)`；缺省 / 非法值回落 `ktp`（兼容 Kartuku 里不带参数的现有入口与旧深链）。路由仍在 `/profile/` 前缀下，**不进** `_controlledExactExceptions`。
3. `IdCardCreatePage` 新增构造参数 `IdCardStyle initialStyle`（默认 `IdCardStyle.ktp`），页面当前卡款由它初始化，卡面预览按该卡款的注册表构建函数渲染（「Story 1.7 前建卡页按该款渲染卡面即可」）。
4. 建卡请求按当前卡款带 `cardType = style.cardType`、`cardVariant = style.cardVariant`（App `CreateIdCardRequest` 若尚无 `cardVariant` 字段则本 story 补上，`toJson` 为 null 时不发该键）——避免「选 Bintang、预览 Bintang、后端落 KLASIK」。
5. 建卡页顶部旧三 Tab（KTP / Paspor / Pelajar）本 story **不删**（Story 1.7 删）。过渡期行为：选中态高亮与当前卡款 `cardType` 对应的旧 Tab（KITAS / SIM 时三个都不高亮）；点旧 Tab 切到 `ktp` / `passport` / `studentKlasik`。
6. 埋点：点格时 `Analytics.capture('id_card_style_tapped', {'card_type': style.cardType, 'card_variant': ?style.cardVariant})`——非学生卡不带 `card_variant` 键（AD-12）。

**AC6 — `id_card_tab_viewed` 埋点** `[L0]`
1. 进入页面上报一次 `tab: 'style'`；此后每次可见 Tab 变化（用户点 Tab / 左右滑动 / 从详情返回被切回 Pilih Kartu）上报一次，`tab` ∈ `style` | `created`。同一 Tab 重复点不报。
2. 属性 `has_cards`（bool）取 `idCardListProvider` 的结果 `cards.isNotEmpty`。进入页面那一次等列表首个结果出来再报（加载失败见 Completion Notes 待确认 3，默认不带 `has_cards` 键照常上报）。
3. 事件名按决策日志 D-13 改为过去式 `id_card_tab_viewed` / `id_card_style_tapped`，**不改** `allowedSuffixes`；`propNames`（`:462` 起）加 `'has_cards'`、`'card_type'`、`'card_variant'`。前缀 `id_card_` 已在表内，无需改。

**AC7 — Kartuku Tab（本 story 只放现有内容）** `[L0]`
1. Kartuku Tab 内容 = **现有列表页内容原样平移**：加载中转圈、失败 `idCardLoadError` 文字、空态（🪪 + `idCardListEmptyTitle` / `Body` + `idCardCreateEntry` 按钮）、有卡态（`idCardListSubtitle` + 顶部「Kartu Baru」入口行 + 卡片行 + 已解锁「HD」徽标）。现有 key（`idCardCreateEntry`、`idCardTile_<id>`）不变。
2. 唯一行为变化：卡片行点进详情返回后切回 Pilih Kartu（AC1.4）。「Kartu Baru」入口与空态按钮仍 push 不带参数的建卡页（→ 回落 KTP），**本 story 不改**。
3. 新说明句、证件图标、「Buka HD」标签反转、吸底「Buat Kartu Baru」、「Coba lagi」重试、付费共享函数**全部属于 Story 1.6**，本 story 不做。

**AC8 — 测试与回归** `[L0]`
1. `test/profile/id_card_page_test.dart` 更新：`_pump` 后默认在 Pilih Kartu；原两条用例改为先点 `idCardTab_created` 再断言（内容不变）。
2. 新增用例（同文件或 `test/profile/id_card_style_grid_test.dart`）：
   - 进入页面 → Pilih Kartu 选中；8 个 `idCardStyleCell_*` 都在，两组顺序与 AC2.2 一致；标题句与两个组标题文案正确（en / id 各一）；
   - 列表接口抛错 / 挂起时网格仍显示；
   - `new` 角标：用 `idCardStyleShowsNewBadge(now: …)` 单测截止前 / 截止时刻 / 截止后三种；页面层断言截止前 5 个角标、恰是那 5 款；
   - 点 `idCardStyleCell_studentMalam` → 路由到 `/profile/id-cards/create?style=studentMalam`（用测试 `GoRouter` 截获），且上报 `id_card_style_tapped{card_type: STUDENT, card_variant: MALAM}`；点 `ktp` 格不带 `card_variant` 键；
   - `id_card_tab_viewed`：进入报 `{tab: style, has_cards: false}`（空列表）；切 Kartuku 报 `{tab: created, has_cards: false}`；有卡时 `has_cards: true`；
   - 在 Kartuku 点卡 → 详情 → 返回，Pilih Kartu 选中并补报一次 `tab: style`；
   - 路由：`/profile/id-cards/create`（无参）与 `?style=bogus` 均以 `IdCardStyle.ktp` 构建页面。
3. `pet_insights_test.dart:96-101`「多卡子路由一字未动」断言路由表源码含字面量 `'/profile/id-cards/create'` 与 `'/profile/id-cards/:id'`：改 builder 时**保留这两个字面量路径**（query 只在 builder 里读），该用例与「旧路径仍为重定向」「无残留 `'/profile/id-card'` 字面量」照常通过。
4. `v112_events_test.dart` 全绿（含「lib/ 里每个非遗留事件都符合前缀 + 动作」）。
5. `flutter analyze` 无新增告警；`flutter test` 全绿。

## Tasks / Subtasks

- [ ] Task 1：l10n（AC1 / AC2）
  - [ ] 1.1 `app_id.arb` / `app_en.arb` 新增 `idCardTabStyles`、`idCardTabMyCards`、`idCardStyleGridTitle`、`idCardStyleGroupIdentity`、`idCardStyleGroupStudent`；`flutter gen-l10n`
- [ ] Task 2：Tab 骨架（AC1 / AC7）
  - [ ] 2.1 `IdCardPage` → `ConsumerStatefulWidget` + `SingleTickerProviderStateMixin`，`TabController(length: 2, initialIndex: 0)`
  - [ ] 2.2 现有 `async.when(...)` 整体移入 Kartuku Tab；Pilih Kartu 不 watch 列表
  - [ ] 2.3 卡片行点进详情：`await context.push(...)` 后 `animateTo(0)`
- [ ] Task 3：网格（AC2 / AC4）
  - [ ] 3.1 网格与格子组件（可放 `presentation/id_card/id_card_style_grid.dart`）
  - [ ] 3.2 `kIdCardNewStylesBadgeUntil` + `idCardStyleShowsNewBadge`；角标
  - [ ] 3.3 Completion Notes 记录发版检查项（AC4.5）
- [ ] Task 4：缩略图脚本与素材（AC3）
  - [ ] 4.1 `test/tool/id_card_style_thumbnails_test.dart`（默认跳过）+ `scripts/gen-id-card-style-thumbnails.sh`
  - [ ] 4.2 跑脚本、目检、8 张 `.webp` 入库；`pubspec.yaml` 登记 `assets/id_card/styles/`
  - [ ] 4.3 素材存在性单测
- [ ] Task 5：跳转建卡页（AC5）
  - [ ] 5.1 `IdCardCreatePage.locationFor` + `initialStyle`；路由读 query
  - [ ] 5.2 建卡页当前卡款由入参初始化、预览按注册表；请求带 `cardType` / `cardVariant`；旧三 Tab 过渡映射
- [ ] Task 6：埋点（AC5.6 / AC6）+ `v112_events_test` 扩表
- [ ] Task 7：测试（AC8）；L2 真机目检网格与缩略图

## Dev Notes

### 现状（改动前必读）

- **列表页** `id_card_page.dart`（196 行，`ConsumerWidget`）：
  - `:19-27` 整个 body 是 `ref.watch(idCardListProvider).when(loading/error/data)`；失败只有文字 `idCardLoadError`（`:25`），无重试。
  - `:31-48` 有卡态：`idCardListSubtitle` → `_createEntry`（紫浅底「Kartu Baru」入口行，key `idCardCreateEntry`，push `/profile/id-cards/create`，`:85-115`）→ 卡片行。
  - `:50-83` 空态：🪪 + `idCardListEmptyTitle` / `Body` + `FilledButton`（同 key `idCardCreateEntry`）。
  - `:117-192` 卡片行：编号 `cardNo` 优先、否则 `serialId` 补零 4 位（`:119-121`）；卡种名按 `effectiveCardType` 三选一（`:123-127`）；已解锁挂「HD」（`:169-184`）；点行 `context.push('/profile/id-cards/${card.id}')`（`:134`）。
  - 本 story 把这些**整体搬进 Kartuku Tab**，只在 `:134` 处加「返回后切回 Pilih Kartu」。卡种名的三选一在 Story 1.2 已收口到注册表的话保持其写法；Kartuku 的其余改造在 Story 1.6。
- **列表数据** `idCardListProvider`（`data/id_card_repository.dart:168-170`）：非 autoDispose 的 `FutureProvider`，建卡后由建卡页 `ref.invalidate` 刷新。
- **路由** `core/router/app_router.dart`：
  - `:780` `PetInsightsRoutes.idCard`（`/profile/pet-insights/id-card`）→ `const IdCardPage()`；
  - `:834` 旧 `/profile/id-card` → 重定向到上者（**不得删除**，AD-A17.2）；
  - `:836` `/profile/id-cards/create` → `const IdCardCreatePage()`（无参数）；
  - `:837-840` `/profile/id-cards/:id` → `IdCardDetailPage(cardId)`；字面量 `create` 必须在 `:id` 之前。
  - `:832-833` 注释：`/profile/id-card` 与 `/profile/id-cards/...` 只差一个字母，**逐条改，禁止前缀字符串替换**（AD-A17.7）。
- **入口**：聚合页 `pet_insights_page.dart:233` `context.push(PetInsightsRoutes.idCard)`；成长档案时间线 `growth_archive_page.dart:838-844`（`TimelineItemType.idCardIssued` → 直达 `PetInsightsRoutes.idCard`）。两处都不需要改，新建的页面实例默认 Pilih Kartu 即满足 AC1.3。
- **建卡页** `id_card_create_page.dart`（730 行）：`const IdCardCreatePage()` **当前不接收任何参数**（`:31-32`）；卡种由 `int _styleIndex`（`:55`，0 KTP / 1 Paspor / 2 Pelajar）决定，`_cardTypeWire`（`:101-106`）与预览 switch（`:113-130`）都读它；顶部三 Tab `_styleSwitcher` / `_styleTab`（`:136, :221-260`，key `idCardStyleTab_<index>`）；建卡请求 `cardType: _cardTypeWire`（`:338-339`），成功后弹付费再 `context.pop()` 回列表（`:353-354`）。Story 1.2 按 AD-6 把 `_styleIndex` 收口到注册表——**动手前先看 1.2 落地后的实际写法**：若已是 `IdCardStyle _style`，本 story 只加 `initialStyle` 入参与 query 解析；若仍是 `_styleIndex`，本 story 把它换成 `IdCardStyle`。
- **App 建卡请求** `domain/id_card.dart:210-260` `CreateIdCardRequest`：有 `cardType`，**无** `cardVariant`（Story 1.1 只给 `IdCard.fromJson` 加了读取）。
- **埋点前缀测试** `test/analytics/v112_events_test.dart`：从 `lib/` 里提取所有 `Analytics.capture('<字面量>'` 的事件名（`:164-165`），逐个要求「前缀在 `allowedPrefixes`（`:217` 起，已有 `id_card_`）且词尾在 `allowedSuffixes`（`:330` 起）」。现有词尾只有 `_viewed` / `_tapped` / `_shown` 等，**没有** `_view`、`_click`——PRD 定的 `id_card_tab_viewed` / `id_card_style_tapped` 不扩表会让该测试红。表内每次扩表都有一段「名字由 PRD 定死，扩表、不改名」的注释，照此写。事件名只能写字面量（变量提取不到）。
- **现有分享埋点** `id_card_detail_page.dart:257` `id_card_share_tapped{card_style}`——Story 1.8 的事，本 story 不碰。
- **App 内没有现成的 `TabBar` 用法**（全仓 `grep TabBar(` 只有底部导航 `BottomTabBar`），本页是第一个页内 Tab；用 Material `TabBar` + `TabBarView` 并按 AC1.2 覆盖样式即可，不必抽共享组件。
- **素材目录**：现有卡面素材在 `assets/ktp/`、`assets/passport/`、`assets/student/`；`assets/id_card/` 目录本 story 前由 Story 1.2 / 1.3 建立（`student_*`、`sim`、`kitas`），`styles/` 子目录由本 story 建并单独登记到 `pubspec.yaml`（Flutter 不递归登记子目录）。
- **字体**：`pubspec.yaml` 已登记 `Poppins`、`IBMPlexMono`、`Rubik`（`Rubik-VariableFont_wght.ttf`）、`Fraunces`；widget test 默认不加载这些字体，生成脚本必须自己 `FontLoader` 加载。
- **头像**：卡面头像走 `AppImage.widget(url)`（网络图，如 `student_card.dart:197-198`），widget test 里取不到网络图——示例数据 `avatarUrl` 置 null，卡面画其无头像占位（见待确认 2）。

### 示例数据（缩略图用，取自 UI 稿 B1 示例卡面）

`name: 'Oyen'`、`petType: 'CAT'`、`breed: 'Domestik'`、`birthday: 2026-03-13`、`gender: 'MALE'`、`birthCity: 'Bandung'`、`address: 'Jl. Melati No. 25'`、`occupation: 'Chief Happiness Officer'`、`school: 'TAILTOPIA ACADEMY'`、`faculty: 'FAKULTAS KEBAHAGIAAN'`、`cardNo: 'TT230326020110'`（与上面生日 / 性别 / 物种一致：13+10=23、03、26、猫 02）、`passportNo` 用 `buildPreviewPassportNo(petType: 'CAT', year: 2026)`、`createdAt: 2026-10-12`、`avatarUrl: null`。其余字段留空走卡面趣味默认值。数据写在生成脚本里一处常量，**不进 `lib/`**。

### 架构约束

- AD-6：网格分组、顺序、名称、缩略图、是否 new、埋点 `card_type` / `card_variant` 全部来自 `IdCardStyle`，页面不写 switch。
- AD-8 / D-2：缩略图由可重复执行的脚本生成后作为静态图入库；设计日后可同名替换，代码不改。
- AD-10：从任何入口进入默认 Pilih Kartu。
- AD-11 / D-3：截止常量写死在代码里，发版检查项；过期后所有用户都不显示，不做后台配置。
- AD-12：新事件只本 story 的两个（`id_card_tab_viewed`、`id_card_style_tapped`），属性键 snake_case。
- 路由：`/profile/` 前缀自动受游客门控；**禁止**把任何新路径放进 `_controlledExactExceptions`；`/profile/id-card` 重定向保留。
- 不引入新依赖（`cwebp` 是开发机工具，不进 `pubspec.yaml`）。

### 本 story 不做

- **Kartuku 内容改造**（说明句、证件图标、`{卡种名} · No.` 新格式、「Buka HD」标签与直接付费、吸底「Buat Kartu Baru」切回 Pilih Kartu、空态新样式、「Coba lagi」重试）→ Story 1.6。
- 建卡页去掉旧三 Tab、按卡种表单、必填校验、`id_card_created` / `id_card_form_opened` 埋点 → Story 1.7。
- 付费落点、详情页按钮文案、`id_card_buka_hd_tapped` / `id_card_paywall_dismissed`、分享埋点补字段 → Story 1.6 / 1.8。
- 不改聚合页（Story 1.4）、不改卡面组件（Story 1.2 / 1.3）。

### 测试

- 页面测试沿用 `id_card_page_test.dart` 的 `_FakeIdCardRepo`（`:10-43`）；涉及跳转的用例用测试内 `GoRouter`（参考 `pet_insights_test.dart` 的 `_pumpHub` 写法），把 `/profile/id-cards/create` 与 `/profile/id-cards/:id` 挂成占位页以截获 location / query。
- 埋点断言用 `Analytics.debugCaptureSink`（见 `v112_events_test.dart:172-176` 的 setUp / tearDown 写法）。
- 角标的时间判定只测纯函数（传 `now`），页面层断言「当前时间早于截止常量」时的 5 个角标——若截止常量已过期会导致页面用例失败，页面用例应通过可注入的 `now`（如给网格组件一个 `@visibleForTesting DateTime Function() clock` 参数）固定时间。
- 生成脚本默认跳过，不进 CI；素材存在性单测进 CI。
- L2：真机看网格（360dp、id / en）、缩略图清晰度与护照等高居中、角标位置、点格进入建卡页卡面正确。

### References

- [Source: _bmad-output/planning-artifacts/v1.3.4/epics-v1.3.4-ktp.md#Story 1.5]
- [Source: _bmad-output/planning-artifacts/v1.3.4/PRD-v1.3.4-ktp.md#3.2 FR-142B / #4 数据埋点需求]
- [Source: _bmad-output/planning-artifacts/v1.3.4/architecture-v1.3.4-ktp-delta.md#AD-6 / AD-8 / AD-10 / AD-11 / AD-12]
- [Source: _bmad-output/planning-artifacts/v1.3.4/决策日志-ktp.md#D-2 / D-3 / D-7 / D-11]
- [Source: _bmad-output/planning-artifacts/v1.3.4/ui-v1.3.4-ktp.html#B1（及 B 线流程说明）]

## 待确认项裁定（2026-10-12，权威，覆盖本文件上文的对应写法）

> 依据 `_bmad-output/planning-artifacts/v1.3.4/决策日志-ktp.md` D-13 ~ D-19 与技术裁定 T-1 ~ T-22。Completion Notes 里原列的「待确认」均已按此关闭。

- D-13：事件改名为 `id_card_tab_viewed` / `id_card_style_tapped`，**不放宽**命名白名单
- D-14：从 Kartuku 点卡进详情返回仍在 Kartuku；建卡路径返回 Pilih Kartu；外部入口默认 Pilih Kartu
- T-9 / T-10 / T-11 / T-12 / T-13 已裁定，见决策日志

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

- 待确认 1：「从详情页返回停在 Pilih Kartu」的范围。FR-142B.1 / UI 稿写「一律停在 Pilih Kartu」，本 story 按字面实现——**包括用户从 Kartuku 点卡进详情再返回**，也会被切回 Pilih Kartu。PRD §3.2 原文只说「从新卡详情页返回」；若产品本意只指建卡后的新卡详情（该路径列表页本来就停在 Pilih Kartu），则 AC1.4 的「返回后切回」可去掉。
- 待确认 2：缩略图的宠物照片。卡面头像只支持网络 URL，示例数据不带头像，8 张缩略图头像位为卡面自身的无头像占位。UI 稿 B1 的 KTP / Paspor 示例是线上真实渲染图（是否带照片未写明）。如需示例宠物照片，要给卡面加本地图来源或改用一张固定的公网图，另行确认。
- 待确认 3：`id_card_tab_viewed` 进入页面时列表**加载失败**，`has_cards` 取什么。PRD 未定义；本 story 暂按「不带 `has_cards` 键照常上报」实现。
- 待确认 4：埋点 `card_type` / `card_variant` 的取值大小写。PRD / AD-12 未写明；本 story 暂用与接口一致的大写 wire 值（`KTP` / `STUDENT`、`BINTANG` …），与 Story 1.6–1.8 需统一。
- 待确认 5：事件名 `id_card_tab_viewed` / `id_card_style_tapped` 与现有命名规范（词尾 `_viewed` / `_tapped`）不一致。按惯例「名字由 PRD 定死，扩表、不改名」处理（AC6.3）；如运营同意也可改成 `_viewed` / `_tapped`。
- 待确认 6：`kIdCardNewStylesBadgeUntil` 的取值在开发期间填什么。上线日未定；建议先填一个明显的占位日期（如计划上线日 + 30 天）并在发版检查清单登记，发版时改为实际值。
- 提示：缩略图转 webp 需要开发机安装 `cwebp`（`brew install webp`），当前本机未安装。
- 提示：UI 稿 B1 画面里没有画出标题句「Pilih dan buat kartu identitas untuk hewanmu!」，但 PRD / epics 要求有；本 story 放在第一个组标题之上。

### File List

# Story 3.1: App 健康记录页状态与筛选

Status: review

> 版本 v1.5.0 · 会话 ① 健康记录提醒 · 分支 `feat/1.5.0-health-reminder`
> 口径优先级：CROSS-STORY-DECISIONS 地基段 > 架构增量 §7 > PRD FR-158 UI、FR-161 UI（Konsultasi 卡）、FR-167 UI（定位 / 警示框）、FR-172（全部）、§6.1（去掉商城行）、AC64 ~ AC68、AC70 > UI 稿 A1 ~ A12（整合稿页头「代码落点」第 1 行）。
> 前置：后端 1.1、1.2 的列表字段契约（`recordStatus`、`displayNextDate`、`vaccinePreset`、`dose`、`dewormType`、`nextDateLocked`）。云端按契约写、用假仓储测，真联调在 4.1。
> 范围层：**只 App**（`petgo_app/`）。表单归 3.2；从提醒进入的路由解析归 3.4（本 story 只让页面**接受**新的路由参数并渲染）。

## Story

As a 宠物主人,
I want 健康记录页每张卡片显示还要不要处理、点分类卡只看这一类、需要注意的安全事项在页顶,
so that 我能快速找到某类记录（比如给兽医看疫苗史），并知道下一步做什么。

## Acceptance Criteria

> 层级：`[L0]` `flutter analyze` + `flutter test`（云端）· `[L2]` 模拟器 / 真机视觉（本地，还原度 ≥95%）。

### AC1 — 数据模型 `[L0]`
1. `HealthListItem` 追加 `vaccinePreset`、`dose`、`dewormType`、`productDurationMonths`、`nextDate`、`displayNextDate`、`recordStatus`（枚举 `HealthRecordStatus { overdue, dueSoon, upcoming, done }`，未知值 → `done`）、`nextDateLocked`；`fromJson` 容错（缺字段 = null / done）。
2. `HealthListPage.recordTypes` 加 `VET_VISIT`（放在 `CUSTOM` 之前，PRD「Kontrol dokter listed before Lainnya」）。

### AC2 — 记录卡 `[L0]` `[L2]`（PRD FR-158 UI、UI A1 / A3 / A4 / A8）
1. 卡片布局照旧；记录自身日期从右栏挪到卡片**右上角、类型标签同行**，格式「3 Des 2026」（`DateFormat('d MMM y', locale)`，与分类卡一致，原为 `yyyy-MM-dd`）。
2. 标题：疫苗 = `Vaksin · {预设标签或原文} · Dosis ke-{n}`（`vaccinePreset` 非空且非 OTHER → 本地化预设标签；否则 `vaccineName`；`dose` 空不显示剂次段），最多 2 行；Kontrol dokter 卡的类型行显示「Konsultasi」、标题「Kontrol dokter」（PRD FR-161 Record card）；驱虫标题带类型名（无类型 = 只写「Obat Cacing」）。
3. 标题下一行状态胶囊按 `recordStatus`（架构 §7）：`overdue` 红（`AppColors.coralTint` 底、`healthEventText` 字、警示图标、文案「Melewati jadwal」）；`dueSoon` 金（`goldTint` / `tipsBadgeText`、闹钟图标、「Berikutnya: 28 Jan」）；`upcoming` 紫（`violet100` / `mint700`、日历图标、「Berikutnya: …」）；`done` 无胶囊，类型标签灰、标题常规字重次级色、图标淡化。日期本年不写年、跨年写年（`d MMM` / `d MMM y`）。每个状态都有图标 + 文字（不只靠颜色）。问诊只读卡按 done 显示。
4. 去掉疫苗 / 驱虫卡下的「Lihat produk Obat & Vitamin」行（`_categoryJumpRow` 与其 `triage_category_jump_tapped` 埋点一起删；`ConsultShopBoundaryTest` 若有相关断言同步改）。

### AC3 — AppBar 与分类卡 `[L0]` `[L2]`（PRD FR-172「Top-right buttons removed」、FR-161 Category cards、AC66、AC70）
1. AppBar 只留返回与标题：删除 `healthStartConsult` 与 `healthAddTop`（含只读锁图标变体）及其测试 key；底部「Tambah catatan」保留（只读宠物仍带锁）。
2. Konsultasi 分类卡：副标题固定「Gejala & dokter」（新 arb 键，EN「Symptoms · vet」），`maxLines: 1` + ellipsis，不再显示日期；右上角常驻 `OnlinePulseDot(pulsing: true)`（`shared/widgets/design/online_pulse_dot.dart`），读屏标签复用「Selalu aktif · Jawaban otomatis」现有键；点击照旧 `push('/triage')` 并报 `health_consult_card_tapped {pet_id}`。**不读在线状态、不发请求。**
3. 其余 5 张卡（Vaksin、Obat Cacing、Sterilisasi、Berahi、Lainnya）副标题照旧（最近日期或「Belum ada」）；类型名「Siklus Berahi」改「Berahi」（arb `healthTypeMenstruation` 两份同步：ID「Berahi」、EN「Heat」）。

### AC4 — 分类筛选 `[L0]` `[L2]`（PRD FR-172、AC64 ~ AC68，UI A11 / A12）
1. 点 5 张卡之一 → 页面状态 `_filter = type`（不改路由、不发请求，在已加载列表上过滤）；选中卡 2px `AppColors.mint` 描边 + 紫色标签，白底不变；同时只能选一张；再点同一张或点「Lihat semua」（复用现有键）→ 清除。
2. 列表标题：无筛选「SEMUA CATATAN · TERBARU」；有筛选「{KATEGORI} · TERBARU」（大写类型名，新 arb 键带占位，EN「{CATEGORY} · LATEST」）+ 右侧「Lihat semua」。
3. 筛选后为空 → 「Belum ada catatan di kategori ini.」（新键，EN「No records in this category yet.」，现有空态样式）；宠物完全没有记录 → 现有 `healthListEmpty`，此时点卡给筛选空态。
4. 有筛选时底部「Tambah catatan」→ `_openForm(presetType: _filter)`；无筛选照旧（默认 Vaksin）。保存后：筛选切到所存记录的类型；所存为 Kontrol dokter（无对应筛选）→ 清除筛选。删掉筛选类最后一条 → 显示筛选空态。
5. 不记忆：离开页面、切宠物、带 `focus` / 提醒参数进入 → 一律无筛选。
6. 埋点 `health_category_filtered {pet_id, category, record_count}`：只在**施加**筛选时报一次（清除不报）；`category` ∈ VACCINE / DEWORM / NEUTER / MENSTRUATION / CUSTOM。
7. widget 测试覆盖 AC64（2 疫苗 + 1 驱虫 + 1 Vet visit + 1 问诊：点 Vaksin 只剩 2 条、标题与 Lihat semua；再点 / Lihat semua 回到 5 条；点 Konsultasi 不筛选）、AC65、AC66、AC67、AC68（筛选中带 focus 进入 → 无筛选 + 定位）。

### AC5 — 从提醒进入的页面参数 `[L0]` `[L2]`（PRD FR-167 UI，UI A3 ~ A7）
1. 路由 `/profile/health` 追加可选 query：`preset`、`dose`、`deworm`、`custom`、`dateEmpty=1`、`safety=PREGNANT,PERMETHRIN`、`nid`（健康通知 token，3.4 用于归因）；`HealthListPage` 构造参数对应追加，`add` / `focus` 语义不变（`add=VET_VISIT` 新值）。表单预填参数原样传给 3.2 的 `_openForm(prefill: …)`。
2. `safety` 非空 → 在分类卡**上方**渲染警示框（可滚动、不可关闭；样式复用推送设置页状态卡：`coralTint` 底、白色圆形图标）：`PREGNANT` 文案「Beberapa obat dapat membahayakan janin/bayi hewan.」（EN「Some medicines can harm unborn or nursing young.」）、`PERMETHRIN` 文案 = permethrin 警示原文（PRD FR-160，ID / EN 两句）；两者都有时怀孕在前。此时**不自动弹表单**（底部按钮仍可开表单）。
3. `focus` 定位沿用现有滚动 + 2.2 秒高亮。

### AC6 — 文案与埋点守卫 `[L0]`
1. 新 arb 键 ID / EN 两份同步，跑 `flutter gen-l10n`；`test/l10n/*` 契约测试不红。
2. 新事件名字面量、前缀 `health_`（`test/analytics/v112_events_test.dart` 已允许）；属性只放枚举 / 数字。

### AC7 — 视觉 `[L2]`
1. 本地 Android 模拟器对照 UI 稿 A1 ~ A12 逐屏，还原度 ≥95%（4.1 统一打分）。

## Tasks / Subtasks

- [x] T1 模型字段（AC1）
- [x] T2 记录卡日期位置、标题、状态胶囊、删商城行（AC2）
- [x] T3 AppBar 去按钮、Konsultasi 卡副标题 + 绿点 + 埋点、Berahi 改名（AC3）
- [x] T4 筛选（AC4）
- [x] T5 路由参数 + 警示框（AC5）
- [x] T6 arb + gen-l10n + 测试（AC6）；widget 测试用假 `healthRecordRepositoryProvider`
- [x] T7 `flutter analyze` + `flutter test`；Completion Notes 标「L2 待本地验收」

## Dev Notes

- 文件：`features/profile/presentation/health_list_page.dart`（974 行，筛选与卡片都在这里，必要时把卡片拆到同目录 `widgets/health_record_card.dart`）、`features/profile/domain/health_list_item.dart`、`features/profile/domain/health_record_icons.dart`（VET_VISIT 图标在 3.2 加）、`core/router/app_router.dart`（`/profile/health` 路由，约 972 行）。
- 颜色只用 `core/theme/colors.dart` 的 token（`coralTint`、`healthEventText`、`goldTint`、`tipsBadgeText`、`violet100`、`mint700`、`mint`），不新造颜色。
- 物种只认 CAT / DOG（这里不涉及物种分支，但标签本地化遇未知预设 → 显示 `vaccineName`）。
- **不要对整个文件跑 `dart format`**；只跑 `flutter analyze`。
- 结构断言代替 golden（云端字体渲染不同）。

### 验证层级
- L0：`flutter analyze`；`test/profile/health_list_page_test.dart`（新增 / 扩展：状态胶囊、筛选、AppBar、警示框）
- L2：模拟器逐屏还原度

## Dev Agent Record

### Completion Notes

- **L1/L2 待本地验收**：AC2 / AC3 / AC4 / AC5 的 `[L2]` 部分（模拟器对照 UI 稿 A1 ~ A12 逐屏还原度 ≥95%，含状态胶囊配色、选中卡描边、警示框样式、绿点脉冲观感）与 AC7 未做，留 4.1 统一打分；本 story 无 L1 项。真后端联调（列表新字段契约）留 4.1。
- L0：`flutter analyze` 无 error / warning（仅有工具链 3.50 带来的全仓 `deprecated_member_use` info，以及本文件既有的 `flutter/rendering.dart` unnecessary_import info——注释写明 3.44 需要它，未动）；`flutter test` 全量绿（首轮仅 D-3 同形词规则红，已按下条处理后复跑 `test/l10n` 绿）。`flutter gen-l10n` 已跑。
- `test/profile/health_list_page_test.dart` 新增 19 例：AC1 模型容错与类型顺序、AC2 日期位置 / 标题拼接 / 三色胶囊（图标 + 文字）/ done 无胶囊 / 跨年写年、AC3 AppBar 无按钮 / Konsultasi 卡副标题 + 绿点 + 埋点 / Berahi 改名、AC64 ~ AC68、筛选中保存 Kontrol dokter 清除筛选、AC5 警示框顺序与不自动弹表单、预填透传。
- **按设计改写的既有测试（改断言表达新规则，未删 / 未 skip）**：
  - `test/shop/consult_shop_boundary_test.dart` FR-110 ③：由「只有驱虫挂品类入口、只跳品类」改为「健康记录页任何卡片都不挂商品入口、点卡不落 Toko、不报 `triage_category_jump_tapped`」（PRD §6.1）。`TriageCategoryJump` 桥本身未删（仍有桥的契约测试；现无 lib 调用方，删不删留给后续清理）。
  - `test/analytics/v140_events_inventory_test.dart`：`triage_category_jump_tapped` 从「必须存在」移到新 `retiredV150`「必须不存在」（同 V1.4.2 `retiredV142` 先例）。
  - `test/pet_slot/pet_readonly_block_test.dart`：AppBar「+」已删 → 断言不存在；空分类卡由 4 → 3（Konsultasi 不再显示「Belum ada」）；只读拦截只剩「Tambah catatan」（筛选前后各点一次都弹 E1），分类卡筛选属查看不拦（AD-8）。
  - `test/l10n/microcopy_rules_test.dart`（D-3 英文基线同形词表）：加入疫苗预设标签与 Heartworm 的词（国际通用药名，架构 §3.1 两语同写）。
  - `test/analytics/v112_events_test.dart`：动作词表加 `_filtered`（事件名 `health_category_filtered` 由 PRD §4 定死，按「扩表、不改名」先例）。
- **绿点脉冲与测试**：`OnlinePulseDot(pulsing: true)` 是常驻 `repeat()` 动画，会让所有以 `pumpAndSettle` 驱动本页的测试（十余个文件）超时。处理：页面加 `@visibleForTesting static bool debugConsultPulse = true`（产品口径恒为 true），`test/flutter_test_config.dart` 统一置 false（同文件既有 `TsRemoteArt.debugLoader` 先例），专门用例打开并断言 `pulsing: true`。
- 分类卡点击由「弹预选表单」改为「切换筛选」（FR-172 取代 FR-45C）；只读宠物点卡不再弹 E1（筛选是查看）。
- 表单关闭结果由 `bool` 改为 `HealthFormOutcome`（`saved(type)` / `deleted()`），用于 AC4.4 保存后切筛选；删除不动筛选（删光 → 筛选空态）。无筛选时保存保持无筛选（PRD AC66）。
- 路由 `/profile/health` 新 query：`preset` / `dose` / `deworm` / `custom` / `dateEmpty=1` → `HealthRecordPrefill`（domain 新类）原样传给表单（本 story 只消费 `custom` 作名称初值，其余归 3.2）；`safety` 逗号分隔，未知值丢弃，按 PREGNANT → PERMETHRIN 排序；`nid` → `notificationToken`（3.4 用）。
- 疫苗预设标签 9 个新 arb 键（`healthVaccinePreset*`，ID / EN 同文，取架构 §3.1 标签表；3.2 表单复用）；`OTHER` / 空 / 未知预设 → 显示 `vaccineName` 原文。驱虫类型 4 键、状态 / 筛选 / 警示 / Konsultasi 副标题等键按 story 文案；`healthTypeMenstruation` 改 Berahi / Heat。
- 类型名映射抽成顶层 `healthTypeLabel`（列表与表单共用，加了 VET_VISIT），`vaccinePresetLabel` / `dewormTypeLabel` 同文件顶层函数。
- **待确认（发版风险，未自行处理）**：App `pubspec.yaml` 仍是 `1.4.2+20`，而后端按 `X-App-Version` ≥ 1.5.0 才返回新字段、才接受 `VET_VISIT`（旧版保存 Kontrol dokter → 422）。本线不改共享的版本号，需在集成 / 发版时升到 1.5.0，否则 4.1 联调会看到状态胶囊全为空、Kontrol dokter 保存被拒。
- 未运行 `bmad-dev-story` / `bmad-code-review` 技能文件（仓库内不存在，gitignored），按 story 手动执行；code review 由本会话逐项核对 diff：修 1 处（记录卡类型行 `Spacer` 与 `Flexible` 分宽导致标签被无谓截断 → 改为 `Expanded` 包左侧），其余无 CONFIRMED 问题。

### File List

- `petgo_app/lib/features/profile/domain/health_list_item.dart`
- `petgo_app/lib/features/profile/presentation/health_list_page.dart`
- `petgo_app/lib/core/router/app_router.dart`
- `petgo_app/lib/l10n/app_id.arb`、`petgo_app/lib/l10n/app_en.arb`
- `petgo_app/test/profile/health_list_page_test.dart`
- `petgo_app/test/flutter_test_config.dart`
- `petgo_app/test/shop/consult_shop_boundary_test.dart`
- `petgo_app/test/analytics/v140_events_inventory_test.dart`
- `petgo_app/test/analytics/v112_events_test.dart`
- `petgo_app/test/pet_slot/pet_readonly_block_test.dart`
- `petgo_app/test/l10n/microcopy_rules_test.dart`

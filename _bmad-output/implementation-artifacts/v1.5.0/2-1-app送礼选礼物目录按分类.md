# Story 2.1: App 送礼选礼物目录按分类（B1 ~ B5）

Status: review

> 版本 v1.5.0 · 会话 ② gift-pack · 分支 `feat/1.5.0-gift-pack`
> 口径优先级：`decision-log.md`（11-02 / 11-11）> PRD A §3.1 / §3.7（FR-187、FR-225）> 架构 D-3、§4.1 > App 稿 B 泳道（拆页 `ui-gift-optimization/拆页-App/B1 ~ B5`，页头「新文案表」）。
> 前置：1-1（目录接口已带 `categories` / `senderExp` / `receivedFrom`）。
> 范围层：**只 App**。包装页归 2-2。

## Story

As a 送礼人,
I want 选礼物页按运营分类分组、看到价格与我能得的 EXP、知道谁已经送过,
so that 挑礼物更直观。

## Acceptance Criteria

### AC1 — 数据层 `[L0]`
1. `A/features/gift/domain/gift_catalog.dart`：解析新字段 `categories[]`（`categoryId`、`nameEn`、`nameId`、`gifts`）、礼物 `senderExp`、`receivedFrom {senders[{nickname, deleted}], more}`；旧字段解析保留（`tiers` 不再用于新 UI，但解析不删，防止空 `categories` 时兜底——兜底规则：`categories` 为空且 `tiers` 非空时，按旧的价格档 + `giftTierName` 显示，保证后端未部署时不白屏）。
2. 单元测试 `test/features/gift/gift_catalog_test.dart`：新旧 JSON 都能解析；`receivedFrom` 为 null；分类名按语言取（`id` → `nameId`，其余 → `nameEn`，与礼物名同口径）。

### AC2 — 选礼物页 B1 ~ B4 `[L0]` `[L2]`
1. 页面形态照 B1：整页（顶栏左关闭、中「Kirim hadiah untuk {petName}」= 复用 `giftPanelTitle` + 下行「{物种} · {品种}」、右宠物头像 32 双环）。由现有入口（Diary Tab / 礼物页 / Diary 详情礼物区）进入，`gift_pet_switch.dart` 多宠物选择保留。
2. 顶部 tab = 分类名（横滑），点 tab 滚动定位到该分组（B2）；手动滑动时 tab 跟随当前可见分组。
3. 每个分组标题只写分类名；礼物格：图标 + 名称 +「{n} PawCoin」（新文案 `{harga} PawCoin` / `{price} PawCoin`）+「+{n} EXP」（`senderExp`；为 0 时整行不显示）。
4. B3：有 `receivedFrom` 的礼物，EXP 行换成紫色「dari {nama}」/「dari {a}, {b}, {c} +{n}」（EN「from {name}」/「from {a}, {b}, {c} +{n}」），最多两行；注销送礼人显示 `feedDeletedUser`。
5. B4：所有分类之后，专属礼物按等级分组，标签复用 `giftExclusiveGroup`（底色按段位 `AppColors.tier*`）；最后锁区块复用 `giftLockTitle` / `giftLockSubtitle`，点击进 `/me/growth`（线上不变）。
6. 照线上保留：物种排序（服务端已排好，App 不再排序）、数量 ×99 上限、目录为空 `giftCatalogEmpty*`、宠物已删 / 被拉黑 toast。
7. Widget 测试：分组顺序 = 后端顺序；空分类不渲染（后端本就不下发，测试给空数组的分类也不渲染）；`senderExp = 0` 不显示 EXP 行；`receivedFrom.more > 0` 渲染「+N」。

### AC3 — 加载失败 B5 `[L0]`
`giftLoadFailed`「Gagal memuat hadiah」+ `commonRetry`，顶栏保留可关闭（复用现有 key）。

### AC4 — 文案 `[L0]`
新增 arb（ID / EN 两份，逐字取 App 稿「新文案表」）：`{harga} PawCoin`、`+{n} EXP`、`dari {nama}`、`dari {a}, {b}, {c} +{n}`。改 ARB 后跑 `flutter gen-l10n`，不手改生成文件。

### AC5 — 不做 `[L0]`
不删 `gift_style.dart` 的 `giftTierName`（兜底与旧代码仍引用）；不改 `orderFor` 相关后端；不加埋点（PRD A §4）。

## Tasks / Subtasks

- [x] 模型与解析 + 单测（AC1）
- [x] 选礼物页 UI（AC2、AC3）
- [x] ARB（AC4）

## Dev Notes

- 现有：`A/features/gift/presentation/gift_panel_sheet.dart`（底部面板 + `_GiftCell` + `_QtyChip` / `_StepButton`）、`gift_style.dart`（`giftTierName`、`giftIcon`）、`gift_repository.dart`。新页面可新建 `gift_catalog_page.dart` 并让 `showGiftPanelSheet` 的调用方改为 push 新页；选完礼物进 2-2 的包装页（本 story 先接一个返回 `GiftSelection` 的回调，2-2 接上）。
- 色值：紫 `#845EC9` = `AppColors.mint`、字 `ink`、灰 `muted`、选中底 `mintTint`（App 稿第四步核对结论）。
- 不要对整个文件跑 `dart format`；只 `flutter analyze`。

## 验证层级

- L0：`flutter analyze`；`flutter test test/features/gift/`
- L2（本地）：B1 ~ B5 逐屏还原度 ≥ 95%（Android 模拟器 + 真后端）

## Dev Agent Record

### Completion Notes

- **L1/L2 待本地验收**：B1 ~ B5 逐屏还原度（Android 模拟器 + 真后端）、tab 跟随滚动的手感待本地。
- L0：`flutter analyze` 无 error / warning（云端 Flutter 3.50 对存量代码报约 800 条 `material.dart` / `ref.exists` 弃用 info，非本线引入；本线新文件无新增 info 以外问题）；`flutter test test/gift test/l10n` 120 条全绿（新增 `gift_catalog_test.dart` 2 条、`gift_catalog_page_test.dart` 5 条）。
- 测试放在 `test/gift/`（仓库现有送礼测试目录），而非 story 写的 `test/features/gift/`。
- 实现要点：
  - 新页 `gift_catalog_page.dart`（整页，B1 ~ B5）：顶栏关闭 + `giftPanelTitle` + 「物种 · 品种」+ 32 双环头像；顶部分类 tab（横滑，点 tab `ensureVisible` 定位，滚动时按「越过视口顶的最后一个分组」跟随）；分组 = 后端 `categories`（空分类不渲染）→ 专属按等级（复用 `giftExclusiveGroup` + 段位渐变）→ 锁区块（复用 `giftLockTitle` / `giftLockSubtitle`）。后端未部署（无 `categories`）→ 价格档 + `giftTierName` 兜底。
  - 礼物格：图标 + 名称 +「{n} PawCoin」+「+{n} EXP」（0 不显示）；收到过 → 紫色「dari …」（最多两行，注销显示 `feedDeletedUser`），替代 EXP 行。点礼物直接 pop `GiftCatalogPicked`（本屏无选中态，2-2 接包装页）；锁区块 pop `GiftCatalogOpenLevelPage`；目录接口拒绝 pop `GiftCatalogCannotSend`。
  - 入口切换（现有 `showGiftPanelSheet` 调用方改 push 新页）放在 2-2 与包装页一起做；旧面板本 story 不删。
  - 文案：`giftCatalogPrice`「{price} PawCoin」、`giftCatalogExp`「+{n} EXP」、`giftReceivedFrom`「dari {names}」、`giftReceivedFromMore`「dari {names} +{n}」（表里「dari {a}, {b}, {c} +{n}」：App 把最多 3 个名字用「, 」拼成 `names` 传入，效果逐字相同）。

- Code review（独立复审）处置：
  - CONFIRMED #1 滚到底时最后几个分组标题到不了顶、tab 永不高亮 → 滚到底直接激活最后一个。已修。
  - CONFIRMED #2 tab 条是懒加载 ListView、当前 tab 不滚进可见区 → 改为非懒加载 Row，激活时 `ensureVisible` 居中。已修。
  - CONFIRMED #3 「到顶」阈值按整页（含状态栏 / 顶栏）算 → 改为以列表视口为基准（阈值 24）。已修。
  - PLAUSIBLE #4 连点两个 tab 时动画标志被提前放开 → 改计数。已修。
  - #5 新页尚无入口 → 2-2 已把 `runGiftSendFlow` 改为 push 本页（与包装页同批）。
  - #6 数量不在本屏：UI 稿 B1「本屏不再有选中态，点礼物直接进 C1」，数量（×1/×5/×10 + 步进，上限 99）挪到 2-2 包装页。
  - #7 补测：锁区块 pop、目录拒绝 pop、点 tab 激活。tab chip 加 `shrinkWrap` 触控区（原 40 高容器里被裁）。
  - #8 PawCoin 数额在 EN 也用点号千分位：与 App 其余 PawCoin 金额口径一致，保留。

### File List

- `petgo_app/lib/features/gift/domain/gift_catalog.dart`（改：`categories`、`senderExp`、`receivedFrom`）
- `petgo_app/lib/features/gift/presentation/gift_catalog_page.dart`（新）
- `petgo_app/lib/l10n/app_en.arb`、`app_id.arb` + 生成产物（改）
- `petgo_app/test/gift/gift_catalog_test.dart`、`gift_catalog_page_test.dart`（新）

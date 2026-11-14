# Story 2.1: App 送礼选礼物目录按分类（B1 ~ B5）

Status: ready-for-dev

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

- [ ] 模型与解析 + 单测（AC1）
- [ ] 选礼物页 UI（AC2、AC3）
- [ ] ARB（AC4）

## Dev Notes

- 现有：`A/features/gift/presentation/gift_panel_sheet.dart`（底部面板 + `_GiftCell` + `_QtyChip` / `_StepButton`）、`gift_style.dart`（`giftTierName`、`giftIcon`）、`gift_repository.dart`。新页面可新建 `gift_catalog_page.dart` 并让 `showGiftPanelSheet` 的调用方改为 push 新页；选完礼物进 2-2 的包装页（本 story 先接一个返回 `GiftSelection` 的回调，2-2 接上）。
- 色值：紫 `#845EC9` = `AppColors.mint`、字 `ink`、灰 `muted`、选中底 `mintTint`（App 稿第四步核对结论）。
- 不要对整个文件跑 `dart format`；只 `flutter analyze`。

## 验证层级

- L0：`flutter analyze`；`flutter test test/features/gift/`
- L2（本地）：B1 ~ B5 逐屏还原度 ≥ 95%（Android 模拟器 + 真后端）

## Dev Agent Record

### Completion Notes

### File List

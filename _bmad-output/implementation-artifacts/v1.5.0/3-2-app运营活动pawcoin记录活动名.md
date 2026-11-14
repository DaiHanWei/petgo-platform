# Story 3.2: App 运营活动 PawCoin 记录活动名（B1）

Status: ready-for-dev

> 版本 v1.5.0 · 会话 ⑥ ops-activity · 分支 `feat/1.5.0-ops-activity`
> 口径优先级：`decision-log.md`（11-13：PawCoin 记录活动赠送显示「Event · 活动名」；全部用「Event · 」写法）> PRD §3.7、§6「App」、附录 A.1 末行 > App 整合稿执行说明「PawCoin 记录」> 架构 OA-19、§4.1。拆页 `拆页-App/B1-KoinJatahSaya-PawCoin记录-活动赠送.html`。B2（免费次数记录活动行）由 gift-pack 6-3 实现，本 story 不做。
> 前置：1-6（后端 `refLabel`）——可用 mock JSON 开发。
> 范围层：**只 App**。

## Story

As a 用户,
I want PawCoin 记录里活动送的那一行写「Event · {活动名}」,
so that 我知道这笔 PawCoin 是哪个活动给的。

## Acceptance Criteria

### AC1 — 模型 `[L0]`
`A/features/pawcoin/domain/pawcoin_transaction.dart` 的 `PawCoinTxnItem` 加可空 `refLabel`（JSON `refLabel`，缺省 null）；`fromJson` 单测覆盖有 / 无。

### AC2 — 标签 `[L0]`
`A/features/pawcoin/presentation/pawcoin_page.dart` 的 `_txnLabel`：**先看** `refType == 'ACTIVITY'`：
- `refLabel` 非空 → `l10n.growthExpEvent(refLabel)`（复用现有 key「Event · {name}」）；
- `refLabel` 为空 → `l10n.growthExpEventNoName`（「Event」）。
其余 `refType`（`GIFT`、`GIFT_REFUND`、其他 → `_typeLabel`）逐字不变；普通 `BONUS` 仍显示「Bonus」。图标照旧按 `BONUS` 的 `card_giftcard_outlined`（`_txnIcon` 不改）。
- ⚠️ gift-pack 6-3 会改这个页面的结构（标题、Tab、`_GiftOrdersEntry` 位置），本 story **只改 `_txnLabel` 函数与模型**，不动 `_LedgerRow` / 页面结构 / 路由，便于合并。

### AC3 — 测试 `[L0]`
`test/pawcoin/pawcoin_page_test.dart` 补：`refType = ACTIVITY` + `refLabel = 'Lomba Foto Kucing Lucu'` → 显示「Event · Lomba Foto Kucing Lucu」；`refLabel` 缺省 → 「Event」；`refType = null` 的 `BONUS` → 「Bonus」；金额颜色 / 图标不变。

## Tasks / Subtasks

- [ ] 模型字段（AC1）
- [ ] `_txnLabel`（AC2）
- [ ] 测试（AC3）

## Dev Notes

- 不新增 `.arb` 词条；不对整个文件跑 `dart format`。
- 旧版 App 不认 `refLabel`，活动行显示「Bonus」（架构 §6，可接受）。

## 验证层级

- L0：`flutter analyze`；`flutter test test/pawcoin/`
- L2（本地）：B1 对照拆页 ≥ 95%（gift-pack 6-3 合入后的「Koin & Jatah Saya」页上验）

## Dev Agent Record

### Completion Notes

### File List

# Story 6.3: App 礼包「Koin & Jatah Saya」合并页（D1 ~ D8）

Status: ready-for-dev

> 版本 v1.5.0 · 会话 ② gift-pack · 分支 `feat/1.5.0-gift-pack`
> 口径优先级：`decision-log.md`（11-13：与 PawCoin 页合并、不用「Dompet」、路由不变）> PRD B §3.5 / §7 > 架构 D-27、D-28、§4.2、§5（活动来源显示）> App 稿 D 泳道（页头「研发执行说明」第 5 步）。
> 前置：4-4。
> 范围层：**只 App**。

## Story

As a 用户,
I want 在一页里看到 PawCoin 余额和我所有的免费次数、各自的记录,
so that 我知道能用什么、什么时候到期。

## Acceptance Criteria

### AC1 — 数据层 `[L0]`
`A/features/entitlement/data/entitlement_repository.dart`：`fetchSummary()`（`GET /api/v1/me/entitlements`）、`fetchHistory(cursor)`；模型 `EntitlementSummary`（`totalRemaining`、`types[{family, remaining, earliestExpiry, expiringSoon, templates[{template, remaining, earliestExpiry}]}]`）、`EntitlementHistoryItem`。Provider `entitlementSummaryProvider` 登记进 `resetUserScopedCaches`；成功使用次数 / 买礼包 / 送免费礼物后 `invalidate`。单测覆盖 JSON。

### AC2 — 页面 D1 ~ D2 `[L0]` `[L2]`
改 `A/features/pawcoin/presentation/pawcoin_page.dart`，**路由 `/me/pawcoin` 不变**（退款成功页、订单列表 PawCoin 提示条、商城顶栏、各处「去充值」跳转都不用改）。
1. 标题「Koin & Jatah Saya」（替换线上「Saldo PawCoin」页标题）。
2. 余额渐变卡 +「Isi Saldo」照线上不动；下方一行灰字「PawCoin hanya untuk dipakai di TailTopia. Tidak bisa dicairkan atau dipindah ke akun lain.」。
3. 「JATAH GRATIS」区：每个 family 一张卡——
   - KTP：badge 图标 +「KTP HD」+「{n} jatah」；下方模板胶囊「KTP 1 · Paspor 1 · SIM 1 · Pelajar Bintang 1 · … · Desain apa saja 2」（剩余 0 的模板不显示）；「Berakhir paling cepat {date}」；「Pakai」→ KTP 卡页面。
   - 性格测试：psychology 图标 +「Buka hasil Tailsonality」+「{n} jatah」；「Berakhir {date}」（单批）或「Berakhir paling cepat {date}」；「Pakai」→ 性格测试页。
   - 礼物：featured_seasonal_and_gifts 图标 +「Hadiah Rp1.000 gratis」+「{n} jatah」；最早到期 ≤ 7 天时红字「{n} jatah berakhir {date}」；「Pakai」→ `/home`（待拍板 Q-4）。
   - `expiringSoon = true` 的卡到期行标红。
4. 「Riwayat Hadiah」送礼记录入口（`_GiftOrdersEntry`）位置不动。
5. 原「Riwayat transaksi」标题换成两个 Tab「Riwayat PawCoin」/「Riwayat Jatah」（样式照 `me_page.dart` 的 `_MeTab`）；PawCoin Tab 内 `_LedgerRow` / 加载更多照旧（行不可点）。

### AC3 — 免费次数记录 D3 `[L0]`
每条：`+N` / `−N`、类型（KTP 写模板：「KTP HD · KITAS」；ANY →「KTP HD · Desain apa saja」）、来源或用途 + 日期：
- 发放：PACK「Paket Hemat · Paket {n}」（复用 6-4 订单名 key）；ACTIVITY「Event · {labelId|labelEn}」（按语言取）；ADMIN「Dari tim TailTopia」。
- 使用：「Dipakai untuk {pet}」（`petName` 为空则只显示日期）。
- 分页加载更多；行不可点。

### AC4 — 空态与失败 D4 ~ D7 `[L0]`
- D4 无次数：「JATAH GRATIS」区换成浅紫说明卡「Belum ada jatah gratis」+「Jatah dari Paket Hemat atau event akan muncul di sini.」+「Lihat Paket Hemat」→ `/starter-pack`（埋点 source 不报——PRD 四个 source 里没有本页）。
- D5 无次数且礼包停售（`starterPackProvider.enabled = false`）：同 D4 但不显示按钮。
- D6 新用户：余额 0、无次数、两 Tab 都空态（复用现有空态 key）。
- D7：余额与次数**任一**接口失败 → 整页 `_ErrorState` + 重试（照线上），不只显示一半。

### AC5 — 到期通知 D8 `[L0]`
`notification_center_page.dart`：`PACK_CREDIT_EXPIRING` 加进读后端 `title` / `body` 的分支（与 `OPS_ANNOUNCEMENT` / `PET_SLOT_EXPIRING` 同一行 `_storedTitle` / `_storedBody`）；图标用「Promo & aktivitas」推送分类的 `campaign_rounded`（1.4.2 定：通知图标与推送分类一一对应；若现有 PROMO 类图标不同，以现有映射为准）；点击 → `/me/pawcoin`；`notification_item.dart` 的后端文案类型集合加 `PACK_CREDIT_EXPIRING`。**不加** `kPushDirectTypes`。

### AC6 — 文案 `[L0]`
PRD B §7 逐字：Koin & Jatah Saya、PawCoin hanya untuk…、JATAH GRATIS、Riwayat PawCoin、Riwayat Jatah、{n} jatah、Pakai、Berakhir paling cepat {date}、{n} jatah berakhir {date}、Desain apa saja、Dipakai untuk {pet}、Dari tim TailTopia、Belum ada jatah gratis、Jatah dari Paket Hemat…、Lihat Paket Hemat。
UI 稿有、§7 没有（ID 逐字取 UI 稿，EN 按下列建议，Completion Notes 标「EN 待产品复核」，待拍板 Q-9）：
| ID（UI 稿） | EN（建议） |
|---|---|
| KTP HD | KTP HD |
| Buka hasil Tailsonality | Tailsonality result unlock |
| Hadiah Rp1.000 gratis | Free Rp1.000 gift |
| Berakhir {date} | Expires {date} |
| Event · {name} | Event · {name} |
| Paspor / Pelajar Bintang / Pelajar Malam / Pelajar Ceria / Pelajar Klasik（KTP / KITAS / SIM 原样） | Passport / Student Bintang / Student Malam / Student Ceria / Student Klasik |
若 KTP 模块已有这些卡种 / 配色的 arb key，**优先复用现有 key**，不新增。

## Tasks / Subtasks

- [ ] 仓库 / 模型 / provider（AC1）
- [ ] 合并页（AC2、AC3）
- [ ] 空态与失败（AC4）
- [ ] 通知（AC5）
- [ ] ARB + widget 测试（AC6）

## Dev Notes

- 现有：`A/features/pawcoin/presentation/pawcoin_page.dart`（`_GiftOrdersEntry`、`_LedgerRow`、`_txnLabel`、`_ErrorState`）、`pawcoin_controller.dart`。PawCoin 流水的 `_txnLabel` 本 story 不改（活动 `refType = ACTIVITY` 的「Event · 活动名」归 ⑥）。
- 余额 0 时余额卡样式照线上。
- 不新增「我的权益」独立页（PRD 11-13 取消）。

## 验证层级

- L0：`flutter analyze`；`flutter test test/features/pawcoin/ test/features/entitlement/ test/features/notify/`
- L2（本地）：D1 ~ D8 还原度 ≥ 95%

## Dev Agent Record

### Completion Notes

### File List

# Story 6.4: App 礼包入口与订单（A1 ~ A3、B1 / B2、G1 / G2、F1 ~ F3）

Status: ready-for-dev

> 版本 v1.5.0 · 会话 ② gift-pack · 分支 `feat/1.5.0-gift-pack`
> 口径优先级：`decision-log.md`（11-13「我的」页活动一行三格、商城胶囊改余额 + 次数、礼包单进我的订单不加页签）> PRD B §3.2 ~ §3.5、§7 > 架构 D-26、§4.2 > App 稿 A / B / F / G 泳道（页头「研发执行说明」第 6 步）。
> 前置：4-4、6-1、6-2、6-3。
> 范围层：**只 App**。

## Story

As a 用户,
I want 在「我的」页、商城顶栏、付费抽屉、我的订单里都能找到礼包与免费次数,
so that 入口顺手、买过的单查得到。

## Acceptance Criteria

### AC1 — 「我的」页「活动」一行三格 A1 ~ A3 `[L0]` `[L2]`
`A/features/me/presentation/me_page.dart` 的 `_ActivitySection` 改为一张卡、一行三格（图标 + 名称 + 一行副信息，卡高约 86）：
1. 「Pesanan Saya」+「Lacak pesanan」（替换线上「Termasuk saldo PawCoin」）→ `/me/orders`。
2. 「Koin & Jatah」：图标 = `ShopCoinMark` + 右下白底票券角标；副信息「{balance} · {n} jatah」（`balance` 用商城顶栏同款简写 `_compactIdr`——抽成共享函数 `compactIdr` 放 `A/core/format/` 或现有格式化文件，两处共用；无次数写「0 jatah」）→ `/me/pawcoin`。**常驻**（礼包停售也显示）；游客点击先走登录。
3. 「Paket Hemat」+「Hemat Rp{maxSaving}」；冷却期副信息「Beli lagi {n} hari」（A2）；停售时整格不渲染、剩两格（A3）。→ `/starter-pack`（埋点 source `me_page`）。

### AC2 — 付费抽屉推荐行 B1 / B2 `[L0]` `[L2]`
KTP 高清抽屉（`hd_paywall_sheet.dart`）与性格测试结果解锁抽屉（`tailsonality_result_page.dart` 的 `PayChannelPicker`）通过 6-1 的 `recommendation` 插槽，在两张卡与主按钮之间加一行「Paket Hemat Rp{tier1Price} · HD 8 desain KTP + Tailsonality ›」（价格取档 1 当前价；EN 同 §7）；点击关闭抽屉、进 `/starter-pack`（source `hd_paywall` / `tailsonality_paywall`）。
**不显示**：冷却期、停售、或该抽屉本来就能用免费次数（该款 / ANY 有剩余，或性格测试有剩余）时。性格测试配型抽屉不加。

### AC3 — 商城顶栏胶囊 G1 / G2 `[L0]`
`A/features/shop/presentation/toko_page_v3.dart` 的 `_pawcoinCapsule`：在余额后加「| 票券图标 + 次数」（`totalRemaining`，> 99 写「99+」）；0 次只显示余额（同线上，G2）；游客仍显示「Masuk」。点击照旧进 `/me/pawcoin`。胶囊高 32 / 命中区 44 不变。

### AC4 — 订单列表与详情 F1 ~ F3 `[L0]` `[L2]`
1. `A/features/order/data/order_repository.dart` 请求加 `includeStarterPack: true`；`OrderType` 加 `starterPack`（`fromCode('STARTER_PACK')`）。
2. 列表（`OrderCardV2`）：礼包单出现在「Semua」里，**不新增页签**；标题「Paket Hemat · Paket {n}」；金额、方式、状态、时间照卡片；PENDING：警示色「待支付」+ 按钮「Bayar sekarang」（复用现有待支付卡按钮）→ 详情 F3。
3. 详情（照 `OrderDetailPage`）：主信息卡（标题 + 状态徽章 + 单号 / 方式 / 金额 / 时间）；新增「Isi paket」内容卡（每项带到期日，名额写「Aktif s/d」）+「Lihat Koin & Jatah Saya」入口（同 keepsake「查看」入口样式）；PENDING（F3）主按钮「Lanjutkan pembayaran」→ 4-2 状态接口取二维码 → `showQrPaymentSheet`；付款成功后刷新为「已完成」。
4. 过期没付的单从列表消失（后端已过滤）。

### AC5 — 文案 `[L0]`
PRD B §7 逐字：Lacak pesanan、Koin & Jatah、{balance} · {n} jatah、Hemat Rp10.000（实际用「Hemat Rp{x}」同一 key）、Beli lagi {n} hari、Paket Hemat Rp8.000 · HD 8 desain KTP + Tailsonality（价格参数化为 `{price}`，ID / EN 两份）、Paket Hemat · Paket {n}、Isi paket。

### AC6 — 测试 `[L0]`
Widget：「我的」页三格 / 停售两格 / 冷却副信息；推荐行四种隐藏条件；胶囊 0 次 / 5 次 / 120 次（99+）/ 游客；订单卡 PENDING 按钮；`OrderType.fromCode('STARTER_PACK')`。

## Tasks / Subtasks

- [ ] 「我的」页三格（AC1）
- [ ] 推荐行（AC2）
- [ ] 商城胶囊（AC3）
- [ ] 订单列表 / 详情（AC4）
- [ ] ARB + 测试（AC5、AC6）

## Dev Notes

- `_compactIdr` 现在是 `toko_page_v3.dart` 的私有函数：抽成共享时保持输出逐字不变（「50rb」），Toko 页测试不改断言。
- 订单详情现有类型分支见 `A/features/order/domain/order_detail.dart`（keepsake 的「查看」目标写法）。

## 验证层级

- L0：`flutter analyze`；`flutter test test/features/me/ test/features/shop/ test/features/order/ test/features/profile/ test/features/tailsonality/`
- L2（本地）：A1 ~ A3、B1 / B2、F1 ~ F3、G1 / G2 还原度 ≥ 95%

## Dev Agent Record

### Completion Notes

### File List

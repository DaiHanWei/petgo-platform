# Story 6.2: App 礼包页、付款与购买成功（C1 ~ C10、D9）

Status: ready-for-dev

> 版本 v1.5.0 · 会话 ② gift-pack · 分支 `feat/1.5.0-gift-pack`
> 口径优先级：`decision-log.md`（11-11 / 11-12 / 11-13 超值礼包）> PRD B §3.1 ~ §3.4、§3.6、§3.7（停售 toast）、§4、§7 > 架构 D-22、D-29、§4.2 > App 稿 C 泳道、D9（拆页 `超值礼包/拆页-App/`，页头「研发执行说明」第 4 步）。
> 前置：4-2、6-1。
> 范围层：**只 App**。入口与订单归 6-4。

## Story

As a 用户,
I want 在一个页面里比较三档礼包、选一档付款、看到到账内容,
so that 我知道买到了什么、去哪用。

## Acceptance Criteria

### AC1 — 数据层 `[L0]`
`A/features/starter_pack/data/starter_pack_repository.dart`：`fetchStarterPack()`（`GET /api/v1/starter-pack`）、`purchase(tier, channel, requestId)`、`fetchPurchase(token)`；模型 `StarterPackView`（`enabled`、`defaultTier`、`maxSaving`、`balance`、`tiers[{tier, price, separateTotal, saving, contents, slotState}]`、`cooldown{until, daysLeft}?`、`pending{purchaseToken, tier, price, payExpiresAt}?`）、`StarterPackPurchase`。错误码映射：`starter-pack-disabled` / `-pending` / `-cooldown` / `-slots-full` / `pawcoin-insufficient`。Provider `starterPackProvider`（6-4 的入口与推荐行共用），登记进 `app.dart` 的 `resetUserScopedCaches`（用户维度缓存，换账号要失效）。单测覆盖 JSON 与错误码。

### AC2 — 礼包页 C1 ~ C6 `[L0]` `[L2]`
路由 `/starter-pack`（加在 `A/core/router/app_router.dart`，需登录；游客进入先走登录）。
1. 顶栏「Paket Hemat」；主标题「Coba semua fitur premium sekaligus」+「Lebih hemat dibanding beli satu per satu.」。
2. 三张档位卡（单选）：「Paket {n}」、划线「Rp{separateTotal}」、价格、「Hemat Rp{saving}」、内容清单逐档累加（`HD untuk 8 desain KTP` / `1× buka hasil Tailsonality` / `1 slot hewan · 30 hari` / `5× hadiah Rp1.000 gratis`，图标 badge / psychology / pets / featured_seasonal_and_gifts）；档 3 标「Paling hemat」。默认选 `defaultTier`；选中卡紫描边 + 实心单选，主按钮价格跟随（C2）。
3. 底部说明「Jatah berlaku 3 bulan sejak dibeli · slot hewan langsung aktif 30 hari. Setelah membeli, paket bisa dibeli lagi 7 hari kemudian.」；吸底主按钮「Beli Rp{x}」（`SafeArea` + 16）。
4. C3 冷却：三档照常可看，主按钮置灰「Bisa beli lagi dalam {n} hari」（`daysLeft`）。
5. C4 有未付单：顶部黄条（schedule 图标）「Pembayaran QRIS belum selesai」+「Paket {n} · Rp{x} · bayar dalam {mm:ss}」（按 `payExpiresAt` 每秒倒计时，到 0 刷新页面）；档位锁定为该单档位；主按钮「Lanjutkan pembayaran」→ `fetchPurchase(token)` 拿二维码 → 线上 `showQrPaymentSheet`。
6. C5 名额已满：`slotState = FULL` 的档置灰并标「Slot penuh」，不可选。
7. C6 加载失败：照 `EmptyState`（cloud_off 图标 + 「Gagal memuat」+「Periksa koneksi lalu coba lagi.」+「Coba lagi」，复用现有 key）。
8. `enabled = false`（停售）：不显示档位，直接 toast「Paket Hemat sedang tidak dijual.」并返回上一页（入口在停售时本就隐藏，只有深链 / 旧通知会走到这里）。

### AC3 — 付款抽屉 C7 / C8 `[L0]` `[L2]`
点「Beli」→ 6-1 改版后的 `PayChannelPicker`：头卡 = 礼包摘要（redeem 图标 +「Paket Hemat · Paket {n}」+ 内容缩写一行 + 价格）；`body` 底部说明「Pembelian paket tidak bisa dibatalkan atau dikembalikan.」；主按钮「Bayar Rp{x}」（复用现有支付按钮文案 key）；余额够默认 PawCoin（C7），不够默认直接付款（C8）。
- PawCoin → `purchase(...)` → 成功进 C9；QRIS → 返回二维码 → `showQrPaymentSheet`（60 分钟、失败可重试照线上）→ 轮询 `fetchPurchase` 到 `status = PAID && fulfilled = true` → C9（PAID 但未 fulfilled 时显示转圈，最多等 30 秒后仍进 C9 并以「Lihat Koin & Jatah Saya」引导）。
- 409 映射：`cooldown` / `pending` / `slots-full` → 刷新页面状态 + toast（复用通用失败文案）；`pawcoin-insufficient` → 留在抽屉。
- `requestId`：每次点「Bayar」生成，终态清空（照 `newGiftRequestId` 的做法）。

### AC4 — 购买成功 C9 / C10 `[L0]` `[L2]`
新页（不可返回到付款抽屉）：check 图标 +「Pembelian berhasil!」+「Paket {n} · Rp{x} · {渠道名}」；逐行：KTP「HD untuk 8 desain KTP」+「Berlaku s/d {date}」+ 入口「Buat KTP」；性格测试「1× buka hasil Tailsonality」+「Berlaku s/d {date}」+「Mulai tes」；名额 `GRANTED`「+1 slot hewan」+「Aktif s/d {date}」+「Tambah hewan」，`EXTENDED`「Slot {pet} diperpanjang 30 hari」+「Aktif s/d {date}」、无入口（C10；`petName` 为空时用「1 slot hewan · 30 hari」，待拍板 Q-5）；礼物「5× hadiah Rp1.000 gratis」+「Berlaku s/d {date}」+「Kirim hadiah」。吸底「Lihat Koin & Jatah Saya」（→ `/me/pawcoin`）+「Selesai」（回到进入礼包页之前的页面）。入口路由：KTP 建卡页、性格测试开始页、添加宠物流程（grep 现有路由）；「Kirim hadiah」→ `/home`（待拍板 Q-4）。

### AC5 — 运营跳转 `STARTER_PACK` 与停售 D9 `[L0]`
1. `A/features/ops/domain/ops_target.dart` 的 `OpsAppPage` 加 `starterPack('STARTER_PACK')`（追加在末尾，`fromWire` 自动识别）。
2. `A/features/ops/presentation/open_ops_target.dart`：`starterPack` → 读 `starterPackProvider`：`enabled` → push `/starter-pack`（埋点 source `ops_popup`）；停售 → **不导航**，`showAppToast("Paket Hemat sedang tidak dijual.")`。
3. 开屏弹窗（`ops_popup_coordinator.dart`）点按钮且停售：只关弹窗、**不弹 toast**——给 `openOpsTarget` 加命名参数 `bool fromPopup = false`，弹窗调用处传 true。
4. 单测：fromWire 解析、停售分支两种来源。

### AC6 — 埋点 `[L0]`（D-29）
经 `Analytics` 门面上报（事件名字面量，登记白名单测试）：`starter_pack_viewed {source}`（进入礼包页时；`source` 由入口传入：`me_page` / `hd_paywall` / `tailsonality_paywall` / `ops_popup`）、`starter_pack_purchase_clicked {tier, channel}`（点「Bayar」时，`channel` = `pawcoin` / `qris`）。

### AC7 — 文案 `[L0]`
PRD B §7 下列条目 ID / EN **逐字**写进两份 arb：Paket Hemat、Coba semua fitur…、Lebih hemat dibanding…、Paket {n}、Paling hemat、Hemat Rp{x}、Slot penuh、HD untuk 8 desain KTP、1× buka hasil Tailsonality、1 slot hewan · 30 hari、5× hadiah Rp1.000 gratis、有效期与限购说明、Beli Rp{x}、Lanjutkan pembayaran、Pembayaran QRIS belum selesai、Paket {n} · Rp{x} · bayar dalam {mm:ss}、Pembelian paket tidak bisa…、Pembelian berhasil!、Berlaku s/d {date}、Aktif s/d {date}、Buat KTP / Mulai tes / Tambah hewan / Kirim hadiah、Lihat Koin & Jatah Saya、Selesai、Paket Hemat sedang tidak dijual.、Slot {pet} diperpanjang 30 hari。
UI 稿有、§7 没有：C3「Bisa beli lagi dalam {n} hari」——ID 逐字取 UI 稿，EN 用「You can buy again in {n} days」，Completion Notes 标「EN 待产品复核」（待拍板 Q-9）。

## Tasks / Subtasks

- [ ] 仓库 / 模型 / provider（AC1）
- [ ] 礼包页（AC2）
- [ ] 付款抽屉接入（AC3）
- [ ] 成功页（AC4）
- [ ] ops 跳转与停售（AC5）
- [ ] 埋点、ARB、测试（AC6、AC7）

## Dev Notes

- 新 feature 目录 `A/features/starter_pack/{data, domain, presentation}`。
- 二维码页：`A/shared/widgets/qr_payment_sheet.dart`（`showQrPaymentSheet`），沿用 KTP / 充值的调用写法。
- toast：`showAppToast`（14 号字、离底 90）。
- 日期格式按语言（「13 Feb 2027」），复用现有日期工具。

## 验证层级

- L0：`flutter analyze`；`flutter test test/features/starter_pack/ test/features/ops/`
- L2（本地）：C1 ~ C10、D9 还原度 ≥ 95%；QRIS 沙箱 / stub 付款到成功页

## Dev Agent Record

### Completion Notes

### File List

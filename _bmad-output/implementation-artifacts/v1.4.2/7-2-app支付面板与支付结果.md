# Story 7.2: 支付面板与支付结果（App，E1–E7）

Status: ready-for-dev

> 版本 v1.4.2 · 主题 shop · 分支 `feat/1.4.2-shop`
> 口径优先级：`决策日志-shop.md`（S-1、S-7）> PRD `电商/1-4-2电商prd.md`（§3.0 旅程 C 4–7、§3.2「支付面板」、§3.8 FR-134 ①、§4、§6 E 组）> `architecture-v1.4.2-shop-delta.md`（D-11、D-19、P-3、P-9、P-10、P-11）> UI 定稿 `电商/ui-1-4-2-shop-integrated-v1.html` E1–E7 每屏说明与「共用组件」说明；云端执行以 `cloud-rules-shop.md` 为准。
> 前置：**7.1**（结算提交后 `?autopay=1` 进订单详情、E6 页 `/shop/orders/:token/paid`、`shopOrderCoinCashSplit`）、**3.3**（`POST /me/shop-orders/{token}/payment-check`、出码 10 分钟限制、宽限期 `displayStatus`）、**6.2**（订单详情 `canPay` / `displayStatus` 字段）。
> 范围层：**只 App**。订单列表「去支付」与订单详情 v3 归 9.1（复用本 story 的支付启动器）。
> 迁移：无。

## ⚠️ 规划收口补充（2026-11-05，优先于下文正文；来源 `决策日志-shop.md`「story 编写后收口」）

- **C-4**：应付为 0（满减 + 免运券抵光）→ 提交即 `markPaid(source=ZERO_PAY)`，不出码，同纯 PawCoin 路径（App 直接进支付成功页）。
- **C-11**：文案：PRD §6 与 UI 稿冲突以 PRD §6 为准（推荐栏头「Untuk {pet}」、详情「标题 → 价格」顺序、确认收货提示）；PRD §6 未列、UI 稿有的文案先按 UI 原文实现，Completion Notes「待设计确认」列出。

## Story

As a 买家,
I want 支付面板只显示我要扫码付的金额、付完能主动确认,
so that 付款不迷惑、不用干等。

## 接口契约（计划名，开工前核对 3.3 / 6.2 实际提交）

- `POST /api/v1/me/shop-orders/{token}/pay`（现有）→ `ShopPayResult {orderStatus, paymentIntentToken, payload}`；3.3 后：已有可用码时复用同一码；订单创建超过 10 分钟仍无可用码 → 拒绝出码（ProblemDetail，计划 `type` 后缀 `payment-window-closed`，**以 3.3 实际为准**）；网关取码失败 → 5xx。
- `POST /api/v1/me/shop-orders/{token}/payment-check`（3.3 新）→ `{paid: bool, status: String}`；同订单 10 秒内重复调用服务端不再查网关（直接返回上次结果）。
- `GET /api/v1/me/shop-orders/{token}`（6.2 扩展）新增 `canPay: bool`、`displayStatus`（宽限期待支付为 `CANCELLED`，S-7）；旧后端缺键 → null。

## Acceptance Criteria

> 层级：`[L0]` `flutter analyze` / `flutter test`（云端可跑）· `[L1]` 连本地 / stag 后端联调 · `[L2]` Android 模拟器视觉（本地）。

### AC1 — 共用面板只加可选参数，默认值 = 现状 `[L0]`（P-11、PRD「支付面板」末段、UI「共用组件」）
`A/shared/widgets/qr_payment_sheet.dart:43` `showQrPaymentSheet(...)` 在现有 `payload / pollPaid / orderRef / onAborted / amountIdr / expiresAt` 之后**只追加**以下具名可选参数（P-11 点名的四个必须同名）：

| 参数 | 类型 / 默认 | 作用（电商传入） | 默认时行为 |
|---|---|---|---|
| `title` | `String?` = null | 面板标题行「Pembayaran」+ 右侧关闭 ×（key `qrPayCloseX`，等同现 `_cancel`，resolve false，不触发 `onAborted`） | 不画标题行（现状） |
| `amountLabel` | `String?` = null | 金额标签「Bayar via QRIS」 | `l10n.qrPayTotalLabel`（现状） |
| `coinHeldIdr` | `int?` = null | 金额下方「+ PawCoin Rp {x} sudah ditahan」（>0 才画） | 不画 |
| `showPaidCheck` | `bool` = false | 「Saya sudah bayar」按钮（E1 / E3 / E4） | 不画 |
| `onPaidCheck` | `Future<bool> Function()?` = null | 点「我已付款」调用；true = 已到账（同轮询到账：关面板 resolve true） | — |
| `notPaidMessage` | `String?` = null | `onPaidCheck` 返回 false / 抛错时的 toast（E4） | — |
| `hideCancel` | `bool` = false | 去掉红色「Batalkan Pembayaran」（`vetPayCancel`） | 画（现状） |
| `collapsibleGuide` | `bool` = false | 支付指引（支持的 App + 5 步）收进「Cara bayar & aplikasi yang didukung ›」折叠，默认收起 | 平铺（现状） |
| `adaptiveHeight` | `bool` = false | 面板高度按内容自适应（上限屏高 90%，超出内部滚动） | 固定 60% 屏高（现状，`:187`） |
| `savedToastMessage` | `String?` = null | 存图成功 toast 换成 E2 扩写文案 | `cardSavedToGallery`（现状） |
| `onSaved` | `VoidCallback?` = null | 存图**成功后**回调一次（电商在回调里埋 `toko_payment_qr_saved`；共用组件内部不埋点） | 不回调 |
| `expiredBody` | `String?` = null | 过期态正文换成「Waktu pembayaran habis. Pesanan dibatalkan.」（E5） | `qrPayExpiredBody`（现状） |
| `codeFailed` | `bool` = false | 以 E7「Kode QR gagal dibuat」态打开（保存禁用、无「我已付款」） | — |
| `onRetryCode` | `Future<String?> Function()?` = null | E7「Coba lagi」：返回新 payload 即切回正常态；返回 null / 抛错保持 E7；为 null 时 E7 不画重试 | — |

1. 返回类型仍为 `Future<bool>`（文件头注释 :28-31 的硬约束）；`QrPaymentAborted` 无参 const 构造保留（:15-26）。
2. `QrPaySummary`（`qr_pay_parts.dart:51`）加可选 `label`、`coinHeldIdr`；`QrPayCodeCard`（:153）加可选 `expiredBody`、`failed`、`onRetry`；`QrPayGuide`（:333）加 `collapsible`；`QrPayBottomBar`（:416）加可选 `paidCheckLabel` / `onPaidCheck` / `paidCheckBusy`。**全部默认值 = 现状**，`recharge_page.dart:344`、`vet_timed_pay_page.dart:567` 等直接使用 parts 的页面不改一行。
3. `saveQrPaymentImage`（`qr_pay_save.dart:53`）加可选 `String? savedMessage`、`VoidCallback? onSaved`；默认行为不变（两个现有调用点不传）。

### AC2 — E1 电商面板 `[L0 widget]` `[L2]`（FR-128.8、UI E1）
1. 电商调用传：`title`「Pembayaran」、`amountLabel`「Bayar via QRIS」、`amountIdr` = 现金段（沿用 `_cashSegment`，`shop_order_detail_page_v2.dart:428`）、`coinHeldIdr` = `order.coinAmount`（>0 时）、`showPaidCheck: true`、`onPaidCheck`、`notPaidMessage`、`hideCancel: true`、`collapsibleGuide: true`、`adaptiveHeight: true`、`savedToastMessage`、`onSaved`、`expiredBody`、`orderRef` = `displayNo`、`expiresAt` = 订单 `expiresAt`（**仍是建单 + 60 分钟**，S-1：App 倒计时 60 分钟，服务端码 65 分钟）。
2. 版式自上而下（原有模块全部保留）：标题行（Pembayaran + ×）→ 金额区（「Bayar via QRIS」+ 金额，混合时下方「+ PawCoin Rp 12.000 sudah ditahan」；右侧「Bayar dalam mm:ss」倒计时）→ 二维码 → 订单号可复制（`qrPayCopy`）→「Menunggu pembayaran…」→ 主按钮「Simpan Kode QR」→ 次按钮「Saya sudah bayar」→「Cara bayar & aplikasi yang didukung ›」折叠。**无红色「Batalkan Pembayaran」**。高度随内容。
3. 轮询仍每 3 秒（`_tick`，`qr_payment_sheet.dart:146`），过期后降为 10 秒且**不停**（:127-137 现有规则，S-7 宽限期到账仍要能进成功页）。

### AC3 — E2 保存二维码 `[L0 widget]`（FR-128.8、UI E2）
1. 点「Simpan Kode QR」→ 成功 toast「Tersimpan ke galeri. Buka e-wallet atau m-banking, lalu pindai dari galeri.」（仅电商传入；其他调用方仍是 `cardSavedToGallery`）；面板不关、轮询继续；权限被拒对话框照线上（`qr_pay_save.dart:112`）。
2. 存图成功后上报 `toko_payment_qr_saved`（无属性；由电商侧 `onSaved` 回调发，**共用组件内部不埋点**——沿用 `shop_order_detail_page_v2.dart:800-802` 注释「sheet 是共用组件，在里面埋点会给其他线凭空多出事件」的原则）。

### AC4 — E3 / E4「我已付款」 `[L0 widget]`（FR-128.8、FR-134.1、D-11、UI E3 / E4）
1. 点「Saya sudah bayar」→ 按钮转圈并换文案「Mengecek pembayaran…」，期间不可再点（E3）；调用 `onPaidCheck` = `ShopOrderRepository.paymentCheck(token)`。
2. `paid == true` → 关面板 resolve true → 进 E6（AC6）。
3. `paid == false` 或请求异常 → toast「Pembayaran belum masuk. Kalau sudah bayar, tunggu sebentar lalu cek lagi.」（面板内 toast，`top` 视面板位置按全局规范），面板保持，自动轮询照旧（E4）。
4. 「我已付款」与 3 秒轮询共用 `_closing` 防重入：任何一方先确认到账，另一方的结果被忽略。

### AC5 — E5 付款超时 `[L0 widget]`（S-1、S-7、UI E5）
1. 倒计时归零 → 面板进现有过期态（`_expire`，:127）：二维码换「Pembayaran kedaluwarsa」（`rechargeExpiredTitle` 复用）+ 正文「Waktu pembayaran habis. Pesanan dibatalkan.」（`shopOrderExpiredNotice` 复用，经 `expiredBody` 传入）、倒计时变灰 `00:00`、只剩「Tutup」+ 折叠指引；不画「我已付款」。
2. 关面板后刷新订单：服务端在宽限期（60–65 分钟）`displayStatus = CANCELLED` → 订单按已取消显示、无「去支付」（S-7）；**`pollPaid` 的中止判据仍用原始 `status`**（`status == CANCELLED` 才抛 `QrPaymentAborted`，:823-827），不用 `displayStatus`——宽限期内到账服务端会把订单转为待发货，过期态面板的 10 秒轮询要能接住并进 E6。

### AC6 — E6 支付成功（接 7.1 已建页面） `[L0 widget]`（旅程 C 7、UI E6）
1. QRIS 到账（轮询或「我已付款」）→ 保留现有 `capturePurchase` / `growthPopupTrigger.afterAction()` / `toko_order_payment_succeeded{pay_channel, attribution_source}`（`shop_order_detail_page_v2.dart:831-845`，属性不变）→ **不再弹 `shopOrderPaid` toast**，改为跳 7.1 建的 E6：订单详情发起的用 `context.pushReplacement('/shop/orders/{token}/paid')`。
2. E6 的金额、拆分、按钮、返回键规则由 7.1 实现，本 story 只补「从 QRIS 路径进入」的测试。

### AC7 — E7 二维码生成失败 `[L0 widget]`（D-11「出码只在订单前 10 分钟内」、UI E7）
1. `/pay` 抛网络错误或 5xx（网关取码失败）→ **不再只弹 `shopOrderPayFailed` toast**（现 `:847-851`），改为以 `codeFailed: true` 打开电商面板：二维码位「Kode QR gagal dibuat」+「Coba lagi」（`commonRetry` 复用），「Simpan Kode QR」禁用，不画「我已付款」；金额、倒计时、订单号照常；订单仍是待付款、倒计时照走。上报沿用 `toko_order_payment_failed_shown`。
2. 「Coba lagi」→ `onRetryCode` 再调一次 `/pay`：拿到 payload → 切回 E1 正常态并开始轮询；再失败保持 E7。
3. `/pay` 返回 3.3 的「超过 10 分钟不再出码」错误 → 同样进 E7 态但**不画「Coba lagi」**（`onRetryCode` 传 null；文案见「待确认」1）。
4. 测试环境 payload 为空串的「Mode uji」占位（`QrPayCodeCard` stub，:199）行为不变——那是成功拿到空码，不是失败。

### AC8 — 支付启动器（单一入口）与「去支付」 `[L0]`（PRD §3.7「待付款可直接去支付，拉起与结算页相同的面板」、epics 7.2）
1. 把 `ShopOrderDetailPageV2._pay`（:772-859）与 `_onPaymentAborted`（:877-917）的逻辑**原样搬到** `A/features/shop/presentation/shop_payment_launcher.dart`：`Future<ShopPaymentOutcome> launchShopPayment(BuildContext context, WidgetRef ref, ShopOrderDetail order, {required bool isRetry})`，`ShopPaymentOutcome ∈ {settledByCoin, paid, dismissed, declined, expired, userCancelled, abortedUnknown, failed}`；所有既有埋点（`toko_order_pay_tapped`、`toko_payment_retry_tapped`、`toko_payment_qr_shown`、`toko_payment_sheet_dismissed`、`toko_payment_declined_shown`、`toko_payment_expired_shown`、`toko_order_payment_failed_shown`、`toko_order_payment_succeeded`）**事件名、属性、触发条件一律不变**，toast 文案不变（成功 toast 除外，见 AC6）。
2. `ShopOrderDetailPageV2._pay` 改为调用启动器并按结果做本页的事（`_lastPaymentDeclined` 记账、到账跳 E6、invalidate）；`autoPay`（7.1）走同一入口。
3. 订单详情 v2 底栏「Bayar Sekarang」（:646-683）显示条件加一条：`order.canPay != false`（null = 旧后端，回落到现有 `expiresAt` 判断）。订单列表「去支付」由 9.1 调用同一启动器，本 story 不改列表。
4. `ShopOrderDetail` 解析新增可空字段 `canPay`、`displayStatus`（缺键 null；9.1 再补其余 D-19 字段）；`ShopOrderRepository` 加 `paymentCheck(String orderToken) → PaymentCheckResult {bool paid, String? status}`。

### AC9 — 其他调用方不受影响（回归证明） `[L0]`（P-11、epics 7.2 第 5 条）
1. 四个非电商调用点**源码不改**：送礼 `features/gift/presentation/gift_send_flow.dart:372`、AI 解锁 `features/triage/presentation/widgets/unlock_method_sheet.dart:185`、身份证高清 `features/profile/presentation/id_card/id_card_hd_flow.dart:128`、一次性解锁 / 宠物名额月租 `features/keepsake/presentation/keepsake_pay_flow.dart:106`（`pet_slot_purchase_flow.dart`、`tailsonality_*` 经它调用）。直接使用 parts 的充值页、兽医计时支付页也不改。
2. 以下既有测试**不改断言、全部保持绿**：`test/shared/qr_payment_sheet_test.dart`（含「渲染二维码 + 取消按钮，点取消关闭返回 false」「不传 onAborted 时 abort 仍 resolve false」）、`test/shared/qr_pay_parts_test.dart`（含「A1：抽屉 60% 屏高 … + 5 条步骤 + 吸底按钮」「A4 过期只剩 Tutup」「过期后到账仍 resolve true」「连点 Simpan 只弹一个权限框」「S1 1080 宽 PNG」）、`test/gift/gift_send_flow_test.dart`、`test/triage/triage_unlock_balance_test.dart`、`test/profile/id_card_hd_flow_test.dart`、`test/profile/ktp_unlock_analytics_test.dart`、`test/keepsake/keepsake_pay_flow_test.dart`、`test/pawcoin/recharge_page_test.dart`、`test/consult/vet_timed_pay_page_test.dart`、`test/consult/vet_timed_pay_balance_test.dart`。
3. 新增 `test/shared/qr_payment_sheet_defaults_test.dart`：不传任何新参数时 —— 无标题行、无 `qrPayCloseX`、金额标签为 `qrPayTotalLabel`、红色 `qrPayCancel` 在、无「Saya sudah bayar」、指引平铺（`qrPayGuide` 下 5 步可见）、高度 = 60% 屏高 − 48、存图 toast 为 `cardSavedToGallery`、过期正文为 `qrPayExpiredBody`、`onSaved` 未被调用。

### AC10 — 埋点 `[L0]`（PRD §4、P-9）
1. 新事件 `toko_payment_qr_saved`（无属性）字面量 `Analytics.capture('toko_payment_qr_saved')`。
2. **`_saved` 不在 `test/analytics/v112_events_test.dart` 的 `allowedSuffixes` 里**（:375 起的白名单无此词尾）→ 按该文件既有先例「名字由 PRD 定死，扩表、不改名」在 `allowedSuffixes` 追加 `'_saved'` 并写注释（V1.4.2 shop · PRD §4 `toko_payment_qr_saved`：「存图成功」这一结果）；不放宽正则、不删守卫。
3. `shop_payment_analytics_test.dart` 末尾「新增事件一个都不进 appsflyerEvents」把 `toko_payment_qr_saved` 加进检查列表。

### AC11 — 文案 `[L0]`（PRD §6 E 组、P-10）
按 Dev Notes「文案表」两份 arb 同时加，跑 `flutter gen-l10n`；E6 的文案已由 7.1 建，本 story 不重复加。

### AC12 — 回归与还原度 `[L0]` `[L2]`
1. `flutter analyze` 零警告、`flutter test` 全绿；按设计打破的断言改写见「测试」。
2. **还原度 ≥ 95%**（L2，本地 Android 模拟器）：E1–E7 逐屏对照打分，另抽查送礼、宠物名额月租两处面板与改动前截图一致，记入 Completion Notes。

## Tasks / Subtasks

- [ ] **T0 开工核对**：3.3 的 `payment-check` 响应字段与「不再出码」错误 `type`；6.2 的 `canPay` / `displayStatus`；7.1 的 E6 路由与 `autoPay` 实际实现；有出入先改本文件
- [ ] **T1 共用组件参数**（AC1、AC9.3）：`qr_payment_sheet.dart`、`qr_pay_parts.dart`、`qr_pay_save.dart`；先写 `qr_payment_sheet_defaults_test.dart` 钉住默认行为
- [ ] **T2 电商面板形态**（AC2–AC5、AC7）：标题 / 金额标签 / 冻结行 / 我已付款 / 折叠指引 / 自适应高度 / E7 态
- [ ] **T3 启动器**（AC8）：`shop_payment_launcher.dart` 搬迁 `_pay` + `_onPaymentAborted`；订单详情改调；`paymentCheck` repo；`ShopOrderDetail.canPay/displayStatus`
- [ ] **T4 E6 接入**（AC6）
- [ ] **T5 埋点与文案**（AC10、AC11）：`v112_events_test.dart` 扩 `_saved`
- [ ] **T6 测试** + `flutter analyze && flutter test`；Completion Notes 标「**L1/L2 待本地验收**」

## Dev Notes

### 现状（核 `dev_1.4.2` @ 2c507755）

| 位置 | 现状 |
|---|---|
| `A/shared/widgets/qr_payment_sheet.dart`（234 行） | `QrPaymentAborted` :15-26（无参 const 构造必须保留）；`showQrPaymentSheet` :43-67（`isDismissible: false`、`showDragHandle: true`）；轮询 3s :111、过期 `_expire` :127-137（降频 10s 不停）；`_tick` :146-172；`_cancel` :174-180；`build` 固定 60% 高 :187；`QrPaySummary` :199、`QrPayCodeCard` :205、`QrPayGuide` :211、`QrPayBottomBar` :216-229（`cancelLabel: l10n.vetPayCancel` :228） |
| `A/shared/widgets/qr_payment/qr_pay_parts.dart`（511 行） | `QrPaySummary` :51（标签 `qrPayTotalLabel` :93、`qrPayCoinsSub` :104）；`QrPayCodeCard` :153（stub `rechargeStubHint` :199、过期蒙层 `rechargeExpiredTitle` :243 / `qrPayExpiredBody` :249、复制 `qrPayCopy` :304、`rechargeWaiting` :322）；`QrPayGuide` :333（`qrPaySupportedApps` :356、`qrPayHowTo` :385、5 步 :344-350）；`QrPayBottomBar` :416-511（过期只剩 `qrPayClose` :450、`qrPaySave` :465、取消 `qrPayCancel` 红字 :483-500） |
| `A/shared/widgets/qr_payment/qr_pay_save.dart` | `saveQrPaymentImage` :53（全局 `_saving` 防连点）；成功 toast `cardSavedToGallery` :100 |
| `A/features/shop/presentation/shop_order_detail_page_v2.dart` | `_payProps` :764-770；`_pay` :772-859（`/pay` → 纯币 :781-791 → `toko_payment_qr_shown` :803 → `showQrPaymentSheet` :805-829（`pollPaid` 先看 `paymentFailure` 再看 `status == cancelled`）→ 到账 :831-846（成功 toast `shopOrderPaid` :845）→ 失败 catch :847-851）；`_onPaymentAborted` :877-917；`_bottomBar` :646 |
| `A/features/shop/data/shop_order_repository.dart` | `detail` / `pay` / `cancel` / `confirmReceipt`；无 `paymentCheck` |
| 调用方 | 电商 :805；送礼 `gift_send_flow.dart:372`；AI 解锁 `unlock_method_sheet.dart:185`；身份证高清 `id_card_hd_flow.dart:128`；一次性解锁 / 月租 `keepsake_pay_flow.dart:106` |
| `test/analytics/v112_events_test.dart` | `allowedSuffixes`（:375 起）无 `_saved` |

### UI 逐屏要点（E1–E7）

| 屏 | 要点 |
|---|---|
| E1 面板 | 原有模块全部保留（金额、倒计时 Bayar dalam、二维码、订单号可复制、等待中、Simpan Kode QR、支付指引 + 5 步、关闭）；改：金额标签「Total pembayaran」→「Bayar via QRIS」（只显示 QRIS 部分，混合时下方注明 PawCoin 已冻结金额）；新增「Saya sudah bayar」（点一次主动查单）；去掉红色「Batalkan Pembayaran」（线上只关面板不取消订单，与右上关闭重复、易误解）；指引收进底部「Cara bayar」折叠；固定高度 → 自适应。⚠️ 送礼 / 月租 / 高清身份证 / AI 解锁共用：新按钮与标签用可选参数、默认关闭 |
| E2 已保存 | 原有 toast「Tersimpan ke galeri」扩写为提示打开电子钱包或手机银行从相册扫码；面板不关、继续轮询；权限被拒照线上 |
| E3 查询中 | 按钮转圈「Mengecek pembayaran…」，期间不可再点；查到已付 → 关面板进 E6 |
| E4 尚未到账 | toast，面板保持，自动轮询照旧（每 3 秒） |
| E5 超时 | 原有过期态：二维码换过期提示（`rechargeExpiredTitle` + `shopOrderExpiredNotice`）、倒计时变灰、只剩「Tutup」 |
| E6 成功 | 新增页（7.1 已建）：应付金额（混合拆分）、「Lihat pesanan」→ 订单详情、「Lanjut belanja」→ Toko；纯币单也到此；系统返回 = Lihat pesanan |
| E7 取码失败 | 线上仅测试环境有「Mode uji」占位；生产取码失败给「Kode QR gagal dibuat」+「Coba lagi」，保存禁用；订单仍待付款、倒计时照走 |

### 文案表（PRD §6 E 组；新 key 两份 arb 都加）

| 屏 | key（建议） | 印尼语 | 英语 | 备注 |
|---|---|---|---|---|
| E1 | `qrPaySheetTitle` | Pembayaran | Payment | 仅电商传入 |
| E1 | `qrPayViaQrisLabel` | Bayar via QRIS | Pay via QRIS | 替换 `qrPayTotalLabel`，仅电商传入 |
| E1 | `qrPayCoinHeld` | + PawCoin Rp {x} sudah ditahan | + PawCoin Rp {x} on hold |  |
| E1 | `qrPayPaidCheck` | Saya sudah bayar | I've paid |  |
| E3 | `qrPayPaidChecking` | Mengecek pembayaran… | Checking payment… |  |
| E1 | `qrPayGuideToggle` | Cara bayar & aplikasi yang didukung | How to pay & supported apps | 折叠头，后接 `›` 图标 |
| E2 | `qrPaySavedOpenWallet` | Tersimpan ke galeri. Buka e-wallet atau m-banking, lalu pindai dari galeri. | Saved to gallery. Open your e-wallet or mobile banking and scan it from your gallery. | 扩写 `cardSavedToGallery`（原 key 不改，供其他调用方） |
| E4 | `qrPayNotPaidYet` | Pembayaran belum masuk. Kalau sudah bayar, tunggu sebentar lalu cek lagi. | Payment not received yet. If you've paid, wait a moment and check again. |  |
| E7 | `qrPayCodeFailed` | Kode QR gagal dibuat | Couldn't create the QR code | 「Coba lagi」复用 `commonRetry` |
| E5 | 复用 `rechargeExpiredTitle`「Pembayaran kedaluwarsa」、`shopOrderExpiredNotice`「Waktu pembayaran habis. Pesanan dibatalkan.」 | — | — |  |
| E1 | 复用 `qrPayPayWithin`「Bayar dalam」、`orderNumberLabel`、`rechargeWaiting`、`qrPaySave`、`commonClose`、`qrPaySupportedApps`、`qrPayHowTo`、`qrPayStep1`~`5` | — | — | 原文不改 |
| E6 | 7.1 已建：`shopOrderPaid`（复用）、`shopOrderCoinCashSplit`、`shopPaySuccessPreparing`、`shopPaySuccessViewOrder`、`shopPaySuccessContinue` | — | — |  |

### 拍板点（按建议实现，Completion Notes 复述）

1. 去掉「取消支付」后的关闭入口：电商面板传 `title` 时画标题行 ×（`qrPayCloseX`），语义同原取消（只关面板、不调接口、不触发 `onAborted`）；`isDismissible` 仍为 false。
2. 「我已付款」查询异常与未到账同一 toast（E4），不另起错误文案。
3. E5 过期后关闭面板维持现状（发 `toko_payment_sheet_dismissed`、不弹 toast），面板内已显示超时说明。
4. 「超过 10 分钟不再出码」复用 E7 态但不给重试（无 §6 专用文案）。
5. 启动器返回结果、不自己跳路由：订单详情到账 `pushReplacement` 到 E6；9.1 的列表可选择 `push`。

### 实现提示

- `adaptiveHeight`：把 `SizedBox(height:)` 换成 `ConstrainedBox(maxHeight: 屏高 * .9 - kMinInteractiveDimension)` + `Column(mainAxisSize: min)` + `Flexible(SingleChildScrollView)`；默认分支保持原 `SizedBox` 代码不动（钉住 60% 测试）。
- 「我已付款」状态放在 `_QrPaymentSheetState`（`bool _checking`），按钮在 `QrPayBottomBar` 里画（`paidCheckBusy` 控制转圈 + 文案切换）。
- `codeFailed` → `_QrPaymentSheetState` 内部持有 `String _payload`（初值 `widget.payload`）与 `bool _failed`；重试成功 `setState` 换 payload；轮询在失败态照常跑（到账仍能接住）。
- 面板内 toast：用 `showAppToast(context, msg, extraBottom: 40)`（与存图 toast 同位置，`qr_pay_save.dart:100`）。
- 启动器搬迁时**逐行保留**原注释中的四态说明与「两个变量缺一不可」（`abortSignalled` / `aborted`），这是既有测试 `shop_payment_analytics_test` 的依据。

### 架构约束

- P-11：只加可选参数，默认值保持现状；四个非电商调用点源码零改动。
- S-1：App 倒计时仍按订单 `expiresAt`（建单 + 60 分钟），不显示码的 65 分钟。
- S-7 / D-11：宽限期显示已取消由服务端 `displayStatus` 给，App 不自行推算；轮询中止判据仍看原始 `status`。
- P-3：到账判定以服务端为准（轮询 / payment-check），App 不自行判定。
- 埋点只在电商侧发；共用组件内零埋点。

### 与前后 story 的边界

- 7.1：结算提交 → `?autopay=1` 进订单详情；E6 页面与文案。本 story 让 `autoPay` 改走启动器。
- 9.1：订单列表「去支付」、订单详情 v3 都调 `launchShopPayment`；v3 必须保留本 story 在 v2 上的行为（E6 跳转、`canPay` 门控）。
- 后端 3.3：`payment-check` 的 10 秒节流在服务端，App 不再做客户端节流（按钮查询中禁用已足够）。

### 测试

**L0（云端可跑）**
- `test/shared/qr_payment_sheet_defaults_test.dart`（新）：AC9.3 全部默认行为。
- `test/shared/qr_payment_sheet_shop_mode_test.dart`（新）：E1 结构（标题 + × / 「Bayar via QRIS」/ 冻结行仅 coin>0 / 无 `qrPayCancel` / 「Saya sudah bayar」/ 指引默认收起、点开出 5 步）；自适应高度 < 60% 屏高（内容少时）；× 关闭 resolve false 且 `onAborted` 未触发；E2 toast 文案 + `onSaved` 调用一次；E3 转圈与禁用、重复点只调用一次；E4 false / 抛错 → toast、面板仍在；到账 resolve true；E5 过期正文换 `shopOrderExpiredNotice`、无「我已付款」、过期后到账仍 resolve true；E7 `codeFailed` 保存禁用、无「我已付款」、「Coba lagi」成功切回二维码、`onRetryCode == null` 时无重试。
- `test/shop/shop_order_detail_page_v2_test.dart`（改）：`:728` 关面板改点 `qrPayCloseX`（**按设计打破**：电商面板不再有 `qrPayCancel`）；到账后断言跳到 `/shop/orders/x/paid`（原「弹 `shopOrderPaid` toast」断言按设计改写）；`/pay` 5xx → E7 面板（原「弹 `shopOrderPayFailed`」断言改写）；`canPay: false` → 无「Bayar Sekarang」；`canPay` 缺键 → 行为同改动前。
- `test/shop/shop_payment_analytics_test.dart`（改）：全部 `qrPayCancel` 点击（:135、145、217、229、244、256、264、273、306）改为 `qrPayCloseX`，事件断言一字不改；新增 `toko_payment_qr_saved` 存图成功才报、失败不报；appsflyer 白名单检查加入新事件。
- `test/shop/shop_payment_launcher_test.dart`（新）：八种 `ShopPaymentOutcome` 各一例（含纯币当场结清、老后端无类别中止 = `abortedUnknown` 且不发 `sheet_dismissed`）。
- `test/shop/shop_pay_success_page_test.dart`（补）：从 QRIS 路径进入显示「PawCoin + QRIS」拆分。
- AC9.2 列出的十个既有测试文件：不改、全绿。
- `test/analytics/v112_events_test.dart`：扩 `_saved` 后全绿。
- 跑：`cd petgo_app && flutter analyze && flutter test`。

**L1（本地 / stag）**：真 GemPay stag 通道或 stag 付款模拟：扫码后点「我已付款」立即进 E6；未付点「我已付款」出 E4；60 分钟后订单列表显示已取消、65 分钟内到账转待发货；关单后再开同单「去支付」复用同一码。
**L2（本地模拟器）**：E1–E7 逐屏还原度 ≥ 95%；送礼与宠物名额月租面板外观与改动前一致（截图对比）。

> 云端只能跑 L0：Completion Notes 必须写「**L1/L2 待本地验收**」。

### Project Structure Notes

- 新增：`A/features/shop/presentation/shop_payment_launcher.dart`、`test/shared/qr_payment_sheet_defaults_test.dart`、`test/shared/qr_payment_sheet_shop_mode_test.dart`、`test/shop/shop_payment_launcher_test.dart`。
- 改动：`A/shared/widgets/qr_payment_sheet.dart`、`qr_payment/qr_pay_parts.dart`、`qr_payment/qr_pay_save.dart`、`shop_order_detail_page_v2.dart`、`data/shop_order_repository.dart`、`domain/shop_order_detail.dart`、`l10n/app_*.arb`、`test/analytics/v112_events_test.dart`、`test/shop/shop_order_detail_page_v2_test.dart`、`test/shop/shop_payment_analytics_test.dart`。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.2/epics-v1.4.2-shop.md#Story 7.2]
- [Source: _bmad-output/planning-artifacts/v1.4.2/电商/1-4-2电商prd.md#3.0 旅程 C；3.2 支付面板（含 11-05 呈现）；3.8 FR-134 ①；§4；§6 E 组]
- [Source: _bmad-output/planning-artifacts/v1.4.2/architecture-v1.4.2-shop-delta.md#D-11、D-19、P-3、P-11、§1.2 第 18 条]
- [Source: _bmad-output/planning-artifacts/v1.4.2/决策日志-shop.md#S-1、S-7、B1]
- [Source: _bmad-output/planning-artifacts/v1.4.2/电商/ui-1-4-2-shop-integrated-v1.html#共用组件、E1–E7]

## 待确认 / 发现的规划冲突

1. **「订单已无法支付」无文案**：3.3 AC 要求「订单创建超 10 分钟服务端拒绝出码时 App 提示订单已无法支付」，PRD §6 / UI 均无此句。本 story 复用 E7「Kode QR gagal dibuat」且不给重试；需设计侧补 §6。
2. **E5 文案与 S-1 的差异**：E5 写「Pesanan dibatalkan」，但按 S-1 服务端要等码失效（再 5 分钟）并查单后才真正取消；App 只按 `displayStatus` 显示已取消（S-7 已拍板），文案沿用不改。
3. **`_saved` 词尾扩表**：`toko_payment_qr_saved` 过不了命名守卫词尾白名单，按守卫文件先例扩表；若产品愿改名可免扩表。
4. **关闭入口**：UI E1 标题行有 ×，线上面板无标题行（靠拖拽条 + 红色取消）。去掉红色取消后电商面板只剩 × 一个关闭入口（`isDismissible: false` 保持）——符合稿，记录备查。

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

#### 待确认

### File List

### Change Log

- 2026-11-05：story 文件创建（ready-for-dev），依据决策日志 S-0 ~ S-9、PRD §6、架构增量、UI 定稿与 `dev_1.4.2` @ 2c507755 代码现状。

---
baseline_commit: d1e8f5d3
branch: feat/1.3.0-shop-v2
theme: shop-v2
epic: 1
story: 1.3
ad: [AD-S9]
decisions: [SD-1]
fr: [SHOP-FR-01]
---

# Story 1-3: App 支付三态处置

Status: ready-for-dev

> 自包含 story，可本地或云端 L0 执行。与用户沟通用中文。执行纪律见根 `CLAUDE.md`（后端→前端→联调、AC 标 L0/L1/L2、Flyway 时间戳版本号、`mvn -B clean package`）。
> 本 story **只碰 App**（`petgo_app/`），后端一行不改。
> 🔴 **前置：1-1 必须先完成并合入** —— 本 story 读的 `paymentStatus` / `paymentFailureCategory` 两个字段由 1-1 下发。1-1 未上线时本 story 的分支全部走不到，属预期（字段为 null ⇒ 退化为当前行为）。

## Story

As a 用户，
I want 付款失败时页面明确告诉我原因，该重试的给我重试入口、不该重试的别给，
so that 我不会以为是自己手机的问题然后放弃购买。

## Context（本 story 必须知道的现状）

### 现状一：轮询只看订单状态，三种结局挤成一个 `false`

`/Users/dai/work/petgo-platform/petgo_app/lib/features/shop/presentation/shop_order_detail_page_v2.dart`，`_pay(...)` 方法 `:730-777`：

```dart
745      final paid = await showQrPaymentSheet(
746        context,
747        payload: result.payload!,
748        orderRef: order.orderToken,
749        // 轮询问的是订单本身的状态 —— 到账由服务端在回调里推进，客户端不自行判定。
750        pollPaid: () async {
751          final fresh =
752              await ref.refresh(shopOrderDetailProvider(widget.orderToken).future);
753          if (fresh.status == ShopOrderStatus.cancelled) {
754            throw const QrPaymentAborted();
755          }
756          return fresh.status.isPaidOrLater;
757        },
758      );
```

`showQrPaymentSheet` 返回 `Future<bool>`（`/Users/dai/work/petgo-platform/petgo_app/lib/shared/widgets/qr_payment_sheet.dart:20-35`），`false` 同时代表**三件完全不同的事**：
- 轮询抛了 `QrPaymentAborted`（订单被取消 / 超时）→ `_tick()` `:79-83` → `pop(false)`
- 用户点了面板上的「取消」按钮 → `_cancel()` `:91-96` → `pop(false)`（**不调后端**，`:18-19` 注释：pending intent 可复用重复支付）
- 面板被别的原因关掉

而「网关拒付」根本走不到 —— 拒付只改 `payment_intents`、不改订单，`fresh.status` 永远还是 `PENDING_PAYMENT`，`pollPaid` 一直返回 `false`，二维码一直挂着，直到 60 分钟窗口耗尽。

### 现状二：🔴「五条链路共用组件」这个说法**不准确**，实测是 3 条业务线 / 4 个调用点

架构 AD-S9 与 epics 都写「`QrPaymentSheet` 是问诊 / AI 解锁 / 高清身份证 / 充值 / 电商五条链路共用组件」。**实测（grep `showQrPaymentSheet` 全量）不是这样**：

| # | 业务线 | 是否用共用 sheet | 调用点 file:line |
|---|---|---|---|
| ① | **电商订单** | ✅ 用 | `petgo_app/lib/features/shop/presentation/shop_order_detail_page_v2.dart:745` |
| ② | **AI 解锁**（问诊自查解锁） | ✅ 用 | `petgo_app/lib/features/triage/presentation/widgets/unlock_method_sheet.dart:182` |
| ③ | **高清身份证 · 创建页** | ✅ 用 | `petgo_app/lib/features/profile/presentation/id_card_create_page.dart:381` |
| ④ | **高清身份证 · 详情页** | ✅ 用 | `petgo_app/lib/features/profile/presentation/id_card_detail_page.dart:336` |
| ⑤ | **问诊（兽医计时付款）** | ❌ **不用** —— 页面内联 `QrImageView` + 自己的 3s 轮询 | `petgo_app/lib/features/consult/presentation/vet_timed_pay_page.dart`：`QrImageView` `:552`、`_pollInterval` `:40`、`_startPolling()` `:80-83`、`_tick()` `:85-103` |
| ⑥ | **PawCoin 充值** | ❌ **不用** —— 页面内联 `QrImageView` + 自己的轮询 | `petgo_app/lib/features/pawcoin/presentation/recharge_page.dart`：`QrImageView` `:329`、`_startPolling(token)` `:106-132` |

⇒ 共用 sheet 的其实是 **3 条业务线、4 个调用点**（高清身份证占两个）。⑤⑥ 是**平行实现**，本 story 完全不碰它们，但它们仍要做回归（它们的「成功 / 超时」表现绝不能因本 story 而变 —— 虽然理论上不该变，这正是回归用例存在的理由）。
另：`QrPaymentAborted` 目前**只有电商一处抛**（`:754`），②③④ 的 `pollPaid` 里都没有 abort 分支。

### 现状三：「过期后不保留支付入口」已经实现了，别重复造

`shop_order_detail_page_v2.dart:618-625`：

```dart
618  Widget? _bottomBar(AppLocalizations l10n, ShopOrderDetail order) {
619    final expiresAt = order.expiresAt;
620    final expired = order.status.isPendingPayment &&
621        expiresAt != null &&
622        !expiresAt.isAfter(DateTime.now().toUtc());
623
624    // 🔴 过期后**不保留支付入口**：一个点下去必然失败的按钮比没有更糟。
625    if (order.status.isPendingPayment && !expired) {
```
过期或已取消 ⇒ 落到 `:658` / `:682` 分支，最终 `return null`（底部条整条消失）。**本 story 只需保证超时那一支不额外塞回一个重试按钮**，不需要改这段逻辑。
倒计时块 `_countdownBlock(...)` `:138`，过期文案已有 `shopOrderExpiredNotice`（`app_en.arb:2805`：「Payment time is up. The order was cancelled.」）。

### 现状四：「用户取消订单」与「仅关闭面板」在代码里本来就是两条路

| | 触发 | 入口 | 是否调后端 | 订单结果 |
|---|---|---|---|---|
| **用户取消订单** | 详情页底部「取消」按钮 `ValueKey('shopOrderCancelV2')` `:631-641` | `_cancel(l10n)` `:808` → 二次确认 `showShopConfirm`（keys `shopOrderCancelDialogV2` / `shopOrderCancelConfirmYesV2`）`:812-820` → `shopOrderRepositoryProvider.cancel(orderToken)` `:826` | ✅ `POST /api/v1/me/shop-orders/{token}/cancel`（`shop_order_repository.dart:32-34`） | 转 `CANCELLED` |
| **仅关闭面板** | 二维码面板上的「取消」按钮 `ValueKey('qrPayCancel')` `qr_payment_sheet.dart:140-151` | `_QrPaymentSheetState._cancel()` `:91-96` | ❌ **不调**（注释 `:18-19` 写明理由：pending intent 可复用重复支付） | **不变**，仍 `PENDING_PAYMENT` |

⚠️ sheet 是 `isDismissible: false` 的模态（`qr_payment_sheet.dart:29`），面板打开时**点不到**详情页底部的取消按钮 —— 两条路不会在同一时刻竞争。

### 现状五：本仓 ARB 只有**两语**，不是三语

`petgo_app/lib/l10n/` 下只有 `app_en.arb`（template）与 `app_id.arb`；`l10n.yaml` 的 `template-arb-file: app_en.arb`。全仓 `find -name "*.arb"` 只有这两个。
epics 1-3 AC5 写的「ARB 三语」是**后台 Thymeleaf 侧**的 `messages{,_en,_id,_zh_CN}.properties`，与 App 无关。**本 story 落 2 个 ARB 文件**，并在 Completion Notes 记录此偏离。

### 现状六：重试确实会生成新的支付单（无需额外做什么）

`ShopOrderPaymentService.ensureIntent(...)` 的幂等键是 `"shop-order-pay:" + orderToken`，但 `PaymentIntentService.createIntent` 的复用判据是「既有意图为 `PAID`，或 `PENDING` 且未过窗」（`PaymentIntentService.java:93-95`）。拒付后意图已是 **`FAILED`（终态）** ⇒ 不满足复用条件 ⇒ **落到新建分支，出新码**。所以「重试生成新的支付单」是既有行为，App 侧只要再调一次 `pay` 即可，**不要为此发明新端点或新参数**。

## Acceptance Criteria

**AC1 · 轮询读支付单字段，不再只看订单状态**
**Given** 1-1 已下发 `paymentStatus` / `paymentFailureCategory`
**When** 二维码面板轮询
**Then** `pollPaid` 同时读 `ShopOrderDetail.status` 与 `paymentFailureCategory`，三种失败类别各自抛出带类别的中止信号 `[L0]`
**And** 两个字段为 `null`（后端未升级 / 纯 PawCoin 单）时，行为与改动前**完全一致** `[L0]`

**AC2 · 支付被拒 → 关码 + 说明原因 + 保留重试**
**Given** 订单仍在待支付倒计时内，支付单 `paymentFailureCategory == GATEWAY_DECLINED`（订单状态仍是 `PENDING_PAYMENT`）
**When** 轮询命中
**Then** 二维码面板关闭 `[L0]`
**And** 页面出现「付款被拒绝」的说明（新 ARB key，**不回显后端 detail 串**，照 `shopOrderPayFailed` 的既有纪律 `app_en.arb:2833`） `[L0]`
**And** 底部条**仍显示支付按钮**（订单未取消、未过期，走 `_bottomBar` 既有 `:625` 分支） `[L0]`
**And** 再次点支付会向后端发起一次新的 `pay` 请求，拿到**新的** `payload` `[L1]`

**AC3 · 超时未付 → 关码 + 告知已取消 + 不给重试**
**Given** `paymentFailureCategory == EXPIRED`（订单已转 `CANCELLED`）
**When** 轮询命中
**Then** 二维码面板关闭 `[L0]`
**And** 展示「付款时间已到，订单已取消」（复用既有 `shopOrderExpiredNotice`） `[L0]`
**And** 底部条**没有任何支付入口**（`_bottomBar` 返回 null） `[L0]`
**And** **不弹** `shopOrderPayFailed` 那条通用失败 toast（它会让用户以为还能再试一次） `[L0]`

**AC4 · 用户取消订单 → 关码，不弹错误**
**Given** `paymentFailureCategory == USER_CANCELLED`（订单已转 `CANCELLED`，由详情页「取消」按钮或他端触发）
**When** 轮询命中
**Then** 二维码面板关闭且**不弹任何错误 toast** `[L0]`
**And** 详情页刷新为已取消态 `[L0]`
**And** 详情页「取消」按钮路径不变：仍二次确认 → `POST /shop-orders/{token}/cancel` → 订单转 `CANCELLED` `[L0/L1]`

**AC5 · 仅关闭面板 → 订单不变，可继续支付**
**Given** 用户点面板上的「取消」按钮（`ValueKey('qrPayCancel')`）或面板因其它原因关闭，**轮询未抛出任何中止信号**
**When** 面板关闭
**Then** **不调用任何取消接口**，订单仍是 `PENDING_PAYMENT` `[L0/L1]`
**And** 详情页底部仍显示支付按钮，可再次发起支付 `[L0]`
**And** **不弹**任何失败或取消文案 `[L0]`
**And** 这一路与 AC4 是**两条独立分支**，测试里各有一条用例，不得合并断言 `[L0]`

**AC6 · 文案全部进 ARB，不硬编码**
**Then** 新增文案全部落 `app_en.arb` + `app_id.arb` **两个**文件，key 集合相等 `[L0]`
**And** 代码中无中文/英文/印尼语字面量 `[L0]`
**And** 印尼语措辞参照既有 `shopOrder*` 系列风格 `[L0]`

**AC7 · 🔴 共用组件走参数，默认行为不变**
**Given** `showQrPaymentSheet` 有 4 个调用点（见 Context 现状二）
**When** 为电商新增中止类别能力
**Then** `showQrPaymentSheet` 的**新能力由一个可选具名参数开启，默认关闭**；返回类型仍是 `Future<bool>` `[L0]`
**And** `QrPaymentAborted` 的**既有无参 `const` 构造仍可用**（`const QrPaymentAborted()` 不得编译失败） `[L0]`
**And** ②AI 解锁 / ③④高清身份证三个调用点**不传新参数、一行不改** `[L0]`
**And** 有一条测试断言「不传新参数时，sheet 的返回值与关闭时机与改动前一致」 `[L0]`

**AC8 · 其余四条支付链路回归（SHOP-FR-01 明写的验收项）**
**Then** 逐条回归，表现与改动前一致：

| 链路 | 调用点 | 回归内容 |
|---|---|---|
| **AI 解锁** | `triage/presentation/widgets/unlock_method_sheet.dart:182` | 成功（`r.locked == false` → 解锁）与超时（面板挂着、用户取消关闭）两支 `[L2]` |
| **高清身份证 · 创建页** | `profile/presentation/id_card_create_page.dart:381` | 成功（`c.hdUnlocked` → `invalidate(idCardListProvider)`）与关闭面板两支 `[L2]` |
| **高清身份证 · 详情页** | `profile/presentation/id_card_detail_page.dart:336` | 成功（`paid` → `_exportHd()`）与关闭面板两支 `[L2]` |
| **问诊（兽医计时付款）** | `consult/presentation/vet_timed_pay_page.dart`（**不走 sheet**，`QrImageView:552` + `_tick():85-103`） | 成功与超时两支 `[L2]` |
| **PawCoin 充值** | `pawcoin/presentation/recharge_page.dart`（**不走 sheet**，`QrImageView:329` + `_startPolling():106-132`） | 成功、`EXPIRED`、`FAILED` 三支 `[L2]` |

**And** `vet_timed_pay_page.dart` 与 `recharge_page.dart` 两个文件在本 story 的 diff 中**零改动**（`git diff --stat` 可证） `[L0]`
**And** 既有 `petgo_app/test/shared/qr_payment_sheet_test.dart` 全绿且**不需要修改**（若必须改，说明默认行为被动了，回去重做 AC7） `[L0]`

**AC9 · 静态与测试**
**Then** `flutter analyze` 零 issue `[L0]`
**And** `flutter test` 全绿，重点：`test/shared/qr_payment_sheet_test.dart`、`test/shop/shop_order_detail_page_v2_test.dart`、`test/shop/checkout_page_v2_test.dart` `[L0]`
**And** 新增 widget 测试覆盖 AC2~AC5 **四条分支各一条用例** `[L0]`

**AC10 · 模拟器验收**
**Then** 在 Android 模拟器连 staging 后端，用支付模拟器构造拒付 / 超时两支，界面表现符合 AC2 / AC3 `[L2]`
**And** 「仅关闭面板」后再次点支付能正常出码 `[L2]`

---

## Tasks / Subtasks

- [ ] **T1 · DTO 字段（若 1-1 未带上则补）**（AC1）
  - [ ] 确认 `petgo_app/lib/features/shop/domain/shop_order_detail.dart` 的 `ShopOrderDetail` 已有 `String? paymentStatus` / `String? paymentFailureCategory`（1-1 的 T5 已加）；没有则补上，`fromJson` 里同名键解析、缺键 → null
  - [ ] 新增本地枚举 `enum ShopPaymentFailure { gatewayDeclined, expired, userCancelled, unknown }` + `static ShopPaymentFailure? fromApi(String? raw)`，照该文件既有 `ShopOrderStatus.fromApi(String?)`（`:28`）的写法
  - [ ] 🔴 未知字符串 → `unknown`，**不要**映射成三态中的任何一个（后端加值时 App 不能猜）；`unknown` 在 UI 上按「通用失败」处理

- [ ] **T2 · 共用 sheet 走参数**（AC7）
  - [ ] `petgo_app/lib/shared/widgets/qr_payment_sheet.dart`：给 `QrPaymentAborted`（`:14-16`）加一个可选字段承载类别，**保留无参 `const` 构造**（如 `final String? category; const QrPaymentAborted([this.category]);`）
  - [ ] `showQrPaymentSheet`（`:20-35`）新增可选具名参数（如 `void Function(QrPaymentAborted abort)? onAborted`），**默认 null**
  - [ ] `_QrPaymentSheet` 透传该回调；`_tick()` 的 `on QrPaymentAborted` 分支（`:79-83`）在 `pop(false)` **之前**回调一次（若非 null）
  - [ ] 🔴 `_cancel()`（`:91-96`）**不碰** —— 用户点面板取消时回调**不触发**，这正是 AC5 与 AC4 的分界
  - [ ] 🔴 返回类型仍是 `Future<bool>`；**不要**改成返回枚举/记录，那会逼 4 个调用点全改（AC7 明禁）
  - [ ] 🔴 ②③④ 三个调用点（`unlock_method_sheet.dart:182`、`id_card_create_page.dart:381`、`id_card_detail_page.dart:336`）**一行不改**

- [ ] **T3 · 电商 `pollPaid` 三态判定**（AC1~AC4）
  - [ ] `shop_order_detail_page_v2.dart` 的 `_pay(...)`（`:730`）里改 `pollPaid` 闭包（`:750-757`）：
    - [ ] 先按 `fresh.paymentFailureCategory` 判：命中三态之一 → `throw QrPaymentAborted(<类别串>)`
    - [ ] 再保留既有 `fresh.status == ShopOrderStatus.cancelled → throw const QrPaymentAborted()`（**兜底**：后端未升级或字段为 null 时行为不退化）
    - [ ] 最后 `return fresh.status.isPaidOrLater`
  - [ ] 在 `_pay` 里用一个局部变量接住 `onAborted` 回调传来的类别（`ShopPaymentFailure? aborted`），面板关闭后按它分派：
    - [ ] `gatewayDeclined` → 展示被拒说明（新 key），**不做**任何跳转/取消，底部条自然保留支付按钮
    - [ ] `expired` → 展示 `shopOrderExpiredNotice`，**不弹** `shopOrderPayFailed`
    - [ ] `userCancelled` → 静默，只刷新
    - [ ] `aborted == null && paid == false`（仅关闭面板）→ **什么都不做**（不 toast、不调接口）
  - [ ] 🔴 现有 `catch (_) { Analytics.capture('toko_order_payment_failed_shown'); showAppToast(shopOrderPayFailed); }`（`:769-774`）是**兜真异常**的（网络错等），中止不是异常路径 —— 三态分派必须走正常返回路径，**不要**塞进这个 catch
  - [ ] 面板关闭后的 `ref.invalidate(shopOrderDetailProvider)` + `ref.invalidate(pawCoinProvider)`（`:759-761`）保留不动

- [ ] **T4 · 文案**（AC6）
  - [ ] `app_en.arb` + `app_id.arb` 各加：被拒说明（如 `shopPaymentDeclinedNotice`）+ 需要的话一个被拒态的行动提示；每条配 `@key` 的 `description`
  - [ ] 复用既有：`shopOrderExpiredNotice`（`app_en.arb:2805`）、`shopOrderPaid`（`:2830`）、`shopOrderPayFailed`（`:2832`）、`shopOrderCancelFailed`（`:2988`）
  - [ ] 🔴 **只有两个 ARB 文件**（本仓无第三语）；两包 key 集合必须相等，跑一次 `diff` 核对并写进 Completion Notes
  - [ ] 🔴 被拒文案**不得回显后端 `detail`**（`shopOrderPayFailed` 的 description 原话：「Never echoes the server detail string」）

- [ ] **T5 · 测试**（AC9）
  - [ ] 在 `petgo_app/test/shop/shop_order_detail_page_v2_test.dart` 追加四条用例（AC2~AC5 各一），沿用该文件既有的 `order({...})` 工厂与 `shopOrderDetailProvider` override 范式（`:26` host / `:33-40` overrides）
  - [ ] 断言点：被拒后底部支付按钮（`ValueKey('shopOrderPayV2')`）**仍在**；超时后**不在**；用户取消后无错误 toast；仅关闭面板后无任何接口调用且支付按钮仍在
  - [ ] 在 `petgo_app/test/shared/qr_payment_sheet_test.dart` 追加：不传 `onAborted` 时行为与原先一致；传了时 abort 会带类别回调一次、用户点 `qrPayCancel` 时**不回调**
  - [ ] 🔴 既有用例**不许改**（AC8）

- [ ] **T6 · 回归核对**（AC8）
  - [ ] `git diff --stat` 确认 `vet_timed_pay_page.dart` 与 `recharge_page.dart` 零改动
  - [ ] `git diff` 确认 `unlock_method_sheet.dart` / `id_card_create_page.dart` / `id_card_detail_page.dart` 零改动
  - [ ] `flutter analyze` + `flutter test`

- [ ] **T7 · 云端执行须知**
  - [ ] 云端只跑 L0：`flutter analyze` + `flutter test`。**云端无 GUI，模拟器与真机视觉一律留本地**
  - [ ] Completion Notes 写「L1/L2 待本地验收」+ ARB 两语（非三语）的偏离说明 + 共用 sheet 实测调用点数（4 个，非 5 条）

---

## Dev Notes

### 四种结局的判定矩阵（实现时对着这张表写，别凭印象）

| 结局 | 订单状态 | `paymentFailureCategory` | 面板 | 重试入口 | 文案 |
|---|---|---|---|---|---|
| 成功 | `PENDING_SHIPMENT`+ | null | 关（`pop(true)`） | — | `shopOrderPaid` |
| **支付被拒** | 仍 `PENDING_PAYMENT` | `GATEWAY_DECLINED` | 关 | **保留** | 新增「被拒」说明 |
| **超时未付** | `CANCELLED` | `EXPIRED` | 关 | **不给** | `shopOrderExpiredNotice` |
| **用户取消订单** | `CANCELLED` | `USER_CANCELLED` | 关 | — | **无**（静默） |
| **仅关闭面板** | **不变** `PENDING_PAYMENT` | null（未变） | 关 | 保留 | **无** |

最后两行的区别是本 story 最容易写错的地方：**都表现为面板关闭 + 返回 false**，唯一可靠的判据是「`pollPaid` 有没有抛出中止信号」。`_cancel()` 走的是 `pop(false)` 而非抛异常，所以只要不在 `_cancel()` 里回调 `onAborted`，两者天然分开（T2 已明确）。

### 为什么不改 `showQrPaymentSheet` 的返回类型

AD-S9 原话：「🔴 `QrPaymentSheet` 是共用组件……本次改造须**走参数而非改默认行为**，其余链路表现不变，回归用例写进 story」。
把 `Future<bool>` 改成 `Future<QrPaymentOutcome>` 会逼 4 个调用点全改，其中 3 个不在本 story 范围内 —— 风险 G-4（「支付面板改动波及其它四条链路」）说的就是这个。可选回调参数是唯一不动其它调用点的改法。

### 重试的实现：什么都不用新做

见 Context 现状六 —— 拒付后意图已是 `FAILED` 终态，`createIntent` 的复用判据不满足，再调 `pay` 自然出新码。
🔴 **不要**为重试新增端点、新增参数，或在 App 侧做「先取消再下单」。

### 与 epics 的偏离（写进 Completion Notes）

1. **ARB 两语不是三语** —— 本仓 App 只有 `app_en.arb` / `app_id.arb`（`l10n.yaml` 为证）。epics AC5 的「三语」指后台 properties。
2. **共用 sheet 是 3 条业务线 / 4 个调用点，不是 5 条** —— 问诊（`vet_timed_pay_page.dart`）与充值（`recharge_page.dart`）是各自内联实现，不走 sheet；高清身份证占 2 个调用点。回归仍按 SHOP-FR-01 的要求覆盖全部 5 条业务线（含两条不走 sheet 的），见 AC8。

### 不要做的事

- 不改 `_bottomBar`（`:618-625`）的过期判定 —— 「过期不留支付入口」已经实现且有注释，重复实现只会互相打架。
- 不改 `qr_payment_sheet.dart` 的 `_cancel()` 去调后端取消 —— `:18-19` 的注释写明了不调的理由（pending intent 可复用），改了会让「关个面板 = 作废订单」。
- 不在客户端把 `paymentFailureCategory` 的未知值兜底成某一态（C4：禁止在客户端兜底转换抹平契约差异）。
- 不碰 `vet_timed_pay_page.dart` / `recharge_page.dart` 一个字符。

### 关键 ValueKey（测试断言用）

`shopOrderPayV2`（支付按钮 `:643`）· `shopOrderCancelV2`（取消订单按钮 `:631`）· `shopOrderCancelDialogV2` / `shopOrderCancelConfirmYesV2`（二次确认 `:812-820`）· `qrPayImage`（`qr_payment_sheet.dart:122`）· `qrPayCancel`（`:140`）· `qrPayOrderRef`（`:130`）

### Project Structure Notes

- 改动文件：`shared/widgets/qr_payment_sheet.dart`、`features/shop/presentation/shop_order_detail_page_v2.dart`、`features/shop/domain/shop_order_detail.dart`、`l10n/app_en.arb`、`l10n/app_id.arb`，加两个测试文件的追加用例。
- 不新建 feature 目录、不引新 package。
- 后端零改动。

### References

- [Source: `_bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-shop-v2-delta.md#AD-S9`（(a) 段的 🔴 共用组件条）+ §6 风险 G-4]
- [Source: `_bmad-output/planning-artifacts/v1.3.0/epics-v1.3.0-shop-v2.md#Story 1-3`]
- [Source: `_bmad-output/planning-artifacts/v1.3.0/PRD-v1.3.0-shop-v2.md:168` SHOP-FR-01]
- [Source: `_bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md` C4（禁客户端兜底抹平契约）]
- [Source: `petgo_app/lib/shared/widgets/qr_payment_sheet.dart`]
- [Source: `petgo_app/lib/features/shop/presentation/shop_order_detail_page_v2.dart:618-625, 730-777, 808-836`]

## 验收与交付

- **L0**
  - `cd petgo_app && flutter analyze`
  - `cd petgo_app && flutter test`（重点 `test/shared/qr_payment_sheet_test.dart`、`test/shop/shop_order_detail_page_v2_test.dart`）
  - `git diff --stat` 核对回归文件零改动（AC8）
  - `diff` 两个 ARB 的 key 集合
- **L1**：本 story 无独立 L1；AC2 的「重试拿到新 payload」与 AC5 的「不调取消接口」可在联调时用接口日志印证（后端全接口 req/resp 落盘）。
- **L2**（需 Android 模拟器 + staging 后端）
  - 电商三支：拒付 / 超时 / 用户取消订单，逐条对照 AC2~AC4
  - 「仅关闭面板」后再次支付能出新码
  - **其余四条链路各跑一次**：AI 解锁、高清身份证（创建页 + 详情页）、问诊计时付款、PawCoin 充值 —— 成功与超时/取消表现与改动前一致（AC8）
  - 支付模拟器只在 `origin/stag` 分支，故 L2 在 staging 跑
- Completion Notes 必须写「**L1/L2 待本地验收**」+ 两处与 epics 的偏离。

## Definition of Done

- [ ] AC1~AC10 全部满足，四种结局各有一条 widget 测试
- [ ] 「用户取消订单」与「仅关闭面板」是两条独立分支、两条独立用例，未合并断言
- [ ] `showQrPaymentSheet` 返回类型未变、新能力走可选参数默认关闭、`const QrPaymentAborted()` 仍可用
- [ ] ②③④ 三个 sheet 调用点与 ⑤⑥ 两个非 sheet 页面**零 diff**（有 `git diff --stat` 证据）
- [ ] 既有 `qr_payment_sheet_test.dart` 未修改且全绿
- [ ] 新文案全部进两个 ARB，key 集合相等，代码无硬编码字面量，不回显后端 detail 串
- [ ] 未改 `_bottomBar` 过期判定、未改 sheet 的 `_cancel()`、未碰问诊与充值页面
- [ ] `flutter analyze` + `flutter test` 全绿
- [ ] Completion Notes 已写「L1/L2 待本地验收」+ ARB 两语偏离 + 共用 sheet 实测 4 个调用点

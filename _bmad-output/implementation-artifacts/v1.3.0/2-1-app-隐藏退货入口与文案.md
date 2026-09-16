---
baseline_commit: d1e8f5d3
branch: feat/1.3.0-shop-v2
theme: shop-v2
epic: 2
story: 2.1
ad: [AD-S12]
decisions: [SD-5, SD-2, SD-13]
fr: [SHOP-FR-05]
---

# Story 2-1: App 隐藏退货入口与文案

Status: ready-for-dev

> 自包含 story，可本地或云端执行。与用户沟通用中文。执行纪律见根 `CLAUDE.md`。
> **本 story 只碰 App 一侧**（`petgo_app/`）：后端退货接口、运营后台退货页面（`/admin/shop/returns*`）保留且可用，**一行后端代码都不改、无迁移**。

## Story

As a 用户，
I want 不要在 App 里看到一个点了也没用的退货入口，
so that 我不会白填一遍表单、传完凭证照片，再发现这条路走不通。

## Context

### 入口现状（唯一 push 点）

| 位置 | 现状 |
|---|---|
| `petgo_app/lib/features/shop/presentation/shop_order_detail_page_v2.dart:706` | `context.push('/shop/orders/${order.orderToken}/return')` —— **全 App 唯一**的退货 push 点，在 `_returnBar()`（:690–709）里 |
| 同文件 `_bottomBar()` :618–684 | 四条分支：① 待支付未过期（:625–656）→ `Batalkan` + `Bayar`；② 待支付已过期 → 落到 :683 返回 `null`（无底部条）；③ `order.status.canConfirmReceipt`（:658–679）→ `Lacak` + `Barang Diterima`；④ `order.status == ShopOrderStatus.completed`（:682）→ `_returnBar(...)`。**只有 ④ 会出现退货按钮** |
| `_returnBar()` :690–709 | 先 `ref.watch(returnEligibilityProvider(order.orderToken))`（provider 定义在 `lib/features/shop/data/shop_return_repository.dart:104`），`e == null` 返回 `null`；`blocked` 时置灰（ValueKey `shopOrderReturnV2`） |
| `lib/features/order/presentation/order_list_page_v2.dart` | 订单列表**没有**任何退货入口（已 grep 确认），只跳订单详情 |
| `_helpBlock()` :604–614 | 「有问题？」帮助区，渲染 `l10n.shopOrderHelpTitle` + `l10n.shopOrderHelpBody`；**后者就是「2×24 小时」那句退货窗口文案** |

### 文案现状（ARB）

App **只有两份 ARB**：`lib/l10n/app_en.arb` 与 `lib/l10n/app_id.arb`，**没有中文包**（`test/shop/return_flow_page_v2_test.dart` 头注明写「App 没有中文包」）。涉及的 key：

| key | 位置 | 当前是否被渲染 | 处置 |
|---|---|---|---|
| `shopOrderHelpBody` | en:3106「Returns can be requested within 2x24 hours of delivery」/ id:2099「Pengembalian bisa diajukan 2×24 jam setelah barang diterima」 | ✅ `shop_order_detail_page_v2.dart:611` | **换文案**（见 AC2） |
| `shopOrderConfirmReceiptBody` | en:2874 / id:1968，句尾「You can still request a return for 7 days after this.」 | ✅ `shop_order_detail_page_v2.dart:785`（确认收货弹窗 body） | **换文案**（见 AC2） |
| `shopOrderRequestReturn` | en:2994 / id:2028 | 仅 `_returnBar` :702 | 保留不删（入口整块不再渲染） |
| `shopOrderReturnInProgress` | en:2996 / id:2029 | 仅 `_returnBar` :701 | 保留不删 |
| `shopOrderReturnWindow` | en:2884 | ❌ 已无任何渲染点（grep 无命中） | 保留不删 |
| `shopOrderAutoCompletedHint` | en:2890，含「Your return window is unaffected.」 | ❌ 已无任何渲染点 | 保留不删 |

### 🔴 不在本 story 范围内（别顺手删）

- **`tokoReturnable*` / `tokoNoReturnAfterOpen*` / `tokoNoReturn*`**（商品详情 `product_detail_page_v2.dart:470–480`、结算页 `checkout_page_v2.dart:520–535`、退货申请页 `return_request_page_v2.dart:424`）是**商品「开封不退」属性的合规明示**（电商一期 FR-104 的三处明示），不是退货入口、也不是退货窗口时长文案。SHOP-FR-05 点名的是「订单详情 / 订单列表 / 帮助区」。**保留原样**；若产品要求连这三处一并隐藏，属范围变更 → 回决策日志确认，不得自行扩大。
- **`/me/refunds/*` 五条路由**（`app_router.dart:838–854`，`features/refund/`）是 **问诊 / 充值退款**链路（后端 `pay/refund/web/MeRefundController`，`/api/v1/me/refund-requests`），与电商退货无关，**不动**。

### 路由现状

`lib/core/router/app_router.dart`：
- :23–24 import `refund_method_page_v2.dart` / `return_request_page_v2.dart`
- :580–585 `/shop/orders/:token/return` → `ReturnRequestPageV2(orderToken: token)`
- :588–592 `/shop/returns/:token/refund-method` → `RefundMethodPageV2(returnToken: token)`

两条路由**必须保留**（老深链、通知跳转、外部分享链接都可能命中），但进入后不得渲染申请表单。

### 既有测试会变红（预期）

- `test/shop/shop_order_detail_page_v2_test.dart:518`「🔴 已有进行中的退货申请 → 入口置灰而不是隐藏」—— 断言 `shopOrderReturnV2` 存在，本 story 后必然消失，**须改写为「任何状态都不存在」**。
- `test/shop/return_flow_page_v2_test.dart`（约 20 条）直接 `pumpWidget(ReturnRequestPageV2/RefundMethodPageV2)`，**不经路由**。按 Tasks T3 的做法（改路由 builder、不改页面类）这些用例**全部照常绿**，是把下一版恢复退货的资产完整留住的关键。

## Acceptance Criteria

**AC1 · 订单详情任何状态都不出现退货入口**
**Given** 订单详情页 `ShopOrderDetailPageV2`
**When** 订单处于 `PENDING_PAYMENT` / `PAID` / `SHIPPED` / `DELIVERED` / `COMPLETED` / `CANCELLED` 任一状态
**Then** `find.byKey(const ValueKey('shopOrderReturnV2'))` 恒为 `findsNothing` `[L0]`
**And** `_bottomBar` 的其余三条分支行为**逐字不变**：待支付未过期仍出 `shopOrderCancelV2` + `shopOrderPayV2`；待支付已过期仍**无底部条**；`canConfirmReceipt` 仍出 `shopOrderTrackV2` + `shopOrderConfirmReceiptV2` `[L0]`
**And** `COMPLETED` 态底部条为 `null`（不留一条空白 bar，也不塞别的按钮）`[L0]`
**And** 页面不再 `ref.watch(returnEligibilityProvider(...))`（少一次无用的网络请求）`[L0]`

**AC2 · 退货窗口文案不出现**
**Given** 订单详情页帮助区与确认收货弹窗
**Then** `shopOrderHelpBody` 的 en / id 值均**不含**「2x24」「2×24」「return」「retur」「pengembalian」等退货措辞，改为指向客服的中性表述（en 建议「Having trouble with this order? Contact our customer service.」，id 建议「Ada kendala dengan pesanan ini? Hubungi layanan pelanggan kami.」，最终文案实施时定）`[L0]`
**And** `shopOrderConfirmReceiptBody` 的 en / id 值删去「still request a return for 7 days」/「tetap bisa ajukan retur sampai 7 hari」整句，只保留「确认收货前请确保包裹已在手上」那半句 `[L0]`
**And** `test/l10n/microcopy_rules_test.dart` 全绿（en / id 两包 key 集合仍相等）`[L0]`
**And** 🔴 上述六个 key **一个都不删**（下一版恢复退货要用），只改值 / 只停渲染；`shopOrderRequestReturn` / `shopOrderReturnInProgress` / `shopOrderReturnWindow` / `shopOrderAutoCompletedHint` 的**值保持原样不动**，仅在各自 `@key` 描述里追加「V1.3.0 hidden by SD-5 — restore in next version」`[L0]`

**AC3 · 只改 App**
**Then** 本 story 的 File List 中**不含** `petgo-backend/` 下任何文件，也不含任何 `.sql` `[L0]`
**And** 后端 `/api/v1/me/shop-returns*`（含 eligibility / submit / progress）与后台 `/admin/shop/returns**`（`AdminReturnController` 的 approve / reject / shipback / inspect-pass / inspect-fail / refund / precedents）保持可用，不做任何下线或门控 `[L0 · 代码审查]`

**AC4 · 退货路由保留但不可达，且不白屏**
**Given** 老版本 App 的深链 / 站外链接 `/shop/orders/{token}/return` 或 `/shop/returns/{token}/refund-method`
**When** 用户进入该路由
**Then** 渲染一屏「暂不支持退货，请联系客服」的说明页：`ShopAppBar` + 一段说明 + **一个可点的客服按钮**（调 `showCustomerServiceSheet(context)`，`lib/shared/widgets/customer_service_sheet.dart:24`）`[L0]`
**And** 该页**不发起任何网络请求**（不 watch `returnEligibilityProvider` / `returnProgressProvider`），因此不出现 loading 转圈、不出现错误重试态 `[L0]`
**And** 返回键可用（`ShopAppBar` 自带 back）；既不白屏、也不 redirect 到首页（redirect 会让人以为链接坏了）`[L0]`
**And** 两条路由的 `GoRoute` path 字符串**逐字不变** `[L0]`

**AC5 · 静态门槛**
**Then** `flutter analyze` 零警告；`flutter test` 全绿（含改写后的 `shop_order_detail_page_v2_test.dart` 与原样保留的 `return_flow_page_v2_test.dart`）`[L0]`

**AC6 · 模拟器逐态验收**
**Given** Android 模拟器 + 真后端 `https://api.tailtopia.id`
**Then** 待发货 / 已发货 / 已送达 / 已完成四种订单详情均**无**退货入口，帮助区文案不提退货，确认收货弹窗不提退货 `[L2]`
**And** 手动输入深链 `/shop/orders/{已完成单 token}/return` 进入，看到说明页 + 客服按钮，点客服按钮弹出客服抽屉 `[L2]`

## Tasks / Subtasks

- [ ] **T1 · 摘掉订单详情的退货入口**（AC1）
  - [ ] `shop_order_detail_page_v2.dart:682` 删去 `if (order.status == ShopOrderStatus.completed) return _returnBar(l10n, order);` 这一行，让 `COMPLETED` 落到 :683 的 `return null`
  - [ ] 整块删除 `_returnBar()` 方法（:686–709）及其头注释
  - [ ] 清理由此产生的未用 import / 未用符号：`returnEligibilityProvider` 的 import（来自 `../data/shop_return_repository.dart`）、`ShopOrderStatus.completed` 若已无其它引用则保留（它在别处仍用，实施时以 `flutter analyze` 为准）
  - [ ] 🔴 **不要**顺手改 `_bottomBar` 其余三条分支的任何一行 —— 支付按钮的金额口径（:646 `_cashSegment(order)`）、过期不留支付入口（:624–625）、支付中不置灰（:647–651）都是踩过坑写死的
  - [ ] 🔴 **不要**删 `shop_return_repository.dart` / `shop_return.dart` / `ReturnRequestPageV2` / `RefundMethodPageV2` —— 下一版要原样恢复，删了等于让 5-7/5-8 两个 story 重做

- [ ] **T2 · 改两条退货文案**（AC2）
  - [ ] `app_en.arb:3106` + `app_id.arb:2099` 改 `shopOrderHelpBody` 值（不删 key，不改 key 名）
  - [ ] `app_en.arb:2874` + `app_id.arb:1968` 改 `shopOrderConfirmReceiptBody` 值，删去退货窗口那半句
  - [ ] 给 `shopOrderRequestReturn` / `shopOrderReturnInProgress` / `shopOrderReturnWindow` / `shopOrderAutoCompletedHint` 四个 key 的 `@` 描述追加「V1.3.0 hidden by SD-5 — restore in next version」（只改 en 包的 `@` 描述即可，`@` 项按现仓习惯只在 en 包里有）
  - [ ] 🔴 改完必须 `flutter gen-l10n`，否则编译失败
  - [ ] 🔴 **不得删 key**：仓内**没有**「未使用 ARB key」检查（`test/l10n/microcopy_rules_test.dart` 只查值不查引用），留着零成本；删掉则下一版恢复时要连印尼语一起重写

- [ ] **T3 · 路由 builder 换成说明页，页面类原样留着**（AC4）
  - [ ] 新建 `lib/features/shop/presentation/shop_return_unavailable_page.dart`：无状态 `StatelessWidget`，`Scaffold(backgroundColor: ShopColors.bg, appBar: ShopAppBar(title: ...))` + 居中说明 + `ShopButton(variant: ShopButtonVariant.outlinePurple, onTap: () => showCustomerServiceSheet(context))`；顶层 `ValueKey('shopReturnUnavailableV2')`
  - [ ] `app_router.dart:583` 与 :591 的 builder 一律 `return const ShopReturnUnavailablePage();`，**path 字符串不动**
  - [ ] 删 `app_router.dart:23–24` 两条 import，加新页 import
  - [ ] 🔴 **不在 `redirect` 里拦**：顶层 `redirect`（:418 起）是受控路由的唯一门控入口，往里加特例会污染那套逻辑，而且 redirect 走了用户就看不到「为什么不行 + 找谁」
  - [ ] 🔴 **不改 `ReturnRequestPageV2` / `RefundMethodPageV2` 本身** —— 改了 `test/shop/return_flow_page_v2_test.dart` 约 20 条会连锁变红，而那些用例守的是「回程运费归属」「凑单套利」「没有的补偿不许承诺」这类资损点，下一版要靠它们
  - [ ] 新 key 三条进两份 ARB：`shopReturnUnavailableTitle` / `shopReturnUnavailableBody` / `shopReturnUnavailableContactCs`

- [ ] **T4 · 测试**（AC1 / AC4 / AC5）
  - [ ] 改写 `test/shop/shop_order_detail_page_v2_test.dart:518` 那条：断言由 `findsOneWidget` 改为 `findsNothing`，用例名改成「🔴 SD-5：任何状态都不出现退货入口」；`host(...)` 里的 `returnEligibilityProvider.overrideWith`（:34）保留无害，也可一并删
  - [ ] 新增用例：遍历 `ShopOrderStatus` 六个值 pump 订单详情，断言 `shopOrderReturnV2` 恒 `findsNothing`
  - [ ] 新增用例：pump `ShopReturnUnavailablePage`，断言说明文案在、客服按钮在、且**没有** `CircularProgressIndicator`
  - [ ] 新增用例（文案守门）：直接解析 `lib/l10n/app_{en,id}.arb`（照 `test/l10n/microcopy_rules_test.dart:10` 的 `_loadArb` 范式），断言 `shopOrderHelpBody` 与 `shopOrderConfirmReceiptBody` 两个值不含 `2x24` / `2×24` / `retur` / `return`（大小写不敏感）—— 这是防「下次有人把老文案抄回来」的护栏
  - [ ] 全量 `flutter test`

- [ ] **T5 · 云端执行须知**
  - [ ] 云端只跑 L0：`flutter analyze` + `flutter test`（`flutter gen-l10n` 先跑）
  - [ ] Completion Notes 标注「L2 待本地验收」（模拟器逐态 + 深链）

## Dev Notes

### 为什么是「路由保留 + 换 builder」而不是「删路由」

删路由后老深链会落到 go_router 的 `errorBuilder`，用户看到的是一个通用错误页 —— 与 AC4「不得白屏 / 要能找到客服」相反。换 builder 的另一个好处是：下一版恢复退货只需把两行 builder 改回去，`ReturnRequestPageV2` / `RefundMethodPageV2` / `shop_return_repository.dart` / 那 20 条测试**一个字都不用动**。

### 为什么文案是「改值」不是「删 key」

SD-5 明写「App 端现有的退货相关入口与文案**一并隐藏**」，SD-4 明写「退货相关功能**下版本再做**」。删 key = 下一版要把印尼语文案重新翻一遍，而印尼语初稿的复核成本是本仓已知的债（见根 `CLAUDE.md` 的 i18n 约定）。`shopOrderHelpBody` / `shopOrderConfirmReceiptBody` 两个 key 仍在渲染，所以只能改值；其余四个已经没有渲染点，原样躺着即可。

### 帮助区改成什么

`_helpBlock` 的结构（标题 + 一行 meta 文字）不动，只换 `shopOrderHelpBody` 的值。**不要**在这里加客服按钮 —— Story 3-1「客服联系方式配置化」会把客服号改成后端下发的配置项，本 story 现在往帮助区塞一个读硬编码号码的按钮，3-1 要拆一次。说明页（T3）里的客服入口直接复用既有共享件 `showCustomerServiceSheet`，它在 3-1 里会被整体改造，本 story 不引入新的号码字面量。

### 现状代码要点

| 文件 | 现状 | 本 story 改什么 | 必须保留 |
|---|---|---|---|
| `shop_order_detail_page_v2.dart` | `_bottomBar` 四分支；`_returnBar` 唯一 push 点；`_helpBlock` 渲染 2×24 文案 | 删 :682 一行 + 删 `_returnBar`；`_helpBlock` 结构不变 | 支付/取消/确认收货三分支逐字不变，`_cashSegment` 口径不变 |
| `app_router.dart` | :580–592 两条退货路由 | 两个 builder 换成说明页；换 import | path 字符串、`redirect`、`_controlledLocations` |
| `return_request_page_v2.dart` / `refund_method_page_v2.dart` | 完整可用 | **不改** | 全部 |
| `shop_return_repository.dart` | `returnEligibilityProvider` :104、`returnProgressProvider` :112 | **不改**（订单详情不再 watch 而已） | 全部 |
| `app_en.arb` / `app_id.arb` | 6 个退货 key | 改 2 个值、追加 4 条 `@` 说明、加 3 个新 key | key 一个不删 |
| `customer_service_sheet.dart` | `showCustomerServiceSheet(context)` :24 | **不改** | 号码常量留给 3-1 处置 |

### Project Structure Notes

新页落 `lib/features/shop/presentation/`（与 `return_request_page_v2.dart` 同目录），符合 `features/<f>/{data,domain,presentation}` 约定。不新建 feature 目录、不动 `shared/`。

### References

- [Source: _bmad-output/planning-artifacts/v1.3.0/epics-v1.3.0-shop-v2.md#Story 2-1]
- [Source: _bmad-output/planning-artifacts/v1.3.0/PRD-v1.3.0-shop-v2.md#3.2 SHOP-FR-05]
- [Source: _bmad-output/planning-artifacts/v1.3.0/决策日志-shop-v2.md SD-5 / SD-4]
- [Source: _bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-shop-v2-delta.md#AD-S12（SHOP-FR-05 = 零后端改动，纯 App）]
- [Source: petgo_app/lib/features/shop/presentation/shop_order_detail_page_v2.dart:604-709]
- [Source: petgo_app/lib/core/router/app_router.dart:580-592]
- [Source: petgo_app/lib/shared/widgets/customer_service_sheet.dart:24]

## 验收与交付

**L0（云端 / 本地都能跑，是本 story 的门槛）**
```bash
cd petgo_app
flutter pub get
flutter gen-l10n            # 改 ARB 后必跑，否则编译失败
flutter analyze             # 零警告
flutter test                # 全量，含改写后的 shop_order_detail_page_v2_test.dart
flutter test test/shop/return_flow_page_v2_test.dart   # 必须原样全绿（证明没动页面类）
flutter test test/l10n/microcopy_rules_test.dart
```

**L1** —— 无。本 story 不碰后端、不碰 DB。

**L2（必须本地，云端 headless 做不了）**
1. Android 模拟器装包连 `https://api.tailtopia.id`，真 Google 登录。
2. 造 / 找四种状态的电商订单，逐个进详情截图：底部条无退货按钮、帮助区文案不提退货。
3. 已发货单点「Barang Diterima」，截图弹窗 body 不提退货。
4. `adb shell am start -a android.intent.action.VIEW -d "<深链>"` 或页内手动导航进 `/shop/orders/{token}/return`，截图说明页 + 点客服按钮弹抽屉。

**云端 session 须在 Completion Notes 写「L2 待本地验收」，并列出待验的 4 项。**

## Definition of Done

- [ ] AC1–AC5 全部满足，`flutter analyze` 零警告、`flutter test` 全绿
- [ ] File List 不含 `petgo-backend/` 任何文件、不含 `.sql`
- [ ] 六个退货 ARB key 一个未删；`return_request_page_v2.dart` / `refund_method_page_v2.dart` / `shop_return_repository.dart` / `shop_return.dart` 四个文件零改动
- [ ] `test/shop/return_flow_page_v2_test.dart` 未被修改且全绿
- [ ] AC6（L2）已在本地验收并附截图，或 Completion Notes 明确标注「L2 待本地验收」
- [ ] Completion Notes 记录：下一版恢复退货的最小改动清单（两行 builder + 一行 `_bottomBar` 分支 + 两条文案值）

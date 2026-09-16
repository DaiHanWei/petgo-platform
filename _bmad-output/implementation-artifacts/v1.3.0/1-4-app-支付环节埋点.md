---
baseline_commit: d1e8f5d3
branch: feat/1.3.0-shop-v2
theme: shop-v2
epic: 1
story: 1.4
ad: [AD-S9]
decisions: [SD-1]
fr: [SHOP-FR-02]
nfr: [SHOP-NFR-01]
---

# Story 1-4: App 支付环节埋点

Status: review

> 自包含 story，可本地或云端 L0 执行。与用户沟通用中文。执行纪律见根 `CLAUDE.md`（后端→前端→联调、AC 标 L0/L1/L2、Flyway 时间戳版本号、`mvn -B clean package`）。
> 本 story **只碰 App**（`petgo_app/`），后端一行不改。
> 🔴 **前置：1-3 必须先完成** —— 本 story 的「被拒 / 超时 / 仅关闭面板 / 重试」四个埋点点位，正是 1-3 建立的四条分支；1-3 没做，这四个事件无处挂。

## Story

As a 运营，
I want 知道用户在支付哪一步离开、有没有重试，
so that 我能判断卡点在出码前还是出码后（SD-1：不查库定性，在 PostHog 持续看）。

## Context（本 story 必须知道的现状）

### 现状一：电商支付链路已有 4 个埋点，本 story 是**补齐**不是重建

`/Users/dai/work/petgo-platform/petgo_app/lib/features/shop/presentation/shop_order_detail_page_v2.dart` 上的既有事件：

| file:line | 事件名 | 属性 |
|---|---|---|
| `:80` | `toko_order_detail_viewed` | 无 |
| `:717` | `toko_order_tracking_copy_tapped` | 无 |
| `:722` | `toko_order_tracking_site_tapped` | 无 |
| **`:731`** | **`toko_order_pay_tapped`** | 无 —— 「进入支付」这一格已经有了 |
| **`:740`** | **`toko_order_payment_succeeded`** | `{pay_channel: 'PAWCOIN', attribution_source}` —— 纯 PawCoin 当场结清那支 |
| **`:763`** | **`toko_order_payment_succeeded`** | `{pay_channel: order.payChannel ?? 'UNKNOWN', attribution_source}` —— 扫码到账那支 |
| **`:771`** | **`toko_order_payment_failed_shown`** | 无 —— 在 `catch (_)` 里，**兜的是真异常**（网络错等），不是三态中止 |
| `:793` / `:799` | `toko_order_receipt_confirm_tapped` / `_succeeded` | 无 |
| **`:823`** | **`toko_order_cancel_tapped`** | 无 |

⇒ 漏斗当前只能拼到「点支付 → 成功」，中间的**出码、离开、被拒、超时、重试**五格全是空的。

### 现状二：`Analytics` 门面的通路与约束

`/Users/dai/work/petgo-platform/petgo_app/lib/core/analytics/analytics.dart`（`class Analytics` `:22`，纯静态门面）：

- `static Future<void> capture(String event, [Map<String, Object>? properties])` `:150` —— properties **自动过 `scrub`**（`:150-151`），再同步分发 PostHog；命中 AppsFlyer 白名单的还会分发 AppsFlyer（`:152-154`）。
- `static Map<String, Object> scrub(Map<String, Object> props)` `:254-262` —— 三道规则：PII 键丢弃（`_piiKeys` `:39`、后缀表 `_piiKeySuffixes = {name,phone,address,email,whatsapp}` `:290-292`）、自由文本键丢弃（`_freeTextKeys` `:53`）、字符串值超 `_maxStringValueLen = 64`（`:58`）丢弃；**递归**处理嵌套 map 与 List（`_scrubValue` `:265-282`）。
- `@visibleForTesting static void Function(String, Map<String,Object>?)? debugCaptureSink` `:146` —— **L0 测试的唯一观察点**，生产恒 null。既有范式见 `/Users/dai/work/petgo-platform/petgo_app/test/shop/epic1_analytics_test.dart:40-44`：
  ```dart
  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, props) => events.add((e, props));
  });
  tearDown(() => Analytics.debugCaptureSink = null);
  ```
- `static const Set<String> appsflyerEvents` `:129-137` —— **6 个**，含 `af_purchase`（注释原话：「PawCoin 充值成功——唯一真实收入事件；PawCoin 消耗**不得**计入」）。

### 现状三：🔴 `_allowedButtonIds` 这条腿在电商侧用不上

- `static Future<void> buttonTapped(String id, {String? screen})` `:231-240` 发的事件名是固定的 **`button_tapped`**，`button_id` 只是一个属性：
  ```dart
  return capture('button_tapped', {'button_id': id, 'screen': ?screen});
  ```
- 白名单 `_allowedButtonIds` `:218-222` 共 **8 条**，全部是 `ButtonId` 常量引用；常量源 `/Users/dai/work/petgo-platform/petgo_app/lib/core/analytics/button_ids.dart:5-13`（`abstract final class ButtonId`，值形如 `triage.start`）。**8 条里没有一条与电商/支付相关。**
- 电商既有的 10 个事件**全部走 `Analytics.capture('toko_*')`**，没有一个走 `buttonTapped`。

⇒ epics 1-4 AC2 写的「按钮类事件的 id 进 `_allowedButtonIds` 白名单与 `button_ids.dart` 常量」，在电商既有约定下**不适用**：走 `buttonTapped` 会让重试/取消落到 `button_tapped` 这个事件里，**拼不进 `toko_*` 漏斗**。本 story 按既有约定走 `capture`，该腿记为 N/A（见 AC7）。

### 现状四：1-3 建立的四条分支就是本 story 的点位

1-3 改完后 `_pay(...)`（`shop_order_detail_page_v2.dart:730`）的结构是：拿到 `payload` → 开面板 → 面板关闭后按「是否抛过中止 + 中止类别」分派成 **被拒 / 超时 / 用户取消 / 仅关闭面板** 四支。本 story 就在这四支上各挂一个事件，**不重新设计分支**。

## Acceptance Criteria

**AC1 · 补齐漏斗缺的五格**
**Given** 现状一列出的 4 个既有支付事件
**When** 本 story 落地
**Then** 新增下列事件，事件名一律 `toko_` 前缀 + snake_case（与既有 10 个 `toko_*` 一致） `[L0]`

| 漏斗格 | 事件名 | 触发时机 |
|---|---|---|
| 进入支付 | **复用既有** `toko_order_pay_tapped`（`:731`） | 点支付按钮 |
| **二维码展示成功** | `toko_payment_qr_shown` | `pay` 返回且 `payload != null`、即将打开面板时 |
| **关闭面板（未完成）** | `toko_payment_sheet_dismissed` | 面板关闭、未到账、**且未抛过中止信号**（1-3 的「仅关闭面板」支） |
| 收到成功反馈 | **复用既有** `toko_order_payment_succeeded`（`:740` / `:763`） | 到账 |
| **收到被拒反馈** | `toko_payment_declined_shown` | 1-3 的 `GATEWAY_DECLINED` 支 |
| **收到超时反馈** | `toko_payment_expired_shown` | 1-3 的 `EXPIRED` 支 |
| **点击重试** | `toko_payment_retry_tapped` | 被拒后再次点支付按钮 |
| 取消订单 | **复用既有** `toko_order_cancel_tapped`（`:823`）+ 新增 `toko_order_cancel_succeeded` | 点取消 / 取消接口成功返回 |

**And** 既有 4 个事件的**名称与属性集合一个字都不改**（运营看板已在用，改名等于断掉历史序列） `[L0]`

**AC2 · 重试与首次支付可区分**
**Given** 被拒后底部条仍保留支付按钮（1-3 AC2）
**When** 用户再次点支付
**Then** 除既有 `toko_order_pay_tapped` 外，额外上报一条 `toko_payment_retry_tapped` `[L0]`
**And** 首次支付（未经历过被拒）**不**上报 `toko_payment_retry_tapped` `[L0]`
**And** 判据是页面状态里「上一次失败类别是否为被拒」，不是计数器猜测 `[L0]`

**AC3 · 属性只含枚举与数值**
**Then** 新增事件的属性**只允许**下列键 `[L0]`：

| 键 | 类型 | 取值 |
|---|---|---|
| `pay_channel` | String | `QRIS` / `PAWCOIN` / `MIXED` / `UNKNOWN`（受控枚举线，与既有 `:763` 同源） |
| `attribution_source` | String | 既有受控值（`ShopOrderDetail.attributionSource`，默认 `'unknown'`） |
| `failure_category` | String | `GATEWAY_DECLINED` / `EXPIRED` / `USER_CANCELLED` / `UNKNOWN` |
| `has_pawcoin` | bool | `coinAmount != null && coinAmount > 0` |

**And** **不含订单号**（`orderToken` / `displayNo` / 任何订单标识），也不含姓名 / 电话 / 地址 / 邮箱 / 任何自由文本 `[L0]`
**And** 有一条测试**逐事件逐键**断言禁用键一个都不出现（不是「写的时候注意点」） `[L0]`

**AC4 · 走既有门面与 scrub，不旁路**
**Then** 全部新增上报走 `Analytics.capture(...)`，不直接调 `Posthog()`、不直接调 `AppsFlyerClient` `[L0]`
**And** 新增事件**一个都不加进** `Analytics.appsflyerEvents`（`analytics.dart:129-137`）—— 那是归因/投放白名单，PawCoin 消耗与电商下单**不得**计为收入事件 `[L0]`

**AC5 · 测试覆盖（epics 点名的两个）**
**Then** `flutter test` 覆盖「**关闭面板**」与「**点击重试**」两个事件的触发 `[L0]`
**And** 另外覆盖「被拒」「超时」两个事件的触发，以及「首次支付不发 retry」这条反例 `[L0]`
**And** 测试用 `Analytics.debugCaptureSink` 观察，沿用 `test/shop/epic1_analytics_test.dart:40-44` 的 setUp/tearDown 范式 `[L0]`

**AC6 · 不污染其它四条支付链路**
**Given** 共用组件 `showQrPaymentSheet` 还服务 AI 解锁与高清身份证两条线（1-3 Context 现状二）
**Then** 本 story **不在 `qr_payment_sheet.dart` 里加任何 `Analytics` 调用** —— 全部事件从电商侧发出 `[L0]`
**And** `petgo_app/lib/shared/widgets/qr_payment_sheet.dart`、`features/triage/presentation/widgets/unlock_method_sheet.dart`、`features/profile/presentation/id_card_create_page.dart`、`features/profile/presentation/id_card_detail_page.dart`、`features/consult/presentation/vet_timed_pay_page.dart`、`features/pawcoin/presentation/recharge_page.dart` 六个文件在本 story diff 中**零改动**（`git diff --stat` 可证） `[L0]`

**AC7 · 按钮白名单这条腿记为 N/A 并留证**
**Given** 现状三：`buttonTapped` 发的是 `button_tapped`，拼不进 `toko_*` 漏斗
**Then** 本 story **不新增任何 `Analytics.buttonTapped` 调用点**，`button_ids.dart` 与 `_allowedButtonIds` **零改动** `[L0]`
**And** 有一条测试断言「`_allowedButtonIds` 仍是 8 条」，防后续有人顺手改坏 `[L0]`
**And** 该偏离（epics 1-4 AC2 的白名单腿 N/A）与理由写进 Completion Notes `[L0]`

**AC8 · 静态与回归**
**Then** `flutter analyze` 零 issue `[L0]`
**And** `flutter test` 全绿；既有 `test/shop/epic1_analytics_test.dart`、`test/shop/epic3_analytics_test.dart`、`test/analytics/*` 与 `test/shop/shop_order_detail_page_v2_test.dart` **不需要修改**即全绿 `[L0]`

**AC9 · staging 实跑能拼出漏斗**
**When** 在 staging 跑一遍完整支付
**Then** PostHog 中能按顺序拼出 `toko_order_pay_tapped` → `toko_payment_qr_shown` → `toko_order_payment_succeeded` 三格漏斗 `[L2]`
**And** 跑一次被拒与一次超时，能看到 `toko_payment_declined_shown` / `toko_payment_expired_shown`，并与服务端 1-2 的 `shop_payment_declined` / `shop_payment_expired` 落在**同一个 PostHog project** `[L2]`
**And** App 侧事件按 App Version 的 `-stag` 后缀与生产区分（既有口径，出包走 `scripts/build-stag-apk.sh`） `[L2]`

---

## Tasks / Subtasks

- [ ] **T1 · 出码与关闭面板**（AC1）
  - [ ] `shop_order_detail_page_v2.dart` 的 `_pay(...)`（`:730`）：在 `showQrPaymentSheet(...)` 调用**之前**、确认 `result.payload != null` 之后，`Analytics.capture('toko_payment_qr_shown', {...})`
  - [ ] 面板返回后，在 1-3 建立的「仅关闭面板」分支里 `Analytics.capture('toko_payment_sheet_dismissed', {...})`
  - [ ] 🔴 「出码成功」必须从**电商侧**发，不要为了「更准」跑去 sheet 内部发 —— sheet 是共用组件（AC6）
  - [ ] 🔴 纯 PawCoin 当场结清那支（`:736-743`）**不出码**，因此**不发** `toko_payment_qr_shown`；它直接发既有的 `toko_order_payment_succeeded`，保持不动

- [ ] **T2 · 三态反馈事件**（AC1、AC3）
  - [ ] 在 1-3 的三个中止分支各挂一条：`toko_payment_declined_shown` / `toko_payment_expired_shown`；「用户取消订单」支用既有 `toko_order_cancel_tapped` 体系，不新造 `shown` 事件（它不是失败反馈，是用户自己的动作）
  - [ ] 三条都带 `failure_category`（受控枚举线），加 `pay_channel` / `has_pawcoin` / `attribution_source`
  - [ ] 🔴 现有 `catch (_)` 里的 `toko_order_payment_failed_shown`（`:771`）**保留不动** —— 它兜的是真异常（网络错、payload 为空等），与三态是两回事。别把三态塞进这个 catch，也别删掉它

- [ ] **T3 · 重试事件**（AC2）
  - [ ] 在 `_ShopOrderDetailPageV2State` 加一个私有状态位记录「上次是否被拒」（如 `bool _lastPaymentDeclined = false`），被拒分支置 true，成功 / 超时 / 取消 / 页面重建置 false
  - [ ] `_pay(...)` 入口：`if (_lastPaymentDeclined) Analytics.capture('toko_payment_retry_tapped', {...});`，放在既有 `toko_order_pay_tapped`（`:731`）之后
  - [ ] 🔴 既有 `toko_order_pay_tapped` **照发不误**（漏斗的「进入支付」分母靠它，重试也是一次进入支付）
  - [ ] 🔴 状态位用 `setState` 之外的普通字段即可（不影响渲染），但要保证 `dispose`/重建后不残留

- [ ] **T4 · 取消成功事件**（AC1）
  - [ ] `_cancel(...)`（`:808`）里，`shopOrderRepositoryProvider.cancel(...)`（`:826`）成功返回后追加 `Analytics.capture('toko_order_cancel_succeeded', {...})`
  - [ ] 既有 `toko_order_cancel_tapped`（`:823`）不动；失败路径（`:834` 的 `shopOrderCancelFailed`）不新增事件

- [ ] **T5 · 属性组装收口**（AC3、AC4）
  - [ ] 在 state 里加一个私有方法统一组装属性，如 `Map<String, Object> _payProps(ShopOrderDetail order, {String? failureCategory})`，返回 `pay_channel` / `attribution_source` / `has_pawcoin`（+ 可选 `failure_category`）
  - [ ] 🔴 **绝不放 `order.orderToken`** —— 订单号对运营有用但对漏斗没用，且它是对外标识，进第三方等于扩大标识面（SHOP-NFR-01）
  - [ ] 🔴 不放 `receiverName` / `receiverPhone` / `addressText`（`ShopOrderDetail` 上就有这三个，离得很近，最容易手滑）
  - [ ] `pay_channel` 取 `order.payChannel ?? 'UNKNOWN'`（与既有 `:763` 同一写法，保持口径一致）
  - [ ] 全部走 `Analytics.capture`；**不碰** `Analytics.appsflyerEvents`

- [ ] **T6 · 测试**（AC5、AC7）
  - [ ] 新建 `petgo_app/test/shop/shop_payment_analytics_test.dart`（**不要**叫 `epic1_analytics_test.dart` —— 那个名字已被电商一期 Epic 1 占用，会误导）
  - [ ] setUp/tearDown 用 `Analytics.debugCaptureSink` 范式（抄 `test/shop/epic1_analytics_test.dart:40-44`）
  - [ ] 用例清单：
    - [ ] 出码 → `toko_payment_qr_shown`
    - [ ] **关闭面板（未完成）→ `toko_payment_sheet_dismissed`**（epics 点名）
    - [ ] 被拒 → `toko_payment_declined_shown` 且 `failure_category == 'GATEWAY_DECLINED'`
    - [ ] 超时 → `toko_payment_expired_shown`
    - [ ] **被拒后再点支付 → `toko_payment_retry_tapped`**（epics 点名）
    - [ ] **首次支付不发 `toko_payment_retry_tapped`**（反例）
    - [ ] 取消成功 → `toko_order_cancel_succeeded`
    - [ ] **PII 断言**：遍历本次捕获的所有事件属性，断言键集合 ⊆ 四个允许键，且不含 `order_token` / `receiver_name` / `phone` / `address` / `email`
    - [ ] `Analytics.isRegisteredButtonId` 白名单仍是 8 条（AC7）
  - [ ] 沿用 `test/shop/shop_order_detail_page_v2_test.dart` 的 `order({...})` 工厂与 provider override 范式构造各态订单

- [ ] **T7 · 回归核对**（AC6、AC8）
  - [ ] `git diff --stat` 确认 AC6 列的六个文件零改动
  - [ ] `git diff` 确认 `core/analytics/analytics.dart` 与 `core/analytics/button_ids.dart` 零改动
  - [ ] `flutter analyze` + `flutter test`

- [ ] **T8 · 云端执行须知**
  - [ ] 云端只跑 L0：`flutter analyze` + `flutter test`。**PostHog 看板验证（L2）必须在 staging 真跑，云端做不了**
  - [ ] Completion Notes 写「L1/L2 待本地验收」+ AC7 的白名单腿 N/A 说明 + 新增事件名全集（交给运营配看板）

---

## Dev Notes

### 事件名全集（交付时复制给运营配看板用）

**本 story 新增 5 个**：
`toko_payment_qr_shown` · `toko_payment_sheet_dismissed` · `toko_payment_declined_shown` · `toko_payment_expired_shown` · `toko_payment_retry_tapped` · `toko_order_cancel_succeeded`（6 个，其中 `toko_order_cancel_succeeded` 属「取消」格）

**复用既有 4 个**：
`toko_order_pay_tapped` · `toko_order_payment_succeeded` · `toko_order_payment_failed_shown` · `toko_order_cancel_tapped`

**漏斗拼法**（SHOP-FR-02 验收项）：
`toko_order_pay_tapped` → `toko_payment_qr_shown` → `toko_order_payment_succeeded`
**失败原因分布**：`toko_payment_declined_shown` / `toko_payment_expired_shown` / `toko_payment_sheet_dismissed` 三者按日分组。

### 与 1-2 服务端事件的对应关系（同一个 PostHog project）

| App 侧 | 服务端侧（1-2） |
|---|---|
| `toko_payment_qr_shown` | `shop_payment_intent_created` |
| `toko_order_payment_succeeded` | `shop_payment_paid` |
| `toko_payment_declined_shown` | `shop_payment_declined` |
| `toko_payment_expired_shown` | `shop_payment_expired` |
| （无，用户取消走 `toko_order_cancel_*`） | `shop_payment_user_cancelled` |
| `toko_payment_sheet_dismissed` | **无服务端对应** —— 关面板不产生任何服务端状态变化，这正是它的价值 |

两侧的 `distinct_id` 必须对得上：App 走 `Analytics.distinctIdFor(userId)`（`analytics.dart:245`，`sha256('tailtopia-user-$userId')`），服务端走 `AnalyticsDistinctId.of(userId)`（同算法，`AnalyticsDistinctId.java:27`）。**本 story 不碰这两处**，只是提醒别自造 distinctId。

### 已知盲区（SD-1 要求如实记录）

「**用户已付款但系统没收到到账通知**」在 App 侧表现为 `toko_payment_expired_shown`，与真超时**无法区分**。本 story 的埋点发现不了它，只能靠支付网关后台 / 对账。
⇒ 不要为此加「用户自报已付款」按钮或任何客户端判定，SD-1 已拍板本版就到监控为止。

### 与 epics 的偏离（写进 Completion Notes）

**epics 1-4 AC2 的「按钮 id 进 `_allowedButtonIds` 与 `button_ids.dart`」在本 story 为 N/A**。理由：`Analytics.buttonTapped(id)` 发出的事件名是固定的 `button_tapped`（`analytics.dart:236-239`），`button_id` 只是属性 —— 走这条通路，重试与取消就**拼不进 `toko_*` 漏斗**，而漏斗正是 SHOP-FR-02 的验收物。电商既有 10 个事件全部走 `Analytics.capture('toko_*')`，本 story 保持一致。
若日后决定把电商按钮纳入 `button_tapped` 体系，须**两处同改**（`button_ids.dart` 加常量 + `_allowedButtonIds` 加引用），且那是一次独立的口径变更，不在本 story 内顺手做。

### 不要做的事

- 不在 `qr_payment_sheet.dart` 里加埋点（共用组件，会给另外 3 个调用点凭空多出事件）。
- 不改既有 4 个事件的名称或属性（看板历史序列会断）。
- 不把新事件加进 `appsflyerEvents`（`analytics.dart:129-137` 注释已明令 PawCoin 消耗不得计入收入）。
- 不在属性里放订单号 —— 即便「只是为了排查方便」。要排查有后端接口日志。
- 不动 `analytics.dart` 与 `button_ids.dart`。

### Project Structure Notes

- 改动文件只有 `features/shop/presentation/shop_order_detail_page_v2.dart` 一个主源 + 一个新测试文件。
- 不新增 package、不新增 provider、不改 `core/analytics/**`。
- 后端零改动、无 Flyway 迁移。

### References

- [Source: `_bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-shop-v2-delta.md#AD-S9`（(b) 段 App 端条）+ §6 风险 G-5]
- [Source: `_bmad-output/planning-artifacts/v1.3.0/epics-v1.3.0-shop-v2.md#Story 1-4`]
- [Source: `_bmad-output/planning-artifacts/v1.3.0/决策日志-shop-v2.md` SD-1]
- [Source: `_bmad-output/planning-artifacts/v1.3.0/PRD-v1.3.0-shop-v2.md:169` SHOP-FR-02 / SHOP-NFR-01]
- [Source: `_bmad-output/planning-artifacts/v1.3.0/validation-2026-09-15-shop-v2/arch-input-extract.md#D.6`（App 侧 scrub / 白名单事实）]
- [Source: `petgo_app/lib/core/analytics/analytics.dart:129-137, 146, 150-162, 218-240, 254-262`]
- [Source: `petgo_app/lib/core/analytics/button_ids.dart:5-13`]
- [Source: `petgo_app/test/shop/epic1_analytics_test.dart:40-44`（debugCaptureSink 范式）]

## 验收与交付

- **L0**
  - `cd petgo_app && flutter analyze`
  - `cd petgo_app && flutter test`（重点 `test/shop/shop_payment_analytics_test.dart`）
  - `git diff --stat` 核对 AC6 的六个文件与 `core/analytics/**` 零改动
- **L1**：本 story 无独立 L1（纯客户端上报，无后端契约变更）。
- **L2**（需 Android 模拟器/真机 + staging 后端 + PostHog 访问）
  - 跑通一次完整支付，PostHog 中确认三格漏斗可拼（AC9）
  - 跑一次被拒、一次超时、一次「只关面板」，确认三个事件各自出现且**只出现一次**
  - 确认与 1-2 的服务端 `shop_payment_*` 落在同一 project、`distinct_id` 对得上
  - 出包走 `scripts/build-stag-apk.sh`（自动注入 `-stag` 版本后缀，埋点按 App Version 区分 stag 与生产）
- Completion Notes 必须写「**L1/L2 待本地验收**」+ AC7 的 N/A 说明 + 新增事件名全集。

## Definition of Done

- [ ] AC1~AC9 全部满足，epics 点名的「关闭面板」与「点击重试」两个事件各有专门用例
- [ ] 「首次支付不发 retry」这条反例用例存在且绿
- [ ] 属性只含 4 个允许键，有逐事件逐键的 PII 断言，**不含订单号**
- [ ] 既有 4 个支付事件的名称与属性零改动
- [ ] 新事件未加入 `appsflyerEvents`；`analytics.dart` 与 `button_ids.dart` 零改动（`_allowedButtonIds` 仍 8 条，有断言）
- [ ] `qr_payment_sheet.dart` 与另外 5 个支付页面零 diff（有 `git diff --stat` 证据）
- [ ] 全部上报走 `Analytics.capture`，无旁路直调 PostHog/AppsFlyer
- [ ] `flutter analyze` + `flutter test` 全绿，既有测试无需修改
- [ ] Completion Notes 已写「L1/L2 待本地验收」+ 白名单腿 N/A 理由 + 事件名全集

---

## Completion Notes（2026-09-16 · 云端 headless 执行）

### 🔴 L1/L2 待本地验收

**AC9 全部是 L2**，云端做不了（需模拟器 + staging 后端 + PostHog 访问）：

- 跑通一次完整支付，PostHog 中确认三格漏斗可拼：
  `toko_order_pay_tapped` → `toko_payment_qr_shown` → `toko_order_payment_succeeded`
- 跑一次被拒、一次超时、一次「只关面板」，确认三个事件各自出现且**只出现一次**
- 确认与 1-2 的服务端 `shop_payment_*` 落在**同一个 PostHog project**、`distinct_id` 对得上
  （App 走 `Analytics.distinctIdFor`，服务端走 `AnalyticsDistinctId.of`，同为 `sha256('tailtopia-user-$id')`；本 story 未碰这两处）
- 出包必须走 `scripts/build-stag-apk.sh`（自动注入 `-stag` 版本后缀，埋点按 App Version 区分 stag 与生产）

本 story **无独立 L1**（纯客户端上报，无后端契约变更）。

### 📋 事件名全集（交付给运营配看板）

**本 story 新增 6 个**

| 事件名 | 漏斗格 | 属性 |
|---|---|---|
| `toko_payment_qr_shown` | 二维码展示成功 | `pay_channel` / `attribution_source` / `has_pawcoin` |
| `toko_payment_sheet_dismissed` | 关闭面板（未完成） | 同上 |
| `toko_payment_declined_shown` | 收到被拒反馈 | 同上 + `failure_category=GATEWAY_DECLINED` |
| `toko_payment_expired_shown` | 收到超时反馈 | 同上 + `failure_category=EXPIRED` |
| `toko_payment_retry_tapped` | 点击重试 | 同上 |
| `toko_order_cancel_succeeded` | 取消成功 | 无 |

**复用既有 4 个**（名称与属性一个字未改）：`toko_order_pay_tapped` ·
`toko_order_payment_succeeded` · `toko_order_payment_failed_shown` · `toko_order_cancel_tapped`

**与 1-2 服务端事件的对应**：`qr_shown`↔`shop_payment_intent_created` ·
`payment_succeeded`↔`shop_payment_paid` · `declined_shown`↔`shop_payment_declined` ·
`expired_shown`↔`shop_payment_expired`。
`toko_payment_sheet_dismissed` **无服务端对应** —— 关面板不产生任何服务端状态变化，这正是它的价值。

### 已完成（L0 绿：`flutter analyze` 零 issue；`flutter test` **1543 例全绿**）

- AC1/AC2/AC3/AC4：6 个新事件；属性由私有 `_payProps(order, {failureCategory})` 一处收口，
  **只有四个键**，绝无 `orderToken` / `receiverName` / `receiverPhone` / `addressText`。
- AC5：新建 `test/shop/shop_payment_analytics_test.dart`，**12 条用例**，含 epics 点名的
  「关闭面板」「点击重试」两条，以及「首次支付不发 retry」与「只关面板之后再点支付仍不算重试」两条反例。
  PII 那条**逐事件逐键**遍历本次捕获的全部事件，并额外断言订单 token / 姓名 / 电话
  **不作为值**混进任何属性。
- AC6/AC7 零改动（已用 `git diff --numstat` 逐个核实，**8 个文件全为 0 行**）：
  `qr_payment_sheet.dart` · `unlock_method_sheet.dart` · `id_card_create_page.dart` ·
  `id_card_detail_page.dart` · `vet_timed_pay_page.dart` · `recharge_page.dart` ·
  `core/analytics/analytics.dart` · `core/analytics/button_ids.dart`。
- AC2 的判据是**上次失败类别是否为被拒**（`_lastPaymentDeclined`），不是计数器 ——
  计数器分不出「被拒后重试」和「关了面板待会再付」，有专门反例用例钉住这点。

### ⚠️ 与 AC8「既有 test/analytics/* 不需修改」的一处偏离

`test/analytics/v112_events_test.dart` 的事件命名护栏要求动作词落在词尾且取自白名单，
`_dismissed` **不在**白名单里 ⇒ `toko_payment_sheet_dismissed`（AC1 指定的名字）会让它变红。

**处置：给白名单加 `_dismissed` 并写明理由**，未改事件名。依据是该护栏自己的既定做法 ——
文件里 `_responded` / `_reported` / `_sent` / `_generated` / `_opened` / `_rewarded` / `_blocked`
每一条都是这样加进去的，且原文写着「🔴 刻意**没有**提前把它加进来 —— 白名单里放尚未用到的条目，
就失去了『改动时被迫想一次』的作用。**本 story 用到了才加**」。这正是「被迫想一次」的那一次。

加的注释说明了为什么不用别的词：不用 `_closed`（关闭是中性的，而这条事件的意义在于「他放弃了」）、
不用 `_tapped`（会被当成取消按钮的全部点击数拿去做分母，而这条只统计「未完成就关」）。

### 与 epics 的偏离：AC7 按钮白名单腿 **N/A**

epics 1-4 AC2 写的「按钮类事件的 id 进 `_allowedButtonIds` 与 `button_ids.dart`」在电商侧不适用：
`Analytics.buttonTapped(id)` 发出的事件名**固定是 `button_tapped`**，`button_id` 只是一个属性 ——
走那条通路，重试与取消就**拼不进 `toko_*` 漏斗**，而漏斗正是 SHOP-FR-02 的验收物。
电商既有 10 个事件全部走 `Analytics.capture('toko_*')`，本 story 保持一致。
`button_ids.dart` 与 `_allowedButtonIds` **零改动**，并有一条用例断言白名单仍是那 8 条。

### 一处事件改名（承 1-3）

1-3 里临时用的 `toko_order_payment_declined_shown` 已按本 story AC1 的规范名改为
**`toko_payment_declined_shown`**。1-3 尚未发版，无线上历史序列，改名无成本。

### 代码复审（bmad-code-review）结论

**已修 1 条 CONFIRMED（本 story 自己的真 bug）：**

`aborted == null` 被我当成了「用户自己关掉面板」，但它其实有**两种成因**：
① 根本没中止；② **中止了但没带类别** —— 也就是 1-3 刻意保留的老后端兜底分支
（`throw const QrPaymentAborted()`，1-1 未上线时的形态）。
⇒ **灰度期每一单被服务端取消的订单都会被记成 `toko_payment_sheet_dismissed`＝「用户自己走掉了」**，
把「出码后放弃率」这个指标整个做废。
已拆成 `abortSignalled`（有没有抛过中止）+ `aborted`（类别）两个变量。第二种成因下
**UI 与改动前完全一致（静默关闭），埋点也保持改动前的样子：什么都不发** ——
发 `sheet_dismissed` 是谎，发 `declined`/`expired` 是猜；灰度期这一格由服务端 1-2 的
`shop_payment_*` 兜着（它不看 App 版本）。已补专门用例钉住。

**未改，第三次留痕（同一条，前两轮已记在 1-1 / 1-3）：**

`gateway_meta` 的 `reason` 键同时承载我方 `failByToken` 的哨兵值与网关回调原文。
本轮复审把后果说得更具体：网关若返回 `reason=CANCELLED`，会被判成 `USER_CANCELLED`，
而 App 对这一态的处置是**完全静默**，订单却仍可支付 —— 用户看到二维码消失、没有任何解释。
AC1 的映射表明确要求 `CANCELLED` → `USER_CANCELLED`，**未自行改动**。
建议后续 story 把我方哨兵换成独立键（如 `petgoReason`）与网关原文隔离。

### 一处无关变更已剔除

`petgo_app/analysis_options.yaml` 的 `build/**`、`android/**` 等 exclude 是 **Flutter 工具自己写进去的**
（每次跑 `flutter analyze` 都会「Upgrading analysis_options.yaml...」），不是手改，已在提交前 checkout 还原。

### 云端环境说明

云端 headless，无 GUI ⇒ L2 全部留本地。本容器无 git remote，推送未执行。

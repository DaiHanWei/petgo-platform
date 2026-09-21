---
baseline_commit: d1e8f5d3
branch: feat/1.3.0-shop-v2
theme: shop-v2
epic: 3
story: 3-3
ad: [AD-S7, AD-S8]
decisions: [SD-10]
fr: [SHOP-FR-25, SHOP-FR-26, SHOP-NFR-05]
---

# Story 3-3: App 工单选订单与 WhatsApp 深链（AD-S7 / AD-S8）

Status: review

> 自包含 story，可本地或云端 L0 执行。与用户沟通用中文。执行纪律见根 `CLAUDE.md`。
> **前置**：Story 3-1（客服号配置与 `GET /api/v1/support/contact`）、Story 3-2（`related_order_type` 与后端按类型解析）。两条都合入后再开工，否则深链没号可读、选中的电商单后端会静默丢弃。
> 主体是 App，另有**一处小后端契约变更**（工单详情下发关联电商单的展示号，见 AC5 与 Dev Notes 的冲突解释）。

## Story

As a 用户，
I want 提工单时能直接选中出问题的那一单，或者一键在 WhatsApp 上找客服并且订单号已经填好，
so that 我不用把一长串订单号手打一遍，客服也不用再问我「哪一单」。

## Context

### 🔴 用户侧建单路径今日恒为 NULL —— 本 story 补的就是这一半

后端**早就准备好了**接字段：

- `support/dto/CreateTicketRequest.java:21` 有 `String relatedOrderToken`；
- `SupportTicketService.createTicket(...)` `:80-110` 在 `:92` 调 `resolveRelatedOrder(userId, relatedOrderToken)`；
- App 的 repository **也有这个可选参**：`petgo_app/lib/features/support/data/support_repository.dart:17-26` 签名里 `String? relatedOrderToken`（`:23`），请求体里 `:35-37` 条件加入。

断点只有一处：**`ticket_compose_page.dart` 从不传它**。

```dart
// petgo_app/lib/features/support/presentation/ticket_compose_page.dart:115-123
final ticket = await ref.read(supportRepositoryProvider).createTicket(
      subject: _subject.text, body: _body.text,
      contactType: _contactType, contactValue: _contactValue.text,
      needContact: _needContact, labels: _labels.toList(),
      attachmentObjectKeys: _photos.map((p) => p.objectKey).toList(),
    );   // ← 没有 relatedOrderToken
```

`grep relatedOrderToken` 在该文件零命中。所以**「用户建单关联订单」这条能力在线上从未被触发过一次**，`feedback_tickets.related_order_id` 的所有非空值都来自后台补挂（架构 F-12）。3-2 把后端从「只认问诊单」扩到「两类订单」，本 story 把 App 这半截接上。

### 深链在代码里还不存在

App 内**没有任何 `wa.me` / `whatsapp://`**。`url_launcher: ^6.3.1`（`pubspec.yaml:57`）的 5 个调用点：

| 文件 | 行 | 用途 |
|---|---|---|
| `features/vet/presentation/vet_me_page.dart` | 73 | — |
| `features/shop/presentation/shop_order_detail_page_v2.dart` | 726（`_openCarrierSite` :721-728） | 打开承运商官网 |
| `features/content/presentation/promo_target.dart` | 28 | — |
| `features/me/presentation/settings_page.dart` | 336 | — |
| `shared/widgets/agreement_links.dart` | 18 | — |

**五处全用 `LaunchMode.externalApplication`，五处都不调 `canLaunchUrl`**。这不是疏忽而是必须：`android/app/src/main/AndroidManifest.xml` 的 `<queries>`（`:74-79`）**只声明了 `PROCESS_TEXT`**，没有 `ACTION_VIEW` + `https` 的条目 —— Android 11+（API 30）包可见性规则下 `canLaunchUrl` 对 `https://wa.me/...` 会返回 `false`，即便浏览器明明装着。照抄 `_openCarrierSite` 的写法（直接 `launchUrl`，看返回值与异常）。

### 订单数据在哪

**订单中心（`features/order`）是唯一同时装两类订单的数据源**，正是「关联订单」选择器需要的：

- `OrderRepository.fetchOrders({OrderType? type, String? cursor, int limit = 20})` `data/order_repository.dart:25`
- `enum OrderType { vetConsult, aiUnlock, pawcoinTopup, idHd, ecommerce, unknown }` `domain/order_summary.dart:8-36`
- `OrderSummary`：`orderType:84` · `orderToken:85` · `displayNo:87` · `amount:94` · `createdAt:96` · `itemTitle:103` · `itemCount:106`
- 列表控制器 `orderListProvider`（`presentation/order_list_controller.dart:98-99`，`AsyncNotifierProvider`，带 `setFilter` / `refresh` / `loadMore`）

电商订单详情另有一套：`shopOrderDetailProvider`（`features/shop/data/shop_order_repository.dart:56-58`），DTO `ShopOrderDetail`（`domain/shop_order_detail.dart:135`）只有 `orderToken:160`，**没有 `displayNo`**。

### 订单详情页今天展示的「订单号」是什么

`features/shop/presentation/shop_order_detail_page_v2.dart:579-580`：

```dart
// 🔴 订单号用等宽 —— 用户要报给客服、要逐位核对。
Text(order.orderToken, style: ShopText.serialNo),
```

即**今天页面把 22 位随机 token 当订单号展示给用户**，而订单中心列表展示的是 `displayNo`（`TOKO-yyyyMMdd-NNNNNN`）。两处口径不一致正是 SHOP-FR-29「订单号统一」要修的缺陷 —— **那是另一条 story，本 story 不修它**（见 Dev Notes「深链预填用哪个号」）。

## Acceptance Criteria

**AC1 · 建单页「关联订单」选择器**
**Given** `ticket_compose_page.dart` 从不传 `relatedOrderToken`
**When** 用户在建单页点「关联订单」
**Then** 弹出选择器，列出**本人最近订单**（问诊 + 电商混排，取订单中心口径，首屏 20 条），每行显示订单号 / 类型 / 金额 / 时间，可搜索可不选 `[L0]`
**And** 选中后 `_submit()`（`:110-135`）的 `createTicket(...)` 调用传入 `relatedOrderToken: <选中订单的 orderToken>` `[L0]`
**And** 不选时**不传该字段**（repository `:35-37` 的条件加入保证请求体里干脆没有这个 key，与今天完全一致） `[L0]`
**And** 🔴 选择器**不得复用 `orderListProvider`** —— 它是订单中心页面的共享状态，`setFilter` 会把用户在订单中心的筛选条件改掉。新建一个 `autoDispose` 的 `FutureProvider` 直接调 `orderRepositoryProvider.fetchOrders(...)` `[L0]`

**AC2 · 从订单详情进建单自动预选**
**Given** 路由 `/me/support-tickets/new`（`core/router/app_router.dart:744`，当前 `builder: (c, s) => const TicketComposePage()`，无参数）
**When** 从订单详情页进入建单
**Then** 路由接受 `?orderToken=<token>`，`TicketComposePage` 接可空构造参并在 `initState` 预选该订单 `[L0]`
**And** 预选的订单**可被用户改掉或清空** `[L0]`
**And** 直接从「我」页进入（无参数）时行为与今天一致 `[L0]`

**AC3 · 订单详情页的 WhatsApp 入口**
**Given** 电商订单详情页 `shop_order_detail_page_v2.dart` 已有售后告知块 `_helpBlock(...)` `:604-614`（`key: ValueKey('shopOrderHelpBlockV2')`，只有文案没有动作）
**When** 用户点「在 WhatsApp 联系我们」
**Then** 以 `LaunchMode.externalApplication` 打开 `https://wa.me/<E.164 去掉+>?text=<urlencoded>`，号码来自 Story 3-1 的 `supportContactProvider`（失败回退本地常量） `[L0]`
**And** 该入口**在订单详情页始终显示**（不分订单状态） `[L0]`
**And** 🔴 入口放在 `_helpBlock` 内，**不塞进 `_bottomBar`**（`:618-684`）—— `_bottomBar` 会对无动作的状态返回 `null`（`:683`），把它改成「有时返回一个只含客服按钮的 bar」会波及全部 8 个订单状态的底栏布局 `[L0]`

**AC4 · 工单页的 WhatsApp 入口（条件显示）**
**Given** 工单详情页 `features/support/presentation/ticket_detail_page.dart`（`_detail(...)` `:41-99` 是一个 `ListView`，无 `bottomNavigationBar`，最后一项是 `_csatSection` `:96`）
**When** 该工单**关联了电商订单**
**Then** 在 `_csatSection` 之后追加同一个 WhatsApp 入口，预填该电商订单号 `[L0]`
**And** 工单**未关联电商订单**（含关联问诊单、含未关联）时**不显示** `[L0]`

**AC5 · 🔴 工单详情下发关联电商单的展示号（后端小变更）**
**Given** Story 3-2 的契约测试把 `relatedOrderId` 与 `relatedOrderType` 都列为**禁止下发用户**的字段，App 因此无从判断 AC4 的显示条件
**When** 解开这个矛盾
**Then** `SupportTicketView` 新增**可空** `relatedShopOrderNo`（String?）：仅当工单关联的是**本人的电商订单**时非空，值 = 该订单的展示号 `[L0]`
**And** 它是**用户自己的订单号**，不是内部自增 id、不是类型枚举 —— 契约测试的禁字段列表**保持 `relatedOrderId` + `relatedOrderType` 不变**，正向断言里加入新字段 `[L0]`
**And** 关联问诊单时该字段为 `null`（问诊工单的客服路径不变） `[L0]`
**And** 契约四处同改（C5）：后端 record + App `SupportTicket` DTO（`petgo_app/lib/features/support/domain/support_ticket.dart:94-108` 字段区 + `fromJson` `:113`）+ 契约 test；App mock 层本仓已移除（实施时确认） `[L0]`

**AC6 · 预填只含订单号**
**Given** SHOP-NFR-05：深链预填不得含个人信息
**When** 构造 `text=` 参数
**Then** 预填串**只含订单号 + 一句固定引导语**，**不含**收件人姓名 / 电话 / 详细地址 / 邮箱 / 用户 id `[L0]`
**And** 单测断言：给一个装满收件人信息的 `ShopOrderDetail`，`buildSupportWhatsAppText(...)` 的输出**不包含** `shipReceiverName` / `shipPhone` / `shipAddressLine` / `kodePos` 任一字段的值 `[L0]`
**And** 🔴 **变异验证**：把构造函数改成拼上收件人姓名，该测试必须变红 `[L0]`
**And** 预填**可编辑**（`wa.me` 的 `text` 参数天然进输入框而非直接发出） `[L2]`

**AC7 · 打开失败的处置**
**Given** 未安装 WhatsApp 的设备
**When** 点击入口
**Then** 系统打开浏览器落地页（`wa.me` 的正常降级），**不是白屏** —— 因此**不做「未安装」的预判**，不调 `canLaunchUrl`（Android 11+ 的 `<queries>` 缺声明会让它假阴性，见 Context） `[L2]`
**And** `launchUrl` 返回 `false` 或抛异常时，才提示「打不开 WhatsApp」并提供**「复制号码」**动作（复制的是展示形态 `081290906953`，不是 E.164） `[L0]`
**And** 🔴 iOS 分享/跳转类动作历史上吞过错（memory：ios-share-needs-position-origin）—— 本处 `await` 返回值 + `try/catch` 都要有，不能写成裸调用 `[L0]`

**AC8 · 埋点**
**Given** §5 指标「WhatsApp 深链点击数（按周）」
**When** 用户点入口
**Then** 走既有白名单机制：`core/analytics/button_ids.dart:6-13` 加常量（如 `support.whatsapp`），`core/analytics/analytics.dart:218-222` 的 `_allowedButtonIds` 加同一个值，调用 `Analytics.buttonTapped(...)`（`:231-238`） `[L0]`
**And** 🔴 **两处都要改**：只加常量不加白名单，事件会被 `isRegisteredButtonId`（`:225`）在 release 下**静默丢弃**，埋点看起来上了实际零数据 `[L0]`
**And** 事件属性**不带订单号、不带号码**（`button_id` + `screen` 即可，PII 红线） `[L0]`
**And** `petgo_app/test/analytics/` 下的事件清单测试同步更新 `[L0]`

**AC9 · 文案**
**Given** App 的 ARB
**Then** 新增文案进 `petgo_app/lib/l10n/app_en.arb`（模板）与 `app_id.arb` 两包，跑 `flutter gen-l10n` `[L0]`
**And** ⚠️ **本仓 App 只有 en / id 两个 locale**（`l10n.yaml` + `app_localizations.dart:96-99` 的 `supportedLocales`），epics 里写的「ARB 三语」与代码不符；后端 `messages_*` 才是四包。实施时按**两包**做，并在 Completion Notes 记明 `[L0]`
**And** 印尼语措辞参考既有 `csWhatsappLabel` / `csWhatsappNote`（`app_id.arb:885-886`）与 `ticketContactWhatsapp`（`app_id.arb:1287`） `[L0]`
**And** `test/l10n/microcopy_rules_test.dart` 仍绿 `[L0]`

**AC10 · 真机验收**
**Then** 装了 WhatsApp 的设备：点击 → 打开 WhatsApp 对话框 → 输入框里是预填文案（含订单号）→ 可编辑 → 能发出 `[L2]`
**And** 未装 WhatsApp 的设备：落到 `wa.me` 浏览器页，不白屏 `[L2]`
**And** 建单页选中一笔电商订单提交后，**后台工单详情能看到那一单**（与 3-2 联调） `[L2]`

**AC11 · 回归**
**Then** `flutter analyze` 零 issue；`flutter test` 全绿；`mvn -B clean package` 通过（AC5 的后端小变更） `[L0]`

---

## Tasks / Subtasks

- [ ] **T1 · 后端：工单详情补展示号**（AC5）
  - [ ] `support/dto/SupportTicketView.java` 加 `String relatedShopOrderNo`（可空）
  - [ ] `SupportTicketService.toView(...)`（`:224`）按 `relatedOrderType == SHOP` 取展示号；`CONSULT` / 未关联 → `null`
  - [ ] 展示号取法与 3-2 的后台一致：先行用 `OrderDisplayNo.of(OrderDisplayNo.ECOMMERCE, id, createdAt)`（`order/dto/OrderDisplayNo.java:34`），Story 4-3 落地后切 `shop_orders.display_no`（留 TODO）
  - [ ] `SupportTicketViewContractTest`：禁字段列表**不动**（仍含 `relatedOrderId`、`relatedOrderType`），正向断言加 `relatedShopOrderNo`
  - [ ] 🔴 只在关联单属于请求者时下发 —— 按构造它必然属于本人（3-2 的解析与补挂都做归属校验），但 `toView` 里仍要防御性带上 userId 比对，别依赖上游

- [ ] **T2 · App：共享的深链构造件**（AC3、AC6、AC7）
  - [ ] 新建 `petgo_app/lib/features/support/domain/support_whatsapp.dart`（或 `shared/support/`）：
    - `String buildSupportWhatsAppText({required String orderNo, required AppLocalizations l10n})` —— **纯函数，只拼订单号 + 固定引导语**
    - `Uri buildSupportWhatsAppUri({required String whatsappE164, required String text})` —— `https://wa.me/${e164.replaceFirst('+', '')}?text=${Uri.encodeComponent(text)}`
  - [ ] 新建共享 widget `SupportWhatsAppButton`（订单详情页与工单页共用，避免两份实现漂移）
  - [ ] 打开逻辑照 `_openCarrierSite`（`shop_order_detail_page_v2.dart:721-728`）：`Uri.tryParse` → `await launchUrl(uri, mode: LaunchMode.externalApplication)` → `false` 或 catch → toast + 「复制号码」（`Clipboard.setData` 写 `whatsappNumber` 展示形态）
  - [ ] 🔴 **不调 `canLaunchUrl`**；也**不改 `AndroidManifest.xml` 的 `<queries>`**（改了会影响全 App 的包可见性行为，收益为零）
  - [ ] 号码来源：Story 3-1 的 `supportContactProvider`（永不进 error 态，失败即兜底常量）

- [ ] **T3 · App：订单详情页入口**（AC3）
  - [ ] `shop_order_detail_page_v2.dart` 的 `_helpBlock(...)` `:604-614` 内追加 `SupportWhatsAppButton`
  - [ ] 订单号取**该页面当前展示给用户的那个号**，即 `:580` 的 `order.orderToken`（见 Dev Notes「深链预填用哪个号」）
  - [ ] 🔴 不动 `_bottomBar` `:618-684`、不动 `_returnBar` `:690`（退货入口是 SHOP-FR-05 隐藏的对象，别顺手碰）

- [ ] **T4 · App：工单页入口**（AC4）
  - [ ] `ticket_detail_page.dart` `_detail(...)` `:41-99` 在 `_csatSection`（`:96`）之后追加 `if (t.relatedShopOrderNo != null) SupportWhatsAppButton(orderNo: t.relatedShopOrderNo!)`
  - [ ] `features/support/domain/support_ticket.dart`：字段区（`:94-108`）加 `final String? relatedShopOrderNo;`，`fromJson`（`:113`）解析
  - [ ] 不加 `bottomNavigationBar`（会改变整页滚动行为）

- [ ] **T5 · App：建单页订单选择器**（AC1、AC2）
  - [ ] `ticket_compose_page.dart`：`TicketComposePage({super.key, this.presetOrderToken})`；`_TicketComposePageState` 加 `OrderSummary? _relatedOrder`
  - [ ] 新 `FutureProvider.autoDispose` 拉最近订单：`ref.read(orderRepositoryProvider).fetchOrders(limit: 20)`（`features/order/data/order_repository.dart:25`），🔴 **不用 `orderListProvider`**（AC1）
  - [ ] 选择器 UI：底部弹窗，行内显示 `displayNo` / `orderType` / `amount` / `createdAt`（字段位置见 `order_summary.dart:84-106`）；带「不关联」项
  - [ ] `_submit()` `:115-123` 的调用补 `relatedOrderToken: _relatedOrder?.orderToken`
  - [ ] `app_router.dart:744`：`builder: (c, s) => TicketComposePage(presetOrderToken: s.uri.queryParameters['orderToken'])`
  - [ ] 订单详情页的「联系客服 / 提工单」入口带上 `?orderToken=`（若该页今天没有提工单入口，则本条只保留路由能力，实施时确认）
  - [ ] 🔴 后端对**不属本人的 token 是静默 null**（`SupportTicketService:218-221`）：选择器只列本人订单所以不会触发，但**不要**据此在 App 端做「提交后回读确认」之类的补偿逻辑，宽松口径是既定决策（OPEN-1）

- [ ] **T6 · 埋点**（AC8）
  - [ ] `core/analytics/button_ids.dart:6-13` 加 `static const supportWhatsapp = 'support.whatsapp';`
  - [ ] `core/analytics/analytics.dart:218-222` 的 `_allowedButtonIds` 加 `ButtonId.supportWhatsapp`
  - [ ] `SupportWhatsAppButton` 的 `onTap` 首行 `Analytics.buttonTapped(ButtonId.supportWhatsapp, screen: <页面名>)`
  - [ ] 更新 `petgo_app/test/analytics/` 的事件清单测试

- [ ] **T7 · 文案**（AC9）
  - [ ] `app_en.arb` / `app_id.arb` 加：按钮标题、预填引导语、打开失败提示、复制号码、选择器标题、「不关联」
  - [ ] `flutter gen-l10n`
  - [ ] 🔴 预填引导语里**不要放变量插值以外的任何用户数据**，模板形如 `"Halo, saya butuh bantuan untuk pesanan {orderNo}"`

- [ ] **T8 · 测试**（AC6、AC11）
  - [ ] L0 `petgo_app/test/support/support_whatsapp_test.dart`（纯函数，参考 `test/notify/deep_link_routes_test.dart` 的无 `pumpWidget` 写法）：
    - URL 形态：`https://wa.me/6281290906953?text=...`（**无 `+`**）
    - 预填只含订单号：喂满收件人信息的 detail，断言输出不含那些值（AC6）
    - 变异验证：拼上姓名后测试红
    - 特殊字符（订单号里的 `-`、引导语里的空格）编码正确
  - [ ] L0 后端 `SupportTicketViewContractTest` 更新
  - [ ] `flutter analyze` / `flutter test` / `mvn -B clean package`

- [ ] **T9 · 云端执行须知**
  - [ ] 云端只跑 L0（`flutter analyze`、`flutter test`、`mvn -B clean package`）
  - [ ] L2（真机 WhatsApp 跳转、未装设备降级、与后台联调）留本地，Completion Notes 标注

---

## Dev Notes

### 🔴 AC4 与 3-2 的契约冲突，以及为什么这样解

epics 里 3-3 的 AC4 要求「工单页**仅当关联了电商订单时**显示按钮」，而 3-2 的 AC5 要求 `related_order_id` 与 `related_order_type` **都不下发给用户**。照字面同时满足是不可能的 —— App 拿不到任何判据。

解法不是放宽 3-2，而是**换一个字段**：下发 `relatedShopOrderNo`（用户自己的订单号）。

- 它**不是内部标识**：`relatedOrderId` 是自增主键（可枚举、跨表撞号），`relatedOrderType` 是内部枚举；而订单号是用户在自己订单列表里天天看见的东西。
- 它**同时解决两个问题**：非空即「关联了电商单」（显示条件），值即预填内容（省一次请求）。
- 它**不扩大暴露面**：只在关联单属于本人时下发，本人看自己的订单号。

3-2 的禁字段列表因此一个字都不用改。这条解释要写进 PR 描述，否则 review 时会被当成「绕过了 3-2 的契约约束」。

### 🔴 深链预填用哪个号

今天 App 里同一笔电商订单有两个「号」：

| 位置 | 展示值 | 来源 |
|---|---|---|
| 订单中心列表 | `displayNo` = `TOKO-yyyyMMdd-NNNNNN` | `OrderSummary.displayNo:87` |
| 电商订单详情页 | `orderToken`（22 位随机串） | `shop_order_detail_page_v2.dart:579-580` |

这个不一致本身是 SHOP-FR-29「订单号统一」的修复对象，**不是本 story 的事**。本 story 的规则是一句话：

> **深链预填 = 该页面此刻展示给用户的那个订单号。**

理由：用户拿去跟客服核对的，就是他屏幕上看得见的那串字符；如果深链填的是另一个号，客服拿到的号在用户那儿找不到，反而制造新的沟通成本。附带好处是 SHOP-FR-29 把 `:580` 改成 `displayNo` 那天，深链**自动跟随，一行码都不用改** —— 因为取的是同一个字段。

工单页那侧则用 AC5 下发的 `relatedShopOrderNo`（人可读的 `TOKO-...`），因为工单页本来就没有 token 可展示。

### 🔴 为什么不预判「有没有装 WhatsApp」

两条：

1. **`wa.me` 本来就是浏览器落地页**。未装 WhatsApp 时系统打开网页版引导页，用户照样能看到号码、照样能点「Continue to Chat」。这是可接受的降级，不是故障 —— 所以没有必要提前分流。
2. **`canLaunchUrl` 在本仓会假阴性**。`AndroidManifest.xml` 的 `<queries>`（`:74-79`）只有 `PROCESS_TEXT`，Android 11+ 的包可见性限制会让它对 `https` 意图返回 `false`。既有 5 个 `launchUrl` 调用点全部不调它 —— 这是本仓已经踩过并绕开的坑，照做即可。

失败处置的顺序因此是：**先尝试打开 → 失败了才提示**，而不是「先检测 → 再决定显不显示按钮」。

### 🔴 `_bottomBar` 是雷区，`_helpBlock` 是正确落点

`shop_order_detail_page_v2.dart:618-684` 的 `_bottomBar` 针对 8 个订单状态返回不同组合，无动作状态直接 `return null`（`:683`），而 `Scaffold.bottomNavigationBar` 绑在 `:104-105`。要让「客服入口始终显示」，就得把 `null` 分支改成「返回一个只有客服按钮的 bar」—— 于是 8 个状态的底栏高度、按钮排布全部要重新验收，还会和 SHOP-FR-05「隐藏退货入口」的改动撞在同一块代码。

`_helpBlock`（`:604-614`，`key: ValueKey('shopOrderHelpBlockV2')`）本来就是「有问题？」的售后告知块，只有文案没有动作 —— 把 CTA 放进它，改动面是一个 widget，且语义天然吻合。

### 🔴 埋点白名单是两处，不是一处

`Analytics.buttonTapped`（`analytics.dart:231-238`）内部先过 `isRegisteredButtonId`（`:225`）查 `_allowedButtonIds`（`:218-222`）。debug 下会 assert，**release 下静默丢弃**。只在 `button_ids.dart` 加常量而忘了白名单，表现是「开发机上有数据、线上永远零」——发版后才发现，且要等下一个版本才能修。

事件属性只放 `button_id` + `screen`：订单号虽然不是严格 PII，但埋点侧的既定红线是「不带业务标识」，而且 §5 的指标只需要点击计数。

### ARB 是两包不是三包

`petgo_app/l10n.yaml` + `lib/l10n/app_localizations.dart:96-99` 的 `supportedLocales` = `[Locale('en'), Locale('id')]`，目录下只有 `app_en.arb`（模板）与 `app_id.arb`。epics / PRD 写的「ARB 三语」是笔误（后端 `i18n/messages_*` 才是四包：默认 / en / id / zh_CN）。按两包做，别去凭空建 `app_zh.arb`。

### 现状代码要点（读过的文件）

| 文件 | 现状 | 本 story 改什么 | 必须保留 |
|---|---|---|---|
| `features/support/presentation/ticket_compose_page.dart:110-135` | `_submit()` 不传 `relatedOrderToken` | + 选择器 + 传参 + 预选构造参 | 提交后 `invalidate(myTicketsProvider)` `:127` 与 `pushReplacement` `:129` |
| `features/support/data/support_repository.dart:17-26,35-37` | 可选参已存在、条件加入已存在 | **不改** | 条件加入逻辑 |
| `features/support/presentation/ticket_detail_page.dart:41-99` | `ListView`，末项 `_csatSection` `:96` | + 条件渲染的 WhatsApp 入口 | 无 bottomNavigationBar 的结构 |
| `features/support/domain/support_ticket.dart:94-108,113` | 14 个字段 + `fromJson` | + `relatedShopOrderNo` | 既有字段 |
| `features/shop/presentation/shop_order_detail_page_v2.dart:579-580,604-614,721-728` | 订单号展示 / 售后告知块 / launchUrl 范例 | `_helpBlock` 内加 CTA | `_bottomBar`、`_returnBar`、承运商跳转 |
| `features/order/data/order_repository.dart:25,45` | `fetchOrders` + `orderRepositoryProvider` | 只调用 | — |
| `features/order/presentation/order_list_controller.dart:98-99` | `orderListProvider`（共享状态） | **不复用** | — |
| `features/order/domain/order_summary.dart:8-36,84-106` | `OrderType` 含 vetConsult 与 ecommerce；字段齐 | 只读 | — |
| `core/router/app_router.dart:744` | `/me/support-tickets/new` 无参 | + `?orderToken=` | 路径不变 |
| `core/analytics/button_ids.dart:6-13` + `analytics.dart:218-222,225,231-238` | 8 个 ButtonId + 白名单 | 各加一个 | 机制本身 |
| `android/app/src/main/AndroidManifest.xml:74-79` | `<queries>` 只有 PROCESS_TEXT | **不改** | — |
| `lib/l10n/app_en.arb` / `app_id.arb` | 两包 | + 新 key | 既有 key |

### 测试标准

- App L0 优先写**纯函数测试**（无 `pumpWidget`），参考 `test/order/order_summary_model_test.dart`、`test/notify/deep_link_routes_test.dart`。
- AC6 的「不含收件人信息」必须是**断言值不出现**，不是断言「函数里没引用那些字段」—— 前者才挡得住将来有人在引导语里加一句「送到 {address}」。
- L2 走 Android 模拟器 / 真机（memory：用户说「模拟器」一律指 Android）。未装 WhatsApp 的降级用干净模拟器验。

### Project Structure Notes

- App 改动集中在 `features/support/`、`features/shop/presentation/` 一处、`core/router`、`core/analytics`、`l10n`。
- 后端只动 `support/dto` + `SupportTicketService.toView` + 一个契约测试。
- 不动 `features/order/` 的任何既有 provider 与控制器。

### References

- [Source: _bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-shop-v2-delta.md#AD-S7（F-12 用户侧恒 NULL）/#AD-S8（深链形态与打开方式）]
- [Source: _bmad-output/planning-artifacts/v1.3.0/PRD-v1.3.0-shop-v2.md#SHOP-FR-26 / #SHOP-NFR-05 / §5 客服可达指标]
- [Source: _bmad-output/planning-artifacts/v1.3.0/决策日志-shop-v2.md SD-10]
- [Source: _bmad-output/planning-artifacts/v1.3.0/epics-v1.3.0-shop-v2.md#Story 3-3]
- [Source: _bmad-output/planning-artifacts/v1.3.0/validation-2026-09-15-shop-v2/arch-input-extract.md#D.7 / #D.8]
- [Source: petgo_app/lib/features/support/presentation/ticket_compose_page.dart]

## 验收与交付

| 层级 | 谁跑 | 内容 |
|---|---|---|
| **L0** | 云端 / 本地 | `flutter analyze`、`flutter test`（含预填不含 PII 的变异验证）、`mvn -B clean package`、ARB 两包 key 齐 |
| **L1** | 本地 | 建单带 `relatedOrderToken` → `feedback_tickets.related_order_id/type` 落 SHOP；工单详情返回 `relatedShopOrderNo` |
| **L2** | 本地 Android 设备 | 装 WhatsApp：跳转 + 预填 + 可编辑 + 能发出；未装：落浏览器页不白屏；打不开：提示 + 复制号码；建单选订单后后台能看到那一单 |

**交付边界**：不修 SHOP-FR-29 的订单号口径不一致（那是另一条 story）；不改退货入口（SHOP-FR-05）；不建后台任何页面。
**部署提示**：AC5 是契约变更（工单详情新增可空字段），**老版本 App 忽略未知字段、行为不变**，但 App 端功能要等后端上线才可用 ⇒ **前后端同批部署**。

## Definition of Done

- [ ] AC1~AC11 全部满足
- [ ] `grep -rn 'relatedOrderToken' petgo_app/lib/features/support/presentation/` 有命中（这条 story 的核心就是让它从零变一）
- [ ] 深链预填的单测含**变异验证**（拼上收件人姓名即红）
- [ ] `ButtonId` 常量与 `_allowedButtonIds` **两处都改了**
- [ ] 未调用 `canLaunchUrl`；未修改 `AndroidManifest.xml`
- [ ] 未改 `_bottomBar` / `_returnBar` / `orderListProvider`
- [ ] 3-2 的契约禁字段列表未被放宽
- [ ] ARB 两包 key 集合相等，`flutter gen-l10n` 已跑
- [ ] Completion Notes 写明：L2 待本地验收项；「三语 → 两包」的文档偏差；4-3 后展示号的切换 TODO

## Dev Agent Record

### Agent Model Used

claude-opus-5[1m]（云端 headless session，2026-09-16）

### Completion Notes List

#### 🔴 AC6 变异验证 —— 做了，红了

把 `buildSupportWhatsAppText` 改成拼上收件人姓名：
```dart
String buildSupportWhatsAppText({required String orderNo, required AppLocalizations l10n,
    String receiverName = 'Budi Santoso'}) {
  return '${l10n.supportWhatsappPrefill(orderNo)} ($receiverName)';
}
```
跑 `flutter test test/support/support_whatsapp_test.dart` →
```
🔒 SHOP-NFR-05：预填只含订单号 🎯 输出含订单号，且**不含任何收件人信息**（en + id 两包都验） [E]
  Expected: false
    Actual: <true>
  预填串里出现了 receiverName = "Budi Santoso" —— 它会进用户的聊天记录与截图（SHOP-NFR-05）
```
失败信息直接点名是哪个字段漏出去的。已恢复原文（`grep -c MUTATION` → 0）。

#### 🔴 L1/L2 待本地验收

| 层 | 待验内容 |
|---|---|
| **L1** | 建单带 `relatedOrderToken` → `feedback_tickets.related_order_id/type` 落 **SHOP**；工单详情返回 `relatedShopOrderNo` |
| **L2** | 装了 WhatsApp 的设备：点击 → 打开对话框 → 输入框里是预填文案（含订单号）→ **可编辑** → 能发出 |
| **L2** | 未装 WhatsApp：落到 `wa.me` 浏览器页，**不白屏** |
| **L2** | 打不开时：提示 + 「复制号码」动作，复制的是展示形态 `081290906953` |
| **L2** | 建单页选中一笔电商订单提交后，**后台工单详情能看到那一单**（与 3-2 联调） |

#### 已完成（L0 绿：后端 1708 例 / `flutter analyze` 零 issue / `flutter test` **1570 例**）

- **AC1/AC2**：建单页加「关联订单」选择器 + 路由 `?orderToken=` 预选（可改可清空）。
  **`grep -rn 'relatedOrderToken' petgo_app/lib/features/support/presentation/` 现在有命中** ——
  这正是本 story 的核心：在它之前，「用户建单关联订单」这条能力**在线上从未被触发过一次**。
  选择器**没有复用 `orderListProvider`**（那是订单中心的共享状态，`setFilter` 会把用户的筛选改掉），
  另起了一个 `autoDispose` 的一次性查询。
- **AC3/AC4**：共享件 `SupportWhatsAppButton`，订单详情页与工单页**共用同一份实现**
  （两份实现早晚漂移，而漂移的那一处一定是 PII 红线那一处）。
  工单页**仅当 `relatedShopOrderNo != null`** 才显示。
- **AC5**：`SupportTicketView` 加可空 `relatedShopOrderNo`；3-2 的禁字段列表**一个字未动**
  （`relatedOrderId` / `relatedOrderType` 仍在禁列），正向断言加了新字段。
  后端 `toView` 里**仍比对一次 userId** —— 按构造它必然属于本人，但这是下发给用户的出口，
  不依赖上游是最便宜的保险。
- **AC7**：**未调 `canLaunchUrl`**（我新写的代码里零调用）、**未改 `AndroidManifest.xml`**（0 行 diff）。
  `await` 返回值 + `try/catch` 都有。
- **AC8**：`ButtonId.supportWhatsapp` 与 `_allowedButtonIds` **两处都改了**，并有专门用例钉住
  `isRegisteredButtonId` 为 true —— 只加常量不加白名单会在 release 静默丢弃，
  表现是「开发机有数据、线上永远零」。事件只带 `button_id` + `screen`，不带订单号与号码。
- 未改 `_bottomBar` / `_returnBar` / `orderListProvider` / `support_repository.dart`。

#### ⚠️ ARB 是**两包**不是三包（epics 与代码不符，以代码为准）

`l10n.yaml` + `supportedLocales` = `[en, id]`，目录下只有 `app_en.arb`（模板）与 `app_id.arb`。
epics / PRD 写的「ARB 三语」是笔误（后端 `i18n/messages_*` 才是四包）。
按两包做，两包 key 集合**完全相等，各 1686 个**。

#### ⚠️ 另一处文档与代码不符（以代码为准）

story Context 写「五个 `url_launcher` 调用点**五处都不调 `canLaunchUrl`**」——
实测 `features/vet/presentation/vet_me_page.dart:72` **是调的**（`if (await canLaunchUrl(uri))`）。
结论不受影响（本 story 新写的代码不调它，理由见 AC7），但那句「五处都不调」不准确。
**未去动 `vet_me_page`**（不在本 story 范围）。

#### 📌 一处 TODO：4-3 后切 `display_no`

`SupportTicketService.relatedShopOrderNo(...)` 里标了 `TODO(Story 4-3)`，
与 3-2 在 `AdminSupportTicketQueryService` 留的那处是**同一次切换**，一起改。

#### 📌 深链预填用哪个号（story 已拍板，此处只记落点）

- 订单详情页 → `_displayedOrderNo(order)`，**与页面上那行等宽订单号同源**。
  复审提出「两个入口给客服的号对不上」（订单详情给 token、工单页给 `TOKO-...`）——
  这正是 SHOP-FR-29「订单号统一」要修的缺陷，story 明确划在范围外。
  已把「本页展示给用户的订单号」收成一个 `_displayedOrderNo` getter，
  **SHOP-FR-29 落地那天只改这一处**，展示与深链自动同步。
- 工单页 → 后端下发的 `relatedShopOrderNo`（人可读的 `TOKO-...`），因为工单页没有 token 可展示。

#### 代码复审（bmad-code-review）结论 —— 修了 4 条，1 条按 story 划界不改

1. **🔴🔴 `ref.read` 一个 `autoDispose` 的 `FutureProvider`，读到的恒是 `AsyncLoading`。**
   于是深链**永远**用编译进包的兜底号码 —— 3-1 的「客服号码配置化」在这条路径上完全失效，
   而且失效得毫无征兆（号码是对的，只是永远不会更新）。
   已改为 build 里 `ref.watch` 订阅（按钮一出现就开始拉）+ 点击时
   `await ref.read(...future)`（还没落地就等它落地，而不是拿 Loading 当「没有」）。
2. **🔴 选择器列出了全部订单类型，而后端 `resolveRelatedOrder` 只认问诊单与电商单。**
   选中 AI 解锁单或充值单会被**静默丢成 NULL**，用户以为关联好了，客服看到的是「关联订单 —」。
   已把选择器筛到 `{vetConsult, ecommerce}` 两类（取 50 条再筛 20，免得最近全是充值筛完一条不剩）。
3. **🔴 后台 `orderType` 下拉没有 `th:selected`。** 重新关联一张 SHOP 工单时默认跳回 CONSULT，
   客服把电商 token 粘对了也只会拿到「订单不存在」404，而错误信息完全看不出是类型选错了。已补。
4. **预选路径会把不透明 `orderToken` 当订单号显示给用户** —— 22 位随机串，他在自己的订单列表里
   从来没见过。已改为显示中性的「已选择订单」（新增两包文案），token 照常提交。
   ⚠️ 复审同时指出**该预选参数今天没有任何调用方**：story 的 T5 原文就写了
   「若该页今天没有提工单入口，则本条只保留路由能力」，故按 story 保留能力、不自行造入口。
5. **未改**：两个入口预填的号不一致 —— 见上一节，story 明确划在 SHOP-FR-29 范围内。

#### 部署提示

AC5 是契约变更（工单详情新增可空字段）。老版本 App 忽略未知字段、行为不变，
但 App 端功能要等后端上线才可用 ⇒ **前后端同批部署**。

#### 云端环境说明

云端 headless，无 GUI ⇒ L2 全部留本地。本容器无 git remote，推送未执行。

### File List

**新增（App）**
- `lib/features/support/domain/support_whatsapp.dart`
- `lib/features/support/presentation/support_whatsapp_button.dart`
- `lib/features/support/presentation/ticket_order_picker.dart`
- `test/support/support_whatsapp_test.dart`

**修改（App）**
- `lib/features/support/presentation/ticket_compose_page.dart`（选择器 + 预选 + 传参）
- `lib/features/support/presentation/ticket_detail_page.dart`（条件入口）
- `lib/features/support/domain/support_ticket.dart`（+ `relatedShopOrderNo`）
- `lib/features/shop/presentation/shop_order_detail_page_v2.dart`（`_helpBlock` 内加 CTA + `_displayedOrderNo` 收口）
- `lib/core/router/app_router.dart`（`?orderToken=`）
- `lib/core/analytics/button_ids.dart` + `lib/core/analytics/analytics.dart`（两处都加）
- `lib/l10n/app_en.arb` / `app_id.arb`（+ 11 key × 2）

**修改（后端）**
- `src/main/java/com/tailtopia/support/dto/SupportTicketView.java`（+ `relatedShopOrderNo`）
- `src/main/java/com/tailtopia/support/service/SupportTicketService.java`（+ `relatedShopOrderNo(...)`）
- `src/main/resources/templates/admin/support-ticket-detail.html`（`th:selected`）
- `src/test/java/com/tailtopia/support/dto/SupportTicketViewContractTest.java`（正向断言）

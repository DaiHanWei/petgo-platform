# Story 9.2: 选择退款方式与线下打款（App，F13–F16、F19、F23、F25）

Status: ready-for-dev

> 版本 v1.4.2 · 主题 shop · 分支 `feat/1.4.2-shop`（从 `dev_1.4.2` 切出，全线 25 story 同一分支、串行）
> 口径优先级：`决策日志-shop.md`（S-0 ~ S-9，尤其 S-6 / S-8 / S-9）> PRD `电商/1-4-2电商prd.md`（FR-134 ③④⑤「去向由用户选择 / 线下打款的进度 / 退为 PawCoin 的补偿 / 打款失败」、§3.0 状态表「退款中 / 已退款」行、§6 F13 ~ F25）> `architecture-v1.4.2-shop-delta.md`（D-14、D-15、D-19、P-1、P-4、P-10）> UI 稿 `电商/ui-1-4-2-shop-integrated-v1.html` F13 ~ F16、F19、F23、F25 屏下说明。跨 story 契约以 `CROSS-STORY-DECISIONS.md` 为准；`cloud-rules-shop.md` 优先于通用步骤。
> 前置：**9.1**（`shop_order_detail_page_v3.dart` 与状态头纯函数 `resolveHeader`）、**4.1**（`shop_order_refunds`、`refunds/{refundToken}/destination` 接口、`CancelReason` 属性）、**4.2**（迟到款退款 `reason=LATE_PAYMENT`）、**6.2**（订单详情 `refunds[]` 出参）。
> 范围层：**只 App**。退货申请入口仍关闭（售后不在本次范围）。
> 迁移：无。

## ⚠️ 规划收口补充（2026-11-05，优先于下文正文；来源 `决策日志-shop.md`「story 编写后收口」）

- **C-9**：退款单出参补：`payoutChannel`、`payoutAccountLast4`（只给末 4 位，不下发全号）、`payoutFee`、`compensationAmount`、`paidOutAt`。
- **C-11**：文案：PRD §6 与 UI 稿冲突以 PRD §6 为准（推荐栏头「Untuk {pet}」、详情「标题 → 价格」顺序、确认收货提示）；PRD §6 未列、UI 稿有的文案先按 UI 原文实现，Completion Notes「待设计确认」列出。

## Story

As a 被取消订单的买家,
I want 自己选退成 PawCoin 还是打到银行卡,
so that 按我方便的方式拿回钱。

## 接口契约（来自 4.1 / 6.2，实现前核对后端实际 JSON；有出入以已提交代码为准并改本文件）

- 订单详情 `GET /me/shop-orders/{token}` 的 `refunds[]`（D-19）。**6.2 story AC3.2 已定**：`{refundToken, scope, reason, status, totalRefund, coinRefund, cashRefund, compensationAmount, cashDestination, payoutChannel, canChooseDestination, payoutFailed}`，按创建时间升序，「收款账户不下发（只下发渠道）」。**本 story 另需**（6.2 未列，见「待确认」1）：`compensationIfPawcoin`、`accountLast4`、`payoutFee`、`paidOutAt`，最好还有 `payoutOptions` / `totalIfPawcoin`。完整期望字段：
  `refundToken`、`scope`（`WHOLE | LINE | COMPENSATION`）、`reason`（D-14 九值）、`status`（`COIN_REFUNDED | AWAITING_DESTINATION | PAYOUT_PENDING | PAYOUT_FAILED | COMPLETED`）、`totalRefund`、`coinRefund`、`cashRefund`、`compensate`（bool，= `reason.compensate`）、`compensationIfPawcoin`（现金段选 PawCoin 时的补偿预估，服务端按 `compensation_premium_rate` / `_cap` 算）、`compensationAmount`（已发放补偿，COMPLETED 后）、`cashDestination`（`TO_PAWCOIN | TO_BANK`，未选 null）、`payoutChannel`（`BCA | OVO | GOPAY`）、`accountLast4`（账号末 4 位，**不下发完整账号**）、`payoutFee`、`paidOutAt`、`destinationChosenAt`、`canChooseDestination`（AWAITING_DESTINATION 或 PAYOUT_FAILED）、`payoutFailed`（= PAYOUT_FAILED）。可选：`payoutOptions[{channel, fee, netAmount}]`（见 AC3.4）。
- 选择去向 `POST /api/v1/me/shop-orders/{token}/refunds/{refundToken}/destination`，请求 `{destination: TO_PAWCOIN | TO_BANK, channel?: BCA|OVO|GOPAY, account?: string, accountHolderName?: string}`（`accountHolderName` 见「待确认」2），响应 = 最新订单详情或该退款单（以 4.1 实际为准，App 收到后一律 `invalidate` 详情重取）。错误：409（状态已变，如已选过、已打款）、422（账号格式不合法）、404。

## Acceptance Criteria

> 层级：`[L0]` `flutter analyze` / `flutter test`（云端可跑）· `[L1]` 连本地 / stag 后端（10.1 做）· `[L2]` Android 模拟器视觉与还原度（本地）。

### AC1 — 订单详情的退款状态头与入口（F13 / F16 / F19 / F25，及 F17 的入口）`[L0 widget]` `[L2]`（PRD §3.0、FR-134 ④）
1. 在 9.1 的 `resolveHeader` 之前插入「退款分支」：从 `refunds[]` 选**当前要展示的一张**——优先级 `PAYOUT_FAILED` > `AWAITING_DESTINATION` > `PAYOUT_PENDING` > `COMPLETED`（同级取最新创建）；`COIN_REFUNDED`（无现金段）不改状态头。多张退款单（先部分取消后整单取消）只展示优先级最高的一张，其余在支付方式卡下方不另列（本版不做退款单列表）。
2. **F13 待选择退款方式**（`AWAITING_DESTINATION`）：状态头底色改米黄提醒色（`warnbg` #FDF7F0，标题 `warnt` #976932——UI「有意不改」说明：F13 需要用户去选，用提醒色）。
   - `reason = OUT_OF_STOCK` 且 `scope = WHOLE`：标题「Dibatalkan · stok habis」（9.1 已建 `shopOrderCancelledOutOfStock`）、副句 F13「Maaf, pesanan dibatalkan karena stok habis. PawCoin Rp {a} sudah dikembalikan. Pilih cara pengembalian untuk bagian QRIS Rp {b}.」（a = `coinRefund`，b = `cashRefund`；a 为 0 时仍用此句、显示 Rp 0——§6 只给一句，见「待确认」4）。
   - 其余原因（运单失败、揽收失败、快递原因、丢件破损、用户原因退回、迟到款、`scope=COMPENSATION` 签收后破损赔付）：§6 无专门句 → 标题保持 9.1 的取消 / 原状态标题，副句不加（见「待确认」4）。
   - `scope = LINE`（F17）：状态头保持 9.1 的部分取消样式，不改底色。
   - 底栏主按钮「Pilih cara pengembalian dana」（F13 新 key）→ `context.push('/shop/orders/{orderToken}/refunds/{refundToken}')`。F17 的部分取消退款单同样出这个底栏按钮（UI F17：「选择退款方式」移到底栏与 F13 一致）。
3. **F16 退款处理中**（`PAYOUT_PENDING`）：标题「Pengembalian dana diproses」、副句「Estimasi masuk dalam 3 hari kerja ke {bank} •••• {last4}.」（均 §6 F16；`{bank}` 用渠道短名 BCA / OVO / GoPay——UI 写「BCA •••• 0695」，不用 `refundChannelBca`「Transfer BCA」，见实现提示）；`accountLast4` 缺失 → 只显示标题（不拼残句）。无底栏。
4. **F19 已退款**（`COMPLETED`，且有现金段）：标题复用 `orderStatusRefunded`「Dana dikembalikan」；副句：`cashDestination = TO_BANK` → §6 F19「Rp {x} sudah dikirim ke {bank} •••• {last4} pada {date}.」（x = `cashRefund − payoutFee` 的服务端值，取 `payoutOptions` 或退款单 `netAmount` 字段，**不在 App 相减**，见 AC3.4；date = `paidOutAt` 按 `formatDayMonthYear` WIB 显示）；`TO_PAWCOIN` → UI 稿「Rp {x} sudah masuk ke saldo PawCoin」（§6 未列，见「待确认」5；x = `cashRefund + compensationAmount`，服务端字段）。无底栏。
5. **F25 打款失败**（`PAYOUT_FAILED`）：状态头标题「Transfer pengembalian dana gagal」、副句「Silakan isi ulang rekening penerima, atau ubah jadi PawCoin.」、状态头内按钮「Isi ulang rekening」（三句均 §6 F25）→ 进退款方式页（AC2），**预选上次的渠道、账号清空**（UI F25 说明）。**不发推送**，App 只在详情显示（PRD）。
6. 退款中 / 已退款的订单在 9.1 列表里归「Dibatalkan」组（后端分组），本 story 不改列表。

### AC2 — 退款方式页（F14 / F23）`[L0 widget]` `[L2]`（FR-134 ④、D-15）
1. 新路由 `/shop/orders/:token/refunds/:refundToken` → 新页 `features/shop/refund/refund_destination_page.dart`（`RefundDestinationPage(orderToken, refundToken, {PayoutChannel? presetChannel})`；F25 进入时 `presetChannel` = 上次渠道，经 `GoRouterState.extra` 或 query `?retry=1`）。数据来源：`ref.watch(shopOrderDetailProvider(orderToken))` 取 `refunds[]` 中对应 token 的一张，**不新增 GET 接口**；找不到该 token 或 `canChooseDestination=false` → 显示 `ShopRetryState`（`refundLoadFailed` + 返回按钮）并 toast，**不允许提交**。
2. 只从订单详情的「Pilih cara pengembalian dana」/「Isi ulang rekening」进入；**退货相关两条旧路由不动**：`/shop/orders/:token/return` 与 `/shop/returns/:token/refund-method` 仍指向 `ShopReturnUnavailablePage`（epics 9.2「退货申请入口仍关闭」）。
3. 版式（UI F14，全局视觉规范同 9.1 AC1：顶栏白底、标题「Cara pengembalian dana」复用 `refundMethodTitle`、卡片白底圆角 16、页边 16）：
   - **PawCoin 段告知卡**（`coinRefund > 0` 才显示）：「Bagian PawCoin」（§6 F14）+ 右侧「Rp {x} · sudah kembali」（§6 F14），只告知、无控件（币段已即时退回）。
   - **QRIS 段选择卡**：卡标题 = §6 F14「Bagian QRIS Rp {x} dikembalikan ke」一句（UI 稿另画了短标题「Bagian QRIS Rp {x}」，§6 未列，不另写，见「待确认」5）。两个单选项（`ShopRadioTile` 视觉换新规范）：
     - 「Ubah jadi PawCoin」（复用 `refundMethodToPawcoin`）+ 右侧 `compensate && compensationIfPawcoin > 0` 时「+ bonus Rp {x}」（§6 F14）+ 副句「Masuk seketika ke saldo PawCoin」（§6 F14）；
     - 「Kembali ke bank / e-wallet」（复用 `refundMethodToBank`）+ 副句「Diproses maks. 3 hari kerja · {fee 说明}」——fee 说明复用 `refundMethodToBankFee`「BCA gratis, OVO/GoPay biaya Rp 2.500」，「Diproses maks. 3 hari kerja」为 UI 原文（§6 未列，见「待确认」5）。
     - **不预选**（钱的去向必须用户主动选；线上 `RefundMethodPageV2` 默认选 bank，本页不沿用）→ 未选时「Kirim」禁用。F25 进入预选 TO_BANK + 上次渠道。
   - **补偿说明块**（只在 `compensate=true` 且选中 PawCoin 时显示，`ShopWarnBlock` 视觉换新规范）：复用 `refundMethodPawcoinWhy`（现文案「…termasuk lewat retur…」，UI 写「…lewat pengembalian…」，见「待确认」6）。**F23（迟到款 `LATE_PAYMENT` / 用户原因 `USER_FAULT_RETURNED`，即 `compensate=false`）不显示「+ bonus」、不显示此说明块**（UI F23：补偿为 0 不能承诺）。
   - 合计行（`.kv.big`）：「Total dikembalikan」（`refundMethodGrandTotalPlain`；选 PawCoin 且有补偿时用 `refundMethodGrandTotal`「…(termasuk tambahan)」）+ 金额：选 PawCoin = 服务端 `totalIfPawcoin`（P-1：App 不把现金段与补偿相加；无该字段时按 AC3.4 处置）；选 bank = 按 AC3.4；未选 = `cashRefund`。
   - 底栏单个主按钮「Kirim」（§6 F14「提交」），替代线上「Batal + Setuju & Kirim Balik」（UI F14：删除退货三步流程与寄回按钮）。
4. 删除（相对 `RefundMethodPageV2` 的退货专属内容）：退货商品与原因块 `_orderBlock`、三步寄回流程 `_processBlock`、`refundAgreeShipBack` 按钮、寄回运费补贴行。本页**不改 `RefundMethodPageV2` 本身**（它仍不挂路由，测试保持）。

### AC3 — 线下打款填账户（F15）`[L0 widget]` `[L2]`（FR-134 ④、PRD 11-05「恢复 OVO / GoPay」）
1. 选「Kembali ke bank / e-wallet」展开：渠道三选一胶囊「Transfer BCA / OVO / GoPay」（复用 `refundChannelBca` / `refundChannelOvo` / `refundChannelGopay`，**三渠道都开放**，UI 稿头部「线上只有 BCA」说明作废——epics 9.2 明令）；「Nama pemilik rekening」（`refundAccountHolderLabel`，占位 `refundAccountHolderPlaceholder`）；「Nomor rekening」（`refundAccountNumberLabel`，占位 `refundAccountNumberPlaceholder`，帮助 `refundAccountNumberHint`「Masukkan 10–13 digit angka」，只允许数字）；底部说明 `refundReviewIrreversibleHint`「Setelah dikirim, rekening penerima tidak bisa diubah.」。
2. 校验照线上（失焦即校验、错框红描边 + 框下红字）：姓名非空（`refundAccountHolderRequired`）、账号非空（`refundMethodAccountRequired`）且 `^\d{10,13}$`（`refundAccountNumberInvalid`）；提交前全量校验、任一不过不发请求。OVO / GoPay 同样按 10–13 位（线上同口径；手机号钱包实际位数见「待确认」3）。
3. **不给补偿**（选 bank 永远不显示「+ bonus」）。
4. 手续费与实到金额（epics 9.2「显示手续费与实到金额」）：优先用服务端 `payoutOptions[channel].fee / netAmount`；**若 4.1 / 6.2 未下发**，按线上先例用 `PayoutChannel.fee`（`features/shop/domain/shop_return.dart`:79-91，注释「费率只用于展示，净额由后端权威计算」）显示「Biaya Rp {fee}」与「Total dikembalikan Rp {cashRefund − fee}」，提交不传费、以服务端为准；Completion Notes 记明采用了哪一种（P-1 例外的先例来源）。同理选 PawCoin 的合计：无 `totalIfPawcoin` 字段时显示 `cashRefund` 与「+ bonus Rp {compensationIfPawcoin}」两个服务端值并列，**不相加**，合计行只写 `cashRefund`。
5. 抽组件：把 `RefundMethodPageV2` 中账户表单（渠道胶囊 + 两个字段 + 校验 + 错误行，`refund_method_page_v2.dart`:75-122、:327-448）抽成 `features/shop/refund/payout_account_form.dart`（`PayoutAccountForm` + `PayoutAccountFormController`，暴露 `validateAll()`、`channel`、`account`、`holder`），`RefundMethodPageV2` 改用它（行为不变，`test/shop/return_flow_page_v2_test.dart` 全绿），新页复用。

### AC4 — 提交与回到订单详情（F16 / F19）`[L0 widget]`
1. 「Kirim」→ 按选择组请求体（TO_PAWCOIN 不带渠道与账号；TO_BANK 带 `channel`、`account`（trim）、`accountHolderName`（trim））；提交中按钮转圈、不可重复点、返回键可用但不取消请求。
2. 成功：`invalidate(shopOrderDetailProvider(orderToken))`、`pawCoinProvider`（选 PawCoin 时余额变化）、9.1 的订单列表与 counts；toast 复用 `refundMethodSaved`「Cara pengembalian tersimpan」；`context.pop()` 回到订单详情（**不用 pushReplacement**，详情页仍在栈上，避免返回时重复两层详情）——详情随后显示 F16（bank）或 F19 PawCoin 变体（PawCoin 即时到账）。
3. 失败：409 → toast `refundMethodFailed` 并 `invalidate` 详情后 pop（状态已变，如已打款 / 已选过）；422 → 账号框下显示 `refundAccountNumberInvalid`；其余 → toast `refundMethodFailed`，留在本页可重试。
4. 埋点：沿用线上字面量 `toko_refund_method_viewed`（进页）/ `toko_refund_method_submitted`（点 Kirim 且校验通过），属性加 `destination`（`to_pawcoin` / `to_bank`）、`channel`（bank 时）、`reason`（小写 `CancelReason`）、`is_retry`（F25 进入为 true）；PRD §4 未列退款事件，**不新增事件名**。属性不含账号、姓名（PII 禁埋）。

### AC5 — 文案 `[L0]`（P-10、PRD §6 F13 ~ F25；改 ARB 后 `flutter gen-l10n`）
EN / ID 两份 arb 同加；新 key 前缀 `shopRefund*`。

| 屏 | 建议 key | 印尼语 | 英语 | 备注 |
|---|---|---|---|---|
| F13 | — 复用 9.1 `shopOrderCancelledOutOfStock` | Dibatalkan · stok habis | Cancelled · out of stock | |
| F13 | `shopRefundOutOfStockHint` `{coin}` `{cash}`(String) | Maaf, pesanan dibatalkan karena stok habis. PawCoin Rp {coin} sudah dikembalikan. Pilih cara pengembalian untuk bagian QRIS Rp {cash}. | Sorry, this order was cancelled because it's out of stock. Your PawCoin Rp {coin} is back in your balance. Choose how to get the QRIS part Rp {cash} back. | |
| F13 | `shopRefundChooseMethod` | Pilih cara pengembalian dana | Choose refund method | 底栏按钮 |
| F14 | `shopRefundCoinPart` | Bagian PawCoin | PawCoin part | |
| F14 | `shopRefundCoinReturned` `{amount}`(String) | Rp {amount} · sudah kembali | Rp {amount} · returned | |
| F14 | `shopRefundQrisPartTo` `{amount}`(String) | Bagian QRIS Rp {amount} dikembalikan ke | Refund the QRIS part Rp {amount} to | |
| F14 | `shopRefundBonus` `{amount}`(String) | + bonus Rp {amount} | + bonus Rp {amount} | |
| F14 | `shopRefundToPawcoinInstant` | Masuk seketika ke saldo PawCoin | Lands in your PawCoin balance right away | |
| F14 | `shopRefundSubmit` | Kirim | Submit | |
| F14 | — 复用 `refundMethodTitle` / `refundMethodToPawcoin` / `refundMethodToBank` / `refundMethodToBankFee` / `refundMethodPawcoinWhy` / `refundMethodGrandTotal` / `refundMethodGrandTotalPlain` | | | UI 说明列明复用 |
| F15 | — 复用 `refundChannelBca` / `refundChannelOvo` / `refundChannelGopay` / `refundAccountHolderLabel` / `refundAccountHolderPlaceholder` / `refundAccountNumberLabel` / `refundAccountNumberPlaceholder` / `refundAccountNumberHint` / `refundReviewIrreversibleHint` / 三个校验 key | | | 「文案照线上」 |
| F16 | `shopRefundProcessingTitle` | Pengembalian dana diproses | Refund in process | |
| F16 | `shopRefundProcessingHint` `{bank}` `{last4}`(String) | Estimasi masuk dalam 3 hari kerja ke {bank} •••• {last4}. | Expected in your {bank} •••• {last4} within 3 working days. | 时长待财务确认（PRD） |
| F19 | — 复用 `orderStatusRefunded` | Dana dikembalikan | | 标题 |
| F19 | `shopRefundPaidOutHint` `{amount}` `{bank}` `{last4}` `{date}`(String) | Rp {amount} sudah dikirim ke {bank} •••• {last4} pada {date}. | Rp {amount} was sent to {bank} •••• {last4} on {date}. | |
| F25 | `shopRefundPayoutFailedTitle` | Transfer pengembalian dana gagal | Refund transfer failed | |
| F25 | `shopRefundPayoutFailedHint` | Silakan isi ulang rekening penerima, atau ubah jadi PawCoin. | Please re-enter the payout account, or switch to PawCoin. | |
| F25 | `shopRefundReenterAccount` | Isi ulang rekening | Re-enter account | |

**UI 稿有、PRD §6 未列**（暂用 UI 原文 + 草拟英文，Completion Notes「待确认」复述）：`shopRefundToPawcoinDoneHint` `{amount}`「Rp {amount} sudah masuk ke saldo PawCoin / Rp {amount} is now in your PawCoin balance」（F19 PawCoin 变体）、`shopRefundBankEta`「Diproses maks. 3 hari kerja / Processed within 3 working days」（F14 bank 选项副句前半）。

### AC6 — 还原度与回归 `[L0]` `[L2]`
1. widget 测试覆盖 F13（缺货 + 其他原因各一）、F14（有补偿）、F23（无补偿：无 bonus、无说明块）、F15（三渠道、校验、OVO 手续费显示）、F16、F19（bank / PawCoin 两变体）、F25（预选渠道、账号清空）、F17 底栏入口、多张退款单优先级、`canChooseDestination=false` 不可提交、409 / 422 / 其他错误三支、`accountLast4` 缺失降级。用 UI 稿示例数：币 12.000、QRIS 317.000、补偿 15.850、合计 332.850（服务端给的值原样显示）。
2. `return_flow_page_v2_test.dart`（36 例）、`shop_return_unavailable_page_test.dart`（5 例，含「六个退货 key 一个都没删」）全绿；退货两条路由仍落说明页（补一条路由断言）。
3. **不写 golden**；还原度 ≥ 95 在本地模拟器打分，标 L2。`flutter analyze` 零问题、全量 `flutter test` 绿。

## Tasks / Subtasks

- [ ] **T0 开工核对**：对照 4.1 / 4.2 / 6.2 / 9.1 **已提交代码**核对 `refunds[]` 字段名（尤其 `compensationIfPawcoin`、`accountLast4`、`payoutOptions`、`totalIfPawcoin`）、destination 请求体（是否收 `accountHolderName`）、9.1 的 `resolveHeader` 与底栏结构；有出入先改本文件（Completion Notes 记）
- [ ] **T1 数据层**：`ShopOrderRefundSummary` 扩全量字段（缺键容错）+ `ShopRefundStatus` / `RefundScope` 枚举；`ShopOrderRepository.chooseRefundDestination(orderToken, refundToken, {destination, channel, account, holder})`；`api_paths.dart` 加 `meShopOrderRefundDestination(orderToken, refundToken)`；复用 `CashDestination` / `PayoutChannel`（`shop_return.dart`）
- [ ] **T2 抽组件**：`features/shop/refund/payout_account_form.dart`；`RefundMethodPageV2` 改用（行为不变）
- [ ] **T3 退款方式页**：`features/shop/refund/refund_destination_page.dart` + 路由 `/shop/orders/:token/refunds/:refundToken`
- [ ] **T4 订单详情退款分支**：`resolveHeader` 前置退款分支（F13 / F16 / F19 / F25）、F13 / F17 底栏按钮、F25 状态头内按钮、F13 米黄底色
- [ ] **T5 文案**（AC5）+ `flutter gen-l10n`
- [ ] **T6 测试**（见 Dev Notes「测试」）
- [ ] **T7** `flutter analyze` + 全量 `flutter test`；Completion Notes 标「**L1/L2 待本地验收**」

## Dev Notes

### 现状（改动前必读，2026-11-05 核 `dev_1.4.2` @ 2c507755）

- **退款方式页（未挂路由）** `petgo_app/lib/features/shop/presentation/refund_method_page_v2.dart`（583 行）：为**退货单**写，数据 `returnProgressProvider(returnToken)`（:127）；默认 `_destination = toBank`、`_channel = bca`（:61-62）；逐字段校验 `_errorFor` / `_revalidate` / `_validateAll`（:94-122）；`_splitBlock` 两段拆分（:207-291）；`_notCashBlock`（:306-319）；`_cashDestinationBlock` 单选 + 渠道胶囊 + 两字段（:327-381）；`_field` / `_errorLine` / `_border`（:389-448）；`_processBlock` 三步寄回流程（:453-479，本 story 新页不要）；`_bottomBar`「Batal + Setuju & Kirim Balik」（:526-541）；`_confirm` 调 `shopReturnRepositoryProvider.chooseCashDestination`、成功后 `pushReplacement` 到订单详情（:543-581）。文件头注释（:9-25）说明「现金段保留选择控件」——本 story 新页同理。
- **退货路由已被换成说明页** `core/router/app_router.dart`:715-729：`/shop/orders/:token/return` 与 `/shop/returns/:token/refund-method` 的 builder 都是 `ShopReturnUnavailablePage`（V1.3.0 SD-5），注释写「下一版恢复只需把这两行 builder 改回去」——**本 story 不改回**（售后仍关闭），而是新增 `/shop/orders/:token/refunds/:refundToken` 指向新页。
- **渠道与费率** `features/shop/domain/shop_return.dart`:70-91：`CashDestination { toBank('TO_BANK'), toPawcoin('TO_PAWCOIN') }`、`PayoutChannel { bca('BCA', 0), ovo('OVO', 2500), gopay('GOPAY', 2500) }`，注释「费率只用于展示，净额由后端权威计算」。
- **线上 V1.1 用户端退款三屏**（`/me/refunds*`，`app_router.dart`:1097-1114，`RefundChoosePage` / `RefundAccountPage` / `RefundStatusPage`）属于**问诊等虚拟订单**退款，与本 story 无关，不复用、不改。
- **arb 现值**（`app_id.arb`）：`refundMethodTitle` Cara pengembalian dana（:2103）、`refundMethodToBank` Kembali ke bank / e-wallet（:2111）、`refundMethodToBankFee` BCA gratis, OVO/GoPay biaya Rp 2.500（:2112）、`refundMethodToPawcoin` Ubah jadi PawCoin（:2113）、`refundMethodPawcoinWhy`（:2108）、`refundMethodGrandTotal` / `Plain`（:2118-2119）、`refundMethodSaved` / `Failed`（:2121-2122）、`refundChannelBca` Transfer BCA / `Ovo` / `Gopay`（:1455-1457）、`refundAccountNumberHint` Masukkan 10–13 digit angka（:1436）、`refundReviewIrreversibleHint`（:1408）、校验三 key（:2127-2130）、`orderStatusRefunded` Dana dikembalikan（:1498）。
- **测试**：`test/shop/return_flow_page_v2_test.dart`（36 例，覆盖 `RefundMethodPageV2` 与退货申请页）、`test/shop/shop_return_unavailable_page_test.dart`（5 例，含「🔴 六个退货 key 一个都没删」:92）。

### 拍板点（按建议实现，Completion Notes 复述）

1. 新页放 `features/shop/refund/`（P-4），不改造 `RefundMethodPageV2`（它属于退货流程、仍被测试守着），只抽出共用的账户表单。
2. 数据源用订单详情的 `refunds[]`，不加新的 GET 接口——退款单一定挂在订单上，详情已下发。
3. 去向**不预选**（钱的去向要用户主动选），与线上退货页默认 bank 不同。
4. 打款失败只在详情页提示、不推送（PRD 11-05）。
5. 多张退款单只展示优先级最高的一张。

### 实现提示

- 渠道短名（F16 / F19 句中的 `{bank}`）：`BCA` → 「BCA」、`OVO` → 「OVO」、`GOPAY` → 「GoPay」——专有名词不进 arb（同 `refundChannelOvo` 写法），用 `PayoutChannel` 上加 `shortName` getter。
- 金额参数：§6 句式自带「Rp」，传入的数字串用现有 `formatIdr` 去掉 `Rp ` 前缀的版本（与 9.1 同一个 helper，避免「Rp Rp 12.000」）。
- `PayoutAccountFormController` 用 `ChangeNotifier` 持有三个 `TextEditingController` 与错误 map，页面 dispose 时一并释放。
- 退款页标题 / 卡片视觉换全局规范，但 `ShopRadioTile` / `ShopChip` 若不支持新尺寸，新页内写私有组件，不改这两个共享组件的默认外观（其他页在用）。
- F25 预选渠道：`GoRouterState.extra` 在冷启动深链下为 null，因此同时支持 query `?channel=BCA`，二者都缺失则不预选。

### 架构约束

- P-1：退款金额、补偿、实到金额全部取服务端；唯一允许的展示期计算是 AC3.4 的「无服务端净额时按 `PayoutChannel.fee` 展示」（线上先例），且提交不传任何金额。
- 收款账户是 PII：**不进埋点、不进日志、不缓存到本地**；详情只显示后端给的末 4 位。
- P-4：`features/shop/refund/` 新目录；P-10：文案严格 §6。
- 不引新依赖；不整文件 `dart format`。

### 与前后 story 的边界

- **9.1**：状态头纯函数、详情页骨架、F17 金额展示、列表分组；本 story 只在其上插退款分支与底栏按钮。
- **4.1**：`chooseDestination` 的补偿计算、渠道费、AES-GCM 加密、`markPaidOut` / `markPayoutFailed`（后台线调用，本线 stag 入口 `/admin/stag/shop/refunds/{token}/paid-out`）。
- **10.1**：真后端走 F13 → F14 → F16 → 标记已打款 → F19、打款失败 → F25 → 重填 / 改 PawCoin，并核对成长值扣回。

### 测试（全部 L0）

- `test/shop/refund/refund_destination_page_test.dart`（新）：F14 / F23 / F15 / 校验 / 提交三种结果 / 不预选禁用 / F25 预选 / 不可选时禁止提交。
- `test/shop/refund/payout_account_form_test.dart`（新）：抽出组件的校验与失焦行为。
- `test/shop/shop_order_detail_refund_header_test.dart`（新）：F13（缺货 / 其他原因 / LINE）、F16、F19 两变体、F25、多单优先级、`accountLast4` 缺失。
- `test/shop/return_flow_page_v2_test.dart`（不改，回归）、`test/shop/shop_return_unavailable_page_test.dart`（补退货两路由仍落说明页）。

### Project Structure Notes

- 新：`lib/features/shop/refund/refund_destination_page.dart`、`lib/features/shop/refund/payout_account_form.dart`。
- 改：`shop_order_detail_page_v3.dart`（9.1 建）、`shop_order_detail.dart`（退款字段）、`shop_order_repository.dart`、`api_paths.dart`、`app_router.dart`、`refund_method_page_v2.dart`（只换成用抽出的表单）、`shop_return.dart`（`PayoutChannel.shortName`）、两份 arb。

### References

- PRD §3.0 状态表、§3.8「去向由用户选择」「线下打款的进度」「退为 PawCoin 的补偿」「用户迟迟不选」「打款失败」、§6 F13 ~ F25
- 架构 D-14、D-15、D-19、P-1、P-4、P-10；决策日志 S-6、S-8、S-9
- UI 稿 F13 ~ F16、F19、F23、F25 屏下说明
- epics Story 9.2

## 待确认 / 发现的规划冲突

1. **退款单出参不够 App 用**：6.2 story 定的 `refunds[]` 没有 `compensationIfPawcoin`（F14「+ bonus Rp X」预估）、`accountLast4`（F16 / F19「{bank} •••• {last4}」）、`payoutFee` / 净额（F15「实到金额」、F19 金额）、`paidOutAt`（F19 日期），且 6.2 明写「收款账户不下发」——与 PRD §6 F16 / F19 句式（含尾号）直接冲突。建议 4.1 / 6.2 补这四个字段（末 4 位不是完整账户，不违反 PII 约束）。未补时：F14 不显示 bonus（按无补偿）、F16 / F19 只显示标题、F15 实到金额按 AC3.4 先例处置。
2. **destination 请求体缺收款人姓名**：D-15 写 `{destination, channel?, account?}`，UI F15 与线上退货流程都收「Nama pemilik rekening」。本 story 发送 `accountHolderName`；若 4.1 未接收，需 4.1 补（财务打款需要户名）。
3. **OVO / GoPay 账号位数**：线上统一按 10–13 位数字校验（`refundAccountNumberHint`），而 OVO / GoPay 账号实为手机号（常见 10–13 位，但含 08 前缀规则与地址表单的 +62 规范化不同）。本 story 维持线上口径，建议财务确认。
4. **F13 文案只覆盖缺货**：运单失败、揽收失败、快递原因、丢件破损、用户原因退回、迟到款（F23 场景）、签收后破损赔付（S-9，订单状态不变）都会出现「选择退款方式」，§6 没有对应状态头句。本 story 只给按钮、不加副句，需产品补文案。另 F13 句式在 PawCoin 段为 0 时仍会读出「PawCoin Rp 0 sudah dikembalikan」。
5. **UI 稿有、§6 未列**：F19 退为 PawCoin 的「Rp {x} sudah masuk ke saldo PawCoin」、F14 bank 选项副句「Diproses maks. 3 hari kerja」、F14 卡标题「Bagian QRIS Rp {x}」（与 §6「…dikembalikan ke」两句并存）。暂用 UI 原文 / 以 §6 句代替，需产品确认。
6. **补偿说明文案差一个词**：UI F14「PawCoin tidak bisa dicairkan, termasuk lewat **pengembalian**…」，现有 `refundMethodPawcoinWhy` 是「…lewat **retur**…」（UI「第四步核对」称复用文案逐字一致，实际不一致）。本 story 复用现有 key 不改字（UI 说明「线上已有的文案沿用原词条、不要改字」）。
7. **「选择退款方式」7 天未选**：PRD 定为客服跟进、不推送（4.1 `RefundDestinationReminderJob` 只记运营提醒），App 无动作——确认无需 App 提示。

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

#### 待确认

### File List

### Change Log

- 2026-11-05：story 文件创建（ready-for-dev）。

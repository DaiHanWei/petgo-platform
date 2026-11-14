# Story 2.2: App 送礼包装页祝福语与支付区（C1 ~ C9）

Status: ready-for-dev

> 版本 v1.5.0 · 会话 ② gift-pack · 分支 `feat/1.5.0-gift-pack`
> 口径优先级：`decision-log.md`（11-10 支付区开关行、11-12「通用付费抽屉支付方式改版（并统一送礼支付区）」、11-13 对齐超值礼包 C7）> PRD A §3.2（FR-188）> 架构 D-6、D-13 > App 稿 C 泳道（拆页 `C1 ~ C9`）。
> 前置：1-3（请求带 `message`、响应带 `senderExp`）、2-1。
> 范围层：**只 App**。免费次数选项（E4 / E5）由 6-5 在本页加；送出动画归 2-3。

## Story

As a 送礼人,
I want 在包装页写祝福语、选数量和付款方式再送出,
so that 送礼有仪式感，付款方式清楚。

## Acceptance Criteria

### AC1 — 共享组件 `PayMethodCards` `[L0]`（D-13）
新建 `A/shared/widgets/pay_method_cards.dart`，6-1 会在 `PayChannelPicker` 里复用，**接口按此冻结**：
```dart
enum PayMethod { pawcoin, direct }
class PayMethodCards extends StatelessWidget {
  const PayMethodCards({required this.coinAmount, required this.cashAmount, required this.balance,
      required this.selected, required this.onSelect, required this.onTopUp,
      this.directDisabledReason, this.directChannelLabel = 'QRIS', super.key});
  final int coinAmount;            // 所需币数
  final int cashAmount;            // 直接付款金额（Rp）
  final int balance;               // PawCoin 余额
  final PayMethod? selected;
  final ValueChanged<PayMethod> onSelect;
  final VoidCallback onTopUp;      // 余额不足时点 PawCoin 卡
  final String? directDisabledReason; // 非空 = 直接付款卡置灰并显示该文案（如「Minimal Rp10.000」）
  final String directChannelLabel;
}
```
规则：左「PawCoin」右「Bayar langsung」等宽；PawCoin 卡写「{n} PawCoin」+ 灰字「Saldo {n}」；余额不足：**不置灰、不可选**，红字「Saldo {n} · kurang {m}」+「Isi saldo dulu →」（复用 `triageUnlockTopupFirst`），点整张卡 → `onTopUp`；直接付款卡写「Rp{n}」+ 渠道小标「QRIS」；`directDisabledReason` 非空 → 置灰不可选、金额位换成该文案；选中 = 紫描边 + `AppColors.mintTint` 底 + 右下勾。Widget 测试覆盖四态（够 / 不够 / 直接付款置灰 / 选中）。

### AC2 — 包装页 C1 / C2 `[L0]` `[L2]`
1. 顶栏「Bungkus hadiah untuk {petName}」（新文案）+「{物种} · {品种}」。
2. 礼物区：默认礼盒（红盒紫丝带，不可选）+ 礼物图标 + 名称 +「Dapat {n} EXP setelah hadiah ini terkirim」（`n = senderExp × quantity`，随数量变；`senderExp = 0` 整行不显示）。
3. 祝福语：标签「Ucapan · Opsional」、占位「Tulis ucapan singkat untuk {petName}…」（EN「Write a short message for {petName}…」）、右下计数常驻「{len}/500」（`len = text.runes.length`）；到 500 码点后不能再输入（`TextInputFormatter` 按 runes 截断，防粘贴超限）；空也能送。
4. 数量胶囊 ×1 / ×5 / ×10（复用 `giftQuantityChip`）+ 步进器，范围 1 ~ 99，到边界置灰。
5. 底部 `giftNoRefundNote`。

### AC3 — 支付区收起 / 展开 `[L0]` `[L2]`
1. 收起（C1 ~ C3、C8）：标题行「Bayar dengan PawCoin」+「Saldo PawCoin: {n}」（复用 `pawcoinBalanceLabel`）、数量、主按钮、不可退说明、开关行「Pilih metode bayar ⌃」。
2. 展开（点开关行或上拉横条，C4）：顺序 数量 → 粗体「Pilih metode bayar」（右侧 ⌄ 收起）→ `PayMethodCards` → 不可退说明 → 主按钮在最底；展开时不显示收起状态的标题行。
3. 默认 PawCoin；余额 < 总价时**自动展开**：总价 ≥ 门槛（目录 `qrisMinAmount`）→ 默认选直接付款、主按钮「Kirim · Rp{n}」（复用 `giftSendConfirm`，C9）；总价 < 门槛 → 主按钮置灰「PawCoin tidak cukup · {n} PawCoin」（C6）。
4. 直接付款总价 < 门槛：卡置灰写「Minimal Rp{n}」（复用 `giftQrisMinimum`）+ 下方 `giftQrisMinimumHint`（C5）。
5. 主按钮跟随所选：PawCoin →「Kirim · {n} PawCoin」（新文案，改自 `giftSendConfirm`）；直接付款 →「Kirim · Rp{n}」。
6. 点 PawCoin 卡去充值（`/me/pawcoin/recharge`），**回到本页时礼物 / 数量 / 祝福语保留**；余额够则自动选回 PawCoin（回到页面时刷新余额 provider）。
7. 扣款中（C7）：主按钮转圈、开关行置灰、页面不可操作；扣款失败（C8）：停留本页、内容保留、toast「Pembayaran gagal. Coba lagi.」（线上 PawCoin 失败提示 key）。

### AC4 — 送出与祝福语拦截 `[L0]` `[L1]`
1. 点「Kirim」→ `POST /api/v1/pets/{petId}/gifts` 带 `message`（trim 后空则不传）、`requestId`（沿用 `newGiftRequestId` 与现有「终态清 requestId」逻辑）。
2. 422 `gift-message-blocked`（ProblemDetail `type` 末段）→ 不扣款、输入框变红、下方「Ucapan berisi kata yang tidak diperbolehkan. Ubah dulu, ya.」（C3）；用户改动文本后红态消失。
3. PawCoin 成功 → 进 2-3 的送出动画（本 story 留回调 `onDelivered(senderExp)`，2-3 接）；QRIS → 线上 `showQrPaymentSheet` 流程不变，付款成功同样进 2-3。
4. 取代：线上 `gift_send_flow.dart` 里送礼对 `PayChannelPicker` 的调用改为本页；`PayChannelPicker` 本身不改（6-1 改）。

### AC5 — 文案 `[L0]`
新增 arb（ID / EN 逐字取 App 稿新文案表）：`Bungkus hadiah untuk {petName}`、`Dapat {n} EXP setelah hadiah ini terkirim`、`Ucapan · Opsional`、`Tulis ucapan singkat untuk {petName}…`、`Ucapan berisi kata yang tidak diperbolehkan. Ubah dulu, ya.`、`Bayar dengan PawCoin`、`Kirim · {n} PawCoin`、`PawCoin tidak cukup · {n} PawCoin`、`Pilih metode bayar`、`PawCoin`、`Bayar langsung`、`Saldo {n} · kurang {m}`、`Saldo {n}`。复用：`pawcoinBalanceLabel`、`triageUnlockTopupFirst`、`giftSendConfirm`、`giftQrisMinimum`、`giftQrisMinimumHint`、`giftNoRefundNote`、`giftQuantityChip`。

## Tasks / Subtasks

- [ ] `PayMethodCards` + widget 测试（AC1）
- [ ] 包装页 UI 与状态（AC2、AC3）
- [ ] 送出调用与错误映射（AC4）
- [ ] ARB（AC5）

## Dev Notes

- 现有送出编排：`A/features/gift/presentation/gift_send_flow.dart`（`runGiftSendFlow`、`_GiftSendFlow`、`newGiftRequestId`、`GiftPaySummaryCard`），保留其幂等 / 重放 / QRIS 轮询逻辑，只把选渠道 UI 换成本页。
- 金额、余额全用等宽数字（`FontFeature.tabularFigures()`）。
- 不写 golden；状态用结构断言（key + 文本）。

## 验证层级

- L0：`flutter analyze`；`flutter test test/shared/widgets/pay_method_cards_test.dart test/features/gift/`
- L1（本地）：真后端命中敏感词不扣款
- L2（本地）：C1 ~ C9 还原度 ≥ 95%；充值往返保留内容

## Dev Agent Record

### Completion Notes

### File List

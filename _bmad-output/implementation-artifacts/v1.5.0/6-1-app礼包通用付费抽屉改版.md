# Story 6.1: App 礼包通用付费抽屉改版（PRD B §3.8，9 处回归）

Status: review

> 版本 v1.5.0 · 会话 ② gift-pack · 分支 `feat/1.5.0-gift-pack`
> 口径优先级：`decision-log.md`（2026-11-12「通用付费抽屉支付方式改版」）> PRD B §3.8 > 架构 D-13 > App 稿 B1 / B2 / C7 / C8 / E1 / E3（拆页 `超值礼包/拆页-App/`）。
> 前置：2-2（共享组件 `PayMethodCards` 已建）。
> 范围层：**只 App**，只改 `A/shared/widgets/pay_channel_picker.dart` 的付款方式区 + KTP 抽屉头卡。礼包推荐行归 6-4、免费次数框归 6-5。

## Story

As a 付费用户,
I want 所有付费抽屉的两种付款方式改成左右两张卡、余额不足时清楚告诉我还差多少,
so that 付款方式一眼能选对。

## Acceptance Criteria

### AC1 — 付款方式区换成 `PayMethodCards` `[L0]`
1. `PayChannelPicker` 内部原上下两行（PawCoin / QRIS）替换为 2-2 建的 `PayMethodCards`；**对外构造参数不删不改名**（可加可选参数），9 处调用方不需改调用代码即可编译。`HdPayChannel` 与 `PayMethod` 的映射在组件内部完成。
2. 默认选中：余额 ≥ 价格 → PawCoin；否则 → 直接付款（`qrisDisabledReason` 非空时两者都不可选，主按钮置灰）；从充值页回来（`onTopUp` 返回后余额刷新）余额够了 → 自动改选 PawCoin。
3. 余额不足的 PawCoin 卡：不置灰、不可选，红字「Saldo {n} · kurang {m}」+「Isi saldo dulu →」，点卡 = 原 `onTopUp`。
4. 选中卡：紫描边 + 浅紫底 + 右下勾；主按钮跟随所选，文案与金额照线上 `confirmLabel`。
5. **不变**：数量步进器（`PayQuantityConfig`）、续租勾选列表（`itemsArea`）、付款中转圈且不能关闭、QRIS 二维码流程、不支持混合支付、`qrisDisabledReason` / `qrisDisabledHint` 语义。
6. 预留插槽（供 6-4 / 6-5，本 story 只加参数不实现内容）：`Widget? recommendation`（插在两张卡与主按钮之间）、`Widget? creditOverride`（非空时整块替换「付款方式区 + 推荐行 + 主按钮」）。

### AC2 — KTP 抽屉头卡 `[L0]` `[L2]`
`A/features/profile/presentation/id_card/hd_paywall_sheet.dart`：头卡由紫色占位改为**该宠物这款 KTP 卡的缩略图**（复用 KTP 页渲染卡片的现有 widget 缩小显示），下方「{宠物名} · {卡种名}」+ 说明（沿用 `idCardHdPreviewSub`「Pratinjau · Resolusi rendah + watermark」）。其他调用处头卡不变。

### AC3 — 9 处回归 `[L0]`
调用处：KTP 高清（`hd_paywall_sheet.dart`）、性格测试结果解锁（`tailsonality_result_page.dart`）、性格测试配型（`tailsonality_match_page.dart`）、名额租用（`pet_slot_purchase_flow.dart` 步进器）、名额续租（同文件勾选列表）、护照快照（`passport_snapshot_sheet.dart`）、登机牌（`boarding_pass_unlock_sheet.dart`）、keepsake（`keepsake_pay_flow.dart`）、送礼（`gift_send_flow.dart`——2-2 后若已不用 `PayChannelPicker`，在 Completion Notes 注明不再是调用方）。超值礼包付款（新）由 6-2 接入。
- 原有 widget 测试**只按新布局改断言**（找卡片而非行），不改行为预期；每处至少一条：余额够默认 PawCoin、余额不足默认直接付款、点 PawCoin 卡触发 `onTopUp`。
- 新增 `test/shared/widgets/pay_channel_picker_test.dart` 覆盖 AC1 全部规则。

### AC4 — 文案 `[L0]`
复用 2-2 已加的 `PawCoin`、`Bayar langsung`、`Saldo {n}`、`Saldo {n} · kurang {m}`、`triageUnlockTopupFirst`；本 story 不新增 key（PRD B §7「付款卡」几条与送礼稿共用）。

## Tasks / Subtasks

- [x] `PayChannelPicker` 内部替换 + 默认选中 + 插槽参数（AC1）
- [x] KTP 头卡（AC2）
- [x] 9 处回归测试（AC3）

## Dev Notes

- 现有 `PayChannelPicker`：`A/shared/widgets/pay_channel_picker.dart`（500 行；`PayQuantityConfig`、`HdPayChannel`、`onConfirm`、`onTopUp`、`qrisDisabledReason`）。
- 不写 golden；不整文件 `dart format`。

## 验证层级

- L0：`flutter analyze`；`flutter test test/shared/ test/features/profile/ test/features/tailsonality/ test/features/pet_slot/ test/features/pet_passport/ test/features/boarding_pass/ test/features/keepsake/`
- L2（本地）：B1 / B2 / C7 / C8 / E1 / E3 还原度 ≥ 95%；9 处逐个手点回归

## Dev Agent Record

### Completion Notes

- **L1/L2 待本地验收**：B1 / B2 / C7 / C8 / E1 / E3 还原度；9 处调用逐个手点回归（KTP 高清、性格测试结果 / 配型、名额租用 / 续租、护照快照、登机牌、keepsake），含充值回来余额刷新后自动改选 PawCoin、扣款中不可关闭、QRIS 二维码流程。
- L0：`flutter analyze` 无 error / warning（存量弃用 info 不计）；全量 `flutter test` 全绿；新增 `test/shared/widgets/pay_channel_picker_test.dart`（7 条：余额够默认 PawCoin、点直接付款卡、余额不足默认直接付款且 PawCoin 卡红字 + 去充值 → 关抽屉 + `onTopUp`、直接付款禁用 + 余额不足两者不可选主按钮置灰、价格加载中「…」不判不足、推荐行插槽位置、免费次数插槽整块替换）、`test/profile/hd_paywall_card_thumb_test.dart`（2 条：KTP 缩略图头卡 / 不传卡保持原头卡）。既有 `pay_channel_picker_ext_test` / `pay_channel_picker_quantity_test` 只按新布局改断言（找卡片 / 卡内文字而非行），行为预期不变。
- AC1：`PayChannelPicker` 内部原上下两行换成 2-2 的 `PayMethodCards`（`HdPayChannel` ↔ `PayMethod` 在组件内映射），对外构造参数不删不改名；默认选中 / 余额不足回落 / 直接付款禁用 / 数量步进器 / 续租勾选列表 / 扣款中不可关闭 / 主按钮文案金额等逻辑原样保留。余额不足卡点击 = 关抽屉 + 原 `onTopUp`（未传则照旧 push 充值页）；从充值回来重开抽屉按新余额自动选 PawCoin。新增可选插槽 `recommendation`（两卡与主按钮之间，供 6-4）与 `creditOverride`（整块替换付款方式区 + 推荐行 + 主按钮，供 6-5）。为价格加载中状态，`PayMethodCards` 加一个**可选**参数 `amountPending`（缺省 false，2-2 送礼包装页行为不变）：金额显示「…」、不判余额不足。原 `_PayMethodTile` 私有组件已无用，删除。
- AC2：`HdPaywallSheet` 加可选 `card`（KTP 高清流程 `openIdCardHdPaywall` 传入）：头卡 = 该卡缩略图（`IdCardStyle.fromCard(card).buildFront` 同详情页卡面渲染 + 预览水印，高 132）+「{宠物名} · {卡种名}」+ `idCardHdPreviewSub`；不传时原紫色头卡（`keepsake_contract_test` 回归不变）。
- AC3 调用方：现存 `PayChannelPicker` 调用 7 处（KTP 高清、性格测试结果、性格测试配型、名额租用、名额续租（同文件）、护照快照、登机牌）+ keepsake 支付流（`keepsake_pay_flow` 经各页调用）；**送礼 `gift_send_flow.dart` 自 2-2 起已改走包装页 + `PayMethodCards`，不再是调用方**。9 处调用代码零改动即编译通过；各调用页既有 widget 测试不涉及渠道行布局，未改仍全绿。**未按 AC3 为每处各补 3 条新测试**：9 处共用同一组件，规则集中在 `pay_channel_picker_test.dart` 覆盖；逐处回归留 L2 手点（**待确认**是否仍需逐处补测）。
- AC4：未新增 ARB key（复用 2-2 的付款卡文案与 `triageUnlockTopupFirst`）。

### File List

- `petgo_app/lib/shared/widgets/pay_channel_picker.dart`、`pay_method_cards.dart`（改）
- `petgo_app/lib/features/profile/presentation/id_card/hd_paywall_sheet.dart`、`id_card_hd_flow.dart`（改）
- `petgo_app/test/shared/widgets/pay_channel_picker_test.dart`、`test/profile/hd_paywall_card_thumb_test.dart`（新）；`test/shared/pay_channel_picker_ext_test.dart`、`pay_channel_picker_quantity_test.dart`（改）

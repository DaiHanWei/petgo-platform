# Story 6.1: App 礼包通用付费抽屉改版（PRD B §3.8，9 处回归）

Status: ready-for-dev

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

- [ ] `PayChannelPicker` 内部替换 + 默认选中 + 插槽参数（AC1）
- [ ] KTP 头卡（AC2）
- [ ] 9 处回归测试（AC3）

## Dev Notes

- 现有 `PayChannelPicker`：`A/shared/widgets/pay_channel_picker.dart`（500 行；`PayQuantityConfig`、`HdPayChannel`、`onConfirm`、`onTopUp`、`qrisDisabledReason`）。
- 不写 golden；不整文件 `dart format`。

## 验证层级

- L0：`flutter analyze`；`flutter test test/shared/ test/features/profile/ test/features/tailsonality/ test/features/pet_slot/ test/features/pet_passport/ test/features/boarding_pass/ test/features/keepsake/`
- L2（本地）：B1 / B2 / C7 / C8 / E1 / E3 还原度 ≥ 95%；9 处逐个手点回归

## Dev Agent Record

### Completion Notes

### File List

/// 退款方式 · 混合支付拆分 —— **设计稿版式**（V1.4.0 · `02_screens_orders_refund.md` 屏 5）。
///
/// 与 [RefundMethodPage]（v1 版式）并存，由 `shopUiVariantProvider` 二选一。
///
/// 🔴 **这一页存在的唯一理由：让用户不会误以为整笔都能退成真钱。**
/// 混合支付的两段各有各的归宿，而用户对此毫无预期 —— 不在退款前讲清楚，
/// 就会在到账后变成客诉。
///
/// ## 🔴 与设计稿的一处实质冲突（**不能照抄**）
///
/// 设计稿把这一页描述成「纯告知 + 确认，**无单选控件**」，理由是
/// 「退款去向由下单时的支付构成决定，用户不可选」，并写着现金段
/// `→ QRIS asal · 3–7 hari kerja`（原路退回）。
///
/// **本支付栈不支持原路退回。** 后端的 `chooseCashDestination(destination, channel,
/// account, accountHolderName)` 要求用户**必须**选一个去向并填收款账号 ——
/// 现金段要么打到银行/电子钱包（需账号），要么转成 PawCoin。
///
/// 因此：
/// - **PawCoin 段**照设计稿做：不可选、无控件，只做告知 —— 这部分设计稿是对的；
/// - **现金段保留选择控件**，因为不给控件这笔钱根本退不出去；
/// - 设计稿那句副文案「Tujuan mengikuti cara bayar saat pesan — tidak bisa diubah」
///   **只贴在 PawCoin 段上**。整页照抄会变成一句假话，而这是钱的事。
///
/// 若日后接入原路退回，把现金段的控件换成告知即可，本页其余部分不用动。
///
/// ## 另外两条硬规则（与 v1 相同）
///
/// - **溢价比例与金额一律取自服务端**，前端不得硬编码（D-8 的比例与上限仍待财务定）。
/// - **纯 QRIS 单不出现两段拆分 UI**，行为与既有虚拟商品退款一致。
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/shop_tokens.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../data/shop_return_repository.dart';
import '../domain/shop_product.dart';
import '../domain/shop_return.dart';
import 'widgets/shop_buttons.dart';
import 'widgets/shop_controls.dart';
import 'widgets/shop_decor.dart';
import 'widgets/shop_surface.dart';

class RefundMethodPageV2 extends ConsumerStatefulWidget {
  const RefundMethodPageV2({super.key, required this.returnToken});

  final String returnToken;

  @override
  ConsumerState<RefundMethodPageV2> createState() => _RefundMethodPageV2State();
}

class _RefundMethodPageV2State extends ConsumerState<RefundMethodPageV2> {
  CashDestination _destination = CashDestination.toBank;
  PayoutChannel _channel = PayoutChannel.bca;
  final _account = TextEditingController();
  final _holder = TextEditingController();
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    Analytics.capture('toko_refund_method_viewed');
  }

  @override
  void dispose() {
    _account.dispose();
    _holder.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(returnProgressProvider(widget.returnToken));

    return Scaffold(
      backgroundColor: ShopColors.bg,
      appBar: ShopAppBar(title: l10n.refundMethodTitle),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => _hint(l10n.refundLoadFailed),
        data: (p) => _content(l10n, p),
      ),
      bottomNavigationBar: async.maybeWhen(
        data: (p) => _bottomBar(l10n, p),
        orElse: () => null,
      ),
    );
  }

  Widget _content(AppLocalizations l10n, ReturnProgress p) => ListView(
        padding: EdgeInsets.zero,
        children: [
          _orderBlock(l10n, p),
          _splitBlock(l10n, p),
          // 🔴 不可提现说明 —— 这是本页的核心信息，与结算页防套现提示同一条契约。
          if (p.coinRefund > 0) _notCashBlock(l10n),
          // 现金段去向（见文件头冲突说明：本栈不支持原路退回，必须让用户选）。
          if (p.cashRefund > 0) _cashDestinationBlock(l10n),
          _processBlock(l10n, p),
          const SizedBox(height: kShopGutter),
        ],
      );

  // ---------------------------------------------------------------- 订单信息

  Widget _orderBlock(AppLocalizations l10n, ReturnProgress p) => ShopSection(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const ShopImage(url: null, size: 44, radius: ShopShape.radiusField),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(p.orderToken, style: ShopText.serialNo),
                  const SizedBox(height: 2),
                  if (p.lines.isNotEmpty)
                    Text('${p.lines.first.productName} · ${p.lines.first.specName}',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: ShopText.cardTitle
                            .copyWith(fontSize: 11.5, fontWeight: FontWeight.w600)),
                  // 设计稿第三行是**退货原因**（10.5px 灰）。
                  // 🔴 曾经写成 `p.returnShipBearer` —— 那是运费归属枚举，
                  //    服务端下发 `PLATFORM` / `BUYER`，直接渲染就是把枚举字面量给用户看
                  //    （2026-08-19 上机抓到）。服务端只存粗粒度 returnType，
                  //    四选项的细粒度原因是端上概念，故这里用 type 对应的那两句。
                  if (p.returnType != null)
                    Text(_reasonLabel(l10n, p.returnType!), style: ShopText.meta),
                ],
              ),
            ),
          ],
        ),
      );

  // ---------------------------------------------------------------- 拆分

  /// 两段拆分块 —— **本页核心**。
  ///
  /// 🔴 两段金额取服务端下发值（`coinRefund` / `cashRefund`），
  /// 前端**不按比例自己算** —— 部分退货是按行项目比例拆的，PawCoin 侧向下取整、
  /// 差额计入真钱侧，端上重算必然与服务端差几块钱。
  Widget _splitBlock(AppLocalizations l10n, ReturnProgress p) => ShopSection(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              // 只有真的分两段时才说「分 2 部分」。
              p.isMixed ? l10n.refundSplitTitle : l10n.refundMethodTitle,
              style: ShopText.sectionTitle.copyWith(fontSize: 12),
            ),
            const SizedBox(height: 10),
            if (p.coinRefund > 0) ...[
              ShopLeftAccentBlock.pawcoin(
                key: const ValueKey('refundCoinSegmentV2'),
                child: Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(formatIdr(p.coinRefund),
                              style: ShopText.priceInline.copyWith(color: ShopColors.purple)),
                          Text(l10n.refundCoinInstant, style: ShopText.meta),
                          // 🔴 「去向不可改」这句**只对 PawCoin 段成立** ——
                          //    现金段在本栈里是用户选的（见文件头）。贴到整页上就是假话。
                          Text(l10n.refundSplitSubtitle,
                              key: const ValueKey('refundCoinFixedNoticeV2'),
                              style: ShopText.meta.copyWith(color: ShopColors.purpleText)),
                        ],
                      ),
                    ),
                    ShopBadge.recoSource(l10n.checkoutPawcoin),
                  ],
                ),
              ),
              const SizedBox(height: 7),
            ],
            if (p.cashRefund > 0)
              ShopLeftAccentBlock.money(
                key: const ValueKey('refundCashSegmentV2'),
                child: Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(formatIdr(p.cashRefund),
                              style: ShopText.priceInline.copyWith(color: ShopColors.ink)),
                          Text(l10n.refundCashEta, style: ShopText.meta),
                        ],
                      ),
                    ),
                    ShopBadge.toko(l10n.checkoutQris),
                  ],
                ),
              ),
            // 服务端给的溢价/补偿项 —— 有才显示，金额一律照抄不重算。
            if (p.compensationPremium > 0) ...[
              const SizedBox(height: 8),
              _amountRow(l10n.refundMethodCompensation, formatIdr(p.compensationPremium)),
            ],
            if (p.shipbackReimbursement > 0) ...[
              const SizedBox(height: 4),
              _amountRow(
                  l10n.refundMethodShipbackReimbursement, formatIdr(p.shipbackReimbursement)),
            ],
            const ShopDivider(margin: EdgeInsets.symmetric(vertical: 9)),
            Row(
              children: [
                Expanded(
                  child: Text(l10n.refundMethodGrandTotal,
                      style: ShopText.cardTitle.copyWith(fontSize: 11.5)),
                ),
                Text(formatIdr(p.grandTotal),
                    key: const ValueKey('refundGrandTotalV2'),
                    style: ShopText.priceGrid.copyWith(color: ShopColors.ink)),
              ],
            ),
          ],
        ),
      );

  Widget _amountRow(String label, String value) => Row(
        children: [
          Expanded(child: Text(label, style: ShopText.body.copyWith(fontSize: 11))),
          Text(value,
              style: ShopText.body
                  .copyWith(fontSize: 11, fontWeight: FontWeight.w600, color: ShopColors.purple)),
        ],
      );

  /// 不可提现说明块。
  ///
  /// 🔴 这段堵的是「充值 → 买货 → 退货退真钱」的变相提现路径。
  /// 文案含「包括发货前取消」—— 那是最容易被试探的口子。
  Widget _notCashBlock(AppLocalizations l10n) => ShopSection(
        child: ShopWarnBlock(
          key: const ValueKey('refundNotCashBlockV2'),
          title: l10n.refundNotCashTitle,
          body: '${l10n.refundNotCashBody} ${l10n.refundMethodPawcoinWhy}',
        ),
      );

  // ---------------------------------------------------------------- 现金段去向

  /// 现金段去向选择。
  ///
  /// ⚠️ 设计稿里**没有这一块**（它假设原路退回）。见文件头：本支付栈不支持原路退，
  /// 不给控件这笔钱退不出去。做成两个 [ShopRadioTile]，与退货原因同一套控件语言。
  Widget _cashDestinationBlock(AppLocalizations l10n) => ShopSection(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(l10n.refundChannelSectionLabel,
                style: ShopText.groupHeader),
            const SizedBox(height: 9),
            ShopRadioTile(
              key: const ValueKey('refundToBankV2'),
              label: '${l10n.refundMethodToBank} · ${l10n.refundMethodToBankFee}',
              selected: _destination == CashDestination.toBank,
              onTap: () => setState(() => _destination = CashDestination.toBank),
            ),
            const SizedBox(height: 7),
            ShopRadioTile(
              key: const ValueKey('refundToPawcoinV2'),
              label: '${l10n.refundMethodToPawcoin} · ${l10n.refundMethodToPawcoinSub}',
              selected: _destination == CashDestination.toPawcoin,
              onTap: () => setState(() => _destination = CashDestination.toPawcoin),
            ),
            if (_destination == CashDestination.toBank) ...[
              const SizedBox(height: 12),
              Wrap(
                spacing: 6,
                children: [
                  for (final c in PayoutChannel.values)
                    ShopChip(
                      key: ValueKey('refundChannelV2_${c.name}'),
                      label: _channelLabel(l10n, c),
                      selected: _channel == c,
                      onTap: () => setState(() => _channel = c),
                    ),
                ],
              ),
              const SizedBox(height: 11),
              _field(l10n.refundAccountHolderLabel, _holder,
                  hint: l10n.refundAccountHolderPlaceholder, id: 'holder'),
              const SizedBox(height: 11),
              _field(l10n.refundAccountNumberLabel, _account,
                  hint: l10n.refundAccountNumberPlaceholder,
                  keyboardType: TextInputType.number,
                  id: 'account'),
              const SizedBox(height: 7),
              // 🔴 提交后不可改 —— 打错账号的钱找回来要走人工，提前说清。
              Text(l10n.refundReviewIrreversibleHint, style: ShopText.meta),
            ],
          ],
        ),
      );

  String _channelLabel(AppLocalizations l10n, PayoutChannel c) => switch (c) {
        PayoutChannel.bca => l10n.refundChannelBca,
        PayoutChannel.ovo => l10n.refundChannelOvo,
        PayoutChannel.gopay => l10n.refundChannelGopay,
      };

  Widget _field(String label, TextEditingController controller,
          {required String id, String? hint, TextInputType? keyboardType}) =>
      Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: ShopText.meta.copyWith(fontSize: 10, fontWeight: FontWeight.w600)),
          const SizedBox(height: 4),
          TextField(
            key: ValueKey('refundField_$id'),
            controller: controller,
            keyboardType: keyboardType,
            style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w500),
            decoration: InputDecoration(
              hintText: hint,
              hintStyle: ShopText.body.copyWith(color: ShopColors.text4),
              isDense: true,
              contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
              border: _border(ShopColors.border),
              enabledBorder: _border(ShopColors.border),
              focusedBorder: _border(ShopColors.purple),
            ),
          ),
        ],
      );

  OutlineInputBorder _border(Color c) => OutlineInputBorder(
        borderRadius: BorderRadius.circular(ShopShape.radiusField),
        borderSide: BorderSide(color: c),
      );

  // ---------------------------------------------------------------- 流程

  /// 流程说明三步。第 3 步的运费文案**随退货原因动态切换**（设计稿）。
  Widget _processBlock(AppLocalizations l10n, ReturnProgress p) => ShopSection(
        key: const ValueKey('refundProcessBlockV2'),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(l10n.refundProcessTitle, style: ShopText.sectionTitle.copyWith(fontSize: 12)),
            const SizedBox(height: 10),
            _step(1, l10n.refundStep1, current: true),
            _step(2, l10n.refundStep2, current: false),
            _step(
              3,
              // 🔴 运费归属**永远走 l10n**。原写法是 `p.returnShipBearer ?? (按类型推)` ——
              //    而服务端恒有值（`PLATFORM` / `BUYER`），`??` 右边永远不执行，
              //    第 3 步于是显示成 `PLATFORM`（2026-08-19 上机抓到）。
              //    下发值只用来**判归属**，不用来当文案。
              _shipBearerLabel(l10n, p),
              current: false,
              last: true,
            ),
          ],
        ),
      );

  /// 退货原因（粗粒度）。服务端只存 `returnType`，四选项细分是端上概念。
  static String _reasonLabel(AppLocalizations l10n, ReturnType t) =>
      t.platformPaysReturnShipping ? l10n.returnReasonQuality : l10n.returnReasonNonQuality;

  /// 回程运费归属文案。
  ///
  /// 🔴 优先信**服务端下发的归属**（`PLATFORM` / `BUYER`）—— 拒收、发货前取消等类型
  /// 的归属由服务端算，端上按 `returnType` 推会漏掉这些分支；服务端没给才回落端上推断。
  static String _shipBearerLabel(AppLocalizations l10n, ReturnProgress p) {
    final bearer = p.returnShipBearer;
    final platformPays = bearer != null
        ? bearer.toUpperCase() == 'PLATFORM'
        : (p.returnType?.platformPaysReturnShipping ?? true);
    return platformPays ? l10n.returnShipBearerPlatform : l10n.returnShipBearerBuyer;
  }

  Widget _step(int n, String text, {required bool current, bool last = false}) => Padding(
        padding: EdgeInsets.only(bottom: last ? 0 : 9),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              width: 16,
              height: 16,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: current ? ShopColors.accent : ShopColors.border,
                shape: BoxShape.circle,
              ),
              child: Text('$n',
                  style: ShopText.badge.copyWith(
                      color: current ? ShopColors.surface : const Color(0xFF6B5B8A))),
            ),
            const SizedBox(width: 9),
            Expanded(
              child: Text(text, style: ShopText.body.copyWith(fontSize: 11, height: 1.55)),
            ),
          ],
        ),
      );

  // ---------------------------------------------------------------- 底部条

  Widget _bottomBar(AppLocalizations l10n, ReturnProgress p) => ShopBottomBarActions(
        secondary: ShopButton(
          key: const ValueKey('refundCancelV2'),
          label: l10n.refundConfirmSubmitNo,
          variant: ShopButtonVariant.outlineMuted,
          onTap: _busy ? null : () => Navigator.of(context).maybePop(),
        ),
        primaryFlex: 2,
        primary: ShopButton(
          key: const ValueKey('refundConfirmV2'),
          label: l10n.refundAgreeShipBack,
          variant: _busy ? ShopButtonVariant.disabled : ShopButtonVariant.pay,
          onTap: _busy ? null : () => _confirm(l10n, p),
        ),
      );

  Future<void> _confirm(AppLocalizations l10n, ReturnProgress p) async {
    // 纯 PawCoin 退款没有现金段 → 无需选去向，直接确认。
    if (p.cashRefund > 0 &&
        _destination == CashDestination.toBank &&
        _account.text.trim().isEmpty) {
      showAppToast(context, l10n.refundMethodAccountRequired);
      return;
    }
    Analytics.capture('toko_refund_method_submitted');
    setState(() => _busy = true);
    try {
      if (p.cashRefund > 0) {
        await ref.read(shopReturnRepositoryProvider).chooseCashDestination(
              returnToken: widget.returnToken,
              destination: _destination,
              channel: _destination == CashDestination.toBank ? _channel : null,
              account: _destination == CashDestination.toBank ? _account.text.trim() : null,
              accountHolderName:
                  _destination == CashDestination.toBank ? _holder.text.trim() : null,
            );
      }
      if (!mounted) return;
      ref.invalidate(returnProgressProvider(widget.returnToken));
      showAppToast(context, l10n.refundMethodSaved);
    } catch (_) {
      if (mounted) showAppToast(context, l10n.refundMethodFailed);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Widget _hint(String text) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Text(text, textAlign: TextAlign.center, style: ShopText.body),
        ),
      );
}

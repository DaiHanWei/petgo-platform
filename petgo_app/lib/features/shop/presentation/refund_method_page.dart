import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../data/shop_return_repository.dart';
import '../domain/shop_product.dart';
import '../domain/shop_return.dart';

/// 退款方式选择页（Story 5.8，FR-100A / C-9 / D-8）。
///
/// 🔴 **这一页存在的唯一理由：让用户不会误以为整笔都能退成真钱。**
/// 混合支付的两段各有各的归宿，而用户对此毫无预期 —— 不在退款前讲清楚，
/// 就会在到账后变成客诉。
///
/// 三条硬规则：
/// 1. 🔴 **PawCoin 段不展示「退回真钱」入口** —— <b>不是展示后再拒绝</b>：
///    不给用户产生预期再打破。它标 `Otomatis / tidak ada pilihan lain`。
/// 2. 🔴 **溢价比例与金额一律取自服务端**，前端不得硬编码 ——
///    原型里的 `+5%` / `Rp 1.500` 是示例值不是规格（D-8 的比例与上限仍待财务定）。
/// 3. 🔴 **纯 QRIS 单不出现两段拆分 UI**，行为与既有虚拟商品退款完全一致，无新增。
class RefundMethodPage extends ConsumerStatefulWidget {
  const RefundMethodPage({super.key, required this.returnToken});

  final String returnToken;

  @override
  ConsumerState<RefundMethodPage> createState() => _RefundMethodPageState();
}

class _RefundMethodPageState extends ConsumerState<RefundMethodPage> {
  CashDestination _destination = CashDestination.toBank;
  PayoutChannel _channel = PayoutChannel.bca;
  final TextEditingController _account = TextEditingController();
  final TextEditingController _holder = TextEditingController();
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
      backgroundColor: AppColors.cream,
      appBar: AppBar(title: Text(l10n.refundMethodTitle), backgroundColor: AppColors.cream),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => Center(
          child: Padding(
            padding: const EdgeInsets.all(AppSpacing.xl),
            child: Text(l10n.returnRequestLoadFailed, textAlign: TextAlign.center),
          ),
        ),
        data: (p) => _content(l10n, p),
      ),
      bottomNavigationBar: async.maybeWhen(
        data: (p) => _bottomBar(l10n, p),
        orElse: () => null,
      ),
    );
  }

  Widget _content(AppLocalizations l10n, ReturnProgress p) => ListView(
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
        children: [
          // 🔴 只有混合支付才渲染两段拆分。纯 QRIS 单走既有虚拟商品退款的样子，无新增 UI。
          if (p.isMixed) ...[
            _splitBar(p),
            _pawcoinSegment(l10n, p),
            _cashSegment(l10n, p),
          ] else
            _cashSegment(l10n, p),
          if (p.compensationPremium > 0) _compensationBlock(l10n, p),
          if (p.shipbackReimbursement > 0)
            _amountRow(l10n.refundMethodShipbackReimbursement,
                formatIdr(p.shipbackReimbursement),
                key: const ValueKey('refundShipbackReimbursement')),
          const SizedBox(height: AppSpacing.xl),
        ],
      );

  /// 比例条。🔴 <b>只用于展示</b>：金额一律用服务端给的两段数值，绝不用比例反算
  /// （比例是取整过的展示值，反算等于把误差乘回金额上）。
  Widget _splitBar(ReturnProgress p) => Padding(
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
        child: ClipRRect(
          key: const ValueKey('refundSplitBar'),
          borderRadius: BorderRadius.circular(AppSpacing.xs),
          child: Row(
            children: [
              Expanded(
                flex: (p.coinShare * 1000).round().clamp(1, 999),
                child: Container(height: 10, color: AppColors.mint),
              ),
              Expanded(
                flex: (1000 - (p.coinShare * 1000).round()).clamp(1, 999),
                child: Container(height: 10, color: AppColors.line),
              ),
            ],
          ),
        ),
      );

  /// PawCoin 段。🔴 <b>没有任何可选项</b> —— 这正是本段的设计：
  /// 展示一个「退回真钱」再拒绝，只会让用户觉得被耍了。
  Widget _pawcoinSegment(AppLocalizations l10n, ReturnProgress p) => Padding(
        padding: const EdgeInsets.fromLTRB(
            AppSpacing.lg, AppSpacing.lg, AppSpacing.lg, AppSpacing.xs),
        child: Card(
          margin: EdgeInsets.zero,
          child: ListTile(
            key: const ValueKey('refundPawcoinSegment'),
            title: Text(
                '${l10n.refundMethodPawcoinPart} ${formatIdr(p.coinRefund)} · '
                '${(p.coinShare * 100).round()}%',
                style: const TextStyle(fontWeight: FontWeight.w600)),
            subtitle: Text(
                '${l10n.refundMethodAutomatic} · ${l10n.refundMethodNoOtherOption}',
                key: const ValueKey('refundPawcoinAutomatic'),
                style: const TextStyle(fontSize: 12, color: AppColors.muted)),
          ),
        ),
      );

  /// 现金段：二选一。
  Widget _cashSegment(AppLocalizations l10n, ReturnProgress p) => Padding(
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.symmetric(vertical: AppSpacing.sm),
              child: Text(
                  '${l10n.refundMethodQrisPart} ${formatIdr(p.cashRefund)}'
                  '${p.isMixed ? ' · ${(100 - p.coinShare * 100).round()}%' : ''}',
                  key: const ValueKey('refundCashSegment'),
                  style: const TextStyle(fontWeight: FontWeight.w600)),
            ),
            RadioGroup<CashDestination>(
              groupValue: _destination,
              onChanged: (v) => setState(() => _destination = v ?? _destination),
              child: Column(
                children: [
                  Card(
                    margin: const EdgeInsets.only(bottom: AppSpacing.xs),
                    child: RadioListTile<CashDestination>(
                      key: const ValueKey('refundToBank'),
                      value: CashDestination.toBank,
                      title: Text(l10n.refundMethodToBank),
                      // 🔴 渠道费是服务端权威值，这里只是把它照抄给用户看
                      subtitle: Text(l10n.refundMethodToBankFee,
                          style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                    ),
                  ),
                  Card(
                    margin: const EdgeInsets.only(bottom: AppSpacing.xs),
                    child: RadioListTile<CashDestination>(
                      key: const ValueKey('refundToPawcoin'),
                      value: CashDestination.toPawcoin,
                      title: Text(l10n.refundMethodToPawcoin),
                      subtitle: Text(l10n.refundMethodToPawcoinSub,
                          style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                    ),
                  ),
                ],
              ),
            ),
            if (_destination == CashDestination.toBank) _bankFields(l10n),
            if (_destination == CashDestination.toPawcoin && p.incentivePremium > 0)
              _amountRow(l10n.refundMethodIncentive, formatIdr(p.incentivePremium),
                  key: const ValueKey('refundIncentivePremium')),
          ],
        ),
      );

  Widget _bankFields(AppLocalizations l10n) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          DropdownButtonFormField<PayoutChannel>(
            key: const ValueKey('refundPayoutChannel'),
            initialValue: _channel,
            items: [
              for (final c in PayoutChannel.values)
                DropdownMenuItem(value: c, child: Text('${c.api} · ${formatIdr(c.fee)}')),
            ],
            onChanged: (v) => setState(() => _channel = v ?? _channel),
          ),
          TextField(
            key: const ValueKey('refundAccount'),
            controller: _account,
            maxLength: 40,
            decoration: InputDecoration(labelText: l10n.refundMethodAccount),
          ),
          TextField(
            key: const ValueKey('refundAccountHolder'),
            controller: _holder,
            maxLength: 60,
            decoration: InputDecoration(labelText: l10n.refundMethodAccountHolder),
          ),
        ],
      );

  /// 平台责任补偿溢价 + 它为什么存在。
  ///
  /// 🔴 这段文案是 AC 明列的：说明「PawCoin 不能提现，包括通过退货；因为这是我们的失误，
  /// 额外补偿余额」。金额取自服务端配置，**前端不写死比例**。
  Widget _compensationBlock(AppLocalizations l10n, ReturnProgress p) => Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Container(
          padding: const EdgeInsets.all(AppSpacing.md),
          decoration: BoxDecoration(
            color: AppColors.mintTint,
            borderRadius: BorderRadius.circular(AppSpacing.sm),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(l10n.refundMethodCompensation,
                      style: const TextStyle(fontWeight: FontWeight.w600)),
                  Text('+ ${formatIdr(p.compensationPremium)}',
                      key: const ValueKey('refundCompensationPremium'),
                      style: const TextStyle(
                          fontWeight: FontWeight.w700, color: AppColors.mint600)),
                ],
              ),
              const SizedBox(height: AppSpacing.xxs),
              Text(l10n.refundMethodPawcoinWhy,
                  key: const ValueKey('refundCompensationWhy'),
                  style: const TextStyle(fontSize: 12)),
            ],
          ),
        ),
      );

  Widget _amountRow(String label, String value, {Key? key}) => Padding(
        padding: const EdgeInsets.symmetric(
            horizontal: AppSpacing.lg, vertical: AppSpacing.xxs),
        child: Row(
          key: key,
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(label, style: const TextStyle(fontSize: 13)),
            Text(value, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
          ],
        ),
      );

  Widget _bottomBar(AppLocalizations l10n, ReturnProgress p) => SafeArea(
        child: Container(
          padding: const EdgeInsets.all(AppSpacing.lg),
          decoration: const BoxDecoration(
            color: AppColors.cream,
            border: Border(top: BorderSide(color: AppColors.line)),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(l10n.refundMethodGrandTotal,
                      style: const TextStyle(fontWeight: FontWeight.w600)),
                  Text(formatIdr(p.grandTotal),
                      key: const ValueKey('refundGrandTotal'),
                      style: const TextStyle(
                          fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.mint)),
                ],
              ),
              const SizedBox(height: AppSpacing.sm),
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  key: const ValueKey('refundMethodConfirm'),
                  onPressed: _busy ? null : () => _confirm(l10n),
                  child: Text(l10n.refundMethodConfirm),
                ),
              ),
            ],
          ),
        ),
      );

  Future<void> _confirm(AppLocalizations l10n) async {
    if (_destination == CashDestination.toBank && _account.text.trim().isEmpty) {
      showAppToast(context, l10n.refundMethodAccountRequired);
      return;
    }
    Analytics.capture('toko_refund_method_submitted');
    setState(() => _busy = true);
    try {
      await ref.read(shopReturnRepositoryProvider).chooseCashDestination(
            returnToken: widget.returnToken,
            destination: _destination,
            channel: _destination == CashDestination.toBank ? _channel : null,
            account: _destination == CashDestination.toBank ? _account.text.trim() : null,
            accountHolderName:
                _destination == CashDestination.toBank ? _holder.text.trim() : null,
          );
      if (!mounted) return;
      ref.invalidate(returnProgressProvider(widget.returnToken));
      showAppToast(context, l10n.refundMethodSaved);
    } catch (_) {
      if (mounted) showAppToast(context, l10n.refundMethodFailed);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }
}

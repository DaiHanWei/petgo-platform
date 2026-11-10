/// 退款方式页（v1.4.2 shop Story 9.2 · UI 稿 F14 / F15 / F23，PRD FR-134 ④、架构 D-15）。
///
/// 被取消订单的**现金段**由用户自己选去向：退为 PawCoin（即时）或线下打款到 BCA / OVO / GoPay。
/// PawCoin 段建单时已即时退回，本页只告知、无控件。
///
/// ## 🔴 规则
/// - **数据源 = 订单详情 `refunds[]`**（不新增 GET）；找不到该退款单或 `canChooseDestination=false` → 不可提交。
/// - **不预选**：钱的去向必须用户主动选（线上 `RefundMethodPageV2` 默认 bank，本页不沿用）；F25 重填预选银行 + 上次渠道。
/// - **金额只展示服务端值**（P-1）：补偿取 `compensationIfPawcoin`，与现金段并列、**不相加**；
///   唯一的展示期计算是银行到账额（无服务端净额字段，按 AC3.4 先例 [payoutNetForDisplay]）。提交**不传任何金额**。
/// - 「+ bonus」与补偿说明只在 `compensate && compensationIfPawcoin > 0` 时出现（F23 迟到款 / 用户原因不承诺）。
/// - 🔒 账号 / 户名不进埋点、不进日志、不落本地。
/// - 提交防重复：转圈期间按钮不可点；返回键可用但不取消请求（结果仍刷新详情）。
library;

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/network/problem_detail.dart';
import '../../../core/theme/shop_tokens.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../order/presentation/order_list_controller.dart';
import '../../pawcoin/presentation/pawcoin_controller.dart';
import '../data/shop_order_repository.dart';
import '../domain/shop_order_detail.dart';
import '../domain/shop_product.dart';
import '../domain/shop_return.dart';
import '../presentation/shop_order_header.dart';
import '../presentation/widgets/shop_buttons.dart';
import '../presentation/widgets/shop_pressable.dart';
import '../presentation/widgets/shop_surface.dart';
import 'payout_account_form.dart';

/// `formatIdr` 去掉 `Rp ` 前缀（§6 句式自带「Rp」）。
String _digits(int v) => formatIdr(v).replaceFirst('Rp ', '');

class RefundDestinationPage extends ConsumerStatefulWidget {
  const RefundDestinationPage({
    super.key,
    required this.orderToken,
    required this.refundToken,
    this.presetChannel,
    this.isRetry = false,
  });

  final String orderToken;
  final String refundToken;

  /// F25 重填：上次渠道（`?channel=`）。非空时预选「退到银行」+ 该渠道，账号清空。
  final PayoutChannel? presetChannel;

  /// F25 进入（`?retry=1`），只用于埋点 `is_retry`。
  final bool isRetry;

  @override
  ConsumerState<RefundDestinationPage> createState() => _RefundDestinationPageState();
}

class _RefundDestinationPageState extends ConsumerState<RefundDestinationPage> {
  CashDestination? _destination;
  late final PayoutAccountFormController _form;
  bool _busy = false;

  /// 已提交成功 / 409 → 详情重取后本单变为不可选是预期，不再弹「不可选」。
  bool _leaving = false;
  bool _viewedReported = false;
  bool _unavailableToasted = false;

  @override
  void initState() {
    super.initState();
    final preset = widget.presetChannel;
    _form = PayoutAccountFormController(preset ?? PayoutChannel.bca);
    if (preset != null) _destination = CashDestination.toBank;
  }

  @override
  void dispose() {
    _form.dispose();
    super.dispose();
  }

  ShopOrderRefundSummary? _refundOf(ShopOrderDetail d) {
    for (final r in d.refunds) {
      if (r.refundToken == widget.refundToken) return r;
    }
    return null;
  }

  /// 补偿只在「该原因给补偿」且服务端预估 > 0 时承诺（D-11 同口径：没有的补偿不许承诺）。
  bool _hasBonus(ShopOrderRefundSummary r) => r.compensate && r.compensationIfPawcoin > 0;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(shopOrderDetailProvider(widget.orderToken));
    final refund = async.asData == null ? null : _refundOf(async.asData!.value);
    final usable = refund != null && refund.canChooseDestination;

    return Scaffold(
      backgroundColor: ShopColors.bg,
      appBar: ShopAppBar(title: l10n.refundMethodTitle),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => ShopRetryState(
          message: l10n.refundLoadFailed,
          retryLabel: l10n.commonRetry,
          onRetry: () => ref.invalidate(shopOrderDetailProvider(widget.orderToken)),
        ),
        data: (_) {
          if (!usable) {
            if (_leaving) return const SizedBox.shrink();
            _toastUnavailableOnce(l10n);
            return ShopRetryState(
              key: const ValueKey('refundDestUnavailable'),
              message: l10n.refundLoadFailed,
              retryLabel: MaterialLocalizations.of(context).backButtonTooltip,
              onRetry: () => Navigator.of(context).maybePop(),
            );
          }
          _reportViewedOnce(refund);
          return _content(l10n, refund);
        },
      ),
      bottomNavigationBar: usable && !_leaving ? _bottomBar(l10n, refund) : null,
    );
  }

  void _toastUnavailableOnce(AppLocalizations l10n) {
    if (_unavailableToasted) return;
    _unavailableToasted = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) showAppToast(context, l10n.refundLoadFailed);
    });
  }

  /// 埋点 `reason`：小写 `CancelReason`。🔒 属性一律不带账号 / 姓名（PII）；属性键写在 capture 调用内，
  /// 让 `epic1_analytics_test` 的 PII 守卫能逐个看到。
  String _reasonProp(ShopOrderRefundSummary r) => (r.reason ?? 'unknown').toLowerCase();

  void _reportViewedOnce(ShopOrderRefundSummary r) {
    if (_viewedReported) return;
    _viewedReported = true;
    Analytics.capture('toko_refund_method_viewed', {
      'reason': _reasonProp(r),
      'is_retry': widget.isRetry,
    });
  }

  // ================================================================ 内容

  Widget _content(AppLocalizations l10n, ShopOrderRefundSummary r) {
    final toBank = _destination == CashDestination.toBank;
    final toPawcoin = _destination == CashDestination.toPawcoin;
    // 非惰性滚动：卡片固定 3 张，一次建好（合计行不依赖滚动到位才存在）。
    return SingleChildScrollView(
      padding: const EdgeInsets.only(top: 12, bottom: 12),
      child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (r.coinRefund > 0)
          ShopCard(
            key: const ValueKey('refundDestCoinCard'),
            child: Row(
              children: [
                Expanded(child: Text(l10n.shopRefundCoinPart, style: ShopText.cardTitle)),
                Text(l10n.shopRefundCoinReturned(_digits(r.coinRefund)),
                    style: ShopText.body.copyWith(color: ShopColors.purple, fontWeight: FontWeight.w600)),
              ],
            ),
          ),
        ShopCard(
          key: const ValueKey('refundDestCashCard'),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(l10n.shopRefundQrisPartTo(_digits(r.cashRefund)),
                  key: const ValueKey('refundDestCashTitle'), style: ShopText.cardTitle),
              const SizedBox(height: 10),
              _Option(
                key: const ValueKey('refundDestToPawcoin'),
                label: l10n.refundMethodToPawcoin,
                trailing: _hasBonus(r) ? l10n.shopRefundBonus(_digits(r.compensationIfPawcoin)) : null,
                trailingKey: const ValueKey('refundDestBonus'),
                subtitle: l10n.shopRefundToPawcoinInstant,
                selected: toPawcoin,
                onTap: _busy ? null : () => setState(() => _destination = CashDestination.toPawcoin),
              ),
              const SizedBox(height: 8),
              _Option(
                key: const ValueKey('refundDestToBank'),
                label: l10n.refundMethodToBank,
                subtitle: '${l10n.shopRefundBankEta} · ${l10n.refundMethodToBankFee}',
                selected: toBank,
                onTap: _busy ? null : () => setState(() => _destination = CashDestination.toBank),
              ),
              if (toBank) ...[
                const SizedBox(height: 12),
                PayoutAccountForm(controller: _form),
              ],
              if (toPawcoin && _hasBonus(r)) ...[
                const SizedBox(height: 12),
                Container(
                  key: const ValueKey('refundDestPawcoinWhy'),
                  width: double.infinity,
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: ShopColors.warnBg,
                    borderRadius: BorderRadius.circular(ShopShape.radiusField),
                    border: Border.all(color: ShopColors.warnBorder),
                  ),
                  child: Text(l10n.refundMethodPawcoinWhy,
                      style: ShopText.body.copyWith(color: ShopColors.warnText)),
                ),
              ],
            ],
          ),
        ),
        ShopCard(key: const ValueKey('refundDestTotalCard'), child: _totals(l10n, r)),
      ],
      ),
    );
  }

  /// 合计（AC2.3 / AC3.4）：未选 / 选 PawCoin = 服务端 `cashRefund`（补偿已在选项上并列显示，不相加，
  /// 故不用「termasuk tambahan」）；选银行 = 渠道费 + 到账额（AC3.4 先例）。
  Widget _totals(AppLocalizations l10n, ShopOrderRefundSummary r) {
    final toBank = _destination == CashDestination.toBank;
    return ListenableBuilder(
      listenable: _form,
      builder: (context, _) {
        final fee = _form.channel.fee;
        // 现金段 ≤ 渠道费（后端会 422 refund-below-payout-fee）：合计显示「—」，绝不显示负数 / 0 到账。
        final belowFee = toBank && _belowFee(r);
        final total = toBank ? payoutNetForDisplay(r.cashRefund, fee) : r.cashRefund;
        return Column(
          children: [
            if (belowFee) ...[
              Text(l10n.shopRefundBelowPayoutFee,
                  key: const ValueKey('refundDestBelowFee'),
                  style: ShopText.meta.copyWith(color: ShopColors.errorText)),
              const SizedBox(height: 8),
            ],
            if (toBank) ...[
              Row(
                children: [
                  Expanded(child: Text(l10n.refundFeeLabel, style: ShopText.body)),
                  Text(formatIdr(fee), key: const ValueKey('refundDestFee'), style: ShopText.body),
                ],
              ),
              const SizedBox(height: 8),
            ],
            Row(
              children: [
                Expanded(
                    child: Text(l10n.refundMethodGrandTotalPlain,
                        style: ShopText.cardTitle.copyWith(fontWeight: FontWeight.w700))),
                Text(belowFee ? '—' : formatIdr(total),
                    key: const ValueKey('refundDestTotal'),
                    style: ShopText.priceGrid.copyWith(color: ShopColors.ink)),
              ],
            ),
          ],
        );
      },
    );
  }

  /// 现金段不高于所选渠道费（与后端 `ShopRefundCalculator.payoutNet` 同判据 `cash ≤ fee`）。只用于界面拦截，
  /// 服务端仍会 422 兜底。
  bool _belowFee(ShopOrderRefundSummary r) => r.cashRefund <= _form.channel.fee;

  Widget _bottomBar(AppLocalizations l10n, ShopOrderRefundSummary r) => ListenableBuilder(
        listenable: _form,
        builder: (context, _) => _bottomBarInner(l10n, r),
      );

  Widget _bottomBarInner(AppLocalizations l10n, ShopOrderRefundSummary r) {
    final ready = _destination != null &&
        !(_destination == CashDestination.toBank && _belowFee(r));
    return ShopBottomBarActions(
      primary: ShopButton(
        key: const ValueKey('refundDestSubmit'),
        label: l10n.shopRefundSubmit,
        variant: ready || _busy ? ShopButtonVariant.ink : ShopButtonVariant.disabled,
        loading: _busy,
        onTap: !ready || _busy ? null : () => _submit(l10n, r),
      ),
    );
  }

  // ================================================================ 提交

  Future<void> _submit(AppLocalizations l10n, ShopOrderRefundSummary r) async {
    final destination = _destination;
    if (destination == null || _busy) return;
    final toBank = destination == CashDestination.toBank;
    if (toBank && !_form.validateAll(l10n)) return;

    Analytics.capture('toko_refund_method_submitted', {
      'reason': _reasonProp(r),
      'is_retry': widget.isRetry,
      'destination': toBank ? 'to_bank' : 'to_pawcoin',
      if (toBank) 'channel': _form.channel.api.toLowerCase(),
    });
    setState(() => _busy = true);
    // 返回键可用、不取消请求：页面已 pop 时仍要刷新详情，所以先拿住容器与导航。
    final container = ProviderScope.containerOf(context, listen: false);
    final router = GoRouter.maybeOf(context);
    void refreshAll() {
      container.invalidate(shopOrderDetailProvider(widget.orderToken));
      container.invalidate(orderListProvider);
      container.invalidate(shopOrderCountsProvider);
      if (!toBank) container.invalidate(pawCoinProvider);
    }

    try {
      await container.read(shopOrderRepositoryProvider).chooseRefundDestination(
            widget.orderToken,
            widget.refundToken,
            destination: destination,
            channel: toBank ? _form.channel : null,
            account: toBank ? _form.account : null,
            accountHolderName: toBank ? _form.holder : null,
          );
      _leaving = true;
      refreshAll();
      if (!mounted) return;
      showAppToast(context, l10n.refundMethodSaved);
      _pop(router);
    } catch (e) {
      final status = e is DioException ? e.response?.statusCode : null;
      if (status == 409) {
        // 状态已变（已选过 / 已打款）：刷新详情后回去看最新状态。
        _leaving = true;
        refreshAll();
        if (!mounted) return;
        showAppToast(context, l10n.refundMethodFailed);
        _pop(router);
        return;
      }
      if (!mounted) return;
      if (status == 422) {
        final slug = ProblemDetail.fromDioException(e as DioException)?.typeSlug;
        if (slug == 'refund-below-payout-fee') {
          showAppToast(context, l10n.shopRefundBelowPayoutFee);
        } else {
          _form.setError(PayoutAccountFormController.accountId, l10n.refundAccountNumberInvalid);
        }
      } else {
        showAppToast(context, l10n.refundMethodFailed);
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// 回到订单详情（**pop，不 pushReplacement**：详情仍在栈上，避免返回时叠两层详情）。
  void _pop(GoRouter? router) {
    if (router != null && router.canPop()) {
      router.pop();
    } else {
      Navigator.of(context).maybePop();
    }
  }
}

/// 单选项（新视觉：标题 + 右侧附注 + 副句）。私有，不改共享 `ShopRadioTile` 默认外观。
class _Option extends StatelessWidget {
  const _Option({
    super.key,
    required this.label,
    required this.subtitle,
    required this.selected,
    required this.onTap,
    this.trailing,
    this.trailingKey,
  });

  final String label;
  final String subtitle;
  final String? trailing;
  final Key? trailingKey;
  final bool selected;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) => Semantics(
        inMutuallyExclusiveGroup: true,
        selected: selected,
        button: true,
        child: ShopPressable(
          onTap: onTap,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
            decoration: BoxDecoration(
              color: selected ? ShopColors.accentBg : ShopColors.surface,
              borderRadius: BorderRadius.circular(ShopShape.radiusChip),
              border: Border.all(
                color: selected ? ShopColors.accent : ShopColors.border2,
                width: selected ? 1.5 : 1,
              ),
            ),
            child: Row(
              children: [
                Icon(selected ? Icons.radio_button_checked : Icons.radio_button_unchecked,
                    size: 18, color: selected ? ShopColors.accent : ShopColors.text4),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: Text(label,
                                style: ShopText.body.copyWith(
                                    fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                                    color: ShopColors.text)),
                          ),
                          if (trailing != null)
                            Text(trailing!,
                                key: trailingKey,
                                style: ShopText.body
                                    .copyWith(color: ShopColors.purple, fontWeight: FontWeight.w700)),
                        ],
                      ),
                      const SizedBox(height: 2),
                      Text(subtitle, style: ShopText.meta),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      );
}

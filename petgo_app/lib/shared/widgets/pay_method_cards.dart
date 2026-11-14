import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../l10n/app_localizations.dart';
import 'pay_channel_picker.dart' show formatIdrAmount;

/// 付款方式（V1.5.0 gift-pack · D-13）：PawCoin / 直接付款（当前渠道 QRIS，1.5.2 起更多渠道也放在直接付款里）。
enum PayMethod { pawcoin, direct }

/// 左右两张等宽付款卡（V1.5.0 gift-pack Story 2-2 · D-13，**接口冻结**：送礼包装页与 6-1 `PayChannelPicker` 共用，
/// 不各写一套）。
///
/// - PawCoin 卡：「{n} PawCoin」+ 灰字「Saldo {n}」；余额不足 → **不置灰、不可选**，红字「Saldo {n} · kurang {m}」
///   +「Isi saldo dulu →」，点整张卡回调 [onTopUp]。
/// - 直接付款卡：「Rp{n}」+ 渠道小标（默认 QRIS）；[directDisabledReason] 非空 → 置灰不可选，金额位换成该文案。
/// - 选中：紫描边 + `AppColors.mintTint` 底 + 右下勾。
class PayMethodCards extends StatelessWidget {
  const PayMethodCards({
    required this.coinAmount,
    required this.cashAmount,
    required this.balance,
    required this.selected,
    required this.onSelect,
    required this.onTopUp,
    this.directDisabledReason,
    this.directChannelLabel = 'QRIS',
    super.key,
  });

  /// 所需币数。
  final int coinAmount;

  /// 直接付款金额（Rp）。
  final int cashAmount;

  /// PawCoin 余额。
  final int balance;
  final PayMethod? selected;
  final ValueChanged<PayMethod> onSelect;

  /// 余额不足时点 PawCoin 卡。
  final VoidCallback onTopUp;

  /// 非空 = 直接付款卡置灰并显示该文案（如「Minimal Rp10.000」）。
  final String? directDisabledReason;
  final String directChannelLabel;

  bool get coinShort => balance < coinAmount;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final short = coinShort;
    final directDisabled = directDisabledReason != null;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          child: _Card(
            key: const ValueKey('payMethodCard-pawcoin'),
            selected: selected == PayMethod.pawcoin && !short,
            disabled: false,
            onTap: short ? onTopUp : () => onSelect(PayMethod.pawcoin),
            title: l10n.payMethodPawcoin,
            amount: '${formatIdrAmount(coinAmount)} PawCoin',
            lines: short
                ? [
                    Text(l10n.payMethodBalanceShort(formatIdrAmount(balance), formatIdrAmount(coinAmount - balance)),
                        key: const ValueKey('payMethodShort'),
                        style: const TextStyle(fontSize: 11, color: AppColors.danger, fontWeight: FontWeight.w600)),
                    Text(l10n.triageUnlockTopupFirst,
                        key: const ValueKey('payMethodTopUp'),
                        style: const TextStyle(fontSize: 11, color: AppColors.mint, fontWeight: FontWeight.w700)),
                  ]
                : [
                    Text(l10n.payMethodBalance(formatIdrAmount(balance)),
                        key: const ValueKey('payMethodBalance'),
                        style: const TextStyle(fontSize: 11, color: AppColors.muted)),
                  ],
          ),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: _Card(
            key: const ValueKey('payMethodCard-direct'),
            selected: selected == PayMethod.direct && !directDisabled,
            disabled: directDisabled,
            onTap: directDisabled ? null : () => onSelect(PayMethod.direct),
            title: l10n.payMethodDirect,
            badge: directChannelLabel,
            amount: directDisabledReason ?? 'Rp${formatIdrAmount(cashAmount)}',
            lines: const [],
          ),
        ),
      ],
    );
  }
}

class _Card extends StatelessWidget {
  const _Card({
    super.key,
    required this.selected,
    required this.disabled,
    required this.onTap,
    required this.title,
    required this.amount,
    required this.lines,
    this.badge,
  });

  final bool selected;
  final bool disabled;
  final VoidCallback? onTap;
  final String title;
  final String amount;
  final List<Widget> lines;
  final String? badge;

  @override
  Widget build(BuildContext context) {
    final card = Container(
      constraints: const BoxConstraints(minHeight: 84),
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
      decoration: BoxDecoration(
        color: selected ? AppColors.mintTint : Colors.white,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: selected ? AppColors.mint : AppColors.line, width: selected ? 1.5 : 1),
      ),
      child: Stack(children: [
        Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
          Row(children: [
            Flexible(
              child: Text(title,
                  style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.ink)),
            ),
            if (badge != null) ...[
              const SizedBox(width: 6),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
                decoration: BoxDecoration(
                    color: AppColors.mintTint, borderRadius: BorderRadius.circular(4)),
                child: Text(badge!,
                    style: const TextStyle(fontSize: 9.5, fontWeight: FontWeight.w700, color: AppColors.mint)),
              ),
            ],
          ]),
          const SizedBox(height: 6),
          Text(amount,
              style: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w700,
                  color: AppColors.ink,
                  fontFeatures: [FontFeature.tabularFigures()])),
          const SizedBox(height: 2),
          ...lines,
        ]),
        if (selected)
          const Positioned(
            right: 0,
            bottom: 0,
            child: Icon(Icons.check_circle, key: ValueKey('payMethodCheck'), size: 18, color: AppColors.mint),
          ),
      ]),
    );
    return Semantics(
      button: true,
      selected: selected,
      enabled: !disabled,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: Opacity(opacity: disabled ? 0.45 : 1, child: card),
      ),
    );
  }
}

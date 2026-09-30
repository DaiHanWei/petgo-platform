import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/theme/colors.dart';
import '../../features/profile/domain/id_card.dart';
import '../../l10n/app_localizations.dart';
import 'price_load_retry.dart';

/// 印尼千分位（点分隔）：`5000` → `5.000`。付费抽屉与购买按钮共用，展示口径只有一份。
String formatIdrAmount(int v) {
  final d = v.toString();
  final b = StringBuffer();
  for (var i = 0; i < d.length; i++) {
    if (i > 0 && (d.length - i) % 3 == 0) b.write('.');
    b.write(d[i]);
  }
  return b.toString();
}

/// 一次性付费的「选渠道」抽屉主体（KTP 高清图 Story 6.3 抽出；V1.3.2 Story 3.2 起 Tailsonality / 护照 / 登机牌复用）。
///
/// 放在 `showModalBottomSheet` 里，确认时 `pop(HdPayChannel)`，下滑关闭 → null。
/// PawCoin 余额够则默认选；不够 → PawCoin 行置灰 + 充值链接跳 `/me/pawcoin/recharge`。
/// 价格由调用方传入（后端实时下发、无本地兜底）：失败显示重试，未取到一律禁确认。
class PayChannelPicker extends StatefulWidget {
  const PayChannelPicker({
    super.key,
    required this.header,
    required this.title,
    required this.body,
    required this.price,
    required this.onRetryPrice,
    required this.balance,
    required this.confirmLabel,
    this.confirmKey = const ValueKey('hdPayConfirm'),
    this.retryKey = const ValueKey('hdPriceRetry'),
  });

  final Widget header;
  final String title;
  final String body;
  final AsyncValue<int> price;
  final VoidCallback onRetryPrice;
  final int balance;

  /// 确认按钮文案；入参为当前价（未取到为 null）。
  final String Function(int? price) confirmLabel;
  final Key confirmKey;
  final Key retryKey;

  @override
  State<PayChannelPicker> createState() => _PayChannelPickerState();
}

class _PayChannelPickerState extends State<PayChannelPicker> {
  /// 用户显式点选的渠道；null=按余额够不够自动定（价格加载完成后才有意义）。
  HdPayChannel? _picked;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final priceAsync = widget.price;
    final int? priceIdr = priceAsync.value;
    final enoughCoin = priceIdr != null && widget.balance >= priceIdr;
    final HdPayChannel selected =
        _picked ?? (enoughCoin ? HdPayChannel.pawcoin : HdPayChannel.qris);
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 6, 20, 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Center(
              child: Container(
                width: 36,
                height: 4,
                decoration:
                    BoxDecoration(color: AppColors.line, borderRadius: BorderRadius.circular(9999)),
              ),
            ),
            const SizedBox(height: 16),
            widget.header,
            const SizedBox(height: 16),
            Text(widget.title,
                style: const TextStyle(color: AppColors.ink, fontSize: 18, fontWeight: FontWeight.w700)),
            const SizedBox(height: 6),
            Text(widget.body,
                style: const TextStyle(color: AppColors.ink2, fontSize: 13, height: 1.5)),
            const SizedBox(height: 18),
            if (priceAsync.hasError) ...[
              Center(
                child: PriceLoadRetry(key: widget.retryKey, onRetry: widget.onRetryPrice),
              ),
              const SizedBox(height: 10),
            ],
            _PayMethodTile(
              icon: Icons.savings_outlined,
              title: 'PawCoin',
              subtitle: priceIdr == null
                  ? l10n.idCardHdPawcoinSub(formatIdrAmount(widget.balance), '…')
                  : l10n.idCardHdPawcoinSub(formatIdrAmount(widget.balance), formatIdrAmount(priceIdr)),
              selected: enoughCoin && selected == HdPayChannel.pawcoin,
              enabled: enoughCoin,
              trailingAction: priceIdr == null || enoughCoin ? null : l10n.triageUnlockTopupFirst,
              onTap: enoughCoin
                  ? () => setState(() => _picked = HdPayChannel.pawcoin)
                  : priceIdr == null
                      ? () {}
                      : () {
                          Navigator.of(context).pop();
                          context.push('/me/pawcoin/recharge');
                        },
            ),
            const SizedBox(height: 10),
            _PayMethodTile(
              icon: Icons.qr_code_2,
              title: 'QRIS',
              subtitle: priceIdr == null ? '…' : 'Rp${formatIdrAmount(priceIdr)}',
              selected: selected == HdPayChannel.qris,
              onTap: () => setState(() => _picked = HdPayChannel.qris),
            ),
            const SizedBox(height: 18),
            FilledButton(
              key: widget.confirmKey,
              // 价格未取到（加载中/失败）禁付款：用户必须先看到本次费用（417 同类无兜底）。
              onPressed: priceIdr == null ? null : () => Navigator.of(context).pop(selected),
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.mint,
                foregroundColor: AppColors.onAccent,
                padding: const EdgeInsets.symmetric(vertical: 14),
              ),
              child: Text(widget.confirmLabel(priceIdr),
                  style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
            ),
          ],
        ),
      ),
    );
  }
}

class _PayMethodTile extends StatelessWidget {
  const _PayMethodTile({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.selected,
    required this.onTap,
    this.enabled = true,
    this.trailingAction,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final bool selected;
  final bool enabled;
  final String? trailingAction;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(14),
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
        decoration: BoxDecoration(
          color: selected ? AppColors.mintTint2 : AppColors.surface,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(
              color: selected ? AppColors.mint : AppColors.line, width: selected ? 1.6 : 1.2),
        ),
        child: Row(
          children: [
            Container(
              width: 40,
              height: 40,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: enabled ? AppColors.mintTint : AppColors.cream2,
                borderRadius: BorderRadius.circular(11),
              ),
              child: Icon(icon, size: 20, color: enabled ? AppColors.mint : AppColors.muted),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(title,
                      style: TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.w700,
                          color: enabled ? AppColors.ink : AppColors.textTertiary)),
                  const SizedBox(height: 2),
                  Text(subtitle, style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                ],
              ),
            ),
            if (trailingAction != null) ...[
              const SizedBox(width: 8),
              Text(trailingAction!,
                  style: const TextStyle(
                      fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.mint)),
            ] else if (selected)
              const Icon(Icons.check_circle, size: 22, color: AppColors.mint)
            else
              Icon(Icons.radio_button_unchecked, size: 22, color: AppColors.line),
          ],
        ),
      ),
    );
  }
}

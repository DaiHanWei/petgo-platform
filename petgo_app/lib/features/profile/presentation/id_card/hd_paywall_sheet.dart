import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/price_load_retry.dart';
import '../../data/id_card_repository.dart';
import '../../domain/id_card.dart';

/// HD 付费方式选择底部抽屉（Story 6.3；6-7 多卡详情页复用）。返回选中的 [HdPayChannel]，下滑关闭 → null。
/// PawCoin 余额足则默认选；不足 → 显充值链接跳 /me/pawcoin/recharge。
class HdPaywallSheet extends ConsumerStatefulWidget {
  const HdPaywallSheet(
      {super.key, this.petName, this.serialId, this.cardNo, this.avatarUrl, required this.balance});

  final String? petName;
  final int? serialId;

  /// 新编码身份码（TT 开头 14 位）。非空时展示优先于 [serialId]；旧卡为 null 走旧展示。
  final String? cardNo;
  final String? avatarUrl;
  final int balance;

  @override
  ConsumerState<HdPaywallSheet> createState() => _HdPaywallSheetState();
}

class _HdPaywallSheetState extends ConsumerState<HdPaywallSheet> {
  /// 用户显式点选的渠道；null=按余额够不够自动定（价格加载完成后才有意义）。
  HdPayChannel? _picked;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // 展示价后端实时下发（417 同类修复）；无本地兜底——失败显示重试并禁付款。
    final priceAsync = ref.watch(idCardHdPriceProvider);
    final int? priceIdr = priceAsync.value;
    final enoughCoin = priceIdr != null && widget.balance >= priceIdr;
    final HdPayChannel selected =
        _picked ?? (enoughCoin ? HdPayChannel.pawcoin : HdPayChannel.qris);
    final no = (widget.cardNo?.isNotEmpty == true)
        ? widget.cardNo!
        : (widget.serialId == null ? '----' : widget.serialId!.toString().padLeft(4, '0'));
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
            Container(
              padding: const EdgeInsets.symmetric(vertical: 22, horizontal: 16),
              decoration: BoxDecoration(
                gradient: const LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [AppColors.mint500, AppColors.mint],
                ),
                borderRadius: BorderRadius.circular(16),
              ),
              child: Column(
                children: [
                  const Text('🐾', style: TextStyle(fontSize: 30)),
                  const SizedBox(height: 8),
                  Text('${widget.petName ?? 'Pet'} · No. $no',
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      textAlign: TextAlign.center,
                      style: const TextStyle(
                          color: Colors.white, fontSize: 16, fontWeight: FontWeight.w700)),
                  const SizedBox(height: 4),
                  Text(l10n.idCardHdPreviewSub,
                      style: TextStyle(color: Colors.white.withValues(alpha: 0.8), fontSize: 12)),
                ],
              ),
            ),
            const SizedBox(height: 16),
            Text(l10n.idCardHdPaywallTitle,
                style: const TextStyle(color: AppColors.ink, fontSize: 18, fontWeight: FontWeight.w700)),
            const SizedBox(height: 6),
            Text(l10n.idCardHdPaywallBody,
                style: const TextStyle(color: AppColors.ink2, fontSize: 13, height: 1.5)),
            const SizedBox(height: 18),
            if (priceAsync.hasError) ...[
              Center(
                child: PriceLoadRetry(
                    key: const ValueKey('hdPriceRetry'),
                    onRetry: () => ref.invalidate(idCardHdPriceProvider)),
              ),
              const SizedBox(height: 10),
            ],
            _HdMethodTile(
              icon: Icons.savings_outlined,
              title: 'PawCoin',
              subtitle: priceIdr == null
                  ? l10n.idCardHdPawcoinSub(_fmt(widget.balance), '…')
                  : l10n.idCardHdPawcoinSub(_fmt(widget.balance), _fmt(priceIdr)),
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
            _HdMethodTile(
              icon: Icons.qr_code_2,
              title: 'QRIS',
              subtitle: priceIdr == null ? '…' : 'Rp${_fmt(priceIdr)}',
              selected: selected == HdPayChannel.qris,
              onTap: () => setState(() => _picked = HdPayChannel.qris),
            ),
            const SizedBox(height: 18),
            FilledButton(
              key: const ValueKey('hdPayConfirm'),
              // 价格未取到（加载中/失败）禁付款：用户必须先看到本次费用（417 同类无兜底）。
              onPressed: priceIdr == null ? null : () => Navigator.of(context).pop(selected),
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.mint,
                foregroundColor: AppColors.onAccent,
                padding: const EdgeInsets.symmetric(vertical: 14),
              ),
              child: Text(l10n.idCardHdPayConfirm,
                  style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
            ),
          ],
        ),
      ),
    );
  }

  static String _fmt(int v) {
    final d = v.toString();
    final b = StringBuffer();
    for (var i = 0; i < d.length; i++) {
      if (i > 0 && (d.length - i) % 3 == 0) b.write('.');
      b.write(d[i]);
    }
    return b.toString();
  }
}

class _HdMethodTile extends StatelessWidget {
  const _HdMethodTile({
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

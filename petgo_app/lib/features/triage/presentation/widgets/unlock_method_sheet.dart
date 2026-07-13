import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../pawcoin/presentation/pawcoin_controller.dart';
import '../../data/triage_repository.dart';
import 'triage_paywall.dart';

/// 解锁方式面板（Story 2.4）。并行读免费额度（`/me/free-quota`）+ PawCoin 余额（provider），
/// 按 `remaining>0` / `balance>=price` 判可用性；现金（QRIS/DANA）恒可选。返回所选 [UnlockMethod]（取消=null）。
Future<UnlockMethod?> showUnlockMethodSheet(
  BuildContext context,
  WidgetRef ref, {
  required int priceIdr,
}) async {
  final int balance = ref.read(pawCoinProvider).value?.balance ?? 0;
  FreeQuotaView? quota;
  try {
    quota = await ref.read(triageRepositoryProvider).fetchFreeQuota();
  } catch (_) {
    quota = null; // 拉取失败 → 免费方式按不可用降级（仍可 PawCoin/现金）
  }
  final int remaining = quota?.remaining ?? 0;
  if (!context.mounted) return null;

  return showModalBottomSheet<UnlockMethod>(
    context: context,
    showDragHandle: true,
    builder: (BuildContext ctx) {
      final l10n = AppLocalizations.of(ctx);
      return SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: <Widget>[
              Padding(
                padding: const EdgeInsets.only(bottom: 8, left: 4),
                child: Text(l10n.triageUnlockSheetTitle,
                    style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
              ),
              _MethodTile(
                keyValue: 'unlockMethodFreeQuota',
                icon: Icons.card_giftcard,
                title: l10n.triageUnlockMethodFree,
                subtitle: l10n.triageUnlockFreeRemaining(remaining),
                enabled: remaining > 0,
                onTap: () => Navigator.pop(ctx, UnlockMethod.freeQuota),
              ),
              _MethodTile(
                keyValue: 'unlockMethodPawcoin',
                icon: Icons.savings_outlined,
                title: l10n.triageUnlockMethodPawcoin,
                subtitle: l10n.triageUnlockPawcoinBalance(balance),
                enabled: balance >= priceIdr,
                onTap: () => Navigator.pop(ctx, UnlockMethod.pawcoin),
              ),
              _MethodTile(
                keyValue: 'unlockMethodQris',
                icon: Icons.qr_code_2,
                title: l10n.triageUnlockMethodQris,
                subtitle: formatIdr(priceIdr),
                enabled: true,
                onTap: () => Navigator.pop(ctx, UnlockMethod.qris),
              ),
              _MethodTile(
                keyValue: 'unlockMethodDana',
                icon: Icons.account_balance_wallet_outlined,
                title: l10n.triageUnlockMethodDana,
                subtitle: formatIdr(priceIdr),
                enabled: true,
                onTap: () => Navigator.pop(ctx, UnlockMethod.dana),
              ),
            ],
          ),
        ),
      );
    },
  );
}

class _MethodTile extends StatelessWidget {
  const _MethodTile({
    required this.keyValue,
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.enabled,
    required this.onTap,
  });

  final String keyValue;
  final IconData icon;
  final String title;
  final String subtitle;
  final bool enabled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Opacity(
      opacity: enabled ? 1 : 0.4,
      child: ListTile(
        key: ValueKey(keyValue),
        enabled: enabled,
        leading: Icon(icon, color: enabled ? AppColors.mint : AppColors.muted),
        title: Text(title, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
        subtitle: Text(subtitle, style: const TextStyle(fontSize: 12, color: AppColors.muted)),
        onTap: enabled ? onTap : null,
      ),
    );
  }
}

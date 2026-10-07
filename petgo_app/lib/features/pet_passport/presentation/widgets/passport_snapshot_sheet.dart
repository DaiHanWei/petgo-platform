import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/pay_channel_picker.dart';
import '../../../keepsake/data/keepsake_repository.dart';
import '../../domain/pet_passport.dart';

/// B7「买这一版」抽屉（V1.3.2 Story 3.4 · AC6.3）：贴底、顶部圆角（样式照 KTP 付费抽屉）。
///
/// 「Nanti」只关抽屉、不发任何请求 → 返回 false；「Buka」→ true，调用方进选渠道抽屉。
Future<bool?> showPassportSnapshotSheet(BuildContext context, {required int stampCount, required int price}) {
  return showModalBottomSheet<bool>(
    context: context,
    backgroundColor: AppColors.card,
    isScrollControlled: true,
    shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(24))),
    builder: (ctx) {
      final l10n = AppLocalizations.of(ctx);
      return SafeArea(
        child: Padding(
          key: const ValueKey('passportSnapshotSheet'),
          padding: const EdgeInsets.fromLTRB(20, 6, 20, 20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Center(
                child: Container(
                  width: 36,
                  height: 4,
                  decoration: BoxDecoration(color: AppColors.line, borderRadius: BorderRadius.circular(9999)),
                ),
              ),
              const SizedBox(height: 16),
              Text(l10n.passportSnapshotSheetTitle,
                  style: const TextStyle(color: AppColors.ink, fontSize: 18, fontWeight: FontWeight.w700)),
              const SizedBox(height: 6),
              Text(l10n.passportSnapshotSheetSub(stampCount, formatIdrAmount(price)),
                  key: const ValueKey('passportSnapshotSheetSub'),
                  style: const TextStyle(color: AppColors.ink2, fontSize: 14, fontWeight: FontWeight.w600)),
              const SizedBox(height: 8),
              Text(l10n.passportSnapshotSheetNote,
                  style: const TextStyle(color: AppColors.muted, fontSize: 13, height: 1.5)),
              const SizedBox(height: 18),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      key: const ValueKey('passportSnapshotLater'),
                      style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(48)),
                      onPressed: () => Navigator.of(ctx).pop(false),
                      child: Text(l10n.passportSnapshotSheetLater),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: FilledButton(
                      key: const ValueKey('passportSnapshotBuy'),
                      style: FilledButton.styleFrom(
                          minimumSize: const Size.fromHeight(48),
                          backgroundColor: AppColors.mint,
                          foregroundColor: AppColors.onAccent),
                      onPressed: () => Navigator.of(ctx).pop(true),
                      child: Text(l10n.passportSnapshotSheetBuy),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      );
    },
  );
}

/// 护照快照的选渠道抽屉：通用 [PayChannelPicker] + 护照缩略 + 护照号（Story 3.4 · AC6.3）。
class PassportSnapshotPayPicker extends ConsumerWidget {
  const PassportSnapshotPayPicker({super.key, required this.passport, required this.balance});

  final PetPassport passport;
  final int balance;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    return PayChannelPicker(
      price: ref.watch(keepsakePricingProvider).whenData((p) => p.passportSnapshot),
      onRetryPrice: () => ref.invalidate(keepsakePricingProvider),
      balance: balance,
      title: l10n.passportSnapshotSheetTitle,
      body: l10n.passportSnapshotPayBody(passport.stampCount),
      confirmLabel: (p) => l10n.keepsakePayConfirm(p == null ? '…' : formatIdrAmount(p)),
      confirmKey: const ValueKey('passportSnapshotPayConfirm'),
      retryKey: const ValueKey('passportSnapshotPayRetry'),
      header: Row(
        children: [
          Container(
            width: 48,
            height: 64,
            decoration: BoxDecoration(
              color: AppColors.cream2,
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: AppColors.lineViolet),
            ),
            alignment: Alignment.center,
            child: const Icon(Icons.menu_book_rounded, color: AppColors.mint),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(passport.petName,
                    style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.ink)),
                const SizedBox(height: 2),
                Text(passport.passportNo,
                    style: const TextStyle(
                        fontSize: 13,
                        letterSpacing: 1.2,
                        color: AppColors.ink2,
                        fontFeatures: [FontFeature.tabularFigures()])),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

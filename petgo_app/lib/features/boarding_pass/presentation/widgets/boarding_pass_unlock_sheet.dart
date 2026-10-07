import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/pay_channel_picker.dart';
import '../../../keepsake/data/keepsake_repository.dart';
import '../../../place/presentation/widgets/place_stamp_view.dart';
import '../../domain/boarding_pass.dart';

/// B7b「买这一张」抽屉（V1.3.2 Story 3.5 · AC9.2）。「Nanti」只关、不发请求 → false；「Buka」→ true。
Future<bool?> showBoardingPassUnlockSheet(BuildContext context, {required String placeName, required int price}) {
  return showModalBottomSheet<bool>(
    context: context,
    backgroundColor: AppColors.card,
    isScrollControlled: true,
    shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(24))),
    builder: (ctx) {
      final l10n = AppLocalizations.of(ctx);
      return SafeArea(
        child: Padding(
          key: const ValueKey('boardingPassUnlockSheet'),
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
              Text(l10n.boardingPassSheetTitle,
                  style: const TextStyle(color: AppColors.ink, fontSize: 18, fontWeight: FontWeight.w700)),
              const SizedBox(height: 6),
              Text(l10n.boardingPassSheetSub(placeName, formatIdrAmount(price)),
                  style: const TextStyle(color: AppColors.ink2, fontSize: 14, fontWeight: FontWeight.w600)),
              const SizedBox(height: 8),
              Text(l10n.boardingPassSheetNote,
                  style: const TextStyle(color: AppColors.muted, fontSize: 13, height: 1.5)),
              const SizedBox(height: 18),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      key: const ValueKey('boardingPassLater'),
                      style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(48)),
                      onPressed: () => Navigator.of(ctx).pop(false),
                      child: Text(l10n.boardingPassSheetLater),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: FilledButton(
                      key: const ValueKey('boardingPassBuy'),
                      style: FilledButton.styleFrom(
                          minimumSize: const Size.fromHeight(48),
                          backgroundColor: AppColors.mint,
                          foregroundColor: AppColors.onAccent),
                      onPressed: () => Navigator.of(ctx).pop(true),
                      child: Text(l10n.boardingPassSheetBuy),
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

/// 登机牌的选渠道抽屉：通用 [PayChannelPicker] + 章面缩略 + 场所名。
class BoardingPassPayPicker extends ConsumerWidget {
  const BoardingPassPayPicker({super.key, required this.pass, required this.balance});

  final BoardingPassDetail pass;
  final int balance;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    return PayChannelPicker(
      price: ref.watch(keepsakePricingProvider).whenData((p) => p.boardingPass),
      onRetryPrice: () => ref.invalidate(keepsakePricingProvider),
      balance: balance,
      title: l10n.boardingPassSheetTitle,
      body: l10n.boardingPassPayBody(pass.placeName),
      confirmLabel: (p) => l10n.keepsakePayConfirm(p == null ? '…' : formatIdrAmount(p)),
      confirmKey: const ValueKey('boardingPassPayConfirm'),
      retryKey: const ValueKey('boardingPassPayRetry'),
      header: Row(
        children: [
          PlaceStampView(placeType: pass.placeType, imageUrl: pass.stampImageUrl, size: 56),
          const SizedBox(width: 14),
          Expanded(
            child: Text(pass.placeName,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.ink)),
          ),
        ],
      ),
    );
  }
}

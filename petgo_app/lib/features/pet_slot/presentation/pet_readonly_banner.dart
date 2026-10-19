import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import 'pet_readonly_block.dart';

/// B4 只读横幅（V1.4.0 multi-pet Story 2.4 · AC5.2）：Diary Tab 当前宠物只读时插在页头最前。
///
/// 锁图标 +「Slot sudah berakhir」+「Perpanjang untuk lanjut mencatat.」+「Perpanjang」（→ [openPetSlotRenewal]）。
/// **不放改选入口**（2026-10-15 拍板，改选只在名额管理页）。
/// 挂载一次上报一次 `pet_readonly_banner_shown`（每次进入该宠物的 Diary 视图一次；属性不带宠物名）。
class PetReadonlyBanner extends ConsumerStatefulWidget {
  const PetReadonlyBanner({super.key});

  @override
  ConsumerState<PetReadonlyBanner> createState() => _PetReadonlyBannerState();
}

class _PetReadonlyBannerState extends ConsumerState<PetReadonlyBanner> {
  @override
  void initState() {
    super.initState();
    Analytics.capture('pet_readonly_banner_shown', const <String, Object>{});
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      key: const ValueKey('petReadonlyBanner'),
      padding: const EdgeInsets.fromLTRB(12, 10, 10, 10),
      decoration: BoxDecoration(
        color: AppColors.violet100,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Row(
        children: [
          Container(
            width: 34,
            height: 34,
            alignment: Alignment.center,
            decoration: const BoxDecoration(color: AppColors.card, shape: BoxShape.circle),
            child: const Icon(Icons.lock_rounded, size: 18, color: AppColors.mint),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(l10n.petReadonlyBannerTitle,
                    style: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w700, color: AppColors.ink)),
                const SizedBox(height: 2),
                Text(l10n.petReadonlyBannerBody,
                    style: const TextStyle(fontSize: 12, color: AppColors.ink2)),
              ],
            ),
          ),
          const SizedBox(width: 8),
          FilledButton(
            key: const ValueKey('petReadonlyBannerRenew'),
            onPressed: () => openPetSlotRenewal(context, ref, source: 'banner'),
            style: FilledButton.styleFrom(
              backgroundColor: AppColors.mint,
              foregroundColor: AppColors.onAccent,
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
              minimumSize: const Size(0, 36),
            ),
            child: Text(l10n.petSlotRenew,
                style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700)),
          ),
        ],
      ),
    );
  }
}

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import 'pet_readonly_block.dart';

/// B4 只读横幅（V1.4.0 multi-pet Story 2.4 · AC5.2）：Diary Tab 当前宠物只读时插在标题行「Diary {name}」与宠物卡之间（稿 B4）。
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
    // 稿 B4 `.ro-banner`：琥珀浅底 #FEF3DE、r14、内边距 12；实心锁 22 #C4870B；
    // 标题 13/w700 #5C3D00、正文 12.5 #8A5A00；「Perpanjang」小号紫按钮（12 / 6×12 / r8）。
    return Container(
      key: const ValueKey('petReadonlyBanner'),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppColors.goldTint,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Row(
        children: [
          const Icon(Icons.lock_rounded, size: 22, color: AppColors.petSlotWarn),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(l10n.petReadonlyBannerTitle,
                    style: const TextStyle(
                        fontSize: 13, height: 1.45, fontWeight: FontWeight.w700, color: Color(0xFF5C3D00))),
                Text(l10n.petReadonlyBannerBody,
                    style: const TextStyle(fontSize: 12.5, height: 1.45, color: AppColors.tipsBadgeText)),
              ],
            ),
          ),
          const SizedBox(width: 10),
          FilledButton(
            key: const ValueKey('petReadonlyBannerRenew'),
            onPressed: () => openPetSlotRenewal(context, ref, source: 'banner'),
            style: FilledButton.styleFrom(
              backgroundColor: AppColors.mint,
              foregroundColor: AppColors.onAccent,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              minimumSize: const Size(0, 32),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
            ),
            child: Text(l10n.petSlotRenew,
                style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600)),
          ),
        ],
      ),
    );
  }
}

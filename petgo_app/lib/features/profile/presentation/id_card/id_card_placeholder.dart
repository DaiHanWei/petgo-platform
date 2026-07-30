import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import 'ktp_card.dart';

/// 证件卡「设计中」占位（Story 6.2）。Paspor / Pelajar 两风格视觉本体待用户出图另做，
/// 本 Story 先预留切换位置：同 1600×900 画布内居中「敬请期待」卡，不渲染证件本体。
class IdCardComingSoon extends StatelessWidget {
  const IdCardComingSoon({super.key, required this.styleLabel, required this.comingSoonText});

  final String styleLabel;
  final String comingSoonText;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: kIdCardCanvas.width,
      height: kIdCardCanvas.height,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: AppColors.mintTint,
          borderRadius: BorderRadius.circular(36),
          border: Border.all(color: AppColors.lineViolet, width: 3),
        ),
        child: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(Icons.badge_outlined, size: 130, color: AppColors.mint500),
              const SizedBox(height: 28),
              Text(
                styleLabel,
                style: const TextStyle(
                  color: AppColors.mint,
                  fontSize: 52,
                  fontWeight: FontWeight.w800,
                ),
              ),
              const SizedBox(height: 14),
              Text(
                comingSoonText,
                style: const TextStyle(
                  color: AppColors.ink2,
                  fontSize: 32,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

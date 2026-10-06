import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';

/// 逐字母对照（V1.3.2 Story 2.5 · AC6.2 · 内容设计 §4.3「展示形态」）：两行上下对齐 + 下方一行 ✓ / ✗。
///
/// 相同位主色高亮、不同位置灰；字母列等宽（NFR-8）。**只在配型页页内，不上卡面**。
class TsLetterCompare extends StatelessWidget {
  const TsLetterCompare({super.key, required this.owner4, required this.pet4, required this.petName});

  final String owner4;
  final String pet4;
  final String petName;

  static const double _col = 34;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    Widget letters(String code, String rowKey) => Row(
          children: [
            for (var i = 0; i < 4; i++)
              SizedBox(
                width: _col,
                child: Text(
                  code[i],
                  key: ValueKey('tsCompare_${rowKey}_$i'),
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 22,
                    fontWeight: FontWeight.w900,
                    color: owner4[i] == pet4[i] ? AppColors.mint : AppColors.muted,
                  ),
                ),
              ),
          ],
        );
    Widget label(String s) => SizedBox(
          width: 92,
          child: Text(s,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.ink2)),
        );
    return Container(
      key: const ValueKey('tsLetterCompare'),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppColors.line),
      ),
      child: Column(
        children: [
          Row(children: [label(l10n.tailsonalityMatchYou), letters(owner4, 'owner')]),
          const SizedBox(height: 6),
          Row(children: [label(petName), letters(pet4, 'pet')]),
          const SizedBox(height: 8),
          Row(
            children: [
              const SizedBox(width: 92),
              for (var i = 0; i < 4; i++)
                SizedBox(
                  width: _col,
                  child: Icon(
                    owner4[i] == pet4[i] ? Icons.check_rounded : Icons.close_rounded,
                    key: ValueKey('tsCompareMark_$i'),
                    size: 18,
                    color: owner4[i] == pet4[i] ? AppColors.mint : AppColors.muted,
                  ),
                ),
            ],
          ),
        ],
      ),
    );
  }
}

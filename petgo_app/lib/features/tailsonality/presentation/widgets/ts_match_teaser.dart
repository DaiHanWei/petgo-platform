import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/dashed_rect.dart';
import '../../domain/content/ts_match_copy.dart';
import '../../domain/content/ts_text.dart';

/// 结果页的配型引流模块（V1.3.2 Story 2.4 · AC4 · 内容设计 §4.3「展示形态 ①」）。
///
/// 只比**四字母**（不带能量后缀）。本 story 只有「未配型」形态且 `onTap == null`（不做假跳转）；
/// Story 2.5 传 [ownerLetters] / [sameCount] / [onTap] 接上「已配型」形态与跳转，不改结构。
class TsMatchTeaser extends StatelessWidget {
  const TsMatchTeaser({super.key, required this.petLetters, this.ownerLetters, this.sameCount, this.onTap});

  final String petLetters;
  final String? ownerLetters;
  final int? sameCount;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final owner = ownerLetters;
    final card = Container(
      key: const ValueKey('tsMatchTeaser'),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppColors.lineViolet),
      ),
      child: Row(
        children: [
          Text(petLetters,
              key: const ValueKey('tsMatchTeaserPet'),
              style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w900, color: AppColors.ink, letterSpacing: 1.5)),
          const Padding(
            padding: EdgeInsets.symmetric(horizontal: 10),
            child: Icon(Icons.sync_alt, size: 18, color: AppColors.muted),
          ),
          if (owner == null)
            CustomPaint(
              key: const ValueKey('tsMatchTeaserUnknown'),
              painter: DashedRRectPainter(color: AppColors.dashedViolet, radius: 8),
              child: const SizedBox(
                width: 56,
                height: 30,
                child: Center(
                  child: Text('?', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800, color: AppColors.mint)),
                ),
              ),
            )
          else
            Text(owner,
                key: const ValueKey('tsMatchTeaserOwner'),
                style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w900, color: AppColors.ink, letterSpacing: 1.5)),
          const SizedBox(width: 14),
          Expanded(
            // 已配型：显示档位标签（Story 2.5 · AC7）；未配型：引导文案。
            child: Text(
                owner != null && sameCount != null && kTsMatchTiers[sameCount] != null
                    ? kTsMatchTiers[sameCount]!.name.of(Localizations.localeOf(context))
                    : l10n.tailsonalityMatchTeaserPrompt,
                key: const ValueKey('tsMatchTeaserLabel'),
                style: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w600, color: AppColors.ink2)),
          ),
          if (onTap != null) const Icon(Icons.chevron_right, color: AppColors.muted),
        ],
      ),
    );
    if (onTap == null) return card;
    return Material(
      color: Colors.transparent,
      child: InkWell(borderRadius: BorderRadius.circular(16), onTap: onTap, child: card),
    );
  }
}

import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../l10n/app_localizations.dart';
import '../../domain/timeline_item.dart';

String _dateLabel(DateTime d) =>
    '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

/// 快乐时刻条目：日期 + 照片 + 文字（Story 2.4）。
class HappyMomentTile extends StatelessWidget {
  const HappyMomentTile({super.key, required this.item});

  final TimelineItem item;

  @override
  Widget build(BuildContext context) {
    return Card(
      key: const ValueKey('happyMomentTile'),
      margin: const EdgeInsets.symmetric(vertical: AppSpacing.xs),
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.md),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(_dateLabel(item.date), style: TextStyle(color: AppColors.textTertiary, fontSize: 12)),
            if (item.imageUrls.isNotEmpty) ...[
              const SizedBox(height: AppSpacing.sm),
              SizedBox(
                height: 80,
                child: ListView(
                  scrollDirection: Axis.horizontal,
                  children: [
                    for (final url in item.imageUrls)
                      Padding(
                        padding: const EdgeInsets.only(right: AppSpacing.xs),
                        child: Image.network(url, width: 80, height: 80, fit: BoxFit.cover,
                            errorBuilder: (context, error, stack) =>
                                Container(width: 80, height: 80, color: AppColors.surface)),
                      ),
                  ],
                ),
              ),
            ],
            if (item.text != null && item.text!.isNotEmpty) ...[
              const SizedBox(height: AppSpacing.sm),
              Text(item.text!),
            ],
          ],
        ),
      ),
    );
  }
}

/// 健康事件条目：日期 + 🏥问诊记录标签 + AI 评级 + 症状摘要（Story 2.4 渲染样式，数据 2.5 承接）。
class HealthEventTile extends StatelessWidget {
  const HealthEventTile({super.key, required this.item});

  final TimelineItem item;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Card(
      key: const ValueKey('healthEventTile'),
      margin: const EdgeInsets.symmetric(vertical: AppSpacing.xs),
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.md),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(_dateLabel(item.date), style: TextStyle(color: AppColors.textTertiary, fontSize: 12)),
            const SizedBox(height: AppSpacing.xs),
            Row(
              children: [
                Text(l10n.healthEventLabel, style: const TextStyle(fontWeight: FontWeight.w600)),
                if (item.aiLevel != null) ...[
                  const SizedBox(width: AppSpacing.sm),
                  _LevelChip(level: item.aiLevel!),
                ],
              ],
            ),
            if (item.symptomSummary != null && item.symptomSummary!.isNotEmpty) ...[
              const SizedBox(height: AppSpacing.xs),
              Text(item.symptomSummary!),
            ],
          ],
        ),
      ),
    );
  }
}

class _LevelChip extends StatelessWidget {
  const _LevelChip({required this.level});

  final String level;

  @override
  Widget build(BuildContext context) {
    final color = switch (level) {
      'RED' => Colors.red,
      'YELLOW' => Colors.orange,
      'GREEN' => Colors.green,
      _ => AppColors.textTertiary,
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.sm, vertical: 2),
      decoration: BoxDecoration(color: color.withValues(alpha: 0.15), borderRadius: BorderRadius.circular(8)),
      child: Text(level, style: TextStyle(color: color, fontSize: 12, fontWeight: FontWeight.w600)),
    );
  }
}

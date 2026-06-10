import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../core/theme/shadows.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/app_image.dart';
import '../../../../shared/widgets/design/striped_photo.dart';
import '../../domain/timeline_item.dart';

String _dateLabel(DateTime d) =>
    '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

/// 时间线行：左侧标记点（emoji 圆）+ 右侧卡片（TailTopia Prototype 换肤）。
class _TimelineRow extends StatelessWidget {
  const _TimelineRow({
    required this.markerEmoji,
    required this.markerBg,
    required this.card,
    super.key,
  });

  final String markerEmoji;
  final Color markerBg;
  final Widget card;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 14),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 34,
            height: 34,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: markerBg,
              shape: BoxShape.circle,
              boxShadow: const [BoxShadow(color: AppColors.cream, blurRadius: 0, spreadRadius: 3)],
            ),
            child: Text(markerEmoji, style: const TextStyle(fontSize: 16)),
          ),
          const SizedBox(width: 14),
          Expanded(child: card),
        ],
      ),
    );
  }
}

Widget _cardShell({required Widget child}) => Container(
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(24),
        boxShadow: AppShadows.md,
      ),
      clipBehavior: Clip.antiAlias,
      child: child,
    );

Widget _badge(String label, Color color, Color bg) => Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(999)),
      child: Text(label,
          style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: color)),
    );

/// 第一条永久标签（AC5 🌟）。
Widget _firstStar(String label) => Container(
      margin: const EdgeInsets.only(top: 6),
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
      decoration: BoxDecoration(color: AppColors.goldTint, borderRadius: BorderRadius.circular(999)),
      child: Text('🌟 $label',
          style: const TextStyle(
              fontSize: 11, fontWeight: FontWeight.w800, color: Color(0xFFA9821E))),
    );

/// 快乐时刻条目：标记 🌈 + 卡片（照片 + 日期 + 徽章 + 文字）。
class HappyMomentTile extends StatelessWidget {
  const HappyMomentTile({super.key, required this.item, this.firstLabel});

  final TimelineItem item;

  /// 非空则为第一条快乐时刻，显 🌟 永久标签（AC5）。
  final String? firstLabel;

  @override
  Widget build(BuildContext context) {
    return _TimelineRow(
      key: const ValueKey('happyMomentTile'),
      markerEmoji: '🌈',
      markerBg: AppColors.goldTint,
      card: _cardShell(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (item.imageUrls.isNotEmpty)
              AppImage.widget(item.imageUrls.first,
                  height: 150,
                  width: double.infinity,
                  fit: BoxFit.cover,
                  errorBuilder: (context, error, stack) =>
                      const StripedPhoto(label: 'foto', height: 150, radius: 0)),
            Padding(
              padding: const EdgeInsets.fromLTRB(14, 11, 14, 13),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      // F9：快乐时刻按事件日期显示。
                      Text(_dateLabel(item.displayDate),
                          style: const TextStyle(
                              fontSize: 11.5, color: AppColors.muted, fontWeight: FontWeight.w700)),
                      const SizedBox(width: 8),
                      _badge('Momen Bahagia', const Color(0xFFA9821E), AppColors.goldTint),
                    ],
                  ),
                  if (firstLabel != null)
                    Align(
                        alignment: Alignment.centerLeft,
                        child: KeyedSubtree(
                            key: const ValueKey('firstHappyStar'), child: _firstStar(firstLabel!))),
                  if (item.text != null && item.text!.isNotEmpty) ...[
                    const SizedBox(height: 5),
                    Text(item.text!,
                        style: const TextStyle(fontSize: 14, color: AppColors.ink2, height: 1.5)),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// 健康事件条目：标记 🩺 + 卡片（日期 + 级别徽章 + 摘要）。
class HealthEventTile extends StatelessWidget {
  const HealthEventTile({super.key, required this.item, this.firstLabel});

  final TimelineItem item;

  /// 非空则为第一次问诊记录，显 🌟 永久标签（AC5）。
  final String? firstLabel;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return _TimelineRow(
      key: const ValueKey('healthEventTile'),
      markerEmoji: '🩺',
      markerBg: AppColors.skyTint,
      card: _cardShell(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 11, 14, 13),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Text(_dateLabel(item.date),
                      style: const TextStyle(
                          fontSize: 11.5, color: AppColors.muted, fontWeight: FontWeight.w700)),
                  const SizedBox(width: 8),
                  _badge(l10n.healthEventLabel, AppColors.sky, AppColors.skyTint),
                  if (item.aiLevel != null) ...[
                    const SizedBox(width: 6),
                    _LevelChip(level: item.aiLevel!),
                  ],
                ],
              ),
              if (firstLabel != null)
                Align(
                    alignment: Alignment.centerLeft,
                    child: KeyedSubtree(
                        key: const ValueKey('firstHealthStar'), child: _firstStar(firstLabel!))),
              if (item.symptomSummary != null && item.symptomSummary!.isNotEmpty) ...[
                const SizedBox(height: 5),
                Text(item.symptomSummary!,
                    style: const TextStyle(fontSize: 14, color: AppColors.ink2, height: 1.5)),
              ],
            ],
          ),
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
    final (Color color, Color bg) = switch (level) {
      'RED' => (AppColors.triageRed, Color(0x22C97A7A)),
      'YELLOW' => (AppColors.triageYellow, Color(0x22E0A458)),
      'GREEN' => (AppColors.triageGreen, Color(0x227FB069)),
      _ => (AppColors.muted, AppColors.cream2),
    };
    return _badge(level, color, bg);
  }
}

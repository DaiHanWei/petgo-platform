import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/utils/date_format.dart';
import '../../../../shared/widgets/app_image.dart';
import '../../domain/timeline_item.dart';

/// 时间线条目（paspor.html tentry/hentry 1:1 还原）。
/// 快乐时刻=紧凑横行（日期列+52缩略+标题/副标题）；健康事件=粉底行+等级徽章。

/// 快乐时刻条目（tentry）：日期列 + 52px 缩略 + 标题 + "Momen Bahagia · N foto"。
class HappyMomentTile extends StatelessWidget {
  const HappyMomentTile({super.key, required this.item, this.firstLabel, this.index = 0});

  final TimelineItem item;

  /// 非空则为第一条快乐时刻（debut），紫色加粗标题 + 🌟（AC5）。
  final String? firstLabel;

  /// 用于无图时缩略底色轮换（对齐原型 violet/gold/green 交替）。
  final int index;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final d = item.displayDate;
    final isFirst = firstLabel != null;
    final photoN = item.imageUrls.length;
    final title = isFirst
        ? '🌟 $firstLabel'
        : (item.text != null && item.text!.isNotEmpty ? item.text! : l10n.timelineHappyMoment);
    final sub = isFirst
        ? '${l10n.timelineDebutPhoto} · ${formatDayMonthYear(context, d)}'
        : (photoN > 0
            ? l10n.timelineHappyMomentPhotos(photoN)
            : l10n.timelineHappyMoment);

    return _entryShell(
      key: const ValueKey('happyMomentTile'),
      monthAbbr: formatMonthAbbr(context, d),
      date: d,
      thumb: _thumb(),
      title: title,
      titleKey: isFirst ? const ValueKey('firstHappyStar') : null,
      titleColor: isFirst ? AppColors.mint : AppColors.ink,
      titleWeight: isFirst ? FontWeight.w700 : FontWeight.w500,
      sub: sub,
    );
  }

  Widget _thumb() {
    if (item.imageUrls.isNotEmpty) {
      return ClipRRect(
        borderRadius: BorderRadius.circular(9),
        child: AppImage.widget(item.imageUrls.first,
            width: 52, height: 52, fit: BoxFit.cover, thumbWidth: 160,
            errorBuilder: (c, e, s) => _emojiThumb()),
      );
    }
    return _emojiThumb();
  }

  Widget _emojiThumb() {
    const bgs = [AppColors.skyTint, AppColors.goldTint, AppColors.momenBadgeBg];
    const emojis = ['🐾', '🧶', '☀️'];
    return Container(
      width: 52,
      height: 52,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: firstLabel != null ? AppColors.momenBadgeBg : bgs[index % bgs.length],
        borderRadius: BorderRadius.circular(9),
      ),
      child: Text(firstLabel != null ? '🌟' : emojis[index % emojis.length],
          style: const TextStyle(fontSize: 22)),
    );
  }
}

/// 健康事件条目（hentry）：粉底 #FDE7EB 行 + 🏥 + 深红标题/副标题 + 等级徽章。
class HealthEventTile extends StatelessWidget {
  const HealthEventTile({super.key, required this.item, this.firstLabel});

  final TimelineItem item;
  final String? firstLabel;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final d = item.date;
    return Padding(
      key: const ValueKey('healthEventTile'),
      padding: const EdgeInsets.only(bottom: 9),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 10),
        decoration: BoxDecoration(
          color: AppColors.coralTint,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          children: [
            const Text('🏥', style: TextStyle(fontSize: 18)),
            const SizedBox(width: 9),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('${l10n.timelineAiConsult} — ${formatDayMonth(context, d)}',
                      style: const TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.w600,
                          color: AppColors.healthEventText)),
                  if (item.symptomSummary != null && item.symptomSummary!.isNotEmpty) ...[
                    const SizedBox(height: 1),
                    Text(item.symptomSummary!,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                            fontSize: 11,
                            color: AppColors.healthEventText.withValues(alpha: 0.8))),
                  ],
                ],
              ),
            ),
            if (item.aiLevel != null) ...[
              const SizedBox(width: 9),
              _levelBadge(l10n, item.aiLevel!),
            ],
          ],
        ),
      ),
    );
  }

  Widget _levelBadge(AppLocalizations l10n, String level) {
    final (String text, Color bg) = switch (level) {
      'RED' => ('🔴 ${l10n.triageBadgeRed}', AppColors.popRed),
      'YELLOW' => ('🟡 ${l10n.triageBadgeYellow}', AppColors.popRed),
      'GREEN' => ('🟢 ${l10n.triageBadgeGreen}', AppColors.triageGreen),
      _ => (level, AppColors.muted),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(7)),
      child: Text(text,
          style: const TextStyle(
              fontSize: 10, fontWeight: FontWeight.w700, color: AppColors.onAccent)),
    );
  }
}

/// tentry 外壳：日期列 + 缩略 + 标题/副标题（白底 rounded-13 + 柔阴影）。
Widget _entryShell({
  required Key key,
  required DateTime date,
  required String monthAbbr,
  required Widget thumb,
  required String title,
  Key? titleKey,
  required Color titleColor,
  required FontWeight titleWeight,
  required String sub,
}) {
  return Padding(
    key: key,
    padding: const EdgeInsets.only(bottom: 9),
    child: Container(
      padding: const EdgeInsets.all(11),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(13),
        boxShadow: const [
          BoxShadow(color: Color(0x0D2B2A27), offset: Offset(0, 2), blurRadius: 8),
        ],
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          SizedBox(
            width: 32,
            child: Column(
              children: [
                Text('${date.day}',
                    style: const TextStyle(
                        fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.muted)),
                Text(monthAbbr,
                    style: const TextStyle(
                        fontSize: 10, fontWeight: FontWeight.w500, color: AppColors.muted)),
              ],
            ),
          ),
          const SizedBox(width: 10),
          thumb,
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title,
                    key: titleKey,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(fontSize: 13, fontWeight: titleWeight, color: titleColor)),
                const SizedBox(height: 2),
                Text(sub,
                    style: const TextStyle(fontSize: 11, color: AppColors.muted)),
              ],
            ),
          ),
        ],
      ),
    ),
  );
}

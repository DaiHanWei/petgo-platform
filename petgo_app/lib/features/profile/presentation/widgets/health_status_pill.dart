import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/utils/date_format.dart';
import '../../domain/health_list_item.dart';

/// 健康记录状态胶囊（V1.5.0 Story 3.1 · AC2.3，架构 §7；3.5 的「Konsultasi saya」Kontrol dokter 条目复用）。
///
/// overdue 红（警示图标 +「Melewati jadwal」）/ dueSoon 金（闹钟 +「Berikutnya: 28 Jan」）/ upcoming 紫（日历 + 同上）；
/// done 不出胶囊。每个状态都有图标 + 文字（不只靠颜色）。日期本年不写年、跨年写年。
/// [keyId] 用于测试 key `healthStatus_<status>_<keyId>`。
class HealthStatusPill extends StatelessWidget {
  const HealthStatusPill({super.key, required this.status, this.nextDate, required this.keyId});

  final HealthRecordStatus status;
  final DateTime? nextDate;
  final Object keyId;

  /// 该状态是否会画出胶囊（done / 缺日期 → 不画）。
  static bool shows(HealthRecordStatus status, DateTime? nextDate) => switch (status) {
        HealthRecordStatus.overdue => true,
        HealthRecordStatus.dueSoon || HealthRecordStatus.upcoming => nextDate != null,
        HealthRecordStatus.done => false,
      };

  static String _dateLabel(BuildContext context, DateTime d) =>
      d.year == DateTime.now().year ? formatDayMonth(context, d) : formatDayMonthYear(context, d);

  @override
  Widget build(BuildContext context) {
    if (!shows(status, nextDate)) return const SizedBox.shrink();
    final l10n = AppLocalizations.of(context);
    final (String text, IconData icon, Color bg, Color fg) = switch (status) {
      HealthRecordStatus.overdue => (
          l10n.healthStatusOverdue,
          Icons.error_outline_rounded,
          AppColors.coralTint,
          AppColors.healthEventText,
        ),
      HealthRecordStatus.dueSoon => (
          l10n.healthStatusNext(_dateLabel(context, nextDate!)),
          Icons.alarm_rounded,
          AppColors.goldTint,
          AppColors.tipsBadgeText,
        ),
      _ => (
          l10n.healthStatusNext(_dateLabel(context, nextDate!)),
          Icons.calendar_today_rounded,
          AppColors.violet100,
          AppColors.mint700,
        ),
    };
    return Container(
      key: ValueKey('healthStatus_${status.name}_$keyId'),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(9999)),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 13, color: fg),
          const SizedBox(width: 4),
          Flexible(
            child: Text(
              text,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(color: fg, fontSize: 11.5, fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }
}

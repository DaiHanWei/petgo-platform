import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/app_image.dart';
import '../../data/timeline_repository.dart';
import '../../domain/calendar_month.dart';

/// 成长档案日历视图（Story 2.4 AC5/AC6 · F9）。
///
/// 月历网格，每天一格四态：有快乐时刻→首图缩略图（含健康事件叠右下角 🏥）；仅健康事件→🏥 图标；
/// 无记录→日期数字 + 淡「+」引导；**未来日→灰显不可点**。月份顶部 + 左右切月。
/// 点有记录格 → [onOpenDay]；点无记录格「+」→ [onAddOnDate]（跳发布预填该日，AC6）。
class ArchiveCalendar extends ConsumerStatefulWidget {
  const ArchiveCalendar({super.key, required this.onOpenDay, required this.onAddOnDate});

  final void Function(DateTime date) onOpenDay;
  final void Function(DateTime date) onAddOnDate;

  @override
  ConsumerState<ArchiveCalendar> createState() => _ArchiveCalendarState();
}

class _ArchiveCalendarState extends ConsumerState<ArchiveCalendar> {
  late int _year;
  late int _month;

  @override
  void initState() {
    super.initState();
    final now = DateTime.now();
    _year = now.year;
    _month = now.month;
  }

  void _shiftMonth(int delta) {
    setState(() {
      final m = _month + delta;
      if (m < 1) {
        _month = 12;
        _year -= 1;
      } else if (m > 12) {
        _month = 1;
        _year += 1;
      } else {
        _month = m;
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final monthAsync = ref.watch(calendarMonthProvider((year: _year, month: _month)));
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _monthHeader(context),
        const SizedBox(height: 6),
        _weekdayRow(context),
        const SizedBox(height: 6),
        monthAsync.when(
          loading: () => const Padding(
              padding: EdgeInsets.all(28), child: Center(child: CircularProgressIndicator())),
          error: (e, _) => _CalendarError(
              onRetry: () =>
                  ref.invalidate(calendarMonthProvider((year: _year, month: _month)))),
          data: _grid,
        ),
      ],
    );
  }

  /// 表头（bug 20260623-047 统一风格）：月份名紫色标题（本地化「July 2026」）+ 右侧紫色左右切月箭头，
  /// 对齐 date_picker_plus 观感。
  Widget _monthHeader(BuildContext context) {
    final locale = Localizations.localeOf(context).toString();
    final title = DateFormat.yMMMM(locale).format(DateTime(_year, _month));
    return Row(
      children: [
        IconButton(
          key: const ValueKey('calPrevMonth'),
          onPressed: () => _shiftMonth(-1),
          icon: const Icon(Icons.chevron_left_rounded, color: AppColors.mint),
        ),
        Expanded(
          child: Text(title,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.mint)),
        ),
        IconButton(
          key: const ValueKey('calNextMonth'),
          onPressed: () => _shiftMonth(1),
          icon: const Icon(Icons.chevron_right_rounded, color: AppColors.mint),
        ),
      ],
    );
  }

  /// 周头行（周日起始，与 date_picker_plus 一致）：本地化短名大写。
  Widget _weekdayRow(BuildContext context) {
    final locale = Localizations.localeOf(context).toString();
    final sunday = DateTime(2024, 1, 7); // 已知周日
    return Row(
      children: [
        for (var i = 0; i < 7; i++)
          Expanded(
            child: Text(
              DateFormat.E(locale).format(sunday.add(Duration(days: i))).toUpperCase(),
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppColors.muted),
            ),
          ),
      ],
    );
  }

  Widget _grid(CalendarMonth month) {
    final byDay = month.byDay;
    final first = DateTime(_year, _month, 1);
    final daysInMonth = DateTime(_year, _month + 1, 0).day;
    final leadingBlanks = first.weekday % 7; // Sun-first（与周头行 + date_picker_plus 一致）
    final today = DateTime.now();
    final todayDate = DateTime(today.year, today.month, today.day);

    final cells = <Widget>[];
    for (var i = 0; i < leadingBlanks; i++) {
      cells.add(const SizedBox.shrink());
    }
    for (var day = 1; day <= daysInMonth; day++) {
      final date = DateTime(_year, _month, day);
      final isFuture = date.isAfter(todayDate);
      cells.add(_dayCell(date, day, byDay[day], isFuture));
    }

    return GridView.count(
      crossAxisCount: 7,
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      mainAxisSpacing: 6,
      crossAxisSpacing: 6,
      children: cells,
    );
  }

  Widget _dayCell(DateTime date, int day, CalendarDayCell? cell, bool isFuture) {
    // 未来日：灰显不可点（AC6）。
    if (isFuture) {
      return Opacity(
        opacity: 0.35,
        child: Container(
          key: ValueKey('calDayFuture_$day'),
          alignment: Alignment.center,
          decoration: BoxDecoration(
              color: AppColors.card, borderRadius: BorderRadius.circular(10)),
          child: Text('$day',
              style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.muted)),
        ),
      );
    }
    // 有记录日：点击进当天详情。
    if (cell != null) {
      return GestureDetector(
        key: ValueKey('calDayRecord_$day'),
        onTap: () => widget.onOpenDay(date),
        child: Stack(
          fit: StackFit.expand,
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(10),
              child: cell.firstImageUrl != null
                  ? AppImage.widget(cell.firstImageUrl!,
                      fit: BoxFit.cover, thumbWidth: 200, // 日历格小图
                      errorBuilder: (_, _, _) => _healthOnlyBox(day))
                  : _healthOnlyBox(day),
            ),
            if (cell.hasHealthEvent)
              const Positioned(right: 2, bottom: 2, child: Text('🏥', style: TextStyle(fontSize: 11))),
          ],
        ),
      );
    }
    // 无记录日：日期 + 淡「+」，点击跳发布预填该日（AC6）。
    return GestureDetector(
      key: ValueKey('calDayEmpty_$day'),
      onTap: () => widget.onAddOnDate(date),
      child: Container(
        alignment: Alignment.center,
        decoration:
            BoxDecoration(color: AppColors.card, borderRadius: BorderRadius.circular(10)),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text('$day', style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.ink2)),
            const Text('+', style: TextStyle(fontSize: 13, color: AppColors.line)),
          ],
        ),
      ),
    );
  }

  Widget _healthOnlyBox(int day) => Container(
        color: AppColors.skyTint,
        alignment: Alignment.center,
        child: const Text('🏥', style: TextStyle(fontSize: 16)),
      );
}

class _CalendarError extends StatelessWidget {
  const _CalendarError({required this.onRetry});

  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      key: const ValueKey('calendarError'),
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(color: AppColors.card, borderRadius: BorderRadius.circular(12)),
      child: Column(
        children: [
          Text(l10n.growthLoadFailed, style: const TextStyle(color: AppColors.muted)),
          const SizedBox(height: 8),
          TextButton(onPressed: onRetry, child: Text(l10n.growthLoadRetry)),
        ],
      ),
    );
  }
}

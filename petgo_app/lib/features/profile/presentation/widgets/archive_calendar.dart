import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

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
        _monthHeader(),
        const SizedBox(height: 10),
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

  Widget _monthHeader() {
    return Row(
      children: [
        IconButton(
          key: const ValueKey('calPrevMonth'),
          onPressed: () => _shiftMonth(-1),
          icon: const Icon(Icons.chevron_left_rounded),
        ),
        Expanded(
          child: Text('$_year-${_month.toString().padLeft(2, '0')}',
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w900)),
        ),
        IconButton(
          key: const ValueKey('calNextMonth'),
          onPressed: () => _shiftMonth(1),
          icon: const Icon(Icons.chevron_right_rounded),
        ),
      ],
    );
  }

  Widget _grid(CalendarMonth month) {
    final byDay = month.byDay;
    final first = DateTime(_year, _month, 1);
    final daysInMonth = DateTime(_year, _month + 1, 0).day;
    final leadingBlanks = first.weekday - 1; // Mon-first
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
              style: const TextStyle(fontSize: 12, color: AppColors.muted)),
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
                      errorBuilder: (_, _, _) => _iconBox(cell))
                  : _iconBox(cell),
            ),
            // 有 diary 图时右下角叠角标（bug 20260722-352）：问诊优先，其次健康记录分类图标。
            if (cell.firstImageUrl != null && (cell.hasHealthEvent || cell.healthRecordType != null))
              Positioned(right: 2, bottom: 2, child: _cornerIcon(cell)),
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
            Text('$day', style: const TextStyle(fontSize: 12, color: AppColors.ink2)),
            const Text('+', style: TextStyle(fontSize: 13, color: AppColors.line)),
          ],
        ),
      ),
    );
  }

  /// 无 diary 图时的整格图标（bug 20260722-352）：优先级 问诊 🏥 > 健康记录分类图标。
  Widget _iconBox(CalendarDayCell cell) {
    if (cell.hasHealthEvent) {
      return Container(
        color: AppColors.skyTint,
        alignment: Alignment.center,
        child: const Text('🏥', style: TextStyle(fontSize: 16)),
      );
    }
    final ({IconData icon, Color color})? cat = _healthCatIcon(cell.healthRecordType);
    if (cat != null) {
      return Container(
        color: cat.color.withValues(alpha: 0.12),
        alignment: Alignment.center,
        child: Icon(cat.icon, size: 16, color: cat.color),
      );
    }
    // 兜底（快乐时刻无图）：淡底 🐾。
    return Container(
      color: AppColors.skyTint,
      alignment: Alignment.center,
      child: const Text('🐾', style: TextStyle(fontSize: 15)),
    );
  }

  /// 有 diary 图时的右下角小角标：问诊 🏥 > 健康记录分类图标。
  Widget _cornerIcon(CalendarDayCell cell) {
    if (cell.hasHealthEvent) {
      return const Text('🏥', style: TextStyle(fontSize: 11));
    }
    final ({IconData icon, Color color})? cat = _healthCatIcon(cell.healthRecordType);
    if (cat != null) {
      return Container(
        padding: const EdgeInsets.all(1.5),
        decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(4)),
        child: Icon(cat.icon, size: 11, color: cat.color),
      );
    }
    return const SizedBox.shrink();
  }

  /// 健康记录分类 → 图标/色（与健康记录页 health_list_page 分类卡一致，bug 20260722-352）。
  ({IconData icon, Color color})? _healthCatIcon(String? type) => switch (type) {
        'VACCINE' => (icon: Icons.vaccines_outlined, color: AppColors.coral),
        'DEWORM' => (icon: Icons.medication_outlined, color: AppColors.triageGreen),
        'NEUTER' => (icon: Icons.healing_outlined, color: AppColors.mint),
        'MENSTRUATION' => (icon: Icons.water_drop_outlined, color: AppColors.infoBlue),
        'CUSTOM' => (icon: Icons.description_outlined, color: AppColors.muted),
        _ => null,
      };
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

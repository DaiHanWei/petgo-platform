import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/utils/date_format.dart';
import '../../../../shared/widgets/app_image.dart';
import '../../data/timeline_repository.dart';
import '../../domain/calendar_month.dart';
import '../../domain/health_record_icons.dart';

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
        _weekdayRow(),
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

  Widget _monthHeader() {
    return Row(
      children: [
        IconButton(
          key: const ValueKey('calPrevMonth'),
          onPressed: () => _shiftMonth(-1),
          icon: const Icon(Icons.chevron_left_rounded),
        ),
        Expanded(
          // A7 稿的 `cal-title` 是「Agustus 2026」这样的本地化月名；此前直接拼 `2026-08`，
          // 在一屏全是印尼语文案里显得像调试输出（2026-08-04 用户实机反馈）。
          child: Text(formatMonthYear(context, DateTime(_year, _month)),
              key: const ValueKey('calMonthTitle'),
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

  /// 星期表头（A7 稿 `cal-weekrow`）：**周日起头**的 7 个窄写星期名（M S S R K J S），
  /// 与下方网格同列宽、同起始日。此前整行缺失，导致「1 号落在第几列」全靠数格子。
  ///
  /// ⚠️ 起始日与 [_grid] 的 `leadingBlanks` **必须同改**，否则表头与日期整列错位。
  Widget _weekdayRow() {
    // 2024-01-07 是周日 —— 只用来取本地化的星期名，与当前显示月份无关。
    final sunday = DateTime(2024, 1, 7);
    return Padding(
      key: const ValueKey('calWeekdayRow'),
      padding: const EdgeInsets.only(bottom: 2),
      child: Row(
        children: List.generate(7, (i) {
          return Expanded(
            child: Text(
              formatWeekdayNarrow(context, sunday.add(Duration(days: i))),
              textAlign: TextAlign.center,
              style: const TextStyle(
                  fontSize: 11, fontWeight: FontWeight.w700, color: AppColors.muted),
            ),
          );
        }),
      ),
    );
  }

  Widget _grid(CalendarMonth month) {
    final byDay = month.byDay;
    final first = DateTime(_year, _month, 1);
    final daysInMonth = DateTime(_year, _month + 1, 0).day;
    // **周日起头**（2026-08-04 用户拍板，与 A7 稿的表头字母序一致）：
    // Dart 的 weekday 是 Mon=1…Sun=7，取模 7 即得「周日为第 0 列」的列号。
    // 与 [_weekdayRow] 的起始日绑定，改一处必须改另一处。
    final leadingBlanks = first.weekday % 7;
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
              // 优先级① diary 带图 → 首图铺满格子，**不再叠加任何角标**（Story 3.4 · AC2 覆盖
              // FR-37 原「照片 + 🏥 角标叠加」）。图片加载失败 → 降级为通用 diary 标记，
              // **绝不回退问诊图标**（AC3）。
              child: cell.firstImageUrl != null
                  ? AppImage.widget(cell.firstImageUrl!,
                      fit: BoxFit.cover, thumbWidth: 200, // 日历格小图
                      errorBuilder: (_, _, _) => _markerBox(kDiaryGenericIcon))
                  : _iconBox(cell),
            ),
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

  /// 无 diary 图时的整格标记（Story 3.4 · AC2 五级优先级，**只显一个**）：
  ///
  /// ② 有 diary 但全无图（纯文字日记）→ 通用 diary 标记；
  /// ③ 无 diary 有问诊 → `local_hospital_outlined`；
  /// ④ 只有结构化健康记录 → 单条用类型图标、**多条用通用医疗箱**。
  ///
  /// ⚠️ **与时间线的优先级方向相反，且刻意不对齐**（AD-16）：时间线逐条分类（同一天既有带图日记
  /// 又有疫苗记录 → 出两条），日历整天取一个代表标记（→ 只显日记首图）。粒度不同所以规则不同，
  /// **不得为了「统一」而把两边对齐** —— 那会让日历失去「一眼扫全月」的作用。
  ///
  /// ⚠️ 判定②用的是 `hasHappyMoment` 而非 `firstImageUrl`：只看首图会让纯文字日记掉到问诊图标，
  /// 那正是本 Story 要修的现网缺陷（AC3）。
  Widget _iconBox(CalendarDayCell cell) {
    // ② 纯文字日记（有 diary、无图）
    if (cell.hasHappyMoment) {
      return _markerBox(kDiaryGenericIcon);
    }
    // ③ 问诊 / AI 健康事件
    if (cell.hasHealthEvent) {
      return _markerBox(healthRecordIconFor('CONSULT'));
    }
    // ④ 结构化健康记录：多条 → 通用医疗箱（不可用 💊，驱虫已占用）
    if (cell.healthRecordType != null || cell.healthRecordCount > 0) {
      return _markerBox(cell.healthRecordCount > 1
          ? kHealthRecordGenericIcon
          : healthRecordIconFor(cell.healthRecordType));
    }
    // 防御：后端返回了记录日但三类信号皆空 → 用 diary 通用标记兜底，**不回退问诊图标**。
    return _markerBox(kDiaryGenericIcon);
  }

  /// 单一标记格：类型主色的浅底 + 描边图标（全表统一，无字面 emoji）。
  Widget _markerBox(HealthRecordIcon marker) => Container(
        color: marker.color.withValues(alpha: 0.12),
        alignment: Alignment.center,
        child: Icon(marker.icon, size: 16, color: marker.color),
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

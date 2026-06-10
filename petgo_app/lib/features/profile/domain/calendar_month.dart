/// 成长档案日历月视图（Story 2.4 AC5/AC6 · F9）。仅含**有记录**的日格子。
class CalendarMonth {
  const CalendarMonth({required this.year, required this.month, required this.days});

  final int year;
  final int month;
  final List<CalendarDayCell> days;

  /// day → 格子（便于按日号查找；无记录日不在表中）。
  Map<int, CalendarDayCell> get byDay => {for (final d in days) d.day: d};

  factory CalendarMonth.fromJson(Map<String, dynamic> json) {
    final raw = json['days'];
    return CalendarMonth(
      year: json['year'] as int,
      month: json['month'] as int,
      days: raw is List
          ? raw.map((e) => CalendarDayCell.fromJson((e as Map).cast<String, dynamic>())).toList()
          : const [],
    );
  }
}

/// 单日格子（仅有记录日返回）。
class CalendarDayCell {
  const CalendarDayCell({
    required this.day,
    this.firstImageUrl,
    this.hasHappyMoment = false,
    this.hasHealthEvent = false,
  });

  final int day;

  /// 该日最早 created_at 快乐时刻首图（无则 null）。
  final String? firstImageUrl;
  final bool hasHappyMoment;
  final bool hasHealthEvent;

  factory CalendarDayCell.fromJson(Map<String, dynamic> json) => CalendarDayCell(
        day: json['day'] as int,
        firstImageUrl: json['firstImageUrl'] as String?,
        hasHappyMoment: (json['hasHappyMoment'] ?? false) as bool,
        hasHealthEvent: (json['hasHealthEvent'] ?? false) as bool,
      );
}

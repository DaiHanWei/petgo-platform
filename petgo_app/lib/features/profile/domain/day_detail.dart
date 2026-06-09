import 'timeline_item.dart';

/// 当天详情（Story 2.4 AC6 · F9）。某事件日期当天快乐时刻 + 健康事件，created_at 正序。
class DayDetail {
  const DayDetail({required this.date, required this.items});

  final DateTime date;
  final List<TimelineItem> items;

  factory DayDetail.fromJson(Map<String, dynamic> json) {
    final raw = json['items'];
    return DayDetail(
      date: DateTime.parse(json['date'] as String),
      items: raw is List
          ? raw.map((e) => TimelineItem.fromJson((e as Map).cast<String, dynamic>())).toList()
          : const [],
    );
  }
}

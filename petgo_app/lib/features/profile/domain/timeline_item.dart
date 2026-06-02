/// 成长时间线条目类型。
enum TimelineKind { happyMoment, healthEvent, unknown }

/// 成长时间线条目（后端 `TimelineItemResponse` 客户端模型）。两类合并倒序。
class TimelineItem {
  const TimelineItem({
    required this.kind,
    required this.date,
    this.postId,
    this.imageUrls = const [],
    this.text,
    this.aiLevel,
    this.symptomSummary,
  });

  final TimelineKind kind;
  final DateTime date;

  // 快乐时刻字段
  final int? postId;
  final List<String> imageUrls;
  final String? text;

  // 健康事件字段
  final String? aiLevel;
  final String? symptomSummary;

  factory TimelineItem.fromJson(Map<String, dynamic> json) {
    final rawImages = json['imageUrls'];
    return TimelineItem(
      kind: _parseKind(json['kind'] as String?),
      date: DateTime.parse(json['date'] as String),
      postId: json['postId'] as int?,
      imageUrls: rawImages is List ? rawImages.map((e) => e.toString()).toList() : const [],
      text: json['text'] as String?,
      aiLevel: json['aiLevel'] as String?,
      symptomSummary: json['symptomSummary'] as String?,
    );
  }

  static TimelineKind _parseKind(String? raw) {
    switch (raw) {
      case 'HAPPY_MOMENT':
        return TimelineKind.happyMoment;
      case 'HEALTH_EVENT':
        return TimelineKind.healthEvent;
      default:
        return TimelineKind.unknown;
    }
  }
}

/// 时间线分页（游标）。
class TimelinePage {
  const TimelinePage({required this.items, this.nextCursor, this.hasMore = false});

  final List<TimelineItem> items;
  final String? nextCursor;
  final bool hasMore;

  factory TimelinePage.fromJson(Map<String, dynamic> json) {
    final rawItems = json['items'];
    return TimelinePage(
      items: rawItems is List
          ? rawItems.map((e) => TimelineItem.fromJson((e as Map).cast<String, dynamic>())).toList()
          : const [],
      nextCursor: json['nextCursor'] as String?,
      hasMore: (json['hasMore'] ?? false) as bool,
    );
  }
}

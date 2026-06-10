/// 成长时间线条目类型。
enum TimelineKind { happyMoment, healthEvent, unknown }

/// 成长时间线条目（后端 `TimelineItemResponse` 客户端模型）。两类合并倒序。
class TimelineItem {
  const TimelineItem({
    required this.kind,
    required this.date,
    this.eventDate,
    this.postId,
    this.imageUrls = const [],
    this.text,
    this.aiLevel,
    this.symptomSummary,
  });

  final TimelineKind kind;

  /// 发生/创建时刻（createdAt）；兼作游标与健康事件显示日期。
  final DateTime date;

  /// 成长日历事件日期（F9，仅快乐时刻有值）；为空回退 [date]。决定时间线显示与排序位置。
  final DateTime? eventDate;

  // 快乐时刻字段
  final int? postId;
  final List<String> imageUrls;
  final String? text;

  // 健康事件字段
  final String? aiLevel;
  final String? symptomSummary;

  /// 时间线显示日期：快乐时刻取事件日期（F9），健康事件取发生时刻。
  DateTime get displayDate => eventDate ?? date;

  factory TimelineItem.fromJson(Map<String, dynamic> json) {
    final rawImages = json['imageUrls'];
    final rawEvent = json['eventDate'] as String?;
    return TimelineItem(
      kind: _parseKind(json['kind'] as String?),
      date: DateTime.parse(json['date'] as String),
      eventDate: rawEvent != null ? DateTime.parse(rawEvent) : null,
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

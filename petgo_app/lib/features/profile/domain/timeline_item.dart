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
    this.sourceType,
    this.sourceRef,
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

  /// 健康事件来源：`AI_TRIAGE` / `VET_CONSULT`（区分 AI 分诊与兽医问诊，bug 20260702-231）。
  final String? sourceType;

  /// 健康事件来源引用（问诊/会话 token，幂等键）；据此深链到对应结果页（bug 20260706-259）。
  /// 后端形如 `<前缀>:<数字 id>`——AI 分诊 `triage:<triageId>`、兽医问诊 `consult:<sessionId>`。
  final String? sourceRef;

  /// 健康事件是否为兽医问诊（否则按 AI 分诊显示）。
  bool get isVetConsult => sourceType == 'VET_CONSULT';

  /// 点击健康事件应跳转的路由（bug 20260706-259）；无 [sourceRef]、未知来源或 id 非法则返回 null（不可点）。
  /// 兽医问诊 → `/consult/conversation/<sessionId>`；AI 分诊 → `/triage/result/<triageId>`。
  /// 两条目标路由都对 id 做 `int.parse`，故须剥掉 `triage:`/`consult:` 前缀、取纯数字段。
  String? get healthEventRoute {
    final ref = sourceRef;
    if (ref == null || ref.isEmpty) return null;
    final id = ref.contains(':') ? ref.substring(ref.lastIndexOf(':') + 1) : ref;
    if (id.isEmpty || int.tryParse(id) == null) return null;
    // from=diary：从 diary 进入，会话页返回应回 diary 而非 /triage(Health) Tab（bug 20260721-336）。
    if (sourceType == 'VET_CONSULT') return '/consult/conversation/$id?from=diary';
    if (sourceType == 'AI_TRIAGE') return '/triage/result/$id';
    return null;
  }

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
      sourceType: json['sourceType'] as String?,
      sourceRef: json['sourceRef'] as String?,
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

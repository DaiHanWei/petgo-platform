/// 问诊历史条目（对应后端 `ConsultHistoryItem`，Story 5.8）。AI / 兽医两类。
class ConsultHistoryItem {
  const ConsultHistoryItem({
    required this.type,
    required this.date,
    this.triageId,
    this.dangerLevel,
    this.symptomSummary,
    this.sessionId,
    this.vetDisplayName,
    this.sessionSummary,
    this.userStars,
    this.archived,
    this.terminalState,
  });

  final String type; // AI | VET
  final DateTime? date;
  // AI
  final int? triageId;
  final String? dangerLevel;
  final String? symptomSummary;
  // VET
  final int? sessionId;
  final String? vetDisplayName;
  final String? sessionSummary;
  final int? userStars;
  final bool? archived;
  final String? terminalState; // CLOSED | INTERRUPTED

  bool get isAi => type == 'AI';

  factory ConsultHistoryItem.fromJson(Map<String, dynamic> json) => ConsultHistoryItem(
        type: (json['type'] ?? 'AI') as String,
        date: json['date'] == null ? null : DateTime.tryParse(json['date'] as String),
        triageId: (json['triageId'] as num?)?.toInt(),
        dangerLevel: json['dangerLevel'] as String?,
        symptomSummary: json['symptomSummary'] as String?,
        sessionId: (json['sessionId'] as num?)?.toInt(),
        vetDisplayName: json['vetDisplayName'] as String?,
        sessionSummary: json['sessionSummary'] as String?,
        userStars: (json['userStars'] as num?)?.toInt(),
        archived: json['archived'] as bool?,
        terminalState: json['terminalState'] as String?,
      );
}

/// 问诊历史分页（对应后端 `ConsultHistoryPage`）。
class ConsultHistoryPage {
  const ConsultHistoryPage({required this.items, this.nextCursor, required this.hasMore});

  final List<ConsultHistoryItem> items;
  final String? nextCursor;
  final bool hasMore;

  factory ConsultHistoryPage.fromJson(Map<String, dynamic> json) => ConsultHistoryPage(
        items: (json['items'] as List? ?? [])
            .map((e) => ConsultHistoryItem.fromJson((e as Map).cast<String, dynamic>()))
            .toList(),
        nextCursor: json['nextCursor'] as String?,
        hasMore: (json['hasMore'] ?? false) as bool,
      );
}

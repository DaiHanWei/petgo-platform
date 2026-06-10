/// 兽医工作台「进行中」/「历史」列表项（demo/mock 数据驱动）。
///
/// 这两个列表在 Story 5.2 仅留空态占位，本模型为工作台填充可演示内容：
/// 「进行中」点卡进 [VetConversationPage]；「历史」只读展示已结束问诊概览。
class VetActiveItem {
  const VetActiveItem({
    required this.sessionId,
    required this.petName,
    required this.lastMessage,
    required this.source,
    this.unread = 0,
  });

  final int sessionId;
  final String petName;
  final String lastMessage;
  final String source; // DIRECT | AI_UPGRADE
  final int unread;

  factory VetActiveItem.fromJson(Map<String, dynamic> json) => VetActiveItem(
        sessionId: (json['sessionId'] as num).toInt(),
        petName: (json['petName'] ?? '') as String,
        lastMessage: (json['lastMessage'] ?? '') as String,
        source: (json['source'] ?? 'DIRECT') as String,
        unread: (json['unread'] as num?)?.toInt() ?? 0,
      );
}

class VetHistoryEntry {
  const VetHistoryEntry({
    required this.sessionId,
    required this.petName,
    required this.summary,
    required this.date,
    this.stars,
    required this.terminalState,
  });

  final int sessionId;
  final String petName;
  final String summary;
  final String date; // ISO8601
  final int? stars; // 用户评分（无则未评）
  final String terminalState; // CLOSED | INTERRUPTED

  /// 仅取日期段（yyyy-MM-dd）供卡片展示，避免引 intl。
  String get dateLabel => date.length >= 10 ? date.substring(0, 10) : date;

  factory VetHistoryEntry.fromJson(Map<String, dynamic> json) => VetHistoryEntry(
        sessionId: (json['sessionId'] as num).toInt(),
        petName: (json['petName'] ?? '') as String,
        summary: (json['summary'] ?? '') as String,
        date: (json['date'] ?? '') as String,
        stars: (json['stars'] as num?)?.toInt(),
        terminalState: (json['terminalState'] ?? 'CLOSED') as String,
      );
}

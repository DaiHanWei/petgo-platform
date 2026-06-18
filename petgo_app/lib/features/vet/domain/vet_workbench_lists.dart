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
    this.dangerLevel,
    this.ownerHandle,
    this.petSpecies,
    this.reviewText,
  });

  final int sessionId;
  final String petName;
  final String summary;
  final String date; // ISO8601
  final int? stars; // 用户评分（无则未评）
  final String terminalState; // CLOSED | INTERRUPTED

  // 历史卡丰富字段（决策：Mock 先做满、后端随后补，同 VetSession/VetInboxItem）。全 nullable →
  // 真后端未下发时卡片对应段优雅降级、不进等级筛选。
  final String? dangerLevel; // GREEN | YELLOW | RED
  final String? ownerHandle; // 不含 @，渲染时前置
  final String? petSpecies; // CAT | DOG
  final String? reviewText; // 用户评价引用文案

  /// 仅取日期段（yyyy-MM-dd）供卡片展示，避免引 intl。
  String get dateLabel => date.length >= 10 ? date.substring(0, 10) : date;

  factory VetHistoryEntry.fromJson(Map<String, dynamic> json) => VetHistoryEntry(
        sessionId: (json['sessionId'] as num).toInt(),
        petName: (json['petName'] ?? '') as String,
        summary: (json['summary'] ?? '') as String,
        date: (json['date'] ?? '') as String,
        stars: (json['stars'] as num?)?.toInt(),
        terminalState: (json['terminalState'] ?? 'CLOSED') as String,
        dangerLevel: json['dangerLevel'] as String?,
        ownerHandle: json['ownerHandle'] as String?,
        petSpecies: json['petSpecies'] as String?,
        reviewText: json['reviewText'] as String?,
      );
}

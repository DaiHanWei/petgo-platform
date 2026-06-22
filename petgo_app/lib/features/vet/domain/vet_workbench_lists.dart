/// 兽医工作台「进行中」/「历史」列表项。
///
/// 「进行中」对应后端 `VetActiveItem`（`sessionId`/`source`/`petName`），点卡进 [VetConversationPage]；
/// 「历史」对应后端 `VetHistoryItem`，只读展示已结束问诊概览。
class VetActiveItem {
  const VetActiveItem({
    required this.sessionId,
    required this.petName,
    required this.source,
    this.lastMessage = '',
    this.unread = 0,
  });

  final int sessionId;
  final String petName;
  final String source; // DIRECT | AI_UPGRADE

  // unread / lastMessage 来自腾讯 IM SDK（后端列表端点不下发）：mock 离线态附占位演示，
  // 真机由 IM 数据填充；缺失时卡片优雅降级（不显未读角标 / 最近消息行）。
  final String lastMessage;
  final int unread;

  factory VetActiveItem.fromJson(Map<String, dynamic> json) => VetActiveItem(
        sessionId: (json['sessionId'] as num).toInt(),
        petName: (json['petName'] ?? '') as String,
        source: (json['source'] ?? 'DIRECT') as String,
        lastMessage: (json['lastMessage'] ?? '') as String,
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

  // 历史卡丰富字段（后端 VetHistoryItem 已下发）。全 nullable → 注销匿名化/DIRECT 无 AI 上下文时
  // 对应段优雅降级、不进等级筛选。
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

/// 兽医待接单列表项（对应后端 `VetInboxItem`，Story 5.5）。
class VetInboxItem {
  const VetInboxItem({
    required this.sessionId,
    required this.source,
    this.aiDangerLevel,
    this.symptomPreview,
    required this.imageCount,
    required this.waitingElapsedSeconds,
  });

  final int sessionId;
  final String source; // DIRECT | AI_UPGRADE
  final String? aiDangerLevel;
  final String? symptomPreview;
  final int imageCount;
  final int waitingElapsedSeconds;

  bool get isAiUpgrade => source == 'AI_UPGRADE';

  factory VetInboxItem.fromJson(Map<String, dynamic> json) => VetInboxItem(
        sessionId: (json['sessionId'] as num).toInt(),
        source: (json['source'] ?? 'DIRECT') as String,
        aiDangerLevel: json['aiDangerLevel'] as String?,
        symptomPreview: json['symptomPreview'] as String?,
        imageCount: (json['imageCount'] as num?)?.toInt() ?? 0,
        waitingElapsedSeconds: (json['waitingElapsedSeconds'] as num?)?.toInt() ?? 0,
      );
}

/// 兽医侧会话视图（对应后端 `VetSessionView`）。
class VetSession {
  const VetSession({
    required this.id,
    required this.status,
    required this.source,
    this.userId,
    this.imConversationId,
    required this.hasAiContext,
  });

  final int id;
  final String status;
  final String source;
  final int? userId;
  final String? imConversationId;
  final bool hasAiContext;

  factory VetSession.fromJson(Map<String, dynamic> json) => VetSession(
        id: (json['id'] as num).toInt(),
        status: (json['status'] ?? 'IN_PROGRESS') as String,
        source: (json['source'] ?? 'DIRECT') as String,
        userId: (json['userId'] as num?)?.toInt(),
        imConversationId: json['imConversationId'] as String?,
        hasAiContext: (json['hasAiContext'] ?? false) as bool,
      );
}

/// FR-5 辅助（对应后端 `ConsultAssistResponse`）。
class ConsultAssist {
  const ConsultAssist({required this.aiReferenceReply, required this.historySummaries});

  final String aiReferenceReply;
  final List<String> historySummaries;

  factory ConsultAssist.fromJson(Map<String, dynamic> json) => ConsultAssist(
        aiReferenceReply: (json['aiReferenceReply'] ?? '') as String,
        historySummaries: (json['historySummaries'] as List?)?.cast<String>() ?? const [],
      );
}

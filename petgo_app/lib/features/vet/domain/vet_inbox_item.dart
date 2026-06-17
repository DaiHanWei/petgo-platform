/// 兽医待接单列表项（对应后端 `VetInboxItem`，Story 5.5）。
class VetInboxItem {
  const VetInboxItem({
    required this.sessionId,
    required this.source,
    this.aiDangerLevel,
    this.symptomPreview,
    required this.imageCount,
    required this.waitingElapsedSeconds,
    this.petName,
    this.petSpecies,
    this.petSex,
    this.petAgeMonths,
    this.ownerHandle,
  });

  final int sessionId;
  final String source; // DIRECT | AI_UPGRADE
  final String? aiDangerLevel;
  final String? symptomPreview;
  final int imageCount;
  final int waitingElapsedSeconds;

  // 宠物身份（决策：Mock 先做满、后端随后补）。全 nullable：真后端未下发 → null → 卡片优雅降级不显身份块。
  final String? petName;
  final String? petSpecies; // CAT | DOG
  final String? petSex; // MALE | FEMALE
  final int? petAgeMonths;
  final String? ownerHandle; // 不含 @，渲染时前置

  bool get isAiUpgrade => source == 'AI_UPGRADE';

  factory VetInboxItem.fromJson(Map<String, dynamic> json) => VetInboxItem(
        sessionId: (json['sessionId'] as num).toInt(),
        source: (json['source'] ?? 'DIRECT') as String,
        aiDangerLevel: json['aiDangerLevel'] as String?,
        symptomPreview: json['symptomPreview'] as String?,
        imageCount: (json['imageCount'] as num?)?.toInt() ?? 0,
        waitingElapsedSeconds: (json['waitingElapsedSeconds'] as num?)?.toInt() ?? 0,
        petName: json['petName'] as String?,
        petSpecies: json['petSpecies'] as String?,
        petSex: json['petSex'] as String?,
        petAgeMonths: (json['petAgeMonths'] as num?)?.toInt(),
        ownerHandle: json['ownerHandle'] as String?,
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

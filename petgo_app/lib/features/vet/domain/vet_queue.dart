/// 兽医付费队列（Story 3.6 + V84/D1，后端 `GET /vet/consultations/queue`）。
///
/// 对应 `consult_requests`（`requestToken` 寻址，不可枚举）。**V84 起含病例摘要**——兽医接单前据此判断
/// 是否接单。完整病例（含现签图）走 `GET /vet/consultations/{requestToken}/case`，列表**不下发签名 URL**。
/// 身份字段全 nullable（注销/无档案兜底 null → 前端降级）。
class VetQueue {
  const VetQueue({this.awaitingPay, this.available = const []});

  /// 本兽医接单后「等待用户支付」中间态（FR-53A），无则 null（未接单）。
  final VetAwaitingPay? awaitingPay;

  /// 可接单 QUEUEING 池（FIFO）。**兽医忙时（接单中/会话中）后端返回空**（不能再接）。
  final List<VetQueueItem> available;

  factory VetQueue.fromJson(Map<String, dynamic> json) => VetQueue(
        awaitingPay: json['awaitingPay'] == null
            ? null
            : VetAwaitingPay.fromJson((json['awaitingPay'] as Map).cast<String, dynamic>()),
        available: ((json['available'] as List?) ?? const [])
            .map((e) => VetQueueItem.fromJson((e as Map).cast<String, dynamic>()))
            .toList(),
      );
}

/// 队列池项：可接单请求（宠物身份 + 等待时长 + 入队截止 + 病例摘要）。
class VetQueueItem {
  const VetQueueItem({
    required this.requestToken,
    this.petName,
    this.petSpecies,
    this.petAgeMonths,
    this.ownerHandle,
    this.waitingSeconds = 0,
    this.queueDeadlineAt,
    this.source,
    this.aiDangerLevel,
    this.symptomPreview,
    this.imageCount = 0,
  });

  final String requestToken;
  final String? petName;
  final String? petSpecies; // CAT | DOG | OTHER
  final int? petAgeMonths;
  final String? ownerHandle; // 不含 @，渲染时前置
  final int waitingSeconds; // 入队至今秒数（服务端算）
  final DateTime? queueDeadlineAt; // 入队截止（服务端权威，UTC）

  // ===== 病例摘要（V84/D1）：接单判断依据 =====

  /// DIRECT（用户自填病例）| AI_UPGRADE（分诊升级）。
  final String? source;

  /// AI 危险等级：GREEN|YELLOW，DIRECT 为 null（自填病例无 AI 评级）。**绝不含 RED**（红色态零引流）。
  final String? aiDangerLevel;

  /// 症状摘要（服务端已截断 40 字），无则 null。
  final String? symptomPreview;

  /// 私密图数量（列表不下发 URL；看图走 `/vet/consultations/{token}/case`）。
  final int imageCount;

  bool get isAiUpgrade => source == 'AI_UPGRADE';

  /// 是否有可展开的病例（有摘要或有图）。
  bool get hasCase => (symptomPreview != null && symptomPreview!.isNotEmpty) || imageCount > 0;

  factory VetQueueItem.fromJson(Map<String, dynamic> json) => VetQueueItem(
        requestToken: (json['requestToken'] ?? '') as String,
        petName: json['petName'] as String?,
        petSpecies: json['petSpecies'] as String?,
        petAgeMonths: (json['petAgeMonths'] as num?)?.toInt(),
        ownerHandle: json['ownerHandle'] as String?,
        waitingSeconds: (json['waitingSeconds'] as num?)?.toInt() ?? 0,
        queueDeadlineAt: json['queueDeadlineAt'] == null
            ? null
            : DateTime.parse(json['queueDeadlineAt'] as String).toUtc(),
        source: json['source'] as String?,
        aiDangerLevel: json['aiDangerLevel'] as String?,
        symptomPreview: json['symptomPreview'] as String?,
        imageCount: (json['imageCount'] as num?)?.toInt() ?? 0,
      );
}

/// 待支付中间态项（FR-53A）：本兽医接单后等待用户支付，含服务端权威支付截止 + 暂停锚（A-4 跳充值）。
class VetAwaitingPay {
  const VetAwaitingPay({
    required this.requestToken,
    this.petName,
    this.payDeadlineAt,
    this.pausedAt,
  });

  final String requestToken;
  final String? petName;
  final DateTime? payDeadlineAt; // 支付截止（服务端权威，UTC），前端倒计时纯显示
  final DateTime? pausedAt; // 非 null = 用户跳充值暂停中（A-4），倒计时暂停显示

  bool get isPaused => pausedAt != null;

  factory VetAwaitingPay.fromJson(Map<String, dynamic> json) => VetAwaitingPay(
        requestToken: (json['requestToken'] ?? '') as String,
        petName: json['petName'] as String?,
        payDeadlineAt: json['payDeadlineAt'] == null
            ? null
            : DateTime.parse(json['payDeadlineAt'] as String).toUtc(),
        pausedAt: json['pausedAt'] == null
            ? null
            : DateTime.parse(json['pausedAt'] as String).toUtc(),
      );
}

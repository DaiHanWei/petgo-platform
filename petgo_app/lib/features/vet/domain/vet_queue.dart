/// 兽医计费队列（Story 3.6，后端 `GET /vet/consultations/queue`）。
///
/// 与 V1.0 免费直连流 [VetInboxItem]（`consult_sessions`，`sessionId` 寻址）**并存不混用**：本模型对应计费流
/// `consult_requests`（`requestToken` 寻址）。`consult_requests` 不存病例，故队列卡**无 AI 危险等级/症状/照片**，
/// 仅宠物身份 + 等待时长（区别于免费流富卡）。身份字段全 nullable（注销/无档案兜底 null → 前端降级）。
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

/// 队列池项：可接单请求（宠物身份 + 等待时长 + 入队截止），无病例字段。
class VetQueueItem {
  const VetQueueItem({
    required this.requestToken,
    this.petName,
    this.petSpecies,
    this.petAgeMonths,
    this.ownerHandle,
    this.waitingSeconds = 0,
    this.queueDeadlineAt,
  });

  final String requestToken;
  final String? petName;
  final String? petSpecies; // CAT | DOG | OTHER
  final int? petAgeMonths;
  final String? ownerHandle; // 不含 @，渲染时前置
  final int waitingSeconds; // 入队至今秒数（服务端算）
  final DateTime? queueDeadlineAt; // 入队截止（服务端权威，UTC）

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

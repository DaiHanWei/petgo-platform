// 客服工单模型（Story 4.2）。对应后端 4-1 `SupportTicketView`（用户视图，绝无内部字段）。
// 手写 fromJson（无 freezed，照 consult_request.dart 惯例）。枚举只做 parse/toApi；显示文案在 presentation 走 l10n。

/// 联系方式类型（后端落库 EMAIL/WHATSAPP）。
enum ContactType {
  email,
  whatsapp;

  String toApi() => switch (this) {
        ContactType.email => 'EMAIL',
        ContactType.whatsapp => 'WHATSAPP',
      };

  static ContactType parse(String? s) => switch (s) {
        'WHATSAPP' => ContactType.whatsapp,
        _ => ContactType.email,
      };
}

/// 工单标签（后端 8 值，多选去重）。
enum TicketLabelType {
  bug,
  feature,
  consultComplaint,
  refund,
  content,
  account,
  praise,
  other;

  String toApi() => switch (this) {
        TicketLabelType.bug => 'BUG',
        TicketLabelType.feature => 'FEATURE',
        TicketLabelType.consultComplaint => 'CONSULT_COMPLAINT',
        TicketLabelType.refund => 'REFUND',
        TicketLabelType.content => 'CONTENT',
        TicketLabelType.account => 'ACCOUNT',
        TicketLabelType.praise => 'PRAISE',
        TicketLabelType.other => 'OTHER',
      };

  static TicketLabelType? parse(String? s) => switch (s) {
        'BUG' => TicketLabelType.bug,
        'FEATURE' => TicketLabelType.feature,
        'CONSULT_COMPLAINT' => TicketLabelType.consultComplaint,
        'REFUND' => TicketLabelType.refund,
        'CONTENT' => TicketLabelType.content,
        'ACCOUNT' => TicketLabelType.account,
        'PRAISE' => TicketLabelType.praise,
        'OTHER' => TicketLabelType.other,
        _ => null, // 未知标签优雅忽略
      };
}

/// 工单状态（后端流转，用户端只读）。
enum TicketStatus {
  open,
  inProgress,
  resolved,
  closed,
  unknown;

  static TicketStatus parse(String? s) => switch (s) {
        'OPEN' => TicketStatus.open,
        'IN_PROGRESS' => TicketStatus.inProgress,
        'RESOLVED' => TicketStatus.resolved,
        'CLOSED' => TicketStatus.closed,
        _ => TicketStatus.unknown,
      };
}

DateTime? _parseInstant(dynamic v) => v == null ? null : DateTime.tryParse(v as String)?.toLocal();

/// 工单用户视图。附件仅有 objectKey（4-1 决策，非签名 URL），前端只用数量展示（详情不渲染缩略图）。
class SupportTicket {
  const SupportTicket({
    required this.ticketToken,
    required this.body,
    required this.contactType,
    required this.contactValue,
    required this.needContactCustomer,
    required this.contactedCustomer,
    required this.status,
    required this.labels,
    required this.attachmentObjectKeys,
    this.subject,
    this.csatScore,
    this.csatComment,
    this.createdAt,
    this.updatedAt,
    this.resolvedAt,
    this.relatedShopOrderNo,
  });

  final String ticketToken;
  final String? subject;
  final String body;
  final ContactType contactType;
  final String contactValue;
  final bool needContactCustomer;
  final bool contactedCustomer;
  final TicketStatus status;
  final List<TicketLabelType> labels;
  final List<String> attachmentObjectKeys;
  final int? csatScore;
  final String? csatComment;
  final DateTime? createdAt;
  final DateTime? updatedAt;
  final DateTime? resolvedAt;

  /// 关联电商订单的**展示号**（V1.3.0 Story 3-3）。非电商工单为 null。
  ///
  /// 🔴 它同时是**显示条件**与**预填内容**：非空即「本工单关联的是电商单」，
  /// 值即 WhatsApp 深链要填的订单号。后端刻意下发这个而不是内部 id 或类型枚举 ——
  /// 那两个是内部标识（自增主键可枚举、跨表撞号），而订单号是用户自己天天看见的东西。
  final String? relatedShopOrderNo;

  int get attachmentCount => attachmentObjectKeys.length;

  factory SupportTicket.fromJson(Map<String, dynamic> json) {
    final labelsRaw = (json['labels'] as List<dynamic>?) ?? const [];
    final keysRaw = (json['attachmentObjectKeys'] as List<dynamic>?) ?? const [];
    return SupportTicket(
      ticketToken: json['ticketToken'] as String,
      subject: json['subject'] as String?,
      body: json['body'] as String? ?? '',
      contactType: ContactType.parse(json['contactType'] as String?),
      contactValue: json['contactValue'] as String? ?? '',
      // 缺键 / 空串 → null（老后端或非电商工单）。null 即「不显示 WhatsApp 入口」。
      relatedShopOrderNo: _blankToNullTicket(json['relatedShopOrderNo'] as String?),
      needContactCustomer: json['needContactCustomer'] as bool? ?? true,
      contactedCustomer: json['contactedCustomer'] as bool? ?? false,
      status: TicketStatus.parse(json['status'] as String?),
      labels: labelsRaw
          .map((e) => TicketLabelType.parse(e as String?))
          .whereType<TicketLabelType>()
          .toList(growable: false),
      attachmentObjectKeys: keysRaw.map((e) => e as String).toList(growable: false),
      csatScore: json['csatScore'] as int?,
      csatComment: json['csatComment'] as String?,
      createdAt: _parseInstant(json['createdAt']),
      updatedAt: _parseInstant(json['updatedAt']),
      resolvedAt: _parseInstant(json['resolvedAt']),
    );
  }
}

/// 空串按「没有」处理 —— 一个空订单号会让入口显示出来却预填不出东西。
String? _blankToNullTicket(String? v) => (v == null || v.isEmpty) ? null : v;

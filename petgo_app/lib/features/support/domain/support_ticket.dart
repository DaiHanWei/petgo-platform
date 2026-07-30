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

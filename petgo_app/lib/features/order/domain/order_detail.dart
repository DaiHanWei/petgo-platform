/// 订单详情领域模型（Story 5.3）。对齐后端 `OrderDetailView`。按 orderType 分支渲染；
/// 宠物已删（FR-54D）→ [petDeleted]=true + pet 字段 null（订单仍存活，非报错）。
library;

import 'order_summary.dart';

/// 退款子阶段（兽医订单退款进度）。
enum RefundStage {
  awaitingMethod,
  awaitingApproval,
  awaitingPayout,
  processing,
  rejected,
  unknown;

  static RefundStage? fromCode(String? code) => switch (code) {
        'AWAITING_METHOD' => RefundStage.awaitingMethod,
        'AWAITING_APPROVAL' => RefundStage.awaitingApproval,
        'AWAITING_PAYOUT' => RefundStage.awaitingPayout,
        'PROCESSING' => RefundStage.processing,
        'REJECTED' => RefundStage.rejected,
        null => null,
        _ => RefundStage.unknown,
      };
}

class OrderDetail {
  const OrderDetail({
    required this.orderType,
    required this.orderToken,
    required this.statusCode,
    required this.statusColor,
    this.amount,
    this.payChannel,
    this.createdAt,
    this.paidAt,
    this.petName,
    this.petType,
    this.petAvatarUrl,
    this.petDeleted = false,
    this.sessionStartedAt,
    this.sessionEndedAt,
    this.refundStage,
    this.refundNetAmount,
    this.coins,
    this.triageTaskId,
    this.consultSessionId,
  });

  final OrderType orderType;
  final String orderToken;
  final String statusCode;
  final OrderStatusColor statusColor;
  final int? amount;
  final String? payChannel;
  final DateTime? createdAt;
  final DateTime? paidAt;

  // 兽医：宠物（已删→null + petDeleted）
  final String? petName;
  final String? petType;
  final String? petAvatarUrl;
  final bool petDeleted;
  final DateTime? sessionStartedAt;
  final DateTime? sessionEndedAt;

  // 退款进度（非退款态→null）
  final RefundStage? refundStage;
  final int? refundNetAmount;

  // 充值 / AI
  final int? coins;
  final int? triageTaskId;

  // 兽医：问诊会话 id（打开只读问诊确认单，bug 20260720-312；无会话→null）
  final int? consultSessionId;

  static DateTime? _dt(dynamic v) => v == null ? null : DateTime.tryParse(v as String)?.toLocal();

  factory OrderDetail.fromJson(Map<String, dynamic> j) => OrderDetail(
        orderType: OrderType.fromCode(j['orderType'] as String?),
        orderToken: (j['orderToken'] ?? '') as String,
        statusCode: (j['statusCode'] ?? '') as String,
        statusColor: OrderStatusColor.fromCode(j['statusColor'] as String?),
        amount: (j['amount'] as num?)?.toInt(),
        payChannel: j['payChannel'] as String?,
        createdAt: _dt(j['createdAt']),
        paidAt: _dt(j['paidAt']),
        petName: j['petName'] as String?,
        petType: j['petType'] as String?,
        petAvatarUrl: j['petAvatarUrl'] as String?,
        petDeleted: (j['petDeleted'] ?? false) as bool,
        sessionStartedAt: _dt(j['sessionStartedAt']),
        sessionEndedAt: _dt(j['sessionEndedAt']),
        refundStage: RefundStage.fromCode(j['refundStage'] as String?),
        refundNetAmount: (j['refundNetAmount'] as num?)?.toInt(),
        coins: (j['coins'] as num?)?.toInt(),
        triageTaskId: (j['triageTaskId'] as num?)?.toInt(),
        consultSessionId: (j['consultSessionId'] as num?)?.toInt(),
      );
}

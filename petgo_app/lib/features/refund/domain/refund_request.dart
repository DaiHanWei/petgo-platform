/// 用户端退款领域模型（Story 4.5）。对齐后端 `MyRefundView`。
///
/// 退款方式由订单原支付渠道决定（后端权威）：PawCoin 付→[RefundMethod.instantPawcoin]（原路即时退币、无手续费、
/// 跳过选账户）；QRIS 付→[RefundMethod.realMoney]（填真钱收款账户）。**净额后端权威**——前端仅展示 [PayoutOption.net]，
/// 提交只回传渠道/账号/户名，绝不回传费/净额（FR-NFR-5）。
library;

/// 退款方式（按 code 本地化，勿渲染后端串）。
enum RefundMethod {
  instantPawcoin,
  realMoney,
  unknown;

  static RefundMethod fromCode(String? code) => switch (code) {
        'INSTANT_PAWCOIN' => RefundMethod.instantPawcoin,
        'REAL_MONEY' => RefundMethod.realMoney,
        _ => RefundMethod.unknown,
      };
}

/// QRIS 出款渠道净额预览（后端权威 net = order − fee）。前端优先用接口下发值（避免与后端费率漂移，OPEN-4）。
class PayoutOption {
  const PayoutOption({required this.channel, required this.fee, required this.net});

  /// 渠道枚举名（BCA / OVO / GOPAY）——按 code 本地化。
  final String channel;

  /// 渠道费（IDR）。
  final int fee;

  /// 到手净额（IDR，后端权威）。
  final int net;

  factory PayoutOption.fromJson(Map<String, dynamic> j) => PayoutOption(
        channel: (j['channel'] ?? '') as String,
        fee: (j['fee'] as num?)?.toInt() ?? 0,
        net: (j['net'] as num?)?.toInt() ?? 0,
      );
}

/// 一条「我的退款」。
class MyRefund {
  const MyRefund({
    required this.refundToken,
    required this.orderToken,
    required this.payChannel,
    required this.method,
    required this.needDecision,
    required this.approvalStatus,
    required this.orderAmount,
    required this.actionable,
    required this.payoutFilled,
    required this.payoutOptions,
  });

  final String refundToken;
  final String orderToken;

  /// 订单原支付渠道（PAWCOIN / QRIS）。
  final String payChannel;
  final RefundMethod method;

  /// 客服判定（PENDING / APPROVED / REJECTED）。
  final String needDecision;

  /// 第二段审批态（可空；QRIS 提交后 PENDING_APPROVAL，PawCoin 即时退后 DONE）。
  final String? approvalStatus;

  /// 订单金额（IDR）。
  final int orderAmount;

  /// 可选退款方式（已批准且未提交/未退）。
  final bool actionable;

  /// 是否已填收款/已退。
  final bool payoutFilled;

  /// QRIS 出款渠道费预览（PawCoin 为空）。
  final List<PayoutOption> payoutOptions;

  factory MyRefund.fromJson(Map<String, dynamic> j) => MyRefund(
        refundToken: (j['refundToken'] ?? '') as String,
        orderToken: (j['orderToken'] ?? '') as String,
        payChannel: (j['payChannel'] ?? '') as String,
        method: RefundMethod.fromCode(j['refundMethod'] as String?),
        needDecision: (j['needDecision'] ?? '') as String,
        approvalStatus: j['approvalStatus'] as String?,
        orderAmount: (j['orderAmount'] as num?)?.toInt() ?? 0,
        actionable: (j['actionable'] ?? false) as bool,
        payoutFilled: (j['payoutFilled'] ?? false) as bool,
        payoutOptions: ((j['payoutOptions'] as List?) ?? const [])
            .map((e) => PayoutOption.fromJson((e as Map).cast<String, dynamic>()))
            .toList(),
      );
}

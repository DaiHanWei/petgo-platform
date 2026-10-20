/// 发起送礼的响应（V1.4.0 gift Story 1.3 · AC8；后端 `GiftSendResponse`，Story 1.1 · AC5.5）。
///
/// 字段与 `KeepsakePurchaseResult` 同名（`payment` 内取 `token` / `displayNo`），另加订单 token 与状态。
class GiftSendResult {
  const GiftSendResult({
    required this.delivered,
    this.paymentToken,
    this.paymentDisplayNo,
    this.payload,
    this.orderToken,
    this.status = GiftOrderStatus.unknown,
  });

  /// true = 已送达（PawCoin 当场成交 / 幂等重放时订单已送达）。
  final bool delivered;
  final String? paymentToken;
  final String? paymentDisplayNo;

  /// QRIS 二维码串；PawCoin / 已送达时为 null。
  final String? payload;

  /// 订单 `public_token`（QRIS 轮询单笔状态用）。
  final String? orderToken;
  final GiftOrderStatus status;

  /// 展示用支付号：优先可读号，缺省退 token。
  String? get paymentRef => paymentDisplayNo ?? paymentToken;

  factory GiftSendResult.fromJson(Map<String, dynamic> json) {
    final payment = json['payment'];
    final p = payment is Map ? payment.cast<String, dynamic>() : null;
    return GiftSendResult(
      delivered: json['delivered'] as bool? ?? false,
      paymentToken: p?['token'] as String?,
      paymentDisplayNo: p?['displayNo'] as String?,
      payload: json['payload'] as String?,
      orderToken: json['orderToken'] as String?,
      status: GiftOrderStatus.fromWire(json['status'] as String?),
    );
  }
}

/// 送礼订单状态（后端 `GiftOrderStatus` 名）。未知值 → [unknown]，按 PENDING 处理（继续轮询）。
enum GiftOrderStatus {
  pending('PENDING'),
  delivered('DELIVERED'),
  expired('EXPIRED'),
  failed('FAILED'),
  undeliveredRefundPending('UNDELIVERED_REFUND_PENDING'),
  undeliveredRefunded('UNDELIVERED_REFUNDED'),
  refundManual('REFUND_MANUAL'),
  unknown('');

  const GiftOrderStatus(this.wire);

  final String wire;

  static GiftOrderStatus fromWire(String? wire) {
    for (final s in values) {
      if (s != unknown && s.wire == wire) return s;
    }
    return unknown;
  }

  /// 付款已到账但无法送达（退回 PawCoin 中 / 已退 / 转人工）。
  bool get isUndelivered =>
      this == undeliveredRefundPending || this == undeliveredRefunded || this == refundManual;
}

/// 送礼对象：一律按宠物（gift Story 4.1 · AC4.3：详情页也先选定宠物，不再按帖子由后端取「第一只」；
/// 后端目录 `?postId=` 已弃用，仅兼容 1.4.0 早期包）。
class GiftRecipientRef {
  const GiftRecipientRef.pet(this.petId);

  final int petId;

  @override
  bool operator ==(Object other) => other is GiftRecipientRef && other.petId == petId;

  @override
  int get hashCode => petId.hashCode;

  @override
  String toString() => 'GiftRecipientRef.pet($petId)';
}

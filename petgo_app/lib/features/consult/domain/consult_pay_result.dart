// 限时支付响应模型（Story 3.5，POST /consultations/{token}/pay）。两形态：
// - DONE：PawCoin 即时支付成功（已建订单+会话，前端跳会话）。
// - PAYMENT_REQUIRED：QRIS 现金（回支付信息，前端引导付款+轮询到账）。
// 手写 fromJson（照 consult_session.dart 惯例）。

/// 现金支付信息（对应后端 PaymentIntentResponse）。
class PaymentInfo {
  const PaymentInfo({
    required this.token,
    required this.channel,
    required this.amount,
    required this.currency,
    required this.status,
  });

  final String token; // 不可枚举支付意图号（轮询到账用）
  final String channel; // QRIS
  final int amount; // IDR 最小单位
  final String currency;
  final String status; // PENDING | PAID | ...

  factory PaymentInfo.fromJson(Map<String, dynamic> json) => PaymentInfo(
        token: json['token'] as String,
        channel: json['channel'] as String,
        amount: (json['amount'] as num).toInt(),
        currency: json['currency'] as String? ?? 'IDR',
        status: json['status'] as String? ?? 'PENDING',
      );
}

/// 同步支付成功的订单精简视图（对应后端 ConsultPayResponse.OrderView）。
class ConsultOrderView {
  const ConsultOrderView({required this.orderToken, required this.status});

  final String orderToken;
  final String status; // IN_PROGRESS

  factory ConsultOrderView.fromJson(Map<String, dynamic> json) => ConsultOrderView(
        orderToken: json['orderToken'] as String,
        status: json['status'] as String? ?? 'IN_PROGRESS',
      );
}

class ConsultPayResult {
  const ConsultPayResult({required this.mode, this.order, this.payment, this.payload});

  final String mode; // DONE | PAYMENT_REQUIRED
  final ConsultOrderView? order;
  final PaymentInfo? payment;
  final String? payload; // QRIS 二维码载荷（EMVCo 串，现金态本地生成二维码；同步态为 null）

  bool get isDone => mode == 'DONE';
  bool get isPaymentRequired => mode == 'PAYMENT_REQUIRED';

  factory ConsultPayResult.fromJson(Map<String, dynamic> json) => ConsultPayResult(
        mode: json['mode'] as String,
        order: json['order'] == null
            ? null
            : ConsultOrderView.fromJson(json['order'] as Map<String, dynamic>),
        payment: json['payment'] == null
            ? null
            : PaymentInfo.fromJson(json['payment'] as Map<String, dynamic>),
        payload: json['payload'] as String?,
      );
}

import '../../profile/domain/id_card.dart';

/// 一次性解锁发起的响应（V1.3.2 Story 3.1 后端 `KeepsakePurchaseResponse`）。
///
/// 前三项与 `HdPurchaseResponse` 同形，直接复用 [HdPurchaseResult.fromJson] 的解析口径；另加 [purchaseToken]。
class KeepsakePurchaseResult {
  const KeepsakePurchaseResult({
    required this.unlocked,
    this.paymentToken,
    this.paymentDisplayNo,
    this.payload,
    this.purchaseToken,
  });

  /// true = 已解锁（PawCoin 当场成交 / 此前已付款）。
  final bool unlocked;
  final String? paymentToken;
  final String? paymentDisplayNo;

  /// QRIS 二维码串；PawCoin / 已解锁时为 null。
  final String? payload;

  /// 购买记录 token（订单中心用）；无购买行时 null。
  final String? purchaseToken;

  /// 展示用支付号：优先可读号，缺省退 token。
  String? get paymentRef => paymentDisplayNo ?? paymentToken;

  factory KeepsakePurchaseResult.fromJson(Map<String, dynamic> json) {
    final base = HdPurchaseResult.fromJson(json);
    return KeepsakePurchaseResult(
      unlocked: base.unlocked,
      paymentToken: base.paymentToken,
      paymentDisplayNo: base.paymentDisplayNo,
      payload: base.payload,
      purchaseToken: json['purchaseToken'] as String?,
    );
  }
}

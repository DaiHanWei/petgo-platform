/// 电商订单详情域模型（Story 3.8，消费 `GET /api/v1/me/shop-orders/{token}`）。
///
/// 🔴 **`expiresAt` 是服务端时刻，客户端只据此渲染倒计时**（AD-8）。
/// 客户端**不**用本地计时去判定「已超时」并改状态 —— 改一下手机时间就能让页面
/// 显示成另一回事；到点后要做的是**重新拉详情**，由服务端告诉你它到底怎么样了。
library;

/// 订单状态（后端 `ShopOrderStatus` 八态；Epic 3 只会出现前两个与 CANCELLED）。
enum ShopOrderStatus {
  pendingPayment('PENDING_PAYMENT'),
  pendingShipment('PENDING_SHIPMENT'),
  shipped('SHIPPED'),
  delivered('DELIVERED'),
  completed('COMPLETED'),
  cancelled('CANCELLED'),
  refunding('REFUNDING'),
  refunded('REFUNDED'),

  /// 认不出的状态（后端新增而 App 未升级）。
  /// 🔴 降级到「不可操作」：既不给支付也不给取消 —— 对一个不认识的状态做资金动作
  /// 是最糟的选择，让用户去更新 App 或找客服才对。
  unknown('');

  const ShopOrderStatus(this.api);

  final String api;

  static ShopOrderStatus fromApi(String? raw) {
    for (final s in values) {
      if (s.api == raw && s != unknown) return s;
    }
    return unknown;
  }

  /// 只有待支付才允许支付/取消。
  bool get isPendingPayment => this == ShopOrderStatus.pendingPayment;
}

class ShopOrderLine {
  const ShopOrderLine({
    required this.productName,
    required this.specName,
    required this.unitPrice,
    required this.qty,
    required this.lineTotal,
  });

  final String productName;
  final String specName;
  final int unitPrice;
  final int qty;
  final int lineTotal;

  factory ShopOrderLine.fromJson(Map<String, dynamic> j) => ShopOrderLine(
        productName: j['productName']?.toString() ?? '',
        specName: j['specName']?.toString() ?? '',
        unitPrice: _int(j['unitPrice']) ?? 0,
        qty: _int(j['qty']) ?? 0,
        lineTotal: _int(j['lineTotal']) ?? 0,
      );
}

class ShopOrderDetail {
  const ShopOrderDetail({
    required this.orderToken,
    required this.status,
    required this.goodsSubtotal,
    required this.shippingFee,
    required this.shippingDiscount,
    required this.totalAmount,
    required this.lines,
    required this.receiverName,
    required this.receiverPhone,
    required this.addressText,
    this.payChannel,
    this.coinAmount,
    this.cashAmount,
    this.expiresAt,
    this.createdAt,
  });

  final String orderToken;
  final ShopOrderStatus status;
  final int goodsSubtotal;
  final int shippingFee;
  final int shippingDiscount;
  final int totalAmount;

  /// `QRIS` / `PAWCOIN` / `MIXED`（下单时固化，不可现改）。
  final String? payChannel;

  /// 🔴 两段金额：非混合支付时为 null（AD-1）。
  final int? coinAmount;
  final int? cashAmount;

  /// 🔴 支付窗截止（服务端时刻）。null = 该单没有支付窗（历史单或已离开待支付态）。
  final DateTime? expiresAt;
  final DateTime? createdAt;

  final List<ShopOrderLine> lines;
  final String receiverName;
  final String receiverPhone;
  final String addressText;

  bool get isMixed => (coinAmount ?? 0) > 0 && (cashAmount ?? 0) > 0;

  /// 剩余支付时间。🔴 只用于**渲染**；「是否已过期」的判定权在服务端。
  Duration remaining(DateTime now) {
    final at = expiresAt;
    if (at == null) return Duration.zero;
    final left = at.difference(now);
    return left.isNegative ? Duration.zero : left;
  }

  factory ShopOrderDetail.fromJson(Map<String, dynamic> j) {
    final ship = j['shipTo'];
    final s = ship is Map<String, dynamic> ? ship : const <String, dynamic>{};
    return ShopOrderDetail(
      orderToken: j['orderToken']?.toString() ?? '',
      status: ShopOrderStatus.fromApi(j['status']?.toString()),
      goodsSubtotal: _int(j['goodsSubtotal']) ?? 0,
      shippingFee: _int(j['shippingFee']) ?? 0,
      shippingDiscount: _int(j['shippingDiscount']) ?? 0,
      totalAmount: _int(j['totalAmount']) ?? 0,
      payChannel: j['payChannel']?.toString(),
      coinAmount: _int(j['coinAmount']),
      cashAmount: _int(j['cashAmount']),
      expiresAt: _time(j['expiresAt']),
      createdAt: _time(j['createdAt']),
      lines: j['lines'] is List
          ? (j['lines'] as List)
              .whereType<Map<String, dynamic>>()
              .map(ShopOrderLine.fromJson)
              .toList(growable: false)
          : const [],
      receiverName: s['receiverName']?.toString() ?? '',
      receiverPhone: s['receiverPhone']?.toString() ?? '',
      addressText: [
        s['addressLine']?.toString(),
        s['kecamatan']?.toString(),
        s['kotaKabupaten']?.toString(),
        s['provinsi']?.toString(),
        s['kodePos']?.toString(),
      ].whereType<String>().where((x) => x.isNotEmpty).join(', '),
    );
  }
}

/// 发起支付的结果。
class ShopPayResult {
  const ShopPayResult({required this.orderStatus, this.paymentIntentToken, this.payload});

  final String orderStatus;
  final String? paymentIntentToken;

  /// QRIS 二维码串。🔴 纯 PawCoin 单为 null —— 当场结清，没有可扫的码。
  final String? payload;

  bool get settledImmediately => payload == null || payload!.isEmpty;

  factory ShopPayResult.fromJson(Map<String, dynamic> j) => ShopPayResult(
        orderStatus: j['orderStatus']?.toString() ?? '',
        paymentIntentToken: j['paymentIntentToken']?.toString(),
        payload: j['payload']?.toString(),
      );
}

int? _int(Object? v) => v is num ? v.toInt() : null;

DateTime? _time(Object? v) {
  if (v is! String || v.isEmpty) return null;
  // 🔴 统一转本地时区再比较：后端一律 UTC（CLAUDE.md 命名映射链），
  //    直接拿 UTC 值与本地 now 相减会差出整整一个时区。
  return DateTime.tryParse(v)?.toLocal();
}

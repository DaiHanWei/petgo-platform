/// 结算试算域模型（Story 3.7，消费 `GET /api/v1/me/checkout`）。
///
/// 🔴 **金额一律取服务端下发值，前端不做任何再计算**（HANDOFF：别另算一遍运费与拆分，
/// 两处必漂移）。这里的每个字段都是「显示什么」，不是「算什么」。
library;

import 'shop_product_detail.dart' show ReturnPolicy;

/// 结算页一行商品。
class CheckoutLine {
  const CheckoutLine({
    required this.skuToken,
    required this.productName,
    required this.specName,
    required this.price,
    required this.qty,
    required this.returnPolicy,
    this.productToken,
    this.mainImageUrl,
    this.invalidReason,
  });

  final String skuToken;
  final String? productToken;
  final String? productName;
  final String specName;
  final int price;
  final int qty;
  final String? mainImageUrl;

  /// 该行的**生效**退货规则（SKU 未设则继承商品级）。与订单行落库的是同一个值。
  final ReturnPolicy returnPolicy;

  /// 失效行才有值（不可购买的行不参与提交）。
  final String? invalidReason;

  int get lineTotal => price * qty;

  factory CheckoutLine.fromJson(Map<String, dynamic> json) => CheckoutLine(
        skuToken: json['skuToken']?.toString() ?? '',
        productToken: _blankToNull(json['productToken']?.toString()),
        productName: _blankToNull(json['productName']?.toString()),
        specName: json['specName']?.toString() ?? '',
        price: json['price'] is num ? (json['price'] as num).toInt() : 0,
        qty: json['qty'] is num ? (json['qty'] as num).toInt() : 0,
        mainImageUrl: _blankToNull(json['mainImageUrl']?.toString()),
        // 🔴 未知值降级到最保守档（`fromApi` 自带），缺失同理 —— 结算页是承诺现场
        returnPolicy: ReturnPolicy.fromApi(json['returnPolicy']?.toString()),
        invalidReason: _blankToNull(json['invalidReason']?.toString()),
      );

  static String? _blankToNull(String? s) => (s == null || s.isEmpty) ? null : s;
}

/// 结算试算。
class CheckoutPreview {
  const CheckoutPreview({
    required this.addressToken,
    required this.receiverName,
    required this.receiverPhone,
    required this.addressText,
    required this.serviceable,
    required this.lines,
    required this.unavailableLines,
    required this.goodsSubtotal,
    required this.coinBalance,
    required this.maxCoinPerOrder,
    required this.coinCapped,
    required this.strictestReturnPolicy,
    this.shippingFee,
    this.shippingDiscount,
    this.payableTotal,
    this.coinAmount,
    this.cashAmount,
  });

  final String addressToken;
  final String receiverName;
  final String receiverPhone;

  /// 「Provinsi · Kota · Kecamatan · 详细地址」拼好的一行（PII，只在本人页面展示）。
  final String addressText;

  /// 🔴 false = 该 Kecamatan 暂不配送。此时金额位全为 null，页面展示警示 + 禁用提交（FR-99）。
  final bool serviceable;

  final List<CheckoutLine> lines;

  /// 🔴 失效行（FR-95 第二次校验的预警）：展示但不参与合计，也不随提交发出。
  final List<CheckoutLine> unavailableLines;

  final int goodsSubtotal;

  /// 超范围时为 null —— **算不出就不显示，绝不填 0**（0 会被读成「免运费」）。
  final int? shippingFee;

  /// 免运抵扣，**负数**（FR-99：一条负数行，不是把运费改成 0）。
  final int? shippingDiscount;

  final int? payableTotal;

  /// 🔴 两段金额分开下发（FR-100A 规则 2）：只显示一个总数会让用户误解扣款构成。
  final int? coinAmount;
  final int? cashAmount;

  final int coinBalance;
  final int maxCoinPerOrder;

  /// 🔴 PawCoin 段**被单笔上限截断**（C-16 / UX-DR14）→ 必须多展示一行「本单最多可用 …」。
  final bool coinCapped;

  /// 🔴 多 SKU 取**最严**（S-6）：不可退 > 开封不退 > 可退。
  final ReturnPolicy strictestReturnPolicy;

  /// 两段都有 → 底栏展示 `PawCoin x + QRIS y`。
  bool get isMixed => (coinAmount ?? 0) > 0 && (cashAmount ?? 0) > 0;

  bool get canSubmit => serviceable && lines.isNotEmpty;

  factory CheckoutPreview.fromJson(Map<String, dynamic> json) {
    final addr = json['address'];
    final a = addr is Map<String, dynamic> ? addr : const <String, dynamic>{};
    return CheckoutPreview(
      addressToken: a['token']?.toString() ?? '',
      receiverName: a['receiverName']?.toString() ?? '',
      receiverPhone: a['receiverPhone']?.toString() ?? '',
      addressText: [
        a['addressLine']?.toString(),
        a['kecamatan']?.toString(),
        a['kotaKabupaten']?.toString(),
        a['provinsi']?.toString(),
        a['kodePos']?.toString(),
      ].whereType<String>().where((s) => s.isNotEmpty).join(', '),
      serviceable: json['serviceable'] == true,
      lines: _lines(json['lines']),
      unavailableLines: _lines(json['unavailableLines']),
      goodsSubtotal: _int(json['goodsSubtotal']) ?? 0,
      shippingFee: _int(json['shippingFee']),
      shippingDiscount: _int(json['shippingDiscount']),
      payableTotal: _int(json['payableTotal']),
      coinAmount: _int(json['coinAmount']),
      cashAmount: _int(json['cashAmount']),
      coinBalance: _int(json['coinBalance']) ?? 0,
      maxCoinPerOrder: _int(json['maxCoinPerOrder']) ?? 0,
      coinCapped: json['coinCapped'] == true,
      strictestReturnPolicy: ReturnPolicy.fromApi(json['strictestReturnPolicy']?.toString()),
    );
  }

  static List<CheckoutLine> _lines(Object? raw) => raw is List
      ? raw.whereType<Map<String, dynamic>>().map(CheckoutLine.fromJson).toList(growable: false)
      : const [];

  static int? _int(Object? v) => v is num ? v.toInt() : null;
}

/// 下单被挡住的一行（后端 409 的 `unavailableLines` 扩展成员，FR-95）。
///
/// 🔴 存在的意义就是**不整单打回**：告诉用户具体是哪件、还剩几件，让他移除后继续。
class UnavailableLine {
  const UnavailableLine({
    required this.skuToken,
    required this.reason,
    required this.available,
    required this.requested,
    this.productName,
    this.specName,
  });

  final String skuToken;
  final String? productName;
  final String? specName;

  /// `DELISTED`（已下架，永久）/ `INSUFFICIENT_STOCK`（库存不足，暂时）。
  final String reason;
  final int available;
  final int requested;

  bool get isDelisted => reason == 'DELISTED';

  factory UnavailableLine.fromJson(Map<String, dynamic> json) => UnavailableLine(
        skuToken: json['skuToken']?.toString() ?? '',
        productName: json['productName']?.toString(),
        specName: json['specName']?.toString(),
        reason: json['reason']?.toString() ?? '',
        available: json['available'] is num ? (json['available'] as num).toInt() : 0,
        requested: json['requested'] is num ? (json['requested'] as num).toInt() : 0,
      );
}

/// 下单结果（Story 3.8 的订单详情会复用这些字段）。
class ShopOrderRef {
  const ShopOrderRef({
    required this.orderToken,
    required this.status,
    required this.totalAmount,
    this.payChannel,
    this.coinAmount,
    this.cashAmount,
  });

  /// 🔴 不可枚举 token（NFR-3）。后端从不下发自增 id / seq_no。
  final String orderToken;
  final String status;
  final int totalAmount;
  final String? payChannel;
  final int? coinAmount;
  final int? cashAmount;

  factory ShopOrderRef.fromJson(Map<String, dynamic> json) => ShopOrderRef(
        orderToken: json['orderToken']?.toString() ?? '',
        status: json['status']?.toString() ?? '',
        totalAmount: json['totalAmount'] is num ? (json['totalAmount'] as num).toInt() : 0,
        payChannel: json['payChannel']?.toString(),
        coinAmount: json['coinAmount'] is num ? (json['coinAmount'] as num).toInt() : null,
        cashAmount: json['cashAmount'] is num ? (json['cashAmount'] as num).toInt() : null,
      );
}

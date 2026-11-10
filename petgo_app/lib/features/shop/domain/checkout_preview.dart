/// 结算试算域模型（v1.4.2 shop Story 7.1 重写，消费 `POST /api/v1/me/checkout/preview`，
/// 后端 `CheckoutQuoteView`）。
///
/// 🔴 **金额一律取服务端下发值，前端不做任何再计算**（P-1：别另算一遍运费与拆分，
/// 两处必漂移）。这里的每个字段都是「显示什么」，不是「算什么」。金额缺键即 null。
library;

import 'free_shipping_progress.dart';
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
    this.entrySource,
    this.triggerType,
    this.availableStock,
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

  /// 失效行才有值（不可购买的行不参与提交）。新试算下发的是 `unavailableReason`
  /// （`DELISTED | OUT_OF_STOCK | INSUFFICIENT_STOCK | NOT_SELLABLE`），解析时两键取其一。
  final String? invalidReason;

  /// 失效行的可售库存（新试算 `availableStock`）；有效行为 null。
  final int? availableStock;

  /// 行级归因：用户从哪个入口进的这件商品（区域② 档案推荐 / 区域④ 全部精选 …）。
  ///
  /// 🔴 服务端在加购时就记在购物车行上、下单时抄到订单行（Story 3.4 / 3.10）。
  /// 端上拿到它**只为在 `toko_order_submitted` 里带上同一份值** —— 两套数据互为校验。
  /// ⚠️ 偏差过大即说明客户端埋点有丢失，**以服务端为准**（Story 9.2）。
  final String? entrySource;

  /// 行级归因：触发类型（复购提醒 / 主动浏览 …）。同 [entrySource]。
  final String? triggerType;

  bool get isUnavailable => invalidReason != null;

  /// 失效行 → 与提交 409 同形的 [UnavailableLine]（结算页内联展示 / 弹窗共用一套文案）。
  UnavailableLine toUnavailable() => UnavailableLine(
        skuToken: skuToken,
        productName: productName,
        specName: specName,
        reason: invalidReason ?? '',
        available: availableStock ?? 0,
        requested: qty,
      );

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
        invalidReason: _blankToNull(json['unavailableReason']?.toString()) ??
            _blankToNull(json['invalidReason']?.toString()),
        availableStock:
            json['availableStock'] is num ? (json['availableStock'] as num).toInt() : null,
        entrySource: _blankToNull(json['entrySource']?.toString()),
        triggerType: _blankToNull(json['triggerType']?.toString()),
      );

  static String? _blankToNull(String? s) => (s == null || s.isEmpty) ? null : s;
}

/// 本页「不可提交」的原因（后端 `notPlaceableReason`）。判序以服务端为准。
enum CheckoutBlockReason {
  noLines('NO_LINES'),
  unavailableLines('UNAVAILABLE_LINES'),
  noAddress('NO_ADDRESS'),
  notServiceable('NOT_SERVICEABLE'),
  quoteFailed('QUOTE_FAILED'),

  /// 未知值：按「不可提交」处理（宁可挡住一次，不放过一次）。
  unknown('');

  const CheckoutBlockReason(this.api);
  final String api;

  static CheckoutBlockReason? fromApi(Object? raw) {
    if (raw == null) return null;
    final s = raw.toString();
    if (s.isEmpty) return null;
    for (final v in values) {
      if (v != unknown && v.api == s) return v;
    }
    return unknown;
  }
}

/// 一家快递报价（服务端已按价格升序）。
class CourierOption {
  const CourierOption({
    required this.courierKey,
    required this.courierCode,
    required this.serviceCode,
    required this.courierName,
    required this.serviceName,
    required this.price,
    required this.insuranceSupported,
    required this.cheapest,
    this.etaText,
  });

  /// `<courierCode>:<serviceCode>`，提交时原样带回。
  final String courierKey;
  final String courierCode;
  final String serviceCode;
  final String courierName;
  final String serviceName;

  /// 时效区间原文（如 `2 - 4`），可空。
  final String? etaText;
  final int price;
  final bool insuranceSupported;
  final bool cheapest;

  factory CourierOption.fromJson(Map<String, dynamic> j) => CourierOption(
        courierKey: j['courierKey']?.toString() ?? '',
        courierCode: j['courierCode']?.toString() ?? '',
        serviceCode: j['serviceCode']?.toString() ?? '',
        courierName: j['courierName']?.toString() ?? '',
        serviceName: j['serviceName']?.toString() ?? '',
        etaText: _blank(j['etaText']?.toString()),
        price: _int(j['price']) ?? 0,
        insuranceSupported: j['insuranceSupported'] == true,
        cheapest: j['cheapest'] == true,
      );
}

/// 运费减免来源。
enum ShippingDiscountSource { none, freeShippingRule, coupon }

/// 金额区（= 后端 `PricingView`，全部为计价器原值）。
///
/// 🔴 [shippingIncluded] 为 false（无地址 / 不可配送 / 询价失败）时 `payable / coinAmount /
/// cashAmount` **不含运费**，页面一律显示「—」，绝不显示 Rp 0（FR-99）。
class CheckoutPricing {
  const CheckoutPricing({
    required this.goodsAmount,
    required this.discountCouponAmount,
    required this.shippingDiscountSource,
    required this.insuranceFee,
    required this.shippingIncluded,
    this.shippingFee,
    this.shippingDiscount,
    this.payable,
    this.coinAmount,
    this.cashAmount,
    this.savedAmount,
    this.freeShippingProgress,
  });

  final int goodsAmount;
  final int discountCouponAmount;
  final int? shippingFee;

  /// 运费减免（**正数**，减了多少）。
  final int? shippingDiscount;
  final ShippingDiscountSource shippingDiscountSource;
  final int insuranceFee;
  final int? payable;
  final int? coinAmount;
  final int? cashAmount;

  /// 已省 = 满减 + 运费减免（不含 PawCoin）。
  final int? savedAmount;
  final bool shippingIncluded;
  final FreeShippingProgress? freeShippingProgress;

  static const empty = CheckoutPricing(
    goodsAmount: 0,
    discountCouponAmount: 0,
    shippingDiscountSource: ShippingDiscountSource.none,
    insuranceFee: 0,
    shippingIncluded: false,
  );

  factory CheckoutPricing.fromJson(Map<String, dynamic> j) => CheckoutPricing(
        goodsAmount: _int(j['goodsAmount']) ?? 0,
        discountCouponAmount: _int(j['discountCouponAmount']) ?? 0,
        shippingFee: _int(j['shippingFee']),
        shippingDiscount: _int(j['shippingDiscount']),
        shippingDiscountSource: switch (j['shippingDiscountSource']?.toString()) {
          'FREE_SHIPPING_RULE' => ShippingDiscountSource.freeShippingRule,
          'COUPON' => ShippingDiscountSource.coupon,
          _ => ShippingDiscountSource.none,
        },
        insuranceFee: _int(j['insuranceFee']) ?? 0,
        payable: _int(j['payable']),
        coinAmount: _int(j['coinAmount']),
        cashAmount: _int(j['cashAmount']),
        savedAmount: _int(j['savedAmount']),
        shippingIncluded: j['shippingIncluded'] == true,
        freeShippingProgress: FreeShippingProgress.fromJson(j['freeShippingProgress']),
      );
}

/// 券种类。
enum CouponKind { discount, freeShipping }

/// 券不可用原因（后端 `unavailableReason`）。未知值落 [unknown]：按「不可用、不显示原因」处理。
enum CouponUnavailableReason {
  belowMinSpend,
  outOfScope,
  expired,
  ruleBetter,

  /// 手选但不在候选里（被占用 / 已失效 / 不是你的）。
  unavailable,
  unknown;

  static CouponUnavailableReason? fromApi(Object? raw) {
    if (raw == null || raw.toString().isEmpty) return null;
    return switch (raw.toString()) {
      'BELOW_MIN_SPEND' => belowMinSpend,
      'OUT_OF_SCOPE' => outOfScope,
      'EXPIRED' => expired,
      'RULE_BETTER' => ruleBetter,
      'UNAVAILABLE' => unavailable,
      _ => unknown,
    };
  }
}

/// 一张用户券（后端 `CouponOption`）。
class CouponOption {
  const CouponOption({
    required this.userCouponToken,
    required this.kind,
    required this.expiringSoon,
    required this.deduction,
    this.couponToken,
    this.nameId,
    this.nameEn,
    this.amount,
    this.minSpend,
    this.validUntil,
    this.reason,
  });

  final String userCouponToken;

  /// 券**模板** token（埋点 `coupon_ids` 用）。
  final String? couponToken;
  final CouponKind kind;
  final String? nameId;
  final String? nameEn;

  /// 满减：面额；免运：封顶额。
  final int? amount;
  final int? minSpend;
  final DateTime? validUntil;
  final bool expiringSoon;

  /// 若选它本单能减多少（服务端算，不可用为 0）。
  final int deduction;
  final CouponUnavailableReason? reason;

  /// 按语言取券名；缺一种取另一种。
  String nameFor(String languageCode) {
    final id = nameId ?? '';
    final en = nameEn ?? '';
    if (languageCode == 'id') return id.isNotEmpty ? id : en;
    return en.isNotEmpty ? en : id;
  }

  factory CouponOption.fromJson(Map<String, dynamic> j) => CouponOption(
        userCouponToken: j['userCouponToken']?.toString() ?? '',
        couponToken: _blank(j['couponToken']?.toString()),
        kind: j['kind']?.toString() == 'FREE_SHIPPING' ? CouponKind.freeShipping : CouponKind.discount,
        nameId: _blank(j['nameId']?.toString()),
        nameEn: _blank(j['nameEn']?.toString()),
        amount: _int(j['amount']),
        minSpend: _int(j['minSpend']),
        validUntil: j['validUntil'] is String ? DateTime.tryParse(j['validUntil'] as String) : null,
        expiringSoon: j['expiringSoon'] == true,
        deduction: _int(j['deduction']) ?? 0,
        reason: CouponUnavailableReason.fromApi(j['unavailableReason']),
      );
}

/// 一类券（满减 / 免运）。
///
/// 🔴 [selectedToken] = 计价器**实际采用**的那张；手选但减免为 0 / 输给满额免运的同样为 null ——
/// 页面据此写「Tidak dipakai」，不让用户以为券已用上。
class CouponGroup {
  const CouponGroup({this.selectedToken, this.available = const [], this.unavailable = const []});

  final String? selectedToken;
  final List<CouponOption> available;
  final List<CouponOption> unavailable;

  static const empty = CouponGroup();

  CouponOption? get selected => selectedToken == null
      ? null
      : available.where((c) => c.userCouponToken == selectedToken).firstOrNull;

  factory CouponGroup.fromJson(Object? raw) {
    if (raw is! Map<String, dynamic>) return empty;
    List<CouponOption> list(Object? v) => v is List
        ? v.whereType<Map<String, dynamic>>().map(CouponOption.fromJson).toList(growable: false)
        : const [];
    return CouponGroup(
      selectedToken: _blank(raw['selectedToken']?.toString()),
      available: list(raw['available']),
      unavailable: list(raw['unavailable']),
    );
  }
}

/// 结算页的地址块（与地址簿同字段，PII 只在本人页面展示）。
class CheckoutAddress {
  const CheckoutAddress({
    required this.token,
    required this.receiverName,
    required this.receiverPhone,
    required this.addressText,
  });

  final String token;
  final String receiverName;
  final String receiverPhone;

  /// 「详细地址, Kecamatan, Kota, Provinsi, 邮编」拼好的一行。
  final String addressText;

  static CheckoutAddress? fromJson(Object? raw) {
    if (raw is! Map<String, dynamic>) return null;
    final token = _blank(raw['token']?.toString());
    if (token == null) return null;
    return CheckoutAddress(
      token: token,
      receiverName: raw['receiverName']?.toString() ?? '',
      receiverPhone: raw['receiverPhone']?.toString() ?? '',
      addressText: [
        raw['addressLine']?.toString(),
        raw['kecamatan']?.toString(),
        raw['kotaKabupaten']?.toString(),
        raw['provinsi']?.toString(),
        raw['kodePos']?.toString(),
      ].whereType<String>().where((s) => s.isNotEmpty).join(', '),
    );
  }
}

/// 结算试算（后端 `CheckoutQuoteView`）。
class CheckoutPreview {
  const CheckoutPreview({
    required this.lines,
    required this.unavailableLines,
    required this.pricing,
    required this.strictestReturnPolicy,
    this.address,
    this.quoteToken,
    this.couriers = const [],
    this.selectedCourierKey,
    this.insuranceAvailable = false,
    this.insurance = false,
    this.discountCoupons = CouponGroup.empty,
    this.freeShippingCoupons = CouponGroup.empty,
    this.coinBalance = 0,
    this.coinCap = 0,
    this.coinEnabled = true,
    this.useCoin = false,
    this.coinCapReached = false,
    this.placeable = false,
    this.blockReason,
  });

  /// null = 用户还没有收货地址。
  final CheckoutAddress? address;

  /// 本次报价 token：提交必须带上（S-4：不带会被当作旧版 App）。无报价为 null。
  final String? quoteToken;
  final List<CourierOption> couriers;
  final String? selectedCourierKey;

  /// 所选快递是否支持保价（false → 不画保价行，D9）。
  final bool insuranceAvailable;
  final bool insurance;

  /// 可购买的行。
  final List<CheckoutLine> lines;

  /// 🔴 失效行（库内仍勾着但已不可买）：展示并引导移除，不参与金额。
  final List<CheckoutLine> unavailableLines;
  final CheckoutPricing pricing;
  final CouponGroup discountCoupons;
  final CouponGroup freeShippingCoupons;
  final int coinBalance;
  final int coinCap;
  final bool coinEnabled;

  /// 有效值（请求 ∧ 规则开启）。
  final bool useCoin;

  /// 用币被单笔上限截断（D13）。
  final bool coinCapReached;

  /// 🔴 多 SKU 取**最严**（S-6）：不可退 > 开封不退 > 可退。
  final ReturnPolicy strictestReturnPolicy;
  final bool placeable;

  /// null = 可下单。
  final CheckoutBlockReason? blockReason;

  bool get hasAddress => address != null;

  CourierOption? get selectedCourier => selectedCourierKey == null
      ? null
      : couriers.where((c) => c.courierKey == selectedCourierKey).firstOrNull;

  /// 金额位（运费 / 应付 / 币 / 现金）是否可展示：有报价才算数。
  bool get amountsKnown => pricing.shippingIncluded && quoteToken != null;

  /// 服务端判定可下单 ∧ 有报价 ∧ 有可买行。
  bool get canSubmit => placeable && blockReason == null && quoteToken != null && lines.isNotEmpty;

  factory CheckoutPreview.fromJson(Map<String, dynamic> json) {
    final all = _lines(json['lines']);
    final coupons = json['coupons'];
    final c = coupons is Map<String, dynamic> ? coupons : const <String, dynamic>{};
    final coin = json['coin'];
    final k = coin is Map<String, dynamic> ? coin : const <String, dynamic>{};
    final pricing = json['pricing'];
    return CheckoutPreview(
      address: CheckoutAddress.fromJson(json['address']),
      quoteToken: _blank(json['quoteToken']?.toString()),
      couriers: json['couriers'] is List
          ? (json['couriers'] as List)
              .whereType<Map<String, dynamic>>()
              .map(CourierOption.fromJson)
              .toList(growable: false)
          : const [],
      selectedCourierKey: _blank(json['selectedCourierKey']?.toString()),
      insuranceAvailable: json['insuranceAvailable'] == true,
      insurance: json['insurance'] == true,
      lines: all.where((l) => !l.isUnavailable).toList(growable: false),
      unavailableLines: all.where((l) => l.isUnavailable).toList(growable: false),
      pricing: pricing is Map<String, dynamic>
          ? CheckoutPricing.fromJson(pricing)
          : CheckoutPricing.empty,
      discountCoupons: CouponGroup.fromJson(c['discount']),
      freeShippingCoupons: CouponGroup.fromJson(c['freeShipping']),
      coinBalance: _int(k['balance']) ?? 0,
      coinCap: _int(k['cap']) ?? 0,
      coinEnabled: k['enabled'] != false,
      useCoin: k['useCoin'] == true,
      coinCapReached: k['capReached'] == true,
      strictestReturnPolicy: ReturnPolicy.fromApi(json['strictestReturnPolicy']?.toString()),
      placeable: json['placeable'] == true,
      blockReason: CheckoutBlockReason.fromApi(json['notPlaceableReason']),
    );
  }

  static List<CheckoutLine> _lines(Object? raw) => raw is List
      ? raw.whereType<Map<String, dynamic>>().map(CheckoutLine.fromJson).toList(growable: false)
      : const [];
}

/// 结算来源。
enum CheckoutSource {
  cart('CART'),
  buyNow('BUY_NOW');

  const CheckoutSource(this.api);
  final String api;
}

/// 试算请求（`POST /me/checkout/preview`）。
class CheckoutRequest {
  const CheckoutRequest({
    required this.source,
    this.buyNowSkuToken,
    this.buyNowQty = 1,
    this.buyNowEntrySource,
    this.addressToken,
    this.courierKey,
    this.insurance = false,
    this.manualCoupons = false,
    this.discountCouponToken,
    this.freeShippingCouponToken,
    this.useCoin = true,
  });

  final CheckoutSource source;
  final String? buyNowSkuToken;
  final int buyNowQty;

  /// BUY_NOW 行的归因来源（v1.4.2 shop 8.2 复审：详情页 `?from=` 一路透传；null = 未知，不发该键）。
  final String? buyNowEntrySource;

  /// null = 服务端用默认地址（没有地址则不询价）。
  final String? addressToken;
  final String? courierKey;
  final bool insurance;

  /// false = `AUTO`（服务端默认最优组合）；true = `MANUAL`（只用下面两张，null = 不用）。
  final bool manualCoupons;
  final String? discountCouponToken;
  final String? freeShippingCouponToken;
  final bool useCoin;

  Map<String, dynamic> toJson() => {
        'source': source.api,
        if (source == CheckoutSource.buyNow)
          'buyNow': {
            'skuToken': buyNowSkuToken,
            'qty': buyNowQty,
            'entrySource': ?buyNowEntrySource,
          },
        'addressToken': ?addressToken,
        'courierKey': ?courierKey,
        'insurance': insurance,
        'couponMode': manualCoupons ? 'MANUAL' : 'AUTO',
        if (manualCoupons) ...{
          'discountCouponToken': ?discountCouponToken,
          'freeShippingCouponToken': ?freeShippingCouponToken,
        },
        'useCoin': useCoin,
      };
}

/// 提交请求（`POST /me/shop-orders` 新结算模式；🔴 判别只看 [quoteToken]）。
class PlaceOrderRequest {
  const PlaceOrderRequest({
    required this.source,
    required this.addressToken,
    required this.quoteToken,
    required this.courierKey,
    required this.insurance,
    required this.useCoin,
    this.buyNowSkuToken,
    this.buyNowQty = 1,
    this.buyNowEntrySource,
    this.discountCouponToken,
    this.freeShippingCouponToken,
  });

  final CheckoutSource source;
  final String? buyNowSkuToken;
  final int buyNowQty;

  /// BUY_NOW 行的归因来源（v1.4.2 shop 8.2 复审：详情页 `?from=` 一路透传；null = 未知，不发该键）。
  final String? buyNowEntrySource;
  final String addressToken;
  final String quoteToken;
  final String courierKey;
  final bool insurance;
  final String? discountCouponToken;
  final String? freeShippingCouponToken;
  final bool useCoin;

  Map<String, dynamic> toJson() => {
        'source': source.api,
        if (source == CheckoutSource.buyNow)
          'buyNow': {
            'skuToken': buyNowSkuToken,
            'qty': buyNowQty,
            'entrySource': ?buyNowEntrySource,
          },
        'addressToken': addressToken,
        'quoteToken': quoteToken,
        'courierKey': courierKey,
        'insurance': insurance,
        'discountCouponToken': ?discountCouponToken,
        'freeShippingCouponToken': ?freeShippingCouponToken,
        'useCoin': useCoin,
        // 页面上的同意勾选未勾时提交按钮不可点，走到这里必为 true（FR-104）。
        'agreeReturnPolicy': true,
      };
}

int? _int(Object? v) => v is num ? v.toInt() : null;

String? _blank(String? s) => (s == null || s.isEmpty) ? null : s;

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

  /// `DELISTED`（已下架，永久）/ `INSUFFICIENT_STOCK`（库存不足，暂时）/
  /// `OUT_OF_STOCK` / `NOT_SELLABLE`（新试算 / 新结算模式；按库存不足展示）。
  final String reason;
  final int available;
  final int requested;

  bool get isDelisted => reason == 'DELISTED';

  /// 包裹参数不全、暂不可售（V1.4.2 验收修复 BUG-142-01：不能按库存不足展示「Sisa N, diminta M」）。
  bool get isNotSellable => reason == 'NOT_SELLABLE';

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
    this.paid = false,
  });

  /// 🔴 不可枚举 token（NFR-3）。后端从不下发自增 id / seq_no。
  final String orderToken;
  final String status;
  final int totalAmount;
  final String? payChannel;
  final int? coinAmount;
  final int? cashAmount;

  /// 服务端判定已付款（纯 PawCoin / 零元单提交即结清，3.2 `ShopOrderView.paid`）。
  final bool paid;

  /// 🔴 已结清 → App 直接进支付成功页、**不调 `/pay`**（S-3 / C-4）。
  /// 兼容未下发 `paid` 的后端：状态已离开待支付且不是已取消也算。
  bool get settled =>
      paid || (status.isNotEmpty && status != 'PENDING_PAYMENT' && status != 'CANCELLED');

  factory ShopOrderRef.fromJson(Map<String, dynamic> json) => ShopOrderRef(
        orderToken: json['orderToken']?.toString() ?? '',
        status: json['status']?.toString() ?? '',
        totalAmount: json['totalAmount'] is num ? (json['totalAmount'] as num).toInt() : 0,
        payChannel: json['payChannel']?.toString(),
        coinAmount: json['coinAmount'] is num ? (json['coinAmount'] as num).toInt() : null,
        cashAmount: json['cashAmount'] is num ? (json['cashAmount'] as num).toInt() : null,
        paid: json['paid'] == true,
      );
}

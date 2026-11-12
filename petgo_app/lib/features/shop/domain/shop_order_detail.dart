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

  /// 已完成付款并进入履约链（含其后的终态）。🔴 二维码轮询只认这些 —— 「不再待支付」
  /// 还包括 CANCELLED（付款窗到期 / 另一台设备取消），把它当成功会弹「支付成功」并污染埋点。
  bool get isPaidOrLater =>
      this == ShopOrderStatus.pendingShipment ||
      this == ShopOrderStatus.shipped ||
      this == ShopOrderStatus.delivered ||
      this == ShopOrderStatus.completed ||
      this == ShopOrderStatus.refunding ||
      this == ShopOrderStatus.refunded;

  /// 🔴 **已发货态即可确认收货**（Story 4.5，SPEC-2 出口②）——不必等系统标记送达。
  /// 用户比谁都先知道货到没到；只留「已送达后才能确认」这一条路，等于把订单能否
  /// 脱离「已发货」完全押在运营记不记得点那个按钮上。
  bool get canConfirmReceipt =>
      this == ShopOrderStatus.shipped || this == ShopOrderStatus.delivered;

  /// 已发货之后（含终态）才展示物流区块。
  bool get hasFulfillmentInfo =>
      this == ShopOrderStatus.shipped ||
      this == ShopOrderStatus.delivered ||
      this == ShopOrderStatus.completed;
}

/// 支付失败类别（Story 1-3；后端 `PaymentFailureCategory` 三值的镜像）。
///
/// 🔴 **未知字符串一律落 [unknown]，绝不映射成三态中的任何一个**（C4：禁止在客户端
/// 兜底转换抹平契约差异）。后端将来加第四个值时，App 猜错一次就是给用户一个错误的
/// 处置建议 —— 该给重试的不给、不该给的给了。[unknown] 在 UI 上按「通用失败」处理。
enum ShopPaymentFailure {
  /// 网关拒付。订单仍在待支付窗内，**保留重试入口**。
  gatewayDeclined('GATEWAY_DECLINED'),

  /// 付款窗超时。订单已取消，**不给重试**。
  expired('EXPIRED'),

  /// 用户自己取消了订单。静默，不弹任何错误。
  userCancelled('USER_CANCELLED'),

  /// 认不出的类别（后端新增而 App 未升级）。
  unknown('');

  const ShopPaymentFailure(this.api);

  final String api;

  /// 缺值 / 空串 → null（＝没失败）；认不出的非空值 → [unknown]。
  static ShopPaymentFailure? fromApi(String? raw) {
    if (raw == null || raw.isEmpty) return null;
    for (final f in values) {
      if (f.api == raw && f != unknown) return f;
    }
    return unknown;
  }
}

/// 一个包裹（Story 4.5，S-2 一单多包）。
///
/// 🔴 **不接承运商 API、不在 App 内渲染物流轨迹**（FR-103）：[trackingUrl] 是承运商官网
/// 地址，点了跳出去查。自建轨迹聚合要为三家 API 的可用性长期负责，V1 不承担这个。
class ShopOrderPackage {
  const ShopOrderPackage({
    required this.carrier,
    required this.carrierName,
    required this.trackingNo,
    required this.trackingUrl,
    required this.delivered,
    this.shippedAt,
    this.deliveredAt,
    this.superseded = false,
  });

  final String carrier;

  /// 已被快递原因重发取代（v1.4.2 shop 5.1 末尾追加；旧后端缺键 → false）。
  final bool superseded;

  /// 展示名（品牌官方写法，不等于枚举名）。
  final String carrierName;
  final String trackingNo;
  final String trackingUrl;
  final bool delivered;
  final DateTime? shippedAt;
  final DateTime? deliveredAt;

  factory ShopOrderPackage.fromJson(Map<String, dynamic> j) {
    final carrier = j['carrier']?.toString() ?? '';
    final name = j['carrierName']?.toString();
    return ShopOrderPackage(
      carrier: carrier,
      // 认不出的承运商就把原始码显示出来 —— 显示一个空白比显示 'JNE' 好：
      // 猜错等于把包裹记到别人家的运单号上，用户点进去查无此单。
      carrierName: (name == null || name.isEmpty) ? carrier : name,
      trackingNo: j['trackingNo']?.toString() ?? '',
      trackingUrl: j['trackingUrl']?.toString() ?? '',
      delivered: j['status']?.toString() == 'DELIVERED',
      shippedAt: _time(j['shippedAt']),
      deliveredAt: _time(j['deliveredAt']),
      superseded: j['superseded'] == true,
    );
  }
}

class ShopOrderLine {
  const ShopOrderLine({
    required this.productName,
    required this.specName,
    required this.unitPrice,
    required this.qty,
    required this.lineTotal,
    this.mainImageUrl,
    this.productToken,
    this.skuToken,
  });

  final String productName;
  final String specName;
  final int unitPrice;
  final int qty;
  final int lineTotal;

  /// 商品主图 CDN 全 URL（2026-09-03 后端追加）。**可空** —— 无图 / CDN 未配置时为 null，
  /// [ShopImage] 收到 null 会画占位斜纹。
  /// ⚠️ 服务端是**读时派生**（现取商品当前主图），不是下单快照：运营换了主图，
  /// 历史订单的缩略图会跟着变。它的用途是「认出是哪件」，不是留证。
  final String? mainImageUrl;

  /// 商品 / SKU 公开 token（analytics-pipeline Story 3.4，后端末尾追加）。**可空** —— 旧后端不下发、
  /// SKU 被物理删除时为 null。只用于埋点 `toko_order_pay_tapped.items[].product_token`（漏斗按商品关联）。
  final String? productToken;
  final String? skuToken;

  factory ShopOrderLine.fromJson(Map<String, dynamic> j) => ShopOrderLine(
        productName: j['productName']?.toString() ?? '',
        specName: j['specName']?.toString() ?? '',
        unitPrice: _int(j['unitPrice']) ?? 0,
        qty: _int(j['qty']) ?? 0,
        lineTotal: _int(j['lineTotal']) ?? 0,
        mainImageUrl: _blankToNull(j['mainImageUrl']?.toString()),
        productToken: _blankToNull(j['productToken']?.toString()),
        skuToken: _blankToNull(j['skuToken']?.toString()),
      );
}

class ShopOrderDetail {
  const ShopOrderDetail({
    required this.orderToken,
    this.displayNo = '',
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
    this.shippedAt,
    this.deliveredAt,
    this.completedAt,
    this.returnWindowEndsAt,
    this.packages = const [],
    this.attributionSource = 'unknown',
    this.paymentStatus,
    this.paymentFailureCategory,
    this.canPay,
    this.displayStatus,
    this.paymentDeadline,
    this.paidAt,
    this.cancelledAt,
    this.cancelReasonRaw,
    this.amounts,
    this.paymentMethods,
    this.logistics,
    this.refunds = const [],
    this.cancelledLines = const [],
    this.couponReturn,
    this.reorderedToOrderToken,
    this.reorderedFromOrderNo,
    this.reorderedFromOrderToken,
  });

  final String orderToken;

  /// 对外展示号 `TOKO-yyyyMMdd-XXXXXX`（Story 4-3 · SHOP-FR-29）。
  ///
  /// 🔴 **给人看的是它，查询用的仍是 [orderToken]**。此前本页展示的就是 22 位
  /// `orderToken`，而订单中心列表/详情展示的是 `TOKO-…` —— 同一张单两个字符串，
  /// 用户报给客服的号后台还搜不到。
  ///
  /// 🔴 **缺省空串而不是 null**：灰度期老后端不下发这个字段，页面回落到 `orderToken`
  /// 显示（见 `_displayedOrderNo`）—— 显示一个旧格式的号，好过显示一片空白。
  final String displayNo;

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

  // ---------- 履约态（Story 4.5） ----------
  final DateTime? shippedAt;

  /// 🔴 「签收」时刻（SPEC-5）。退货窗口以此起算。
  final DateTime? deliveredAt;
  final DateTime? completedAt;

  /// 退货窗口截止 —— **服务端算好下发**，App 不自己加 7 天：
  /// 时区与设备时钟一错，用户看到的截止日就和后端不是同一天。
  final DateTime? returnWindowEndsAt;

  /// S-2 一单多包：逐条列出，各自标明送达状态。
  final List<ShopOrderPackage> packages;

  final List<ShopOrderLine> lines;
  final String receiverName;
  final String receiverPhone;
  final String addressText;

  /// 整单归因来源（服务端算好下发）：全部行同源取该源 / 多源 `mixed` / 无源 `unknown`。
  ///
  /// 🔴 只用于 `toko_order_payment_succeeded` 的 `attribution_source`，
  /// **与服务端行级归因互为校验**（Story 9.2）。⚠️ 权威值始终在服务端。
  final String attributionSource;

  // ---------- 支付可感知（Story 1-1，后端 AD-S9(a) 下发） ----------

  /// 支付意图状态原文：`PENDING` / `PAID` / `FAILED` / `EXPIRED`。
  /// 无支付单（纯 PawCoin 单）或后端版本较老时为 null。
  ///
  /// 🔴 **刻意保持 `String?` 不在此处枚举化、不兜底成某个默认值**（C4：禁止在客户端
  /// 兜底转换抹平契约差异）——把未知值悄悄折成 `FAILED` 之类，等于用客户端的猜测
  /// 覆盖服务端的事实。枚举化与界面处置全部留给 Story 1-3。
  final String? paymentStatus;

  /// 支付失败类别原文：`GATEWAY_DECLINED` / `EXPIRED` / `USER_CANCELLED`。未失败为 null。
  ///
  /// 🔒 分类由后端一处算出（`PaymentFailureCategory.of`），App **不解析网关 meta** ——
  /// meta 是第三方回调原文，随时可能含 PII。
  final String? paymentFailureCategory;

  /// [paymentFailureCategory] 的枚举形态（Story 1-3）。未失败为 null。
  ShopPaymentFailure? get paymentFailure =>
      ShopPaymentFailure.fromApi(paymentFailureCategory);

  // ---------- v1.4.2 shop Story 3.3 / 7.2 付款窗口（S-7） ----------

  /// 现在还能不能点「去支付」（与服务端 `/pay` 放行同一判定 `payGate`）。
  /// 缺键 = 旧后端 → null，调用方回落到既有 `expiresAt` 判断。
  final bool? canPay;

  /// 对用户展示的状态原文（宽限期待支付为 `CANCELLED`，S-7）。缺键 → null。
  /// 🔴 只用于**展示**；支付面板的中止判据仍看原始 [status]（宽限期到账要能接住）。
  final String? displayStatus;

  // ---------- v1.4.2 shop Story 6.2 / App 9.1 详情收口（D-19；全部可空、缺键容错，存量单 / 旧后端为 null） ----------

  /// 付款截止（= 订单 `expires_at`，后端 3.3）。缺键回落 [expiresAt]，见 [deadline]。
  final DateTime? paymentDeadline;
  final DateTime? paidAt;
  final DateTime? cancelledAt;

  /// 取消原因原文（后端 `CancelReason` 名）；枚举形态见 [cancelReason]。
  final String? cancelReasonRaw;

  /// 金额明细（订单头快照，P-1：App 只展示不加减）。缺失 → 页面整块回落旧字段。
  final ShopOrderAmounts? amounts;

  /// 支付方式两段 + 各自状态位（服务端判定）。缺失 → 页面回落旧字段 [coinAmount] / [cashAmount]。
  final ShopPaymentMethods? paymentMethods;

  /// 最新活动运单（5.1 / 5.2）。null = 无运单或存量 → 页面降级读 [packages]。
  final ShopLogistics? logistics;

  /// 取消退款单（4.1）。本 story 只读 `scope=LINE` 的金额做 F17 合计；其余归 9.2。
  final List<ShopOrderRefundSummary> refunds;

  /// 部分取消的行（F17）。
  final List<ShopCancelledLine> cancelledLines;

  /// 券退回结果原文：`RETURNED | EXPIRED | NOT_RETURNED`；未取消 / 未用券为 null。
  final String? couponReturn;

  /// 本单是被重新下单的原单 → 新单 token（F20）。
  final String? reorderedToOrderToken;

  /// 本单由原单重下 → 原单展示号（F21）。
  final String? reorderedFromOrderNo;

  /// 本单由原单重下 → 原单 token（C-9，可点回原单）。
  final String? reorderedFromOrderToken;

  CancelReason? get cancelReason => CancelReason.fromApi(cancelReasonRaw);

  /// 展示状态（S-7）：`displayStatus` 缺失回落原始 [status]。🔴 只用于展示。
  ShopOrderStatus get shownStatus =>
      displayStatus == null ? status : ShopOrderStatus.fromApi(displayStatus);

  /// 倒计时用的截止时刻：服务端 `paymentDeadline`，旧后端回落 `expiresAt`。
  DateTime? get deadline => paymentDeadline ?? expiresAt;

  /// 已确认的配送异常（5.2：`logistics.exception == DELIVERY_ISSUE`）。
  bool get hasDeliveryIssue => logistics?.exception == ShopLogistics.deliveryIssue;

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
      displayNo: j['displayNo']?.toString() ?? '',
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
      shippedAt: _time(j['shippedAt']),
      deliveredAt: _time(j['deliveredAt']),
      completedAt: _time(j['completedAt']),
      returnWindowEndsAt: _time(j['returnWindowEndsAt']),
      attributionSource: j['attributionSource']?.toString() ?? 'unknown',
      // 缺键 → null（老后端）。不做任何值的归一化或兜底，见字段注释。
      paymentStatus: j['paymentStatus']?.toString(),
      paymentFailureCategory: j['paymentFailureCategory']?.toString(),
      canPay: j['canPay'] is bool ? j['canPay'] as bool : null,
      displayStatus: j['displayStatus']?.toString(),
      paymentDeadline: _time(j['paymentDeadline']),
      paidAt: _time(j['paidAt']),
      cancelledAt: _time(j['cancelledAt']),
      cancelReasonRaw: _blankToNull(j['cancelReason']?.toString()),
      amounts: j['amounts'] is Map<String, dynamic>
          ? ShopOrderAmounts.fromJson(j['amounts'] as Map<String, dynamic>)
          : null,
      paymentMethods: j['paymentMethods'] is Map<String, dynamic>
          ? ShopPaymentMethods.fromJson(j['paymentMethods'] as Map<String, dynamic>)
          : null,
      logistics: j['logistics'] is Map<String, dynamic>
          ? ShopLogistics.fromJson(j['logistics'] as Map<String, dynamic>)
          : null,
      refunds: _list(j['refunds'], ShopOrderRefundSummary.fromJson),
      cancelledLines: _list(j['cancelledLines'], ShopCancelledLine.fromJson),
      couponReturn: _blankToNull(j['couponReturn']?.toString()),
      reorderedToOrderToken: _blankToNull(j['reorderedToOrderToken']?.toString()),
      reorderedFromOrderNo: _blankToNull(j['reorderedFromOrderNo']?.toString()),
      reorderedFromOrderToken: _blankToNull(j['reorderedFromOrderToken']?.toString()),
      packages: j['packages'] is List
          ? (j['packages'] as List)
              .whereType<Map<String, dynamic>>()
              .map(ShopOrderPackage.fromJson)
              .toList(growable: false)
          : const [],
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

/// 取消原因（后端 `CancelReason` 九值镜像，4.1 / D-14）。认不出 → [unknown]（只影响副句，不做资金判断）。
enum CancelReason {
  userUnpaidCancel('USER_UNPAID_CANCEL'),
  paymentTimeout('PAYMENT_TIMEOUT'),
  outOfStock('OUT_OF_STOCK'),
  waybillFailed('WAYBILL_FAILED'),
  pickupFailed('PICKUP_FAILED'),
  courierFault('COURIER_FAULT'),
  userFaultReturned('USER_FAULT_RETURNED'),
  lostDamaged('LOST_DAMAGED'),
  latePayment('LATE_PAYMENT'),
  unknown('');

  const CancelReason(this.api);

  final String api;

  /// 缺值 → null（存量 / 未取消）；认不出的非空值 → [unknown]。
  static CancelReason? fromApi(String? raw) {
    if (raw == null || raw.isEmpty) return null;
    for (final r in values) {
      if (r.api == raw && r != unknown) return r;
    }
    return unknown;
  }
}

/// 金额明细（后端 `ShopOrderDetailView.Amounts`，订单头快照）。🔴 App 不做任何加减（P-1）。
class ShopOrderAmounts {
  const ShopOrderAmounts({
    required this.goodsAmount,
    this.discountCouponAmount = 0,
    this.shippingFee = 0,
    this.shippingDiscount = 0,
    this.shippingDiscountSource,
    this.insuranceFee = 0,
    required this.payable,
    this.courierName,
    this.courierServiceName,
  });

  final int goodsAmount;
  final int discountCouponAmount;
  final int shippingFee;
  final int shippingDiscount;

  /// `NONE | FREE_SHIPPING_RULE | COUPON`；存量单为 null。
  final String? shippingDiscountSource;
  final int insuranceFee;

  /// 应付（`total_amount`）。
  final int payable;
  final String? courierName;
  final String? courierServiceName;

  factory ShopOrderAmounts.fromJson(Map<String, dynamic> j) => ShopOrderAmounts(
        goodsAmount: _int(j['goodsAmount']) ?? 0,
        discountCouponAmount: _int(j['discountCouponAmount']) ?? 0,
        shippingFee: _int(j['shippingFee']) ?? 0,
        // 后端订单口径存负数（shipping_discount ≤ 0），这里统一成正的减免额，界面按「−Rp X」展示。
        shippingDiscount: (_int(j['shippingDiscount']) ?? 0).abs(),
        shippingDiscountSource: _blankToNull(j['shippingDiscountSource']?.toString()),
        insuranceFee: _int(j['insuranceFee']) ?? 0,
        payable: _int(j['payable']) ?? 0,
        courierName: _blankToNull(j['courierName']?.toString()),
        courierServiceName: _blankToNull(j['courierServiceName']?.toString()),
      );
}

/// 支付方式两段（后端 `PaymentMethodsView`）：金额为 0 的一段整段为 null。
/// 🔴 状态位由服务端判定（HELD / PAID / RETURNED；AWAITING / PAID / UNPAID），App 不推断。
class ShopPaymentMethods {
  const ShopPaymentMethods({this.coinAmount, this.coinStatus, this.cashAmount, this.cashStatus});

  final int? coinAmount;
  final String? coinStatus;
  final int? cashAmount;
  final String? cashStatus;

  factory ShopPaymentMethods.fromJson(Map<String, dynamic> j) => ShopPaymentMethods(
        coinAmount: _int(j['coinAmount']),
        coinStatus: _blankToNull(j['coinStatus']?.toString()),
        cashAmount: _int(j['cashAmount']),
        cashStatus: _blankToNull(j['cashStatus']?.toString()),
      );
}

/// 物流块（后端 `ShopOrderDetailView.Logistics`，5.1 / 5.2）。
class ShopLogistics {
  const ShopLogistics({
    required this.provider,
    this.courierName,
    this.courierServiceName,
    this.waybillId,
    this.trackingUrl,
    this.statusKey,
    this.exception,
    this.exceptionType,
  });

  static const String deliveryIssue = 'DELIVERY_ISSUE';

  /// `MANUAL | BITESHIP`。
  final String provider;
  final String? courierName;
  final String? courierServiceName;
  final String? waybillId;
  final String? trackingUrl;

  /// Biteship `user_status_key`（`awaiting_pickup | picked | in_transit | out_for_delivery | delivered`）；手工恒 null。
  final String? statusKey;

  /// 已确认的用户侧配送异常 → `DELIVERY_ISSUE`，否则 null。
  final String? exception;

  /// `RETURNED | LOST | DAMAGED`（C-6），否则 null。
  final String? exceptionType;

  bool get isBiteship => provider == 'BITESHIP';

  factory ShopLogistics.fromJson(Map<String, dynamic> j) => ShopLogistics(
        provider: j['provider']?.toString() ?? '',
        courierName: _blankToNull(j['courierName']?.toString()),
        courierServiceName: _blankToNull(j['courierServiceName']?.toString()),
        waybillId: _blankToNull(j['waybillId']?.toString()),
        trackingUrl: _blankToNull(j['trackingUrl']?.toString()),
        statusKey: _blankToNull(j['statusKey']?.toString()),
        exception: _blankToNull(j['exception']?.toString()),
        exceptionType: _blankToNull(j['exceptionType']?.toString()),
      );
}

/// 取消退款单状态（后端 `ShopRefundStatus` 四值镜像，4.1 / D-15）。
///
/// ⚠️ 后端**没有** `COIN_REFUNDED`（币段建单即退、现金段 = 0 直接 `COMPLETED`）。认不出 → [unknown]：
/// 不改状态头、不给入口（对认不出的状态做资金动作是最糟的选择）。
enum ShopRefundStatus {
  awaitingDestination('AWAITING_DESTINATION'),
  payoutPending('PAYOUT_PENDING'),
  payoutFailed('PAYOUT_FAILED'),
  completed('COMPLETED'),
  unknown('');

  const ShopRefundStatus(this.api);

  final String api;

  static ShopRefundStatus fromApi(String? raw) {
    for (final s in values) {
      if (s.api == raw && s != unknown) return s;
    }
    return unknown;
  }
}

/// 退款单范围（后端 `RefundScope`）：整单 / 部分取消一行 / 签收后破损赔付（S-9，订单状态不变）。
enum RefundScope {
  whole('WHOLE'),
  line('LINE'),
  compensation('COMPENSATION'),
  unknown('');

  const RefundScope(this.api);

  final String api;

  static RefundScope fromApi(String? raw) {
    for (final s in values) {
      if (s.api == raw && s != unknown) return s;
    }
    return unknown;
  }
}

/// 取消退款单（后端 `ShopOrderRefundView`，4.1 / C-9；订单详情 `refunds[]` 与选择去向接口响应同一结构）。
///
/// 🔴 **金额全部是服务端值**（P-1），App 只展示。🔒 收款账号只有末 4 位（[payoutAccountLast4]），
/// 全号与户名不下发；本类不持有、也不得被扩成持有完整账号。缺键一律容错（旧后端 / 存量单）。
class ShopOrderRefundSummary {
  const ShopOrderRefundSummary({
    required this.refundToken,
    required this.scope,
    this.reason,
    this.status,
    this.totalRefund = 0,
    this.coinRefund = 0,
    this.cashRefund = 0,
    this.compensationAmount = 0,
    this.payoutFee = 0,
    this.cashDestination,
    this.payoutChannel,
    this.payoutAccountLast4,
    this.canChooseDestination = false,
    this.payoutFailed = false,
    this.paidOutAt,
    this.createdAt,
    this.destinationChosenAt,
    this.compensate = false,
    this.compensationIfPawcoin = 0,
  });

  final String refundToken;

  /// `WHOLE | LINE | COMPENSATION`。
  final String scope;
  final String? reason;
  final String? status;
  final int totalRefund;
  final int coinRefund;
  final int cashRefund;

  /// 已发放补偿（现金段选 PawCoin 完成后；其余为 0）。
  final int compensationAmount;

  /// 渠道费（选 TO_BANK 后由服务端按渠道写入；其余为 0）。
  final int payoutFee;

  /// `TO_PAWCOIN | TO_BANK`；未选为 null。
  final String? cashDestination;

  /// `BCA | OVO | GOPAY`；未选银行为 null。
  final String? payoutChannel;

  /// 收款账号末 4 位（C-9）。🔒 只末 4 位。
  final String? payoutAccountLast4;
  final bool canChooseDestination;
  final bool payoutFailed;
  final DateTime? paidOutAt;
  final DateTime? createdAt;
  final DateTime? destinationChosenAt;

  /// 该原因是否给补偿（= 后端 `CancelReason.compensate()`）。
  final bool compensate;

  /// 现金段选 PawCoin 时将发放的补偿（服务端按当前配置预估；无补偿 / 不可选为 0）。
  final int compensationIfPawcoin;

  bool get isLine => scope == 'LINE';

  ShopRefundStatus get refundStatus => ShopRefundStatus.fromApi(status);

  RefundScope get refundScope => RefundScope.fromApi(scope);

  CancelReason? get cancelReason => CancelReason.fromApi(reason);

  factory ShopOrderRefundSummary.fromJson(Map<String, dynamic> j) => ShopOrderRefundSummary(
        refundToken: j['refundToken']?.toString() ?? '',
        scope: j['scope']?.toString() ?? '',
        reason: _blankToNull(j['reason']?.toString()),
        status: _blankToNull(j['status']?.toString()),
        totalRefund: _int(j['totalRefund']) ?? 0,
        coinRefund: _int(j['coinRefund']) ?? 0,
        cashRefund: _int(j['cashRefund']) ?? 0,
        compensationAmount: _int(j['compensationAmount']) ?? 0,
        payoutFee: _int(j['payoutFee']) ?? 0,
        cashDestination: _blankToNull(j['cashDestination']?.toString()),
        payoutChannel: _blankToNull(j['payoutChannel']?.toString()),
        payoutAccountLast4: _blankToNull(j['payoutAccountLast4']?.toString()),
        canChooseDestination: j['canChooseDestination'] == true,
        payoutFailed: j['payoutFailed'] == true,
        paidOutAt: _time(j['paidOutAt']),
        createdAt: _time(j['createdAt']),
        destinationChosenAt: _time(j['destinationChosenAt']),
        compensate: j['compensate'] == true,
        compensationIfPawcoin: _int(j['compensationIfPawcoin']) ?? 0,
      );
}

/// 部分取消行（F17，后端 `ShopOrderDetailView.CancelledLine`）。
class ShopCancelledLine {
  const ShopCancelledLine({
    required this.lineIndex,
    this.productName = '',
    this.specName = '',
    this.cancelledQty = 0,
    this.refundAmount = 0,
  });

  /// 对应 `lines` 数组下标。
  final int lineIndex;
  final String productName;
  final String specName;
  final int cancelledQty;
  final int refundAmount;

  factory ShopCancelledLine.fromJson(Map<String, dynamic> j) => ShopCancelledLine(
        lineIndex: _int(j['lineIndex']) ?? -1,
        productName: j['productName']?.toString() ?? '',
        specName: j['specName']?.toString() ?? '',
        cancelledQty: _int(j['cancelledQty']) ?? 0,
        refundAmount: _int(j['refundAmount']) ?? 0,
      );
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

/// 「我已付款」查单结果（后端 `PaymentCheckView`，v1.4.2 shop Story 3.3）。
class PaymentCheckResult {
  const PaymentCheckResult({required this.paid, this.status, this.displayStatus});

  /// 服务端判定已到账（订单不在待支付 / 已取消）。
  final bool paid;
  final String? status;
  final String? displayStatus;

  factory PaymentCheckResult.fromJson(Map<String, dynamic> j) => PaymentCheckResult(
        // 缺键 / 非布尔一律按未到账：宁可让用户再查一次，也不把没到账说成到账。
        paid: j['paid'] == true,
        status: j['status']?.toString(),
        displayStatus: j['displayStatus']?.toString(),
      );
}

int? _int(Object? v) => v is num ? v.toInt() : null;

List<T> _list<T>(Object? v, T Function(Map<String, dynamic>) f) => v is List
    ? v.whereType<Map<String, dynamic>>().map(f).toList(growable: false)
    : List<T>.empty();

/// 空串按「没有」处理：后端派生不出 URL 时给的是 null，但历史/边界数据可能是空串，
/// 两者对 UI 是同一件事 —— 走占位图，而不是拿空串去请求自己的域名。
String? _blankToNull(String? s) => (s == null || s.isEmpty) ? null : s;

DateTime? _time(Object? v) {
  if (v is! String || v.isEmpty) return null;
  // 🔴 统一转本地时区再比较：后端一律 UTC（CLAUDE.md 命名映射链），
  //    直接拿 UTC 值与本地 now 相减会差出整整一个时区。
  return DateTime.tryParse(v)?.toLocal();
}

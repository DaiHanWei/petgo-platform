/// 电商订单列表域模型（v1.4.2 shop Story 9.1，消费 6.2 `GET /api/v1/me/shop-orders?group=&cursor=`
/// 与 `/me/shop-orders/counts`）。
///
/// 🔴 分组按服务端 `displayStatus`（S-7：宽限期待支付归 `cancelled`），App 不按本地时钟自判。
library;

import 'shop_order_detail.dart' show ShopOrderStatus;

/// 「购物」二级分组（6 值 ↔ API 小写串）。
enum ShopOrderGroup {
  all('all'),
  pendingPayment('pending_payment'),
  pendingShipment('pending_shipment'),
  toReceive('to_receive'),
  completed('completed'),
  cancelled('cancelled');

  const ShopOrderGroup(this.api);

  final String api;
}

/// 列表卡（后端 `ShopOrderListItem`）。null 字段后端不下发（NON_NULL）。
class ShopOrderSummary {
  const ShopOrderSummary({
    required this.orderToken,
    this.displayNo = '',
    required this.status,
    this.displayStatus,
    required this.totalAmount,
    this.createdAt,
    this.thumbnailUrl,
    this.itemTitle,
    this.itemCount,
    this.paymentDeadline,
    this.canPay = false,
    this.logisticsStatusKey,
    this.deliveryIssue = false,
    this.refundActionRequired = false,
  });

  final String orderToken;
  final String displayNo;

  /// 原始状态。
  final ShopOrderStatus status;

  /// 展示状态原文（缺失回落 [status]，见 [shownStatus]）。
  final String? displayStatus;

  /// 订单应付总额（F1：列表金额统一为订单总额）。
  final int totalAmount;
  final DateTime? createdAt;
  final String? thumbnailUrl;
  final String? itemTitle;
  final int? itemCount;

  /// 仅 `displayStatus = PENDING_PAYMENT` 时下发。
  final DateTime? paymentDeadline;
  final bool canPay;

  /// `awaiting_pickup | picked | in_transit | out_for_delivery | delivered`；手工运单 / 无运单为 null。
  final String? logisticsStatusKey;
  final bool deliveryIssue;

  /// 有待选去向 / 打款失败的退款单（9.2 用）。
  final bool refundActionRequired;

  ShopOrderStatus get shownStatus =>
      displayStatus == null ? status : ShopOrderStatus.fromApi(displayStatus);

  String get shownNo => displayNo.isEmpty ? orderToken : displayNo;

  factory ShopOrderSummary.fromJson(Map<String, dynamic> j) => ShopOrderSummary(
        orderToken: j['orderToken']?.toString() ?? '',
        displayNo: j['displayNo']?.toString() ?? '',
        status: ShopOrderStatus.fromApi(j['status']?.toString()),
        displayStatus: _blankToNull(j['displayStatus']?.toString()),
        totalAmount: _int(j['totalAmount']) ?? 0,
        createdAt: _time(j['createdAt']),
        thumbnailUrl: _blankToNull(j['thumbnailUrl']?.toString()),
        itemTitle: _blankToNull(j['itemTitle']?.toString()),
        itemCount: _int(j['itemCount']),
        paymentDeadline: _time(j['paymentDeadline']),
        canPay: j['canPay'] == true,
        logisticsStatusKey: _blankToNull(j['logisticsStatusKey']?.toString()),
        deliveryIssue: j['deliveryIssue'] == true,
        refundActionRequired: j['refundActionRequired'] == true,
      );
}

/// 一页（后端 `ShopOrderListPage`）：`nextCursor` 仅 `hasMore` 时下发。
class ShopOrderListPage {
  const ShopOrderListPage({required this.items, this.nextCursor, this.hasMore = false});

  final List<ShopOrderSummary> items;
  final String? nextCursor;
  final bool hasMore;

  factory ShopOrderListPage.fromJson(Map<String, dynamic> j) => ShopOrderListPage(
        items: j['items'] is List
            ? (j['items'] as List)
                .whereType<Map<String, dynamic>>()
                .map(ShopOrderSummary.fromJson)
                .toList(growable: false)
            : const [],
        nextCursor: _blankToNull(j['nextCursor']?.toString()),
        hasMore: j['hasMore'] == true,
      );
}

/// 角标计数（D-18）：口径与列表分组一致。
class ShopOrderCounts {
  const ShopOrderCounts({this.pendingPayment = 0, this.toReceive = 0});

  final int pendingPayment;
  final int toReceive;

  factory ShopOrderCounts.fromJson(Map<String, dynamic> j) => ShopOrderCounts(
        pendingPayment: _int(j['pendingPayment']) ?? 0,
        toReceive: _int(j['toReceive']) ?? 0,
      );
}

int? _int(Object? v) => v is num ? v.toInt() : null;

String? _blankToNull(String? s) => (s == null || s.isEmpty) ? null : s;

DateTime? _time(Object? v) {
  if (v is! String || v.isEmpty) return null;
  return DateTime.tryParse(v)?.toLocal();
}

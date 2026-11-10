import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/shop/domain/shop_order_detail.dart';
import 'package:tailtopia/features/shop/presentation/shop_order_header.dart';

/// `resolveShopOrderHeader` 全状态表（v1.4.2 shop Story 9.1 AC3.3 / AC3.4 / AC5 / AC6，PRD §3.0）。
void main() {
  ShopOrderDetail d({
    String status = 'PENDING_SHIPMENT',
    String? displayStatus,
    String? cancelReason,
    Map<String, dynamic>? logistics,
    List<Map<String, dynamic>>? cancelledLines,
    String? reorderedTo,
    String? reorderedFromNo,
  }) =>
      ShopOrderDetail.fromJson({
        'orderToken': 'o1',
        'status': status,
        'displayStatus': ?displayStatus,
        'cancelReason': ?cancelReason,
        'logistics': ?logistics,
        'cancelledLines': ?cancelledLines,
        'reorderedToOrderToken': ?reorderedTo,
        'reorderedFromOrderNo': ?reorderedFromNo,
      });

  const issue = {'provider': 'BITESHIP', 'exception': 'DELIVERY_ISSUE', 'exceptionType': 'RETURNED'};

  final table = <String, (ShopOrderDetail, ShopOrderHeader)>{
    'F4 待付款': (d(status: 'PENDING_PAYMENT', displayStatus: 'PENDING_PAYMENT'),
        const ShopOrderHeader(ShopHeaderKind.pendingPayment)),
    'F6 待发货': (d(), const ShopOrderHeader(ShopHeaderKind.pendingShipment)),
    'F7 已发货': (d(status: 'SHIPPED'), const ShopOrderHeader(ShopHeaderKind.shipped)),
    'F9 已送达': (d(status: 'DELIVERED'), const ShopOrderHeader(ShopHeaderKind.delivered)),
    'F10 已发货 + 配送异常': (d(status: 'SHIPPED', logistics: issue),
        const ShopOrderHeader(ShopHeaderKind.deliveryIssue)),
    'F10 已送达 + 配送异常': (d(status: 'DELIVERED', logistics: issue),
        const ShopOrderHeader(ShopHeaderKind.deliveryIssue)),
    'F11 已完成': (d(status: 'COMPLETED'), const ShopOrderHeader(ShopHeaderKind.completed)),
    'F12 超时取消': (d(status: 'CANCELLED', cancelReason: 'PAYMENT_TIMEOUT'),
        const ShopOrderHeader(ShopHeaderKind.cancelledTimeout)),
    'S-7 宽限期（原始待支付 · 展示已取消）': (d(status: 'PENDING_PAYMENT', displayStatus: 'CANCELLED'),
        const ShopOrderHeader(ShopHeaderKind.cancelledTimeout)),
    'F13 缺货取消': (d(status: 'CANCELLED', cancelReason: 'OUT_OF_STOCK'),
        const ShopOrderHeader(ShopHeaderKind.cancelledOutOfStock)),
    '用户取消 → 只给标题': (d(status: 'CANCELLED', cancelReason: 'USER_UNPAID_CANCEL'),
        const ShopOrderHeader(ShopHeaderKind.cancelled)),
    '存量无原因 → 只给标题': (d(status: 'CANCELLED'), const ShopOrderHeader(ShopHeaderKind.cancelled)),
    '未知原因 → 只给标题': (d(status: 'CANCELLED', cancelReason: 'NEW_REASON'),
        const ShopOrderHeader(ShopHeaderKind.cancelled)),
    'F20 原单已重下（优先于取消原因）': (
      d(status: 'CANCELLED', cancelReason: 'LATE_PAYMENT', reorderedTo: 'new1'),
      const ShopOrderHeader(ShopHeaderKind.reordered)
    ),
    'F21 新单（待发货 + 副句）': (d(reorderedFromNo: 'TOKO-1'),
        const ShopOrderHeader(ShopHeaderKind.pendingShipment, hint: ShopHeaderHint.reorderedFrom)),
    'F17 部分取消（标题不变、副句改）': (
      d(cancelledLines: [
        {'lineIndex': 1, 'cancelledQty': 1, 'refundAmount': 60333}
      ]),
      const ShopOrderHeader(ShopHeaderKind.pendingShipment, hint: ShopHeaderHint.partialCancel)
    ),
    '整单已取消时不给 F17 副句': (
      d(status: 'CANCELLED', cancelledLines: [
        {'lineIndex': 0, 'cancelledQty': 1}
      ]),
      const ShopOrderHeader(ShopHeaderKind.cancelled)
    ),
    '复审：仍有效但有 WHOLE 退款单 → 不给 F17 副句': (
      ShopOrderDetail.fromJson({
        'orderToken': 'o1',
        'status': 'PENDING_SHIPMENT',
        'cancelledLines': [
          {'lineIndex': 0, 'cancelledQty': 1}
        ],
        'refunds': [
          {'refundToken': 'w', 'scope': 'WHOLE'}
        ],
      }),
      const ShopOrderHeader(ShopHeaderKind.pendingShipment)
    ),
    '退款中': (d(status: 'REFUNDING'), const ShopOrderHeader(ShopHeaderKind.refunding)),
    '已退款': (d(status: 'REFUNDED'), const ShopOrderHeader(ShopHeaderKind.refunded)),
    '未知状态': (d(status: 'WHAT'), const ShopOrderHeader(ShopHeaderKind.other)),
  };

  table.forEach((name, row) {
    test(name, () => expect(resolveShopOrderHeader(row.$1), row.$2));
  });

  test('配送异常埋点取值（C-6）：returned / lost / damaged；缺字段 → unknown', () {
    expect(deliveryExceptionTypeForAnalytics('RETURNED'), 'returned');
    expect(deliveryExceptionTypeForAnalytics('LOST'), 'lost');
    expect(deliveryExceptionTypeForAnalytics('DAMAGED'), 'damaged');
    expect(deliveryExceptionTypeForAnalytics(null), 'unknown');
    expect(deliveryExceptionTypeForAnalytics('LOST_DAMAGED'), 'unknown');
  });

  test('物流 5 段：statusKey → 段下标；未知 / null 不画', () {
    expect(logisticsStepOf('awaiting_pickup'), 0);
    expect(logisticsStepOf('picked'), 1);
    expect(logisticsStepOf('in_transit'), 2);
    expect(logisticsStepOf('out_for_delivery'), 3);
    expect(logisticsStepOf('delivered'), 4);
    expect(logisticsStepOf('weird'), isNull);
    expect(logisticsStepOf(null), isNull);
  });
}

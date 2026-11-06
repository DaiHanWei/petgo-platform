import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/shop/domain/shop_order_detail.dart';
import 'package:tailtopia/features/shop/domain/shop_order_summary.dart';

/// 订单详情 / 列表模型解析（v1.4.2 shop Story 9.1 T1）：新字段全部缺键容错。
void main() {
  test('存量 / 旧后端：新字段全缺 → null / 空数组，不抛', () {
    final o = ShopOrderDetail.fromJson({'orderToken': 'o1', 'status': 'COMPLETED'});
    expect(o.amounts, isNull);
    expect(o.paymentMethods, isNull);
    expect(o.logistics, isNull);
    expect(o.refunds, isEmpty);
    expect(o.cancelledLines, isEmpty);
    expect(o.cancelReason, isNull);
    expect(o.couponReturn, isNull);
    expect(o.reorderedToOrderToken, isNull);
    expect(o.reorderedFromOrderToken, isNull);
    expect(o.shownStatus, ShopOrderStatus.completed, reason: 'displayStatus 缺失回落 status');
    expect(o.hasDeliveryIssue, isFalse);
  });

  test('6.2 / 5.x / 4.x 字段完整解析', () {
    final o = ShopOrderDetail.fromJson({
      'orderToken': 'o1',
      'status': 'SHIPPED',
      'displayStatus': 'SHIPPED',
      'paymentDeadline': '2026-11-05T10:00:00Z',
      'cancelReason': 'OUT_OF_STOCK',
      'amounts': {
        'goodsAmount': 349000,
        'discountCouponAmount': 20000,
        'shippingFee': 10000,
        'shippingDiscount': 10000,
        'shippingDiscountSource': 'COUPON',
        'insuranceFee': 0,
        'payable': 329000,
        'courierName': 'JNE',
        'courierServiceName': 'REG',
      },
      'paymentMethods': {'coinAmount': 12000, 'coinStatus': 'PAID', 'cashAmount': 317000, 'cashStatus': 'PAID'},
      'logistics': {
        'provider': 'BITESHIP',
        'courierName': 'JNE',
        'waybillId': 'WB1',
        'statusKey': 'in_transit',
        'exception': 'DELIVERY_ISSUE',
        'exceptionType': 'LOST',
      },
      'refunds': [
        {'refundToken': 'r1', 'scope': 'LINE', 'totalRefund': 60333, 'coinRefund': 2200, 'cashRefund': 58133}
      ],
      'cancelledLines': [
        {'lineIndex': 1, 'productName': 'P', 'specName': 'S', 'cancelledQty': 1, 'refundAmount': 60333}
      ],
      'couponReturn': 'RETURNED',
      'reorderedFromOrderNo': 'TOKO-1',
      'reorderedFromOrderToken': 'old1',
    });
    expect(o.amounts!.payable, 329000);
    expect(o.amounts!.shippingDiscountSource, 'COUPON');
    expect(o.paymentMethods!.cashAmount, 317000);
    expect(o.logistics!.isBiteship, isTrue);
    expect(o.hasDeliveryIssue, isTrue);
    expect(o.refunds.single.isLine, isTrue);
    expect(o.cancelledLines.single.lineIndex, 1);
    expect(o.cancelReason, CancelReason.outOfStock);
    expect(o.deadline, isNotNull);
    expect(o.reorderedFromOrderToken, 'old1');
  });

  test('CancelReason：九值 + 未知 → unknown；空 → null', () {
    expect(CancelReason.fromApi('LOST_DAMAGED'), CancelReason.lostDamaged);
    expect(CancelReason.fromApi('LATE_PAYMENT'), CancelReason.latePayment);
    expect(CancelReason.fromApi('SOMETHING_NEW'), CancelReason.unknown);
    expect(CancelReason.fromApi(''), isNull);
    expect(CancelReason.fromApi(null), isNull);
  });

  test('paymentDeadline 缺失回落 expiresAt', () {
    final o = ShopOrderDetail.fromJson(
        {'orderToken': 'o1', 'status': 'PENDING_PAYMENT', 'expiresAt': '2026-11-05T10:00:00Z'});
    expect(o.deadline, o.expiresAt);
  });

  test('列表项 / 一页 / 计数解析（NON_NULL 缺键容错）', () {
    final page = ShopOrderListPage.fromJson({
      'items': [
        {
          'orderToken': 't1',
          'displayNo': 'TOKO-1',
          'status': 'PENDING_PAYMENT',
          'displayStatus': 'CANCELLED',
          'totalAmount': 329000,
          'canPay': false,
          'deliveryIssue': false,
          'refundActionRequired': false,
        },
        {'orderToken': 't2', 'status': 'SHIPPED', 'totalAmount': 1, 'logisticsStatusKey': 'picked'},
      ],
      'nextCursor': 'c2',
      'hasMore': true,
    });
    expect(page.items, hasLength(2));
    expect(page.items.first.shownStatus, ShopOrderStatus.cancelled);
    expect(page.items.first.paymentDeadline, isNull);
    expect(page.items[1].shownNo, 't2', reason: 'displayNo 缺失回落 token');
    expect(page.nextCursor, 'c2');
    expect(page.hasMore, isTrue);
    final c = ShopOrderCounts.fromJson({'pendingPayment': 2, 'toReceive': 0});
    expect(c.pendingPayment, 2);
    expect(c.toReceive, 0);
    expect(ShopOrderGroup.values.map((g) => g.api),
        ['all', 'pending_payment', 'pending_shipment', 'to_receive', 'completed', 'cancelled']);
  });
}

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';

void main() {
  late List<(String, Map<String, Object>?)> sent;

  setUp(() {
    sent = [];
    Analytics.debugCaptureSink = (e, p) => sent.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  test('现金付款 → af_purchase（IDR 金额 + 用途代号 + 订单号），并且是投放回传事件', () async {
    await Analytics.capturePurchase(amountIdr: 49000, purpose: 'VET_CONSULT', orderRef: 'PAYVET-1');
    expect(sent.single.$1, Analytics.adPurchaseEvent);
    expect(sent.single.$2, {
      'af_revenue': 49000,
      'af_currency': 'IDR',
      'af_content_id': 'VET_CONSULT',
      'af_quantity': 1,
      'af_order_id': 'PAYVET-1',
    });
    expect(Analytics.isAppsFlyerEvent(Analytics.adPurchaseEvent), isTrue);
  });

  test('金额未知：照样计一次付款，但不带 af_revenue（不能拿 0 冒充，会拉低 ROAS）', () async {
    await Analytics.capturePurchase(amountIdr: null, purpose: 'ID_HD');
    expect(sent.single.$2!.containsKey('af_revenue'), isFalse);
    expect(sent.single.$2!.containsKey('af_order_id'), isFalse);
    expect(sent.single.$2!['af_content_id'], 'ID_HD');

    sent.clear();
    await Analytics.capturePurchase(amountIdr: 0, purpose: 'ID_HD', orderRef: '');
    expect(sent.single.$2!.containsKey('af_revenue'), isFalse);
    expect(sent.single.$2!.containsKey('af_order_id'), isFalse);
  });
}

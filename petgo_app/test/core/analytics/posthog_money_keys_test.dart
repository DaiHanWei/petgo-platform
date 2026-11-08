import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';

/// V1.4.2（2026-11-08 拍板）：金额一律不进 PostHog，事件照发；投放回传（af_*）不受影响。
void main() {
  test('KTP / Tailsonality / 礼物 / 结账 的金额键在 PostHog 一路被剥掉，其余维度保留', () {
    final out = Analytics.posthogProperties({
      'entry': 'detail',
      'method': 'QRIS',
      'price_idr': 10000,
      'price': 5000,
      'total_amount': 250000,
      'coin_used': 300,
      'shipping_fee': 12000,
      'order_amount': 285000,
      'amount': 1,
      'quantity': 10,
      'card_type': 'KTP',
    });
    expect(out, {'entry': 'detail', 'method': 'QRIS', 'quantity': 10, 'card_type': 'KTP'});
  });

  test('af_purchase：PostHog 不带 af_revenue，但事件与用途代号仍在', () {
    final out = Analytics.posthogProperties({
      'af_revenue': 49000,
      'af_currency': 'IDR',
      'af_content_id': 'VET_CONSULT',
      'af_quantity': 1,
      'af_order_id': 'PAYVET-1',
    });
    expect(out!.containsKey('af_revenue'), isFalse);
    expect(out['af_content_id'], 'VET_CONSULT');
  });

  test('af_initiated_checkout：PostHog 不带 af_price', () {
    expect(Analytics.posthogProperties({'af_price': 50000, 'af_content_id': 'T1'}), {'af_content_id': 'T1'});
  });

  test('null / 空属性原样', () {
    expect(Analytics.posthogProperties(null), isNull);
    expect(Analytics.posthogProperties({}), isEmpty);
  });
}

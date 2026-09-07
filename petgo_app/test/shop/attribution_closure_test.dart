import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/shop/domain/checkout_preview.dart';
import 'package:tailtopia/features/shop/domain/shop_order_detail.dart';

/// Story 9.2 · L0：**归因链闭合**（客户端侧）。
///
/// **为什么这条 story 存在**：原埋点清单只到 `add_to_cart` 为止 —— 能算点击率，
/// **算不出转化率**。而 AB-13B 用「触发卡转化率 vs 普通商品曝光转化率」判定 A-16
/// （复购引擎是否成立），是本版本核心论证的唯一依据。
///
/// 🔴 **权威归因在服务端**（`shop_order_lines.entry_source` / `trigger_type`，Story 3.4 落地）。
/// 客户端这一份的作用是**互为校验**：两套一比就知道端上丢了多少。
/// ⚠️ 偏差过大即说明客户端埋点有丢失，**以服务端为准**。
///
/// L-6 前车之鉴：V1.1.2 因埋点与改版同版本发布，三项核心指标不可得、「唯一裁决指标」失效。
void main() {
  group('行级归因从服务端一路带到客户端事件', () {
    test('结算行解析出 entrySource / triggerType（服务端加购时就记下的那一份）', () {
      final line = CheckoutLine.fromJson(const {
        'skuToken': 'sku_abc',
        'specName': '3 kg',
        'price': 185000,
        'qty': 2,
        'returnPolicy': 'RETURNABLE',
        'entrySource': 'toko_repurchase_card',
        'triggerType': 'FOOD_LOW',
      });
      expect(line.entrySource, 'toko_repurchase_card');
      expect(line.triggerType, 'FOOD_LOW');
    });

    test('服务端没给归因时落到 null（**不要瞎编一个默认来源**）', () {
      final line = CheckoutLine.fromJson(const {
        'skuToken': 'sku_abc',
        'specName': '3 kg',
        'price': 1,
        'qty': 1,
        'returnPolicy': 'RETURNABLE',
        'entrySource': '',
      });
      // 🔴 空串归 null：把它当成一个叫 "" 的来源，看板上会多出一档假分类
      expect(line.entrySource, isNull);
      expect(line.triggerType, isNull);
    });

    test('整单归因来源由服务端算好下发；缺失时是 unknown 而不是空', () {
      final withSrc = ShopOrderDetail.fromJson(const {
        'orderToken': 'o1',
        'status': 'PAID',
        'attributionSource': 'toko_repurchase_card',
      });
      expect(withSrc.attributionSource, 'toko_repurchase_card');

      final without = ShopOrderDetail.fromJson(const {'orderToken': 'o2', 'status': 'PAID'});
      // 🔴 缺失是一种取值，不是空洞 —— 看板上要能把「没归因」单独数出来
      expect(without.attributionSource, 'unknown');
    });
  });

  group('🔒 NFR-5：items[] 这一层同样不许夹带 PII', () {
    test('🔴 List 里的 map 也过三道规则（此前 List 是整块透传的）', () {
      final clean = Analytics.scrub({
        'item_count': 2,
        'items': [
          {'sku_id': 'sku_a', 'qty': 1, 'entry_source': 'toko_featured'},
          // 有人往行里塞了收件人 —— 必须在出门前被摘掉
          {'sku_id': 'sku_b', 'qty': 2, 'receiver_name': 'Budi', 'phone': '08123456789'},
        ],
      });
      final items = (clean['items'] as List).cast<Map<String, Object>>();
      expect(items[0], {'sku_id': 'sku_a', 'qty': 1, 'entry_source': 'toko_featured'});
      expect(items[1].containsKey('receiver_name'), isFalse,
          reason: '🔴 收件人姓名随 items[] 漏出去了 —— NFR-5 明令禁记');
      expect(items[1].containsKey('phone'), isFalse);
      expect(items[1], {'sku_id': 'sku_b', 'qty': 2});
    });

    test('嵌套更深一层也不放过', () {
      final clean = Analytics.scrub({
        'wrap': [
          {
            'inner': [
              {'sku_id': 'x', 'receiver_name': 'Budi'},
            ],
          },
        ],
      });
      final inner = (((clean['wrap'] as List).first as Map)['inner'] as List).first as Map;
      expect(inner.containsKey('receiver_name'), isFalse);
      expect(inner['sku_id'], 'x');
    });

    test('超长字符串元素在 List 里同样被丢（规则不打折）', () {
      final long = 'x' * 200;
      final clean = Analytics.scrub({
        'items': [
          'ok',
          long,
        ],
      });
      expect(clean['items'], ['ok']);
    });
  });
}

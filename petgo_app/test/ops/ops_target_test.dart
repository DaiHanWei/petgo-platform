import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/ops/domain/ops_target.dart';
import 'package:tailtopia/features/ops/domain/ops_target_action.dart';
import 'package:tailtopia/features/profile/presentation/pet_insights_page.dart';

/// Story 5.2 · AC1 / AC2 / AC5.1：解析与映射表逐行断言（L0）。
void main() {
  group('AC1 OpsTarget.fromJson', () {
    test('五类型 wire 值逐字解析（与后端 OpsTargetType 一致）', () {
      expect(OpsTarget.fromJson({'type': 'NONE'}), OpsTarget.none);
      expect(OpsTarget.fromJson({'type': 'PRODUCT', 'value': 'abc'}),
          const OpsTarget(OpsTargetType.product, 'abc'));
      expect(OpsTarget.fromJson({'type': 'CATEGORY', 'value': 'MAKANAN'}),
          const OpsTarget(OpsTargetType.category, 'MAKANAN'));
      expect(OpsTarget.fromJson({'type': 'APP_PAGE', 'value': 'HOME'}),
          const OpsTarget(OpsTargetType.appPage, 'HOME'));
      expect(OpsTarget.fromJson({'type': 'EXTERNAL', 'value': 'https://a.b'}),
          const OpsTarget(OpsTargetType.external, 'https://a.b'));
    });

    test('wire 集合与后端枚举逐字一致', () {
      expect(OpsTargetType.values.map((t) => t.wire).toList(),
          ['NONE', 'PRODUCT', 'CATEGORY', 'APP_PAGE', 'EXTERNAL']);
      expect(OpsAppPage.values.map((p) => p.wire).toList(),
          ['HOME', 'PUBLISH', 'TOKO', 'KNOW_YOUR_PET', 'HEALTH']);
    });

    test('null / type 缺失 / 未知 / 小写 / 非字符串 → none，不抛', () {
      expect(OpsTarget.fromJson(null), OpsTarget.none);
      expect(OpsTarget.fromJson({}), OpsTarget.none);
      expect(OpsTarget.fromJson({'value': 'x'}), OpsTarget.none);
      expect(OpsTarget.fromJson({'type': 'LINK', 'value': 'x'}), OpsTarget.none);
      expect(OpsTarget.fromJson({'type': 'product', 'value': 'x'}), OpsTarget.none);
      expect(OpsTarget.fromJson({'type': 3}), OpsTarget.none);
    });

    test('value 非字符串 → null', () {
      expect(OpsTarget.fromJson({'type': 'PRODUCT', 'value': 123}),
          const OpsTarget(OpsTargetType.product, null));
      expect(OpsTarget.fromJson({'type': 'EXTERNAL', 'value': {'u': 1}}).value, isNull);
    });
  });

  group('AC2 resolveOpsTarget', () {
    OpsTargetAction r(OpsTargetType t, String? v) => resolveOpsTarget(OpsTarget(t, v));
    const no = OpsNoAction();

    test('NONE → 不跳', () {
      expect(resolveOpsTarget(OpsTarget.none), no);
      expect(r(OpsTargetType.none, 'x'), no);
    });

    test('PRODUCT → push /shop/products/{token}，不需登录', () {
      expect(r(OpsTargetType.product, 'Ab_9-x'), const OpsPushAction('/shop/products/Ab_9-x'));
      expect(r(OpsTargetType.product, 'a' * 32), OpsPushAction('/shop/products/${'a' * 32}'));
      expect((r(OpsTargetType.product, 'abc') as OpsPushAction).requiresLogin, isFalse);
    });

    test('PRODUCT 非法：空串 / 33 位 / 含 / / null', () {
      expect(r(OpsTargetType.product, ''), no);
      expect(r(OpsTargetType.product, 'a' * 33), no);
      expect(r(OpsTargetType.product, 'ab/cd'), no);
      expect(r(OpsTargetType.product, null), no);
    });

    test('CATEGORY → go /shop?category=，四个品类', () {
      for (final c in ['MAKANAN', 'OBAT_VITAMIN', 'CAMILAN', 'PERAWATAN']) {
        expect(r(OpsTargetType.category, c), OpsGoAction('/shop?category=$c'));
      }
    });

    test('CATEGORY 非法：makanan / FOOD / null', () {
      expect(r(OpsTargetType.category, 'makanan'), no);
      expect(r(OpsTargetType.category, 'FOOD'), no);
      expect(r(OpsTargetType.category, null), no);
    });

    test('APP_PAGE 五个页面逐行', () {
      expect(r(OpsTargetType.appPage, 'HOME'), const OpsGoAction('/home'));
      expect(r(OpsTargetType.appPage, 'PUBLISH'), const OpsOpenPublishAction(requiresLogin: true));
      expect(r(OpsTargetType.appPage, 'TOKO'), const OpsGoAction('/shop'));
      expect(r(OpsTargetType.appPage, 'KNOW_YOUR_PET'),
          const OpsPushAction(PetInsightsRoutes.hub, requiresLogin: true));
      expect(PetInsightsRoutes.hub, '/profile/pet-insights');
      expect(r(OpsTargetType.appPage, 'HEALTH'),
          const OpsPushAction('/profile/health', requiresLogin: true));
    });

    test('APP_PAGE 非法：home / SETTINGS / null', () {
      expect(r(OpsTargetType.appPage, 'home'), no);
      expect(r(OpsTargetType.appPage, 'SETTINGS'), no);
      expect(r(OpsTargetType.appPage, null), no);
    });

    test('EXTERNAL 合法：http / https / 大写 scheme', () {
      expect(r(OpsTargetType.external, 'https://promo.tailtopia.id/a?b=1'),
          OpsExternalAction(Uri.parse('https://promo.tailtopia.id/a?b=1')));
      expect(r(OpsTargetType.external, 'http://a.b'), isA<OpsExternalAction>());
      final upper = r(OpsTargetType.external, 'HTTPS://promo.tailtopia.id');
      expect(upper, isA<OpsExternalAction>());
      expect((upper as OpsExternalAction).uri.host, 'promo.tailtopia.id');
    });

    test('EXTERNAL 长度边界：512 合法 / 513 非法', () {
      const prefix = 'https://a.b/';
      final ok = prefix + 'x' * (512 - prefix.length);
      expect(ok.length, 512);
      expect(r(OpsTargetType.external, ok), isA<OpsExternalAction>());
      expect(r(OpsTargetType.external, '${ok}x'), no);
    });

    test('EXTERNAL 非法：javascript: / ftp: / 无 host / 相对地址 / null', () {
      expect(r(OpsTargetType.external, 'javascript:alert(1)'), no);
      expect(r(OpsTargetType.external, 'ftp://a.b'), no);
      expect(r(OpsTargetType.external, 'https://'), no);
      expect(r(OpsTargetType.external, '/shop'), no);
      expect(r(OpsTargetType.external, 'intent://x#Intent;end'), no);
      expect(r(OpsTargetType.external, null), no);
    });
  });
}

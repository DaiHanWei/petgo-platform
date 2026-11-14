import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/push/push_service.dart';
import 'package:tailtopia/core/router/deep_link_routes.dart';

/// V1.5.0 健康记录提醒 Story 3.4 · AC1：健康提醒推送直达点开路由页（不进通知中心）。
void main() {
  test('23 个 HEALTH_* 全部是直达类型，且原有直达值不丢（三方合并取并集）', () {
    expect(kHealthReminderTypes, hasLength(23));
    expect(kHealthReminderTypes.every((t) => t.startsWith('HEALTH_')), isTrue);
    expect(kPushDirectTypes, containsAll(kHealthReminderTypes));
    expect(kPushDirectTypes, containsAll(['GROWTH_LEVEL_UP', 'SHOP_ORDER_REORDERED', 'COUPON_EXPIRING']));
  });

  test('pushPayloadToLocation：全部 23 个类型都不落通知中心，默认 src=inbox', () {
    for (final t in kHealthReminderTypes) {
      final loc = DeepLinkRoutes.pushPayloadToLocation(t, 'tok-1');
      expect(loc, isNot(DeepLinkRoutes.notificationsCenter), reason: t);
      expect(Uri.parse(loc).path, '/health-reminder/open', reason: t);
      expect(Uri.parse(loc).queryParameters, {'t': 'tok-1', 'src': 'inbox'}, reason: t);
    }
  });

  test('targetRef 为空 → 通知中心', () {
    expect(DeepLinkRoutes.pushPayloadToLocation('HEALTH_VACCINE_D_DAY', null), DeepLinkRoutes.notificationsCenter);
    expect(DeepLinkRoutes.pushPayloadToLocation('HEALTH_VACCINE_D_DAY', ''), DeepLinkRoutes.notificationsCenter);
  });

  test('pushClickLocation：有 token 也直达，src 换成 push', () {
    final deep = DeepLinkRoutes.pushPayloadToLocation('HEALTH_VACCINE_D_DAY', 'tok 2');
    final loc = pushClickLocation(hasToken: true, deepLink: deep, type: 'HEALTH_VACCINE_D_DAY');
    final uri = Uri.parse(loc);
    expect(uri.path, '/health-reminder/open');
    expect(uri.queryParameters, {'t': 'tok 2', 'src': 'push'});
  });

  test('pushClickLocation：健康类型但深链兜底为通知中心 → 照旧通知中心；非健康类型行为不变', () {
    expect(
        pushClickLocation(hasToken: true, deepLink: DeepLinkRoutes.notificationsCenter, type: 'HEALTH_CHECKUP'),
        DeepLinkRoutes.notificationsCenter);
    expect(pushClickLocation(hasToken: true, deepLink: '/x', type: 'CONTENT_LIKED'),
        DeepLinkRoutes.notificationsCenter);
    expect(pushClickLocation(hasToken: true, deepLink: '/me/growth', type: 'GROWTH_LEVEL_UP'), '/me/growth');
  });
}

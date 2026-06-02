import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/core/router/deep_link_routes.dart';

/// Story 6.1 J4：pushPayloadToLocation 四类映射 + 评论区锚点 + 未知 type/空 token 兜底。
void main() {
  test('VET_REPLY / CONSULT_CLOSED → 问诊会话（token 非顺序 id）', () {
    expect(DeepLinkRoutes.pushPayloadToLocation('VET_REPLY', 'tok9'), '/consult/conversation/tok9');
    expect(DeepLinkRoutes.pushPayloadToLocation('CONSULT_CLOSED', 'tok9'), '/consult/conversation/tok9');
  });

  test('CONTENT_LIKED → 详情；CONTENT_COMMENTED → 详情 + 评论锚点', () {
    expect(DeepLinkRoutes.pushPayloadToLocation('CONTENT_LIKED', 'abc'), '/content/abc');
    expect(DeepLinkRoutes.pushPayloadToLocation('CONTENT_COMMENTED', 'abc', commentAnchor: true),
        '/content/abc?focus=comments');
    // 无锚点参数时退化为详情
    expect(DeepLinkRoutes.pushPayloadToLocation('CONTENT_COMMENTED', 'abc'), '/content/abc');
  });

  test('NEW_CONSULT_REQUEST → 兽医工作台', () {
    expect(DeepLinkRoutes.pushPayloadToLocation('NEW_CONSULT_REQUEST', 'x'), '/vet/workbench');
  });

  test('未知 type / 空 token → 通知中心兜底（不崩溃）', () {
    expect(DeepLinkRoutes.pushPayloadToLocation('SOMETHING_NEW', 'x'),
        DeepLinkRoutes.notificationsCenter);
    expect(DeepLinkRoutes.pushPayloadToLocation(null, 'x'), DeepLinkRoutes.notificationsCenter);
    expect(DeepLinkRoutes.pushPayloadToLocation('VET_REPLY', null),
        DeepLinkRoutes.notificationsCenter);
    expect(DeepLinkRoutes.pushPayloadToLocation('VET_REPLY', ''),
        DeepLinkRoutes.notificationsCenter);
  });
}

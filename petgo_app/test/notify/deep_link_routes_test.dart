import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/router/deep_link_routes.dart';

/// Story 6.1 J4：pushPayloadToLocation 七类映射 + 评论区锚点 + 未知 type/空 token 兜底。
/// 🔄 PRD V1.0.0 修订（Fx · 2026-06-08）：四类→七类，新增生日/纪念日/里程碑节点三类固定目标（F2/F5）。
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

  test('🔄 固定目标类（生日/纪念日/里程碑节点）→ 不依赖 token 也命中（F2/F5）', () {
    // PET_BIRTHDAY → 「+发布」预选成长日历（FR-40）
    expect(DeepLinkRoutes.pushPayloadToLocation('PET_BIRTHDAY', 'tok'),
        '/publish?preset=growth-calendar');
    // COMPANION_ANNIVERSARY → 成长档案 Tab（FR-41）
    expect(DeepLinkRoutes.pushPayloadToLocation('COMPANION_ANNIVERSARY', 'tok'), '/profile');
    // MILESTONE_NODE → 里程碑列表页壳（FR-42）
    expect(DeepLinkRoutes.pushPayloadToLocation('MILESTONE_NODE', 'tok'), '/profile/milestones');
    // 固定目标类不依赖 token：空/缺 token 仍命中固定目标（非兜底）
    expect(DeepLinkRoutes.pushPayloadToLocation('PET_BIRTHDAY', null),
        '/publish?preset=growth-calendar');
    expect(DeepLinkRoutes.pushPayloadToLocation('COMPANION_ANNIVERSARY', ''), '/profile');
    expect(DeepLinkRoutes.pushPayloadToLocation('MILESTONE_NODE', null), '/profile/milestones');
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

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

  test('NAME_RESET（内容审核 cm-4）→ targetRef 区分昵称 vs 宠物名', () {
    // 昵称重置：targetRef="NICKNAME" → 我的页（昵称编辑底抽屉入口）
    expect(DeepLinkRoutes.pushPayloadToLocation('NAME_RESET', 'NICKNAME'), '/me');
    // 宠物名重置：targetRef=cardToken → 宠物档案编辑页（V1 单宠物自解析，不拼 token 入路径）
    expect(DeepLinkRoutes.pushPayloadToLocation('NAME_RESET', 'card_abc123'), '/profile/edit');
    // 缺 targetRef 安全兜底到昵称页（不崩溃）
    expect(DeepLinkRoutes.pushPayloadToLocation('NAME_RESET', null), '/me');
  });

  test('AVATAR_RESET（内容审核 cm-5）→ targetRef 区分用户头像 vs 宠物头像', () {
    // 用户头像重置：targetRef="USER_AVATAR" → 我的页（编辑资料底抽屉换头像入口）
    expect(DeepLinkRoutes.pushPayloadToLocation('AVATAR_RESET', 'USER_AVATAR'), '/me');
    // 宠物头像重置：targetRef=cardToken → 宠物档案编辑页（换头像入口，V1 单宠物自解析，不拼 token 入路径）
    expect(DeepLinkRoutes.pushPayloadToLocation('AVATAR_RESET', 'card_abc123'), '/profile/edit');
    // 缺 targetRef 安全兜底到我的页（不崩溃）
    expect(DeepLinkRoutes.pushPayloadToLocation('AVATAR_RESET', null), '/me');
  });

  test('CONTENT_REMOVED（内容审核 cm-3/6）→ targetRef=postId 落内容详情（帖子/评论共用）', () {
    expect(DeepLinkRoutes.pushPayloadToLocation('CONTENT_REMOVED', '42'), '/content/42');
    // 空/缺 targetRef → 无深链兜底（不拼非法路由）
    expect(DeepLinkRoutes.pushPayloadToLocation('CONTENT_REMOVED', null),
        DeepLinkRoutes.notificationsCenter);
  });

  test('REJECTED / TIMED_OUT / REPORT_REVIEWED（targetRef=null）→ 无深链，点击不跳（落兜底）', () {
    expect(DeepLinkRoutes.pushPayloadToLocation('CONTENT_REVIEW_REJECTED', null),
        DeepLinkRoutes.notificationsCenter);
    expect(DeepLinkRoutes.pushPayloadToLocation('CONTENT_REVIEW_TIMED_OUT', null),
        DeepLinkRoutes.notificationsCenter);
    expect(DeepLinkRoutes.pushPayloadToLocation('REPORT_REVIEWED', null),
        DeepLinkRoutes.notificationsCenter);
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

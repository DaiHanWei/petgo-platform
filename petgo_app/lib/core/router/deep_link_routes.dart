/// 深链路由表（Story 6.1，FR-38）。纯映射：推送 payload `type + token` → go_router location。
///
/// 后端只下发 `type + deepLinkToken`（绝不顺序 id），映射规则在客户端。目标 route 只接受 **token**。
/// 未知 type 兜底落通知中心（6.6），不崩溃。
///
/// 端到端点击直达（冷启动/后台/前台三态 + 真机推送）属 L2；本纯函数是 L0 可单测核心。
class DeepLinkRoutes {
  DeepLinkRoutes._();

  /// 通知中心（6.6）——未知/缺失映射的安全兜底落点。
  static const String notificationsCenter = '/notifications';

  /// 「+发布」预选成长日历（生日深链落点，FR-40）。
  static const String publishGrowthCalendar = '/publish?preset=growth-calendar';

  /// 成长档案 Tab（纪念日深链落点，FR-41）。
  static const String growthArchive = '/profile';

  /// 里程碑列表页（壳）（L级里程碑节点深链落点，FR-42；本体属里程碑 mini-epic）。
  static const String milestoneList = '/profile/milestones';

  /// 推送 payload → go_router location。
  ///
  /// **token 寻址类**（缺 token 落兜底）：
  /// - `VET_REPLY` / `CONSULT_CLOSED` → 问诊会话（评分态由会话页据状态展示）。
  /// - `CONTENT_LIKED` → 内容详情。
  /// - `CONTENT_COMMENTED` → 内容详情 + 评论区锚点（`?focus=comments`）。
  /// - `NEW_CONSULT_REQUEST` → 兽医工作台。
  ///
  /// **固定目标类**（V1 单宠物，目标页自解析当前用户宠物，不依赖 token，FR-40/41/42）：
  /// - `PET_BIRTHDAY` → 「+发布」预选成长日历。
  /// - `COMPANION_ANNIVERSARY` → 成长档案 Tab。
  /// - `MILESTONE_NODE` → 里程碑列表页（壳）。
  ///
  /// 其它/空 → 通知中心兜底。
  static String pushPayloadToLocation(String? type, String? token, {bool commentAnchor = false}) {
    if (type == null) return notificationsCenter;
    // 固定目标类：不依赖 token（生日/纪念日/里程碑节点，决策 F2/F5）。
    switch (type) {
      case 'PET_BIRTHDAY':
        return publishGrowthCalendar;
      case 'COMPANION_ANNIVERSARY':
        return growthArchive;
      case 'MILESTONE_NODE':
        return milestoneList;
    }
    // token 寻址类：缺 token 落兜底。
    if (token == null || token.isEmpty) return notificationsCenter;
    switch (type) {
      case 'VET_REPLY':
      case 'CONSULT_CLOSED':
        return '/consult/conversation/$token';
      case 'CONTENT_LIKED':
        return '/content/$token';
      case 'CONTENT_COMMENTED':
        return commentAnchor ? '/content/$token?focus=comments' : '/content/$token';
      case 'NEW_CONSULT_REQUEST':
        return '/vet/workbench';
      default:
        return notificationsCenter;
    }
  }
}

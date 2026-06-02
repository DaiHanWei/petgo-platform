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

  /// 推送 payload → go_router location。
  ///
  /// - `VET_REPLY` / `CONSULT_CLOSED` → 问诊会话（评分态由会话页据状态展示）。
  /// - `CONTENT_LIKED` → 内容详情。
  /// - `CONTENT_COMMENTED` → 内容详情 + 评论区锚点（`?focus=comments`）。
  /// - `NEW_CONSULT_REQUEST` → 兽医工作台。
  /// - 其它/空 → 通知中心兜底。
  static String pushPayloadToLocation(String? type, String? token, {bool commentAnchor = false}) {
    if (type == null || token == null || token.isEmpty) {
      return notificationsCenter;
    }
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

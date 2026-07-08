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

  /// 通知 payload → go_router location。
  ///
  /// **id 寻址类**（用 `targetRef`=帖子 id / 会话 id 拼路由，缺则落兜底）：目标页均以数字 id 寻址
  /// （`ContentDetailPage(postId)` / `ConsultConversationPage(sessionId)`，builder 内 `int.parse(:id)`），
  /// **不可**用通知自身的随机 `deepLinkToken`（会 `int.parse` 抛异常/查无此帖）。
  /// - `VET_REPLY` / `CONSULT_CLOSED` → 问诊会话（评分态由会话页据状态展示）。
  /// - `CONTENT_LIKED` → 内容详情。
  /// - `CONTENT_COMMENTED` → 内容详情 + 评论区锚点（`?focus=comments`）。
  ///
  /// **固定目标类**（V1 单宠物，目标页自解析当前用户宠物，不依赖 ref，FR-40/41/42）：
  /// - `NEW_CONSULT_REQUEST` → 兽医工作台。
  /// - `PET_BIRTHDAY` → 「+发布」预选成长日历。
  /// - `COMPANION_ANNIVERSARY` → 成长档案 Tab。
  /// - `MILESTONE_NODE` → 里程碑列表页（壳）。
  ///
  /// 其它/空 → 通知中心兜底。
  static String pushPayloadToLocation(String? type, String? targetRef, {bool commentAnchor = false}) {
    if (type == null) return notificationsCenter;
    // 固定目标类：不依赖 targetRef（生日/纪念日/里程碑节点/兽医新请求，决策 F2/F5）。
    switch (type) {
      case 'NEW_CONSULT_REQUEST':
        return '/vet/workbench';
      case 'PET_BIRTHDAY':
        return publishGrowthCalendar;
      case 'COMPANION_ANNIVERSARY':
        return growthArchive;
      case 'MILESTONE_NODE':
        return milestoneList;
      case 'NAME_RESET':
        // 名称违规重置（内容审核 cm-4）：单一 NAME_RESET 类型，targetRef 区分昵称 vs 宠物名。
        // 宠物名 targetRef=cardToken → 宠物档案编辑页（V1 单宠物自解析，不拼 token 入路径）；
        // "NICKNAME" 或缺失 → 我的页（昵称编辑底抽屉入口，安全兜底）。
        // 走 targetRef 而非随机 token（[notify 跳转改用 targetRef] 教训）。通知文案本地化 arb 归 cm-7（TODO）。
        return (targetRef == null || targetRef.isEmpty || targetRef == 'NICKNAME')
            ? '/me'
            : '/profile/edit';
    }
    // id 寻址类：缺 targetRef 落兜底（避免拼出非法路由）。
    if (targetRef == null || targetRef.isEmpty) return notificationsCenter;
    switch (type) {
      case 'VET_REPLY':
      case 'CONSULT_CLOSED':
        return '/consult/conversation/$targetRef';
      case 'CONTENT_LIKED':
        return '/content/$targetRef';
      case 'CONTENT_COMMENTED':
        return commentAnchor ? '/content/$targetRef?focus=comments' : '/content/$targetRef';
      default:
        return notificationsCenter;
    }
  }
}

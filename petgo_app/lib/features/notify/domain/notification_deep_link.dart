import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/router/deep_link_routes.dart';
import '../data/notification_repository.dart';

/// 通知深链打开入口（Story 6.6 AC3 · F2b）。**列表点击与系统推送直跳共用同一口径**：
/// 先按 token 标记已读 + 角标重算，再算目标 go_router location。
///
/// AC3 ②：用户经系统推送 deepLinkToken **直跳目标页**（不经通知中心列表点击）时，亦走本入口 →
/// 通知中心对应条目同步 `is_read=true` + 角标递减/重算，避免「推送已读但中心仍显未读」的不一致。
class NotificationDeepLink {
  NotificationDeepLink._();

  /// 标记已读（token 在场）+ 失效角标 provider，返回深链目标 location。
  ///
  /// - 所有类型的通知行都带 token（6.1 `NotificationService.send` 生成），故无论固定目标类
  ///   （生日/纪念日/里程碑，location 不依赖 token）还是 token 寻址类，**都按 token 标记已读**。
  /// - 标记失败不阻断跳转（与列表点击同口径）。
  static Future<String> open(
    WidgetRef ref, {
    required String? type,
    required String? token,
    bool commentAnchor = false,
  }) async {
    if (token != null && token.isNotEmpty) {
      try {
        await ref.read(notificationRepositoryProvider).markRead(token);
        ref.invalidate(unreadCountProvider); // 角标重算（与列表点击一致）
      } catch (_) {
        // 标记失败不阻断跳转。
      }
    }
    return DeepLinkRoutes.pushPayloadToLocation(type, token, commentAnchor: commentAnchor);
  }
}

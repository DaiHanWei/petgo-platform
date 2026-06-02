import 'package:flutter_riverpod/flutter_riverpod.dart';

/// 当前用户正在查看的会话 id（Story 6.2 F1）。会话页激活时设置、离开时清空，
/// 供前台推送抑制判定（用户正在看该会话 → 不重复弹 in-app Banner）。
class ActiveConsultSession extends Notifier<int?> {
  @override
  int? build() => null;

  void set(int? sessionId) => state = sessionId;
}

final activeConsultSessionProvider =
    NotifierProvider<ActiveConsultSession, int?>(ActiveConsultSession.new);

/// 前台推送 in-app 抑制判定（Story 6.2，纯函数，AC1）。
///
/// 仅当推送指向「用户当前正在查看的同一会话」时抑制（不重复打扰）；
/// 其它会话 / 非会话类推送 / 后台冷启动一律不抑制（按 6.1 三态正常直达）。
///
/// [activeSessionId] 当前激活会话（null=不在任何会话页）；[type] 推送类型；
/// [targetToken] 推送目标 token（会话类即会话 token）。
class PushSuppression {
  PushSuppression._();

  static bool shouldSuppressInApp(int? activeSessionId, String? type, String? targetToken) {
    if (activeSessionId == null || type == null || targetToken == null) return false;
    final isSessionPush = type == 'VET_REPLY' || type == 'CONSULT_CLOSED';
    if (!isSessionPush) return false;
    return targetToken == activeSessionId.toString();
  }
}

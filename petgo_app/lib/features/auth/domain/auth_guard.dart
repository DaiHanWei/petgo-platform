import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/router/route_intent.dart';
import 'auth_state.dart';
import 'login_guide_controller.dart';

/// 受控操作的**单一门控入口**（Story 1.5 F2）。
///
/// 已登录 → 执行 [onAllowed] 并返回 true；未登录 → 调 Story 1.4 强弹窗（注入 pendingAction）、
/// 返回 false（不进入目标）。受控 Tab 点击、受控入口（问诊/发布/创建档案）统一调此，
/// 避免各页散落 `if (!loggedIn)` 判断（防 Epic 间不一致）。
bool requireLogin(
  WidgetRef ref,
  BuildContext context, {
  RouteIntent? pendingAction,
  VoidCallback? onAllowed,
}) {
  final state = ref.read(authControllerProvider);
  if (state.isLoggedIn) {
    onAllowed?.call();
    return true;
  }
  ref.read(loginGuideControllerProvider).showHardDialog(context, pendingAction: pendingAction);
  return false;
}

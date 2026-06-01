import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/network/dio_client.dart';
import '../../../core/router/route_intent.dart';
import '../../../shared/widgets/login_hard_dialog.dart';
import '../../../shared/widgets/login_soft_sheet.dart';
import '../data/auth_repository.dart';
import 'auth_routing.dart';
import 'login_response.dart';

/// 登录流程执行器：返回 [LoginResponse]，用户取消时返回 null。
typedef LoginRunner = Future<LoginResponse?> Function();

/// 登录引导协调器（Story 1.4）。
///
/// 暴露 [showSoftSheet] / [showHardDialog] 供任意触发源注入触发——**组件本身不含触发条件**。
/// 软浮层「每 session 最多一次」去重用内存标志（重启清空，非持久 prefs）。
/// 登录成功后按新老分流回跳：老用户执行 pendingAction；新用户先 `/onboarding` 引导，
/// 引导完成后由 [resumePendingAfterOnboarding] 执行 pendingAction。
class LoginGuideController {
  LoginGuideController(this._login);

  final LoginRunner _login;

  bool _softShownThisSession = false;
  RouteIntent? _pending;

  bool get softShownThisSession => _softShownThisSession;
  bool get hasPending => _pending != null;

  /// 软浮层（每 session 最多一次；第 2 次起 no-op）。
  Future<void> showSoftSheet(BuildContext context, {RouteIntent? pendingAction}) async {
    if (_softShownThisSession) return;
    _softShownThisSession = true;
    _pending = pendingAction;
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (sheetCtx) => LoginSoftSheet(
        onLogin: () => _attemptLogin(context, sheetCtx),
        onClose: () {
          _pending = null;
          Navigator.of(sheetCtx).pop();
        },
      ),
    );
  }

  /// 强弹窗（不去重，按触发即弹）。
  Future<void> showHardDialog(BuildContext context, {RouteIntent? pendingAction}) async {
    _pending = pendingAction;
    await showDialog<void>(
      context: context,
      barrierDismissible: true,
      builder: (dlgCtx) => LoginHardDialog(
        onLogin: () => _attemptLogin(context, dlgCtx),
        onClose: () {
          _pending = null;
          Navigator.of(dlgCtx).pop();
        },
      ),
    );
  }

  Future<void> _attemptLogin(BuildContext rootContext, BuildContext overlayContext) async {
    final resp = await _login();
    if (resp == null) return; // 取消：保持引导，停留原页
    if (overlayContext.mounted && Navigator.of(overlayContext).canPop()) {
      Navigator.of(overlayContext).pop();
    }
    if (!rootContext.mounted) return;
    _handleSuccess(rootContext, resp);
  }

  void _handleSuccess(BuildContext context, LoginResponse resp) {
    if (decidePostLoginRoute(resp) == PostLoginRoute.toApp) {
      _runPending(context); // 老用户：直接回触发点
    } else {
      context.go('/onboarding'); // 新用户：先引导，_pending 保留
    }
  }

  /// 新用户引导完成回调 → 再执行 pendingAction（先引导后回跳）。
  void resumePendingAfterOnboarding(BuildContext context) => _runPending(context);

  void _runPending(BuildContext context) {
    final p = _pending;
    _pending = null;
    if (p == null || p.isEmpty) return; // 无后续操作：由调用方/默认页兜底
    p.run(context);
  }
}

final Provider<LoginGuideController> loginGuideControllerProvider =
    Provider<LoginGuideController>((ref) {
  return LoginGuideController(() async {
    try {
      return await ref.read(authRepositoryProvider).loginWithGoogle();
    } on LoginCancelled {
      return null;
    }
  });
});

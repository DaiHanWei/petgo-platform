import 'login_response.dart';

/// 登录成功后的路由去向（AC4 新老用户分流）。
enum PostLoginRoute {
  /// 老用户 / 已完成引导 → 进 App 主框架。
  toApp,

  /// 新用户 / 未完成引导 → 进新用户引导（Story 1.6 本体，本 Story 占位）。
  toOnboarding,
}

/// 纯函数：依据登录响应决定分流去向。
///
/// 规则：`onboardingCompleted==true` → 进 App；否则（含 `isNewUser`）→ 进引导。
PostLoginRoute decidePostLoginRoute(LoginResponse resp) {
  if (resp.onboardingCompleted) return PostLoginRoute.toApp;
  return PostLoginRoute.toOnboarding;
}

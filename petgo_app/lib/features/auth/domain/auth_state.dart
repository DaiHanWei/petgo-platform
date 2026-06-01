import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'auth_routing.dart';
import 'login_response.dart';

/// 登录态（游客 / 已登录 / 新用户待引导）。
enum AuthStatus { guest, authenticated, newUserPendingOnboarding }

/// 不可变登录态。
class AuthState {
  const AuthState({required this.status, this.role, this.profile});

  final AuthStatus status;
  final String? role;
  final UserProfile? profile;

  bool get isLoggedIn => status == AuthStatus.authenticated;

  const AuthState.guest()
      : status = AuthStatus.guest,
        role = null,
        profile = null;
}

/// 登录态管理。副作用（令牌读写）在 repository，本 Notifier 仅持有不可变态。
class AuthController extends Notifier<AuthState> {
  @override
  AuthState build() => const AuthState.guest();

  /// 登录成功后根据分流信号置态。
  void applyLogin(LoginResponse resp) {
    final route = decidePostLoginRoute(resp);
    state = AuthState(
      status: route == PostLoginRoute.toApp
          ? AuthStatus.authenticated
          : AuthStatus.newUserPendingOnboarding,
      role: resp.role,
      profile: resp.profile,
    );
  }

  /// 新用户完成引导（Story 1.6 回调）→ 转为已登录，并回填最新 profile。
  void completeOnboarding(UserProfile profile) {
    state = AuthState(status: AuthStatus.authenticated, role: state.role, profile: profile);
  }

  /// 资料更新（昵称/状态）后回填 profile（不改变登录态语义）。
  void applyProfile(UserProfile profile) {
    state = AuthState(status: state.status, role: state.role, profile: profile);
  }

  /// 续期失败 / 注销 → 落游客态。
  void toGuest() => state = const AuthState.guest();
}

final authControllerProvider =
    NotifierProvider<AuthController, AuthState>(AuthController.new);

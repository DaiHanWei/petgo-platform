import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/dio_client.dart';
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

  /// 兽医登录态（Story 5.1：单 App 双角色，role=VET 走兽医工作台壳）。
  bool get isVet => isLoggedIn && role == 'VET';

  const AuthState.guest()
      : status = AuthStatus.guest,
        role = null,
        profile = null;
}

/// 登录态管理。副作用（令牌读写）在 repository，本 Notifier 仅持有不可变态。
class AuthController extends Notifier<AuthState> {
  @override
  AuthState build() {
    // 冷启动恢复会话（修复:登录态未跨重启持久）。fire-and-forget,先返回游客态,
    // restore 成功后异步切换到已登录/待引导态。
    _restoreSession();
    return const AuthState.guest();
  }

  /// 冷启动用本地 token 调 `/me` 恢复登录态;无 token / 失效则保持游客。
  Future<void> _restoreSession() async {
    try {
      final profile = await ref.read(authRepositoryProvider).restoreSession();
      if (profile == null) return;
      state = AuthState(
        status: profile.onboardingCompleted
            ? AuthStatus.authenticated
            : AuthStatus.newUserPendingOnboarding,
        role: 'USER',
        profile: profile,
      );
    } catch (_) {
      // 恢复失败保持游客态（不阻塞启动）。
    }
  }

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

  /// 兽医账密登录成功（Story 5.1）→ 已登录态、role=VET（无 UserProfile，无引导流）。
  void applyVetLogin() {
    state = const AuthState(status: AuthStatus.authenticated, role: 'VET');
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

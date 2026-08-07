import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/im/im_service.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/push/push_service.dart';
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
  /// 冷启动恢复任务（splash 据此等待，按真实角色精确分流，避免先进 home 再跳工作台的闪屏）。
  Future<void>? _restoreFuture;

  @override
  AuthState build() {
    // 冷启动恢复会话（修复:登录态未跨重启持久）。fire-and-forget,先返回游客态,
    // restore 成功后异步切换到已登录/待引导态。
    _restoreFuture = _restoreSession();
    return const AuthState.guest();
  }

  /// 等冷启动会话恢复结束（成功或失败均完成）。splash 用它在分流前确保 role 已就绪。
  Future<void> ensureRestored() => _restoreFuture ?? Future<void>.value();

  bool _restored = false;

  /// 冷启动恢复是否**已经结束**（成功或失败都算）。**同步可读，不需要 await。**
  ///
  /// 为什么需要它（code-review 2026-08-04）：落地分流要区分「按真实状态落地」与
  /// 「等超时了、按当时已知态兜底落地」（后者要打 `restore_timeout` 标记并武装 FR-91 迟到纠正）。
  /// 改前路由层是靠**自己再 `await restore.timeout(5s)`** 来得出这个结论的 —— 而 splash 侧已经
  /// 有一个 5s 兜底，两段串联成了最坏 10s，且「5~10s 之间完成」会被误判成「没超时」，
  /// 使兜底标记与迟到纠正双双失效。
  /// 现在等待预算**只由 splash 持有**（单一时间源），路由层只读这个标志、不再二次等待。
  bool get isRestored => _restored;

  /// 冷启动按本地 token 的真实 role 恢复登录态;无 token / 失效则保持游客。
  ///
  /// 兽医(role=VET)走 `/vet/me` 校验 → 置 VET 态，router 据此直达兽医工作台(不再误进用户首页);
  /// 用户走 `/me` 恢复 profile。**修复**:原先 role 写死 'USER'，致兽医冷启动被当成用户进 home。
  Future<void> _restoreSession() async {
    // 无论成功、失败还是提前 return，都要标记「已结束」—— 落地分流据此判断是否属超时兜底。
    // 放在 finally 里，保证在本 future 完成**之前**就已置位（外部 `.then` 一定看得到）。
    try {
      final repo = ref.read(authRepositoryProvider);
      final role = await repo.readTokenRole();
      if (role == 'VET') {
        if (await repo.restoreVetSession()) {
          state = const AuthState(status: AuthStatus.authenticated, role: 'VET');
        }
        return; // 兽医无 UserProfile / 无引导流
      }
      final profile = await repo.restoreSession();
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
    } finally {
      _restored = true;
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
  ///
  /// 同时解绑 IM 登录：这是登出 / 账号注销 / 强制 401 / 引导中止的唯一收口。
  /// 不解绑则腾讯 IM SDK 仍以上一用户身份登录（app 级 [imServiceProvider] + SDK 登录态跨账号存活），
  /// 同设备下一用户 loginIfNeeded 幂等空转 → 拉到上一用户的兽医聊天历史（跨用户隐私泄漏）。
  /// best-effort fire-and-forget：不阻塞游客态切换，失败静默（页面已离场）。
  void toGuest() {
    state = const AuthState.guest();
    // 顺序硬约束：先反注册离线推送、再 IM logout——反注册需要 IM 登录态才能解绑 token；
    // 不解绑则同设备下一用户仍收上一账号的推送（与 IM 漏登出同型的跨用户隐私泄漏）。
    // 反注册限时 5s（code-review 2026-08-07）：原生调用挂起时绝不能饿死后面的 IM logout。
    // 两步均 best-effort fire-and-forget：不阻塞游客态切换，失败静默（页面已离场）。
    //
    // 竞态防护（PR#34 finding #10）：logout 被压到反注册之后，5s 窗口内可能有新账号登录
    //（缓存秒登 / 401 强制登录弹窗）。① 此刻同步作废本地 IM 凭证——新账号 loginIfNeeded
    // 会做真实重登而非在旧凭证上幂等空转；② 记录登出代际号，迟到的 logout 发现代际已变
    //（新登录发生过）则放弃执行，绝不清掉新账号的 IM 会话。
    final push = ref.read(pushServiceProvider);
    final im = ref.read(imServiceProvider);
    final logoutGeneration = im.invalidateCredential();
    push
        .unregister()
        .timeout(const Duration(seconds: 5))
        .catchError((_) {})
        .whenComplete(
            () => im.logout(ifGeneration: logoutGeneration).catchError((_) {}));
  }
}

final authControllerProvider =
    NotifierProvider<AuthController, AuthState>(AuthController.new);

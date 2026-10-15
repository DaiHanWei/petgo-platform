import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/social/domain/follow_state.dart';

/// V1.3.4 follow · Story 1.3 · AC2.4：全局关注覆盖表的 set / forget / effective 与账号切换清空。
class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: 1, nickname: 'A', onboardingCompleted: true),
      );

  /// 真 toGuest 会去反注册推送 / 登出 IM —— 测试里只要状态切换。
  @override
  void toGuest() => state = const AuthState.guest();
}

void main() {
  const followed = FollowState(iFollow: true, followsMe: false);
  const server = FollowState(iFollow: false, followsMe: true);

  ProviderContainer make() {
    final c = ProviderContainer(overrides: [authControllerProvider.overrideWith(_Auth.new)]);
    addTearDown(c.dispose);
    return c;
  }

  test('无覆盖时取服务端初值；set 后取覆盖值', () {
    final c = make();
    final n = c.read(followStateProvider.notifier);
    expect(n.effective(9, server), server);
    n.set(9, followed);
    expect(c.read(followStateProvider.notifier).effective(9, server), followed);
    expect(c.read(followStateProvider)[9], followed);
  });

  test('forget 回到服务端初值；对不存在的 key forget 不报错', () {
    final c = make();
    final n = c.read(followStateProvider.notifier);
    n.set(9, followed);
    n.forget(9);
    n.forget(42);
    expect(c.read(followStateProvider.notifier).effective(9, server), server);
  });

  test('换账号（applyProfile 换 id）后覆盖表清空', () {
    final c = make();
    c.read(followStateProvider.notifier).set(9, followed);
    expect(c.read(followStateProvider), isNotEmpty);

    c.read(authControllerProvider.notifier)
        .applyProfile(const UserProfile(id: 2, nickname: 'B', onboardingCompleted: true));

    expect(c.read(followStateProvider), isEmpty);
  });

  test('退到游客后覆盖表清空', () {
    final c = make();
    c.read(followStateProvider.notifier).set(9, followed);

    c.read(authControllerProvider.notifier).toGuest();

    expect(c.read(followStateProvider), isEmpty);
  });

  test('FollowResult.fromJson 缺键容忍', () {
    final r = FollowResult.fromJson(const {'iFollow': true, 'followerCount': 3});
    expect(r.state, const FollowState(iFollow: true, followsMe: false));
    expect(r.followerCount, 3);
    expect(FollowResult.fromJson(const {}).state, FollowState.none);
  });
}

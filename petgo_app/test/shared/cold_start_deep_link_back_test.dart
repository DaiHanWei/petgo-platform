import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/app.dart';
import 'package:tailtopia/core/router/app_router.dart';
import 'package:tailtopia/core/router/deep_link_routes.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/feed_repository.dart';

import '../support/fake_feed_repository.dart';

/// 已登录用户（`/notifications` 是受控路由，游客会被门控弹走 —— 而收得到推送的必然是登录用户）。
class _LoggedInAuth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(
          nickname: 'Aurel',
          petStatus: 'HAS_PET',
          hasPetProfile: true,
          onboardingCompleted: true,
        ),
      );

  @override
  Future<void> ensureRestored() => Future<void>.value();
}

/// 🐛 2026-08-07 iOS 实机：杀后台 → 点系统推送唤醒 → 落通知中心，**返回不了**
/// （顶栏没有返回箭头，iOS 边缘左滑手势也无效），只能杀进程。而从 Feed 进同一个页面正常。
///
/// 根因在冷启动的深链落地：当时是无条件 `go(pending)`，而 `go` **替换整个路由栈** ⇒
/// `/notifications` 成了栈里唯一一页 ⇒ `Navigator.canPop()` 为 false ⇒ `AppBar` 不生成返回键、
/// 左滑手势也因没有上一页而失效。
///
/// 影响面不止通知中心：所有 shell 外的深链落点（内容详情 / 问诊会话 / 里程碑 / 发布…）
/// 在冷启动下都是死路。App 已在运行时反而没事 —— 那条路径本来就是 `push`。
void main() {
  group('判定：哪些深链需要先铺底座', () {
    test('shell 四个 Tab 分支根 → 直接 go（push 会撞 GlobalKey 白屏）', () {
      for (final root in DeepLinkRoutes.shellTabRoots) {
        expect(deepLinkNeedsBaseRoute(root), isFalse, reason: '$root 是分支根，必须 go');
      }
    });

    test('/vet/* → 直接 go（靠角色守卫收口到工作台）', () {
      expect(deepLinkNeedsBaseRoute('/vet/workbench'), isFalse);
    });

    test('shell 外的深链落点 → 需要底座，否则冷启动进去就出不来', () {
      const dead = [
        '/notifications', // 本次 bug 的现场
        '/content/42',
        '/consult/conversation/7',
        '/profile/milestones',
        '/publish?preset=growth-calendar',
        '/me/orders',
      ];
      for (final loc in dead) {
        expect(deepLinkNeedsBaseRoute(loc), isTrue, reason: '$loc 若直接 go 会成为栈底 → 返回不了');
      }
    });
  });

  testWidgets('🐛 回归：冷启动点推送落通知中心后，栈里必须有上一页可返回', (tester) async {
    final container = ProviderContainer(overrides: [
      authControllerProvider.overrideWith(_LoggedInAuth.new),
      feedRepositoryProvider.overrideWithValue(FakeFeedRepository()),
    ]);
    addTearDown(container.dispose);

    // 推送点击发生在启动屏期间 → 存 pending，交 splash 的 onComplete 消费（真实链路）。
    container.read(pendingDeepLinkProvider.notifier).set(DeepLinkRoutes.notificationsCenter);

    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const TailTopiaApp()),
    );
    await tester.pump();

    final router = container.read(routerProvider);
    expect(router.routerDelegate.currentConfiguration.uri.path, '/splash');

    // 走完启动屏停留（含 5s 兜底余量），让 onComplete 消费 pending。
    await tester.pump(const Duration(milliseconds: 5200));
    for (var i = 0; i < 5; i++) {
      await tester.pump(const Duration(milliseconds: 10));
    }

    // ⚠️ 判断落点要看**栈顶 match**，不能用 `currentConfiguration.uri`：
    // go_router 对 push 上去的路由，`uri` 仍返回**底座**的地址（这里是 /profile），
    // 栈顶在 `matches.last`。2026-08-07 写这条测试时先按 `uri` 断言，红了半天才发现
    // 代码其实是对的、错的是断言。
    final cfg = router.routerDelegate.currentConfiguration;
    expect(cfg.matches.last.matchedLocation, DeepLinkRoutes.notificationsCenter,
        reason: '深链优先级最高，栈顶仍应是通知中心');
    expect(cfg.matches.length, 2, reason: '底座 + 深链两层');

    // 🔴 本用例的核心：底下必须还有一页。
    // 改前这里是 false —— 用户没有任何办法离开通知中心。
    expect(rootNavigatorKey.currentState?.canPop(), isTrue,
        reason: '冷启动深链落地后栈底没有页面 ⇒ 没有返回箭头、iOS 左滑也无效，用户被困死');

    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('Tab 根深链不受影响：仍是替换栈，不平白多压一层', (tester) async {
    final container = ProviderContainer(overrides: [
      authControllerProvider.overrideWith(_LoggedInAuth.new),
      feedRepositoryProvider.overrideWithValue(FakeFeedRepository()),
    ]);
    addTearDown(container.dispose);

    container.read(pendingDeepLinkProvider.notifier).set('/home');

    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const TailTopiaApp()),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 5200));
    for (var i = 0; i < 5; i++) {
      await tester.pump(const Duration(milliseconds: 10));
    }

    expect(container.read(routerProvider).routerDelegate.currentConfiguration.uri.path, '/home');
    // Tab 根是 shell 分支切换，不该在它下面再垫一层（垫了就等于按返回能退出到另一个 Tab，
    // 与「点推送直达该 Tab」的语义不符，也会和 goBranch 的分支栈打架）。
    expect(rootNavigatorKey.currentState?.canPop(), isFalse);

    await tester.pumpWidget(const SizedBox());
  });
}

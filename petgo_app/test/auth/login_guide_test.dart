import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/network/dio_client.dart';
import 'package:tailtopia/core/router/route_intent.dart';
import 'package:tailtopia/features/auth/data/auth_repository.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_guide_controller.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/login_hard_dialog.dart';
import 'package:tailtopia/shared/widgets/login_soft_sheet.dart';

LoginResponse _resp({required bool onboardingCompleted, bool isNewUser = false}) => LoginResponse(
      accessToken: 'a',
      refreshToken: 'r',
      role: 'USER',
      isNewUser: isNewUser,
      onboardingCompleted: onboardingCompleted,
    );

/// 假 AuthRepository：loginWithGoogle 直接返回固定响应，其余成员不触及。
class _FakeAuthRepository implements AuthRepository {
  _FakeAuthRepository(this._response);
  final LoginResponse _response;
  @override
  Future<LoginResponse> loginWithGoogle() async => _response;
  @override
  dynamic noSuchMethod(Invocation invocation) => throw UnimplementedError();
}

GoRouter _router(LoginGuideController controller, {RouteIntent? pending, bool soft = false}) {
  return GoRouter(
    initialLocation: '/start',
    routes: [
      GoRoute(
        path: '/start',
        builder: (c, s) => Scaffold(
          body: Center(
            child: ElevatedButton(
              key: const ValueKey('trigger'),
              onPressed: () => soft
                  ? controller.showSoftSheet(c, pendingAction: pending)
                  : controller.showHardDialog(c, pendingAction: pending),
              child: const Text('go'),
            ),
          ),
        ),
      ),
      GoRoute(
        path: '/onboarding',
        builder: (c, s) => Scaffold(
          body: Center(
            child: ElevatedButton(
              key: const ValueKey('resume'),
              onPressed: () => controller.resumePendingAfterOnboarding(c),
              child: const Text('ONBOARDING PAGE'),
            ),
          ),
        ),
      ),
      GoRoute(path: '/triage', builder: (c, s) => const Scaffold(body: Center(child: Text('TRIAGE PAGE')))),
      GoRoute(path: '/home', builder: (c, s) => const Scaffold(body: Center(child: Text('HOME PAGE')))),
    ],
  );
}

Future<void> _pump(WidgetTester tester, GoRouter router) async {
  await tester.pumpWidget(ProviderScope(
    child: MaterialApp.router(
      routerConfig: router,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('AC1: 软浮层结构（主 CTA + 弱化关闭）+ 每 session 一次去重', (tester) async {
    final controller = LoginGuideController(() async => null); // 登录不参与本例
    await _pump(tester, _router(controller, soft: true));

    await tester.tap(find.byKey(const ValueKey('trigger')));
    await tester.pumpAndSettle();
    expect(find.byType(LoginSoftSheet), findsOneWidget);
    expect(find.byKey(const ValueKey('softSheetGoogleCta')), findsOneWidget);
    expect(find.byKey(const ValueKey('softSheetClose')), findsOneWidget);

    // 关闭后再次触发 → no-op（每 session 一次）
    await tester.tap(find.byKey(const ValueKey('softSheetClose')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('trigger')));
    await tester.pumpAndSettle();
    expect(find.byType(LoginSoftSheet), findsNothing);
    expect(controller.softShownThisSession, isTrue);
  });

  testWidgets('AC1: 强弹窗结构 + 连续触发每次都弹（不去重）', (tester) async {
    final controller = LoginGuideController(() async => null);
    await _pump(tester, _router(controller, soft: false));

    await tester.tap(find.byKey(const ValueKey('trigger')));
    await tester.pumpAndSettle();
    expect(find.byType(LoginHardDialog), findsOneWidget);
    expect(find.text('Sign in to continue using this feature'), findsOneWidget);
    expect(find.byKey(const ValueKey('hardDialogGoogleCta')), findsOneWidget);
    expect(find.byKey(const ValueKey('hardDialogClose')), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('hardDialogClose')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('trigger')));
    await tester.pumpAndSettle();
    expect(find.byType(LoginHardDialog), findsOneWidget); // 再次弹出
  });

  testWidgets('AC2: 老用户登录 → 执行 pendingAction（回到触发点）', (tester) async {
    final controller = LoginGuideController(() async => _resp(onboardingCompleted: true));
    await _pump(tester, _router(controller, pending: const RouteIntent(location: '/triage')));

    await tester.tap(find.byKey(const ValueKey('trigger')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('hardDialogGoogleCta')));
    await tester.pumpAndSettle();

    expect(find.text('TRIAGE PAGE'), findsOneWidget);
  });

  testWidgets('AC2: 新用户 → 先引导 → 引导完成后再执行 pendingAction', (tester) async {
    final controller =
        LoginGuideController(() async => _resp(onboardingCompleted: false, isNewUser: true));
    await _pump(tester, _router(controller, pending: const RouteIntent(location: '/triage')));

    await tester.tap(find.byKey(const ValueKey('trigger')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('hardDialogGoogleCta')));
    await tester.pumpAndSettle();

    // 先到引导占位
    expect(find.text('ONBOARDING PAGE'), findsOneWidget);
    expect(controller.hasPending, isTrue);

    // 引导完成 → 再执行 pendingAction
    await tester.tap(find.byKey(const ValueKey('resume')));
    await tester.pumpAndSettle();
    expect(find.text('TRIAGE PAGE'), findsOneWidget);
  });

  testWidgets('AC2: 主动关闭引导（未登录）→ 取消 pendingAction、停留原页', (tester) async {
    final controller = LoginGuideController(() async => null);
    await _pump(tester, _router(controller, pending: const RouteIntent(location: '/triage')));

    await tester.tap(find.byKey(const ValueKey('trigger')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('hardDialogClose')));
    await tester.pumpAndSettle();

    expect(controller.hasPending, isFalse);
    expect(find.byKey(const ValueKey('trigger')), findsOneWidget); // 仍在原页
    expect(find.text('TRIAGE PAGE'), findsNothing);
  });

  // ===== R2 / AC3（决策 F13）：授权失败回触发前页面 + 重试 =====

  testWidgets('AC3: 强弹窗登录失败 → 失败态+重试入口；pending 保留、不路由注册引导', (tester) async {
    final controller = LoginGuideController(() async => throw Exception('boom'));
    await _pump(tester, _router(controller, pending: const RouteIntent(location: '/triage')));

    await tester.tap(find.byKey(const ValueKey('trigger')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('hardDialogGoogleCta')));
    await tester.pumpAndSettle();

    // 失败态内联渲染 + 重试入口（主 CTA 仍在）
    expect(find.byKey(const ValueKey('hardDialogError')), findsOneWidget);
    expect(find.text('Sign-in failed, please try again'), findsOneWidget);
    expect(find.byKey(const ValueKey('hardDialogGoogleCta')), findsOneWidget);
    // pendingAction 保留、未前进到注册引导、未执行 pendingAction
    expect(controller.hasPending, isTrue);
    expect(find.text('ONBOARDING PAGE'), findsNothing);
    expect(find.text('TRIAGE PAGE'), findsNothing);
    expect(find.byType(LoginHardDialog), findsOneWidget); // 引导仍在（停留原页之上）
  });

  testWidgets('AC3: 失败后重试成功（老用户）→ 用保留的 pendingAction 回跳触发点', (tester) async {
    var calls = 0;
    final controller = LoginGuideController(() async {
      calls++;
      if (calls == 1) throw Exception('boom'); // 首次失败
      return _resp(onboardingCompleted: true); // 重试成功（老用户）
    });
    await _pump(tester, _router(controller, pending: const RouteIntent(location: '/triage')));

    await tester.tap(find.byKey(const ValueKey('trigger')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('hardDialogGoogleCta')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('hardDialogError')), findsOneWidget); // 失败态

    // 重试 → 成功 → 用保留 pending 回跳
    await tester.tap(find.byKey(const ValueKey('hardDialogGoogleCta')));
    await tester.pumpAndSettle();
    expect(find.text('TRIAGE PAGE'), findsOneWidget);
    expect(controller.hasPending, isFalse);
  });

  testWidgets('AC3: 失败后主动关闭 → pendingAction 清空、停留原页', (tester) async {
    final controller = LoginGuideController(() async => throw Exception('boom'));
    await _pump(tester, _router(controller, pending: const RouteIntent(location: '/triage')));

    await tester.tap(find.byKey(const ValueKey('trigger')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('hardDialogGoogleCta')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('hardDialogError')), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('hardDialogClose')));
    await tester.pumpAndSettle();
    expect(controller.hasPending, isFalse);
    expect(find.byKey(const ValueKey('trigger')), findsOneWidget); // 停留原页
    expect(find.text('TRIAGE PAGE'), findsNothing);
  });

  testWidgets('AC3: 软浮层登录失败 → 失败态+重试入口；pending 保留', (tester) async {
    final controller = LoginGuideController(() async => throw Exception('boom'));
    await _pump(tester,
        _router(controller, soft: true, pending: const RouteIntent(location: '/triage')));

    await tester.tap(find.byKey(const ValueKey('trigger')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('softSheetGoogleCta')));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('softSheetError')), findsOneWidget);
    expect(find.byKey(const ValueKey('softSheetGoogleCta')), findsOneWidget); // 重试入口
    expect(controller.hasPending, isTrue);
    expect(find.text('TRIAGE PAGE'), findsNothing);
  });

  // 回归测试：用【真实】loginGuideControllerProvider（含 applyLogin wiring），
  // 防再次出现「浮层登录成功却没把 authController 置为已登录」的缺陷。
  // 注：上方用例直接 new LoginGuideController(fakeRunner)，绕过了 provider 的真实 wiring，故漏检。
  testWidgets('回归: 浮层登录成功 → authController 必须置为已登录（applyLogin wiring）', (tester) async {
    final container = ProviderContainer(overrides: [
      authRepositoryProvider
          .overrideWithValue(_FakeAuthRepository(_resp(onboardingCompleted: true))),
    ]);
    addTearDown(container.dispose);

    // 真实 provider 构造的 controller（这才是线上路径）
    final controller = container.read(loginGuideControllerProvider);

    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp.router(
        routerConfig: _router(controller, pending: const RouteIntent(location: '/triage')),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
      ),
    ));
    await tester.pumpAndSettle();

    expect(container.read(authControllerProvider).isLoggedIn, isFalse); // 前置：游客

    await tester.tap(find.byKey(const ValueKey('trigger')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('hardDialogGoogleCta')));
    await tester.pumpAndSettle();

    // 关键断言（修复前为 false）：浮层登录后真的已登录，且 pendingAction 执行。
    expect(container.read(authControllerProvider).isLoggedIn, isTrue);
    expect(find.text('TRIAGE PAGE'), findsOneWidget);
  });
}

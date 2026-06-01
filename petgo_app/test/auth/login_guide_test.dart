import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:petgo/core/router/route_intent.dart';
import 'package:petgo/features/auth/domain/login_guide_controller.dart';
import 'package:petgo/features/auth/domain/login_response.dart';
import 'package:petgo/l10n/app_localizations.dart';
import 'package:petgo/shared/widgets/login_hard_dialog.dart';
import 'package:petgo/shared/widgets/login_soft_sheet.dart';

LoginResponse _resp({required bool onboardingCompleted, bool isNewUser = false}) => LoginResponse(
      accessToken: 'a',
      refreshToken: 'r',
      role: 'USER',
      isNewUser: isNewUser,
      onboardingCompleted: onboardingCompleted,
    );

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
}

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/startup/startup_popup_ledger.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/ops/presentation/ops_popup_coordinator.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/app_shell.dart';

/// Story 5.4 · T4：AppShell 首帧后把协调器挂到**宿主** GoRouter 上，并传入挂载中的 ref。
class _SpyCoordinator extends OpsPopupCoordinator {
  _SpyCoordinator()
      : super(
          fetchCandidates: () async => const [],
          reportImpression: (_) async {},
          prefs: () => throw UnimplementedError(),
          auth: () => const AuthState.guest(),
        );

  final List<GoRouter> routers = [];
  final List<WidgetRef?> refs = [];

  @override
  void attach(GoRouter router, {WidgetRef? ref}) {
    routers.add(router);
    refs.add(ref);
  }
}

void main() {
  testWidgets('首帧后 attach 一次：router 为宿主实例、ref 非空且挂载中', (tester) async {
    StartupPopupLedger.instance.resetForTest();
    final spy = _SpyCoordinator();
    Widget page(String n) => Scaffold(body: Text('page:$n'));
    final router = GoRouter(
      initialLocation: '/profile',
      routes: [
        StatefulShellRoute.indexedStack(
          builder: (context, state, shell) => AppShell(navigationShell: shell),
          branches: [
            StatefulShellBranch(routes: [GoRoute(path: '/profile', builder: (_, _) => page('profile'))]),
            StatefulShellBranch(routes: [GoRoute(path: '/shop', builder: (_, _) => page('shop'))]),
            StatefulShellBranch(routes: [GoRoute(path: '/home', builder: (_, _) => page('home'))]),
            StatefulShellBranch(routes: [GoRoute(path: '/me', builder: (_, _) => page('me'))]),
          ],
        ),
      ],
    );
    addTearDown(router.dispose);
    await tester.pumpWidget(ProviderScope(
      overrides: [opsPopupCoordinatorProvider.overrideWithValue(spy)],
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
      ),
    ));
    // attach 在首帧的 post-frame 回调里（pumpWidget 那一帧结束即执行）。
    await tester.pump();
    expect(spy.routers, hasLength(1));
    expect(identical(spy.routers.single, router), isTrue, reason: '必须是承载外壳的那个 GoRouter');
    final ref = spy.refs.single;
    expect(ref, isNotNull);
    expect(ref!.context.mounted, isTrue);
    expect(find.text('page:profile'), findsOneWidget);
  });
}

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/router/app_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_guide_controller.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/ops/domain/ops_target.dart';
import 'package:tailtopia/features/ops/presentation/open_ops_target.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/login_hard_dialog.dart';
import 'package:url_launcher/url_launcher.dart';

/// Story 5.2 · AC3 / AC5.2：执行入口（测试 GoRouter + fake launcher，L0）。
LoginResponse _user() => const LoginResponse(
    accessToken: 'a', refreshToken: 'r', role: 'USER', isNewUser: false, onboardingCompleted: true);

LoginResponse _vet() => const LoginResponse(
    accessToken: 'a', refreshToken: 'r', role: 'VET', isNewUser: false, onboardingCompleted: true);

Widget _placeholder(String name) => Scaffold(body: Text('page:$name'));

class _Harness {
  _Harness(this.router, this.container);
  final GoRouter router;
  final ProviderContainer container;

  /// 起点页外层 Consumer 的 ref（页面级，始终挂载）。
  late WidgetRef pageRef;

  /// 起点页内一个可卸载子 widget 的 context（模拟「弹窗先关自己再跳」时已失效的调用方 context）。
  BuildContext? childContext;
  final ValueNotifier<bool> showChild = ValueNotifier(true);

  /// 栈顶落点（含 push 上去的页面；`currentConfiguration.uri` 不反映 imperative push）。
  String get location => router.state.uri.toString();
}

/// 起点页 `/origin` 上放一个按钮，点击即以当前 [target] 调 [openOpsTarget]。
Future<_Harness> _pump(
  WidgetTester tester, {
  required OpsTarget Function() target,
  LoginResponse? login,
  OpsUrlLauncher? launcher,
  LoginRunner? loginRunner,
}) async {
  final container = ProviderContainer(overrides: [
    if (loginRunner != null)
      loginGuideControllerProvider.overrideWith((ref) => LoginGuideController(loginRunner)),
  ]);
  addTearDown(container.dispose);
  late final _Harness h;
  if (login != null) {
    container.read(authControllerProvider.notifier).applyLogin(login);
  }
  final router = GoRouter(
    navigatorKey: rootNavigatorKey,
    initialLocation: '/origin',
    routes: [
      GoRoute(
        path: '/origin',
        builder: (_, _) => Scaffold(
          body: Consumer(
            builder: (context, ref, _) {
              h.pageRef = ref;
              return Column(children: [
                ElevatedButton(
                  key: const ValueKey('open'),
                  onPressed: () => openOpsTarget(context, ref, target(), launcher: launcher),
                  child: const Text('open'),
                ),
                ValueListenableBuilder<bool>(
                  valueListenable: h.showChild,
                  builder: (_, show, _) => show
                      ? Builder(builder: (c) {
                          h.childContext = c;
                          return const SizedBox.shrink();
                        })
                      : const SizedBox.shrink(),
                ),
              ]);
            },
          ),
        ),
      ),
      GoRoute(path: '/home', builder: (_, _) => _placeholder('home')),
      GoRoute(
        path: '/shop',
        builder: (_, s) => _placeholder('shop:${s.uri.queryParameters['category'] ?? ''}'),
        routes: [
          GoRoute(
            path: 'products/:token',
            builder: (_, s) => _placeholder('product:${s.pathParameters['token']}'),
          ),
        ],
      ),
      GoRoute(path: '/profile/pet-insights', builder: (_, _) => _placeholder('insights')),
      GoRoute(path: '/profile/health', builder: (_, _) => _placeholder('health')),
    ],
  );
  addTearDown(router.dispose);
  h = _Harness(router, container);
  addTearDown(h.showChild.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(
      routerConfig: router,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
    ),
  ));
  await tester.pumpAndSettle();
  return h;
}

Future<void> _tapOpen(WidgetTester tester) async {
  await tester.tap(find.byKey(const ValueKey('open')));
  await tester.pumpAndSettle();
}

void main() {
  late List<(String, Map<String, Object>?)> captured;

  setUp(() {
    captured = [];
    Analytics.debugCaptureSink = (e, p) => captured.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  int blockedCount() => captured
      .where((c) => c.$1 == 'login_guide_entry_blocked' && c.$2?['entry'] == 'ops_target')
      .length;

  group('go 类（Tab 根）', () {
    for (final (target, expected) in [
      (const OpsTarget(OpsTargetType.appPage, 'HOME'), '/home'),
      (const OpsTarget(OpsTargetType.appPage, 'TOKO'), '/shop'),
      (const OpsTarget(OpsTargetType.category, 'MAKANAN'), '/shop?category=MAKANAN'),
    ]) {
      testWidgets('游客 $target → go $expected，不弹登录', (tester) async {
        final h = await _pump(tester, target: () => target);
        await _tapOpen(tester);
        expect(h.location, expected);
        // 钉住 Tab 根用 go 不用 push：go 替换栈，没有可 pop 的页。
        expect(h.router.canPop(), isFalse);
        expect(find.byType(LoginHardDialog), findsNothing);
        expect(blockedCount(), 0);
      });
    }
  });

  group('push 类', () {
    testWidgets('游客 PRODUCT → push 商品详情，不弹登录，可 pop 回原页', (tester) async {
      final h = await _pump(tester, target: () => const OpsTarget(OpsTargetType.product, 'P_tok-1'));
      await _tapOpen(tester);
      expect(find.byType(LoginHardDialog), findsNothing);
      expect(h.location, '/shop/products/P_tok-1');
      expect(find.text('page:product:P_tok-1'), findsOneWidget);
      h.router.pop();
      await tester.pumpAndSettle();
      expect(h.location, '/origin');
    });

    for (final (value, page, loc) in [
      ('KNOW_YOUR_PET', 'insights', '/profile/pet-insights'),
      ('HEALTH', 'health', '/profile/health'),
    ]) {
      testWidgets('已登录 $value → push $loc 且可 pop 回原页', (tester) async {
        final h = await _pump(tester,
            target: () => OpsTarget(OpsTargetType.appPage, value), login: _user());
        await _tapOpen(tester);
        expect(h.location, loc);
        expect(find.text('page:$page'), findsOneWidget);
        expect(h.router.canPop(), isTrue);
        h.router.pop();
        await tester.pumpAndSettle();
        expect(h.location, '/origin');
        expect(blockedCount(), 0);
      });
    }
  });

  group('需登录页面 · 游客', () {
    for (final value in ['KNOW_YOUR_PET', 'HEALTH', 'PUBLISH']) {
      testWidgets('游客 $value → 强弹窗、路由不变、login_guide_entry_blocked 一次', (tester) async {
        final h = await _pump(tester, target: () => OpsTarget(OpsTargetType.appPage, value));
        await _tapOpen(tester);
        expect(find.byType(LoginHardDialog), findsOneWidget);
        expect(h.location, '/origin');
        expect(blockedCount(), 1);
      });
    }
  });

  testWidgets('VET 已登录 → 一律不跳', (tester) async {
    final h = await _pump(tester,
        target: () => const OpsTarget(OpsTargetType.appPage, 'HOME'), login: _vet());
    await _tapOpen(tester);
    expect(h.location, '/origin');
  });

  testWidgets('复审：调用方 context 已卸载（ref 仍挂载）→ 已登录 HEALTH 仍能 push', (tester) async {
    final h = await _pump(tester,
        target: () => const OpsTarget(OpsTargetType.appPage, 'HEALTH'), login: _user());
    final stale = h.childContext!;
    h.showChild.value = false;
    await tester.pumpAndSettle();
    expect(stale.mounted, isFalse);

    unawaited(openOpsTarget(stale, h.pageRef, const OpsTarget(OpsTargetType.appPage, 'HEALTH')));
    await tester.pumpAndSettle();
    expect(h.location, '/profile/health');
    expect(h.router.canPop(), isTrue);
  });

  for (final (value, page, loc) in [
    ('KNOW_YOUR_PET', 'insights', '/profile/pet-insights'),
    ('HEALTH', 'health', '/profile/health'),
  ]) {
    testWidgets('游客 $value → 登录成功 → onResume push 到目标页且可返回', (tester) async {
      late final ProviderContainer c;
      final h = await _pump(tester,
          target: () => OpsTarget(OpsTargetType.appPage, value), loginRunner: () async {
        c.read(authControllerProvider.notifier).applyLogin(_user());
        return _user();
      });
      c = h.container;
      await _tapOpen(tester);
      expect(find.byType(LoginHardDialog), findsOneWidget);
      expect(h.location, '/origin');

      await tester.tap(find.byKey(const ValueKey('hardDialogGoogleCta')));
      await tester.pumpAndSettle();
      expect(find.byType(LoginHardDialog), findsNothing);
      expect(h.location, loc);
      expect(find.text('page:$page'), findsOneWidget);
      h.router.pop();
      await tester.pumpAndSettle();
      expect(h.location, '/origin');
    });
  }

  group('EXTERNAL', () {
    final url = Uri.parse('https://promo.tailtopia.id/x');
    const target = OpsTarget(OpsTargetType.external, 'https://promo.tailtopia.id/x');

    testWidgets('VET 已登录 + EXTERNAL → launcher 零调用', (tester) async {
      var calls = 0;
      await _pump(tester, target: () => target, login: _vet(), launcher: (_, _) async {
        calls++;
        return true;
      });
      await _tapOpen(tester);
      expect(calls, 0);
    });

    testWidgets('inAppBrowserView 成功 → 只调一次', (tester) async {
      final calls = <(Uri, LaunchMode)>[];
      final h = await _pump(tester, target: () => target, launcher: (u, m) async {
        calls.add((u, m));
        return true;
      });
      await _tapOpen(tester);
      expect(calls, [(url, LaunchMode.inAppBrowserView)]);
      expect(h.location, '/origin');
    });

    testWidgets('返回 false → 退回 externalApplication', (tester) async {
      final calls = <(Uri, LaunchMode)>[];
      await _pump(tester, target: () => target, launcher: (u, m) async {
        calls.add((u, m));
        return false;
      });
      await _tapOpen(tester);
      expect(calls, [(url, LaunchMode.inAppBrowserView), (url, LaunchMode.externalApplication)]);
    });

    testWidgets('抛异常 → 同样退回，且 openOpsTarget 不抛', (tester) async {
      final calls = <LaunchMode>[];
      await _pump(tester, target: () => target, launcher: (u, m) async {
        calls.add(m);
        throw Exception('no activity');
      });
      await _tapOpen(tester);
      expect(calls, [LaunchMode.inAppBrowserView, LaunchMode.externalApplication]);
      expect(tester.takeException(), isNull);
    });
  });

  group('NONE / 非法值', () {
    for (final target in [
      OpsTarget.none,
      const OpsTarget(OpsTargetType.appPage, 'SETTINGS'),
      const OpsTarget(OpsTargetType.appPage, 'home'),
      const OpsTarget(OpsTargetType.category, 'FOOD'),
      const OpsTarget(OpsTargetType.product, 'a/b'),
      const OpsTarget(OpsTargetType.external, 'javascript:alert(1)'),
      const OpsTarget(OpsTargetType.external, 'ftp://a.b'),
    ]) {
      testWidgets('$target → 路由不变、launcher 零调用、不抛', (tester) async {
        var calls = 0;
        final h = await _pump(tester, target: () => target, launcher: (_, _) async {
          calls++;
          return true;
        });
        await _tapOpen(tester);
        expect(h.location, '/origin');
        expect(calls, 0);
        expect(find.byType(LoginHardDialog), findsNothing);
        expect(tester.takeException(), isNull);
      });
    }
  });
}

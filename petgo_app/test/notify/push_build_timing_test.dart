import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:petgo/core/storage/prefs.dart';
import 'package:petgo/features/notify/data/push_permission_providers.dart';
import 'package:petgo/features/notify/domain/push_permission_gate.dart';
import 'package:petgo/features/profile/domain/share_service.dart';
import 'package:petgo/features/profile/presentation/profile_created_celebration_page.dart';
import 'package:petgo/l10n/app_localizations.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Story 6.4 AC3（F15）：建档时机推送权限的**弹出位置锚点**——FR-0G 庆祝页主 CTA「开始探索」
/// 回调之后、进首页之前触发 `maybeRequestAfterProfileCreated`，且全程仅弹一次。
///
/// 本测试在庆祝页 + 真实 [PushPermissionGate] + GoRouter 上锁定该时序契约（与 app_router 的
/// `/profile/created` 路由 `onStartExplore` 闭包同构：先 gate 后 `go('/home')`）。
void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  Future<({int requestCount})> pumpAndStart(WidgetTester tester) async {
    final prefs = await AppPrefs.create();
    var requestCount = 0;
    final gate = PushPermissionGate(
      prefs: prefs,
      requestSystemPermission: () async {
        requestCount++;
        return true;
      },
    );

    final router = GoRouter(
      initialLocation: '/c',
      routes: [
        GoRoute(
          path: '/c',
          builder: (c, s) => Consumer(builder: (ctx, ref, _) {
            return ProfileCreatedCelebrationPage(
              petName: 'Momo',
              cardToken: 'tok',
              avatarUrl: null,
              onStartExplore: () async {
                // 与 app_router /profile/created 同构：建档时机锚点 = CTA 后、go home 前。
                final g = await ref.read(pushPermissionGateProvider.future);
                await g.maybeRequestAfterProfileCreated(neverConsulted: true);
                if (ctx.mounted) ctx.go('/home');
              },
            );
          }),
        ),
        GoRoute(path: '/home', builder: (c, s) => const Scaffold(body: Text('HOME'))),
      ],
    );

    await tester.pumpWidget(ProviderScope(
      overrides: [
        shareServiceProvider.overrideWithValue((_) async {}),
        pushPermissionGateProvider.overrideWith((ref) async => gate),
      ],
      child: MaterialApp.router(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('en'),
        routerConfig: router,
      ),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('celebrationStartExplore')));
    await tester.pumpAndSettle();
    return (requestCount: requestCount);
  }

  testWidgets('AC3: 庆祝页「开始探索」→ 触发建档推送时机 → 进首页（时序：gate 在 go home 前）',
      (tester) async {
    final r = await pumpAndStart(tester);
    expect(r.requestCount, 1); // 建档时机触发了推送权限请求
    expect(find.text('HOME'), findsOneWidget); // 且最终落到首页（await 保证 gate 先于 go）
  });

  testWidgets('AC3: 已问过权限（asked=true）→ 点「开始探索」不再弹、仍进首页（仅一次）', (tester) async {
    SharedPreferences.setMockInitialValues({'petgo.push_permission_asked': true});
    final r = await pumpAndStart(tester);
    expect(r.requestCount, 0); // 门控跳过，不重复弹
    expect(find.text('HOME'), findsOneWidget); // 仍正常进首页
  });
}

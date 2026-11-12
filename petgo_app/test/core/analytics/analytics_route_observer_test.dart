import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/analytics/analytics_route_observer.dart';
import 'package:tailtopia/core/analytics/event_names.dart';

/// analytics-pipeline Story 2.4：AnalyticsRouteObserver 逐条复刻 PosthogObserver 的触发时机。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late List<(String, Map<String, Object>?)> events;

  List<Object?> screens() => events
      .where((e) => e.$1 == AnalyticsEvents.screenViewed)
      .map((e) => e.$2?['screen'])
      .toList();

  PageRoute<void> page(String? name) => MaterialPageRoute<void>(
        settings: RouteSettings(name: name),
        builder: (_) => const SizedBox(),
      );

  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
    Analytics.debugResetCurrentScreen();
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  test('didPush PageRoute → 1 条 screen_viewed，screen = 路由路径模板（原样）', () {
    final o = AnalyticsRouteObserver();
    o.didPush(page('/shop/products/:token'), null);
    expect(events, hasLength(1));
    expect(events.single.$1, 'screen_viewed');
    expect(events.single.$2, {'screen': '/shop/products/:token'});
    expect(Analytics.currentScreen, '/shop/products/:token');
  });

  test('didPop(详情, 列表) → 对返回到的列表页报 1 条', () {
    final o = AnalyticsRouteObserver();
    o.didPop(page('/shop/products/:token'), page('/shop/cart'));
    expect(screens(), ['/shop/cart']);
  });

  test('didReplace → 对新路由报 1 条', () {
    final o = AnalyticsRouteObserver();
    o.didReplace(newRoute: page('/b'), oldRoute: page('/a'));
    expect(screens(), ['/b']);
  });

  test('详情 → 返回 → 再进详情：每次都报，不做同名去重（与 PostHog 口径一致）', () {
    final o = AnalyticsRouteObserver();
    final list = page('/shop');
    o.didPush(page('/shop/products/:token'), list);
    o.didPop(page('/shop/products/:token'), list);
    o.didPush(page('/shop/products/:token'), list);
    expect(screens(), ['/shop/products/:token', '/shop', '/shop/products/:token']);
  });

  test('对话框 / 底部弹层（PopupRoute）不报', () {
    final o = AnalyticsRouteObserver();
    o.didPush(
      RawDialogRoute<void>(
        settings: const RouteSettings(name: '/dialog'),
        pageBuilder: (_, _, _) => const SizedBox(),
      ),
      null,
    );
    o.didPush(
      ModalBottomSheetRoute<void>(
        settings: const RouteSettings(name: '/sheet'),
        builder: (_) => const SizedBox(),
        isScrollControlled: false,
      ),
      null,
    );
    // pop 回到弹层也不报
    o.didPop(page('/x'), RawDialogRoute<void>(pageBuilder: (_, _, _) => const SizedBox()));
    expect(events, isEmpty);
  });

  test('settings.name 为 null / 空白不报；pop 无上一页不报', () {
    final o = AnalyticsRouteObserver();
    o.didPush(page(null), null);
    o.didPush(page('  '), null);
    o.didPop(page('/a'), null);
    expect(events, isEmpty);
  });

  test("'/' 改写为 \"root ('/')\"", () {
    final o = AnalyticsRouteObserver();
    o.didPush(page('/'), null);
    expect(screens(), ["root ('/')"]);
  });

  test('收到 paused 后不报，resumed 后恢复', () {
    final o = AnalyticsRouteObserver();
    o.didChangeAppLifecycleState(AppLifecycleState.paused);
    o.didPush(page('/a'), null);
    expect(events, isEmpty);
    o.didChangeAppLifecycleState(AppLifecycleState.inactive);
    o.didPush(page('/a'), null);
    expect(events, isEmpty);
    o.didChangeAppLifecycleState(AppLifecycleState.resumed);
    o.didPush(page('/a'), null);
    expect(screens(), ['/a']);
  });

  testWidgets('GoRouter 实挂：push 报路径模板、pop 报返回页；refreshListenable 重算 redirect 不重复上报',
      (tester) async {
    final refresh = ValueNotifier<int>(0);
    addTearDown(refresh.dispose);
    final router = GoRouter(
      observers: [AnalyticsRouteObserver()],
      refreshListenable: refresh,
      initialLocation: '/list',
      redirect: (_, _) => null,
      routes: [
        GoRoute(
          path: '/list',
          builder: (_, _) => const Text('list'),
          routes: [
            GoRoute(path: 'items/:token', builder: (_, _) => const Text('detail')),
          ],
        ),
      ],
    );
    addTearDown(router.dispose);
    await tester.pumpWidget(MaterialApp.router(routerConfig: router));
    await tester.pumpAndSettle();
    expect(screens(), ['/list']);

    router.push('/list/items/abc');
    await tester.pumpAndSettle();
    expect(screens(), ['/list', 'items/:token']);

    final before = events.length;
    refresh.value++;
    await tester.pumpAndSettle();
    refresh.value++;
    await tester.pumpAndSettle();
    expect(events, hasLength(before), reason: 'redirect 重算但未导航，不得重复上报');

    router.pop();
    await tester.pumpAndSettle();
    expect(screens().last, '/list');
  });
}

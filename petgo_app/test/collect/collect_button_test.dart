import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/collect/data/collect_providers.dart';
import 'package:tailtopia/features/collect/data/collect_repository.dart';
import 'package:tailtopia/features/collect/domain/collect_target.dart';
import 'package:tailtopia/features/collect/presentation/collect_button.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/login_hard_dialog.dart';

/// V1.4.0 ④ Story 2.3 · AC2–AC7：收藏按钮状态机、Toast、落点、埋点、信号、作者数字锁。
class _FakeCollectRepo extends Fake implements CollectRepository {
  bool fail = false;
  Completer<CollectResult>? gate;
  final List<String> calls = [];

  Future<CollectResult> _answer(String call, bool collected) async {
    calls.add(call);
    if (gate != null) return gate!.future;
    if (fail) throw Exception('network');
    return CollectResult(collected: collected);
  }

  @override
  Future<CollectResult> collectPost(int postId) => _answer('collectPost:$postId', true);

  @override
  Future<CollectResult> uncollectPost(int postId) => _answer('uncollectPost:$postId', false);

  @override
  Future<CollectResult> collectPlace(String placeToken) => _answer('collectPlace:$placeToken', true);

  @override
  Future<CollectResult> uncollectPlace(String placeToken) => _answer('uncollectPlace:$placeToken', false);
}

LoginResponse _user() => const LoginResponse(
    accessToken: 'a', refreshToken: 'r', role: 'USER', isNewUser: false, onboardingCompleted: true);

void main() {
  late _FakeCollectRepo repo;
  late List<MapEntry<String, Map<String, Object>?>> events;

  setUp(() {
    repo = _FakeCollectRepo();
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add(MapEntry(e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Future<(ProviderContainer, GoRouter)> pump(WidgetTester tester, Widget button, {bool loggedIn = true}) async {
    final container = ProviderContainer(overrides: [collectRepositoryProvider.overrideWithValue(repo)]);
    addTearDown(container.dispose);
    if (loggedIn) container.read(authControllerProvider.notifier).applyLogin(_user());
    final router = GoRouter(initialLocation: '/detail', routes: [
      GoRoute(path: '/detail', builder: (c, s) => Scaffold(body: Center(child: button))),
      GoRoute(path: '/me', builder: (c, s) => Scaffold(body: Text('me:${s.uri.queryParameters['saved']}'))),
    ]);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
      ),
    ));
    await tester.pumpAndSettle();
    return (container, router);
  }

  Icon iconOf(WidgetTester tester, String key) => tester.widget<Icon>(
      find.descendant(of: find.byKey(ValueKey(key)), matching: find.byType(Icon)).first);

  List<MapEntry<String, Map<String, Object>?>> named(String e) => events.where((x) => x.key == e).toList();

  Future<void> finishToast(WidgetTester tester) => tester.pump(const Duration(seconds: 3));

  const post = CollectButton.post(postId: 5, wireType: 'GROWTH_MOMENT', initialCollected: false);

  testWidgets('帖子：收藏 → 实心紫 + Toast「Disimpan · Lihat」→ 点 Lihat 落 /me?saved=post', (tester) async {
    final (container, _) = await pump(tester, post);
    await tester.tap(find.byKey(const ValueKey('detailCollectButton')));
    await tester.pump();
    expect(iconOf(tester, 'detailCollectButton').icon, Icons.bookmark_rounded);
    expect(iconOf(tester, 'detailCollectButton').color, AppColors.mint);
    await tester.pump(const Duration(milliseconds: 50));
    expect(find.text('Disimpan'), findsOneWidget);
    expect(find.text('Lihat'), findsOneWidget);
    expect(container.read(collectionsRevisionProvider(CollectTarget.post)), 1);
    expect(named('content_collected').single.value, {'post_id': 5, 'content_type': 'diary', 'source': 'detail'});
    await tester.tap(find.byKey(const ValueKey('appToastAction')));
    await tester.pumpAndSettle();
    expect(find.text('me:post'), findsOneWidget);
  });

  testWidgets('帖子：取消成功 → 描边、无 Toast、报 content_uncollected', (tester) async {
    final (container, _) = await pump(tester,
        const CollectButton.post(postId: 5, wireType: 'DAILY', initialCollected: true));
    await tester.tap(find.byKey(const ValueKey('detailCollectButton')));
    await tester.pumpAndSettle();
    expect(iconOf(tester, 'detailCollectButton').icon, Icons.bookmark_border_rounded);
    expect(find.text('Disimpan'), findsNothing);
    expect(named('content_uncollected').single.value, {'post_id': 5, 'content_type': 'moment', 'source': 'detail'});
    expect(container.read(collectionsRevisionProvider(CollectTarget.post)), 1);
  });

  testWidgets('收藏失败 → 回描边 + 「Gagal menyimpan」；不报埋点不 bump', (tester) async {
    repo.fail = true;
    final (container, _) = await pump(tester, post);
    await tester.tap(find.byKey(const ValueKey('detailCollectButton')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
    expect(iconOf(tester, 'detailCollectButton').icon, Icons.bookmark_border_rounded);
    expect(find.text('Gagal menyimpan, coba lagi'), findsOneWidget);
    expect(named('content_collected'), isEmpty);
    expect(container.read(collectionsRevisionProvider(CollectTarget.post)), 0);
    await finishToast(tester);
  });

  testWidgets('取消失败 → 回实心 + 「Gagal membatalkan」', (tester) async {
    repo.fail = true;
    await pump(tester, const CollectButton.post(postId: 5, wireType: 'DAILY', initialCollected: true));
    await tester.tap(find.byKey(const ValueKey('detailCollectButton')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
    expect(iconOf(tester, 'detailCollectButton').icon, Icons.bookmark_rounded);
    expect(find.text('Gagal membatalkan, coba lagi'), findsOneWidget);
    await finishToast(tester);
  });

  testWidgets('请求在途时连点只发一次', (tester) async {
    repo.gate = Completer<CollectResult>();
    await pump(tester, post);
    await tester.tap(find.byKey(const ValueKey('detailCollectButton')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('detailCollectButton')));
    await tester.pump();
    expect(repo.calls, ['collectPost:5']);
    repo.gate!.complete(const CollectResult(collected: true));
    await tester.pump();
    await finishToast(tester);
  });

  testWidgets('游客 → 强登录弹窗；不发请求、不翻转、无埋点', (tester) async {
    await pump(tester, post, loggedIn: false);
    await tester.tap(find.byKey(const ValueKey('detailCollectButton')));
    await tester.pumpAndSettle();
    expect(find.byType(LoginHardDialog), findsOneWidget);
    expect(repo.calls, isEmpty);
    expect(iconOf(tester, 'detailCollectButton').icon, Icons.bookmark_border_rounded);
    expect(events.where((e) => e.key.contains('collect')), isEmpty);
  });

  testWidgets('作者：ownerCount 2 → 「2」+ 锁；自己收藏后数字不变；0 也显示；null 无数字锁', (tester) async {
    await pump(tester, const CollectButton.post(postId: 5, wireType: 'DAILY', initialCollected: false, ownerCount: 2));
    expect(find.byKey(const ValueKey('detailCollectCount')), findsOneWidget);
    expect(find.text('2'), findsOneWidget);
    expect(find.byKey(const ValueKey('detailCollectLock')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('detailCollectButton')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
    expect(find.text('2'), findsOneWidget);
    await finishToast(tester);
  });

  testWidgets('ownerCount 0 → 「0」+ 锁；null → 都没有', (tester) async {
    await pump(tester, const CollectButton.post(postId: 5, wireType: 'DAILY', initialCollected: false, ownerCount: 0));
    expect(find.text('0'), findsOneWidget);
    expect(find.byKey(const ValueKey('detailCollectLock')), findsOneWidget);
  });

  testWidgets('非作者 → 无数字无锁', (tester) async {
    await pump(tester, post);
    expect(find.byKey(const ValueKey('detailCollectCount')), findsNothing);
    expect(find.byKey(const ValueKey('detailCollectLock')), findsNothing);
  });

  testWidgets('didUpdateWidget：同一帖外部值变化且不在途 → 跟随；在途 → 不覆盖乐观值', (tester) async {
    final container = ProviderContainer(overrides: [collectRepositoryProvider.overrideWithValue(repo)]);
    addTearDown(container.dispose);
    container.read(authControllerProvider.notifier).applyLogin(_user());
    final initial = ValueNotifier<bool>(false);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: ValueListenableBuilder<bool>(
            valueListenable: initial,
            builder: (c, v, _) => CollectButton.post(postId: 5, wireType: 'DAILY', initialCollected: v),
          ),
        ),
      ),
    ));
    initial.value = true;
    await tester.pump();
    expect(iconOf(tester, 'detailCollectButton').icon, Icons.bookmark_rounded, reason: '不在途 → 跟随');

    repo.gate = Completer<CollectResult>();
    await tester.tap(find.byKey(const ValueKey('detailCollectButton'))); // 乐观取消
    await tester.pump();
    initial.value = false;
    await tester.pump();
    initial.value = true; // 外部又变回 true，但请求在途 → 不覆盖乐观值（false）
    await tester.pump();
    expect(iconOf(tester, 'detailCollectButton').icon, Icons.bookmark_border_rounded);
    repo.gate!.complete(const CollectResult(collected: false));
    await tester.pump();
  });

  testWidgets('场所：收藏 → place_collected{place_id: token}、落 /me?saved=place、信号 place 自增', (tester) async {
    final (container, _) = await pump(tester, const CollectButton.place(placeToken: 'tokA', initialCollected: false));
    expect(find.byTooltip('Simpan'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('placeDetailCollect')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
    expect(repo.calls, ['collectPlace:tokA']);
    expect(named('place_collected').single.value, {'place_id': 'tokA'});
    expect(container.read(collectionsRevisionProvider(CollectTarget.place)), 1);
    expect(container.read(collectionsRevisionProvider(CollectTarget.post)), 0);
    await tester.tap(find.byKey(const ValueKey('appToastAction')));
    await tester.pumpAndSettle();
    expect(find.text('me:place'), findsOneWidget);
  });
}

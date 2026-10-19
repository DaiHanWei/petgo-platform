import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/collect/data/collect_repository.dart';
import 'package:tailtopia/features/me/data/me_counts_provider.dart';
import 'package:tailtopia/features/me/data/my_posts_repository.dart';
import 'package:tailtopia/features/me/presentation/me_page.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import '../collect/collect_fakes.dart';

/// V1.4.0 ④ Story 2.4 · AC5：`/me?saved=post|place` 落点（选中、清参数、重复「Lihat」仍生效）。
void main() {
  late List<MapEntry<String, Map<String, Object>?>> events;

  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add(MapEntry(e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  List<Map<String, Object>?> viewed() =>
      events.where((x) => x.key == 'collection_list_viewed').map((x) => x.value).toList();

  ProviderContainer container() {
    final c = ProviderContainer(retry: (_, _) => null, overrides: [
      myPostsProvider.overrideWith((ref) async => const <MyPost>[]),
      meCountsProvider.overrideWith((ref, userId) async =>
          const MeCounts(postCount: 0, likeCount: 0, followingCount: 0, followerCount: 0)),
      // 四线合并（V1.4.0 multi-pet Story 1.5）：「我的」页宠物区改读宠物列表，原 petProfileProvider / archiveStatsProvider 桩换成它。
      petListProvider.overrideWith((ref) async => const <PetListItem>[]),
      collectRepositoryProvider.overrideWithValue(FakeCollectRepo()),
    ]);
    c.read(authControllerProvider.notifier).applyProfile(
        const UserProfile(nickname: '小明', petStatus: 'NO_PET', hasPetProfile: false));
    return c;
  }

  Future<GoRouter> pump(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1200, 4000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final c = container();
    addTearDown(c.dispose);
    final router = GoRouter(initialLocation: '/home', routes: [
      GoRoute(path: '/home', builder: (c, s) => const Text('home')),
      // 同 app_router `_tabRootPage` 的写法。
      GoRoute(path: '/me', builder: (c, s) => MePage(saved: s.uri.queryParameters['saved'])),
    ]);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: c,
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
      ),
    ));
    await tester.pumpAndSettle();
    return router;
  }

  /// 分段胶囊选中 = mint 底。
  bool selected(WidgetTester tester, String key) {
    final box = tester.widget<Container>(
        find.descendant(of: find.byKey(ValueKey(key)), matching: find.byType(Container)).first);
    return (box.decoration as BoxDecoration?)?.color == AppColors.mint;
  }

  testWidgets('saved=place → Disimpan + Tempat、报一次 viewed、参数被清；手动切回后再 saved=post 仍生效', (tester) async {
    final router = await pump(tester);
    router.go('/me?saved=place');
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('savedSegPlace')), findsOneWidget);
    expect(selected(tester, 'savedSegPlace'), isTrue);
    expect(viewed(), [
      {'tab': 'place'},
    ]);
    expect(router.state.uri.toString(), '/me', reason: '参数被清');

    await tester.tap(find.byKey(const ValueKey('meTabMyPosts')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('savedSegPlace')), findsNothing);

    router.go('/me?saved=post');
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('savedSegPost')), findsOneWidget);
    expect(selected(tester, 'savedSegPost'), isTrue);
    expect(viewed().last, {'tab': 'post'});
  });

  testWidgets('saved=foo 不改选中态', (tester) async {
    final router = await pump(tester);
    router.go('/me?saved=foo');
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('meNoPosts')), findsOneWidget);
    expect(find.byKey(const ValueKey('savedSegPost')), findsNothing);
    expect(viewed(), isEmpty);
  });

  testWidgets('无 GoRouter（MaterialApp(home: MePage(saved: post))）不抛', (tester) async {
    tester.view.physicalSize = const Size(1200, 4000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final c = container();
    addTearDown(c.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: c,
      child: const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: MePage(saved: 'post'),
      ),
    ));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.byKey(const ValueKey('savedSegPost')), findsOneWidget);
  });

  test('源码守卫：app_router 的 me Tab 读 ?saved=（防有人改回 const MePage()）', () {
    final src = File('lib/core/router/app_router.dart').readAsStringSync();
    expect(src, contains("AppTab.me => MePage(saved: state.uri.queryParameters['saved'])"));
  });

  test('AC8a：他人主页不出现 Disimpan（源码层面不引用收藏列表）', () {
    final src = File('lib/features/user_profile/presentation/public_profile_page.dart').readAsStringSync();
    expect(src, isNot(contains('meTabSaved')));
    expect(src, isNot(contains('SavedCollectionsSection')));
    expect(src, isNot(contains('collectedPostsProvider')));
  });
}

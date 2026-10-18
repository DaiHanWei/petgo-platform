import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/collect/data/collect_repository.dart';
import 'package:tailtopia/features/me/data/me_counts_provider.dart';
import 'package:tailtopia/features/me/data/my_posts_repository.dart';
import 'package:tailtopia/features/me/presentation/me_page.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/data/timeline_repository.dart';
import 'package:tailtopia/features/profile/domain/archive_stats.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import '../collect/collect_fakes.dart';

/// V1.4.0 ④ Story 2.4 · AC1–AC4 / AC6.2 / AC8：「我的」页 Postinganku / Disimpan。
void main() {
  late FakeCollectRepo repo;
  late List<MapEntry<String, Map<String, Object>?>> events;

  setUp(() {
    repo = FakeCollectRepo();
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add(MapEntry(e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  List<Map<String, Object>?> named(String e) => events.where((x) => x.key == e).map((x) => x.value).toList();

  Future<GoRouter> pump(WidgetTester tester, {Object? myPostsError}) async {
    tester.view.physicalSize = const Size(1200, 4000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    // Riverpod 3 默认对抛错的 provider 自动重试（会留下挂起的 Timer）—— 测试里关掉。
    final container = ProviderContainer(retry: (_, _) => null, overrides: [
      myPostsProvider.overrideWith((ref) async {
        if (myPostsError != null) throw myPostsError;
        return const <MyPost>[];
      }),
      meCountsProvider.overrideWith((ref, userId) async =>
          const MeCounts(postCount: 0, likeCount: 0, followingCount: 0, followerCount: 0)),
      petProfileProvider.overrideWith((ref) async => null),
      archiveStatsProvider.overrideWith((ref) async => const ArchiveStats(
          happyMomentCount: 0, consultCount: 0, milestoneCompleted: 0, milestoneTotal: 30)),
      collectRepositoryProvider.overrideWithValue(repo),
    ]);
    addTearDown(container.dispose);
    container.read(authControllerProvider.notifier).applyProfile(
        const UserProfile(nickname: '小明', petStatus: 'NO_PET', hasPetProfile: false));
    final router = GoRouter(initialLocation: '/me', routes: [
      GoRoute(path: '/me', builder: (c, s) => MePage(saved: s.uri.queryParameters['saved'])),
      GoRoute(path: '/content/:id', builder: (c, s) => Text('content:${s.pathParameters['id']}')),
      GoRoute(path: '/places/:token', builder: (c, s) => Text('place:${s.pathParameters['token']}')),
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
    return router;
  }

  Future<void> openSaved(WidgetTester tester) async {
    await tester.tap(find.byKey(const ValueKey('meTabSaved')));
    await tester.pumpAndSettle();
  }

  testWidgets('默认停在 Postinganku：原空态照旧，两个收藏列表零请求', (tester) async {
    await pump(tester);
    expect(find.byKey(const ValueKey('meTabMyPosts')), findsOneWidget);
    expect(find.byKey(const ValueKey('meTabSaved')), findsOneWidget);
    expect(find.byKey(const ValueKey('meNoPosts')), findsOneWidget);
    expect(find.byKey(const ValueKey('savedSegPost')), findsNothing);
    expect(repo.postFetches + repo.placeFetches, 0);
    expect(named('collection_list_viewed'), isEmpty);
  });

  testWidgets('点 Disimpan → 分段 + 隐私横条 + 帖子列表按接口顺序；切 Tempat；切回 Postinganku', (tester) async {
    repo.postPages.add((items: [visiblePost(3), visiblePost(1), visiblePost(2)], nextCursor: null));
    repo.placePages.add((items: [visiblePlace('a')], nextCursor: null));
    await pump(tester);
    await openSaved(tester);
    expect(find.byKey(const ValueKey('savedSegPost')), findsOneWidget);
    expect(find.byKey(const ValueKey('savedSegPlace')), findsOneWidget);
    expect(find.byKey(const ValueKey('savedPrivacyHint')), findsOneWidget);
    final ys = [for (final id in [3, 1, 2]) tester.getTopLeft(find.byKey(ValueKey('collectedPostRow_$id'))).dy];
    expect(ys, orderedEquals([...ys]..sort()), reason: '客户端不重排');
    expect(repo.placeFetches, 0, reason: '另一个子分类不预取');

    await tester.tap(find.byKey(const ValueKey('savedSegPlace')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('collectedPlaceRow_a')), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('meTabMyPosts')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('meNoPosts')), findsOneWidget);
  });

  testWidgets('空态：两子分类各自文案、无按钮；横条仍在', (tester) async {
    await pump(tester);
    await openSaved(tester);
    expect(find.byKey(const ValueKey('savedPostsEmpty')), findsOneWidget);
    expect(find.text('Belum ada postingan disimpan'), findsOneWidget);
    expect(find.byKey(const ValueKey('emptyStateAction')), findsNothing);
    expect(find.byKey(const ValueKey('savedPrivacyHint')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('savedSegPlace')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('savedPlacesEmpty')), findsOneWidget);
    expect(find.text('Belum ada tempat disimpan'), findsOneWidget);
  });

  testWidgets('失败态：只在 Tab 内容区；横条不显示；名片与 Tab 行照常；重试成功出列表', (tester) async {
    repo.fetchError = Exception('net');
    await pump(tester);
    await openSaved(tester);
    expect(find.byKey(const ValueKey('savedError')), findsOneWidget);
    expect(find.text('Gagal memuat'), findsOneWidget);
    expect(find.byKey(const ValueKey('savedPrivacyHint')), findsNothing);
    expect(find.byKey(const ValueKey('meEditNickname')), findsOneWidget);
    expect(find.byKey(const ValueKey('meTabSaved')), findsOneWidget);
    repo.fetchError = null;
    repo.postPages.add((items: [visiblePost(9)], nextCursor: null));
    await tester.tap(find.byKey(const ValueKey('emptyStateAction')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('collectedPostRow_9')), findsOneWidget);
  });

  testWidgets('Postinganku 加载失败仍静默（回归）', (tester) async {
    await pump(tester, myPostsError: Exception('net'));
    expect(find.text('Gagal memuat'), findsNothing);
    expect(find.byKey(const ValueKey('savedError')), findsNothing);
  });

  testWidgets('加载更多：点击追加；失败显示重试且已加载行仍在', (tester) async {
    repo.postPages.addAll([
      (items: [visiblePost(1)], nextCursor: 'c1'),
      (items: [visiblePost(2)], nextCursor: 'c2'),
    ]);
    await pump(tester);
    await openSaved(tester);
    await tester.tap(find.byKey(const ValueKey('savedLoadMore')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('collectedPostRow_2')), findsOneWidget);
    repo.loadMoreError = Exception('net');
    await tester.tap(find.byKey(const ValueKey('savedLoadMore')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('savedLoadMoreRetry')), findsOneWidget);
    expect(find.byKey(const ValueKey('collectedPostRow_1')), findsOneWidget);
    expect(named('collection_list_viewed'), hasLength(1), reason: '加载更多不报');
  });

  testWidgets('移除不可见帖子：确认 → 以 postId 调一次、行消失、Toast；取消不调', (tester) async {
    repo.postPages.add((items: [visiblePost(1), hiddenPost(2)], nextCursor: null));
    await pump(tester);
    await openSaved(tester);
    await tester.tap(find.byKey(const ValueKey('collectedPostRow_2')));
    await tester.pumpAndSettle();
    expect(find.text('Postingan ini mungkin sudah dihapus atau disembunyikan. Hapus dari Disimpan?'), findsOneWidget);
    expect(find.text('Hapus dari Disimpan'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('savedRemoveCancel')));
    await tester.pumpAndSettle();
    expect(repo.uncollects, isEmpty);

    await tester.tap(find.byKey(const ValueKey('collectedPostRow_2')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('savedRemoveConfirm')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(repo.uncollects, ['post:2']);
    expect(find.byKey(const ValueKey('collectedPostRow_2')), findsNothing);
    expect(find.byKey(const ValueKey('collectedPostRow_1')), findsOneWidget);
    expect(find.text('Dihapus dari Disimpan'), findsOneWidget);
    expect(repo.postFetches, 1, reason: '本地删行，不整页重拉');
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('移除失败：抽屉仍在 + 「Gagal membatalkan」、行仍在；404 按成功处理', (tester) async {
    repo.postPages.add((items: [hiddenPost(2)], nextCursor: null));
    await pump(tester);
    await openSaved(tester);
    repo.uncollectError = Exception('net');
    await tester.tap(find.byKey(const ValueKey('collectedPostRow_2')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('savedRemoveConfirm')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.byKey(const ValueKey('savedRemoveConfirm')), findsOneWidget);
    expect(find.text('Gagal membatalkan, coba lagi'), findsOneWidget);
    repo.uncollectError = http404();
    await tester.tap(find.byKey(const ValueKey('savedRemoveConfirm')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.byKey(const ValueKey('collectedPostRow_2')), findsNothing);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('移除不可见场所：按 placeToken', (tester) async {
    repo.placePages.add((items: [hiddenPlace('z')], nextCursor: null));
    await pump(tester);
    await openSaved(tester);
    await tester.tap(find.byKey(const ValueKey('savedSegPlace')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('collectedPlaceRow_z')));
    await tester.pumpAndSettle();
    expect(find.text('Tempat ini mungkin sudah dihapus. Hapus dari Disimpan?'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('savedRemoveConfirm')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(repo.uncollects, ['place:z']);
    expect(find.byKey(const ValueKey('collectedPlaceRow_z')), findsNothing);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('点正常帖子 / 场所 → /content/{id} / /places/{token}；埋点 item_opened', (tester) async {
    repo.postPages.add((items: [visiblePost(5)], nextCursor: null));
    await pump(tester);
    await openSaved(tester);
    await tester.tap(find.byKey(const ValueKey('collectedPostRow_5')));
    await tester.pumpAndSettle();
    expect(find.text('content:5'), findsOneWidget);
    expect(named('collection_item_opened').single, {'tab': 'post', 'is_unavailable': false});
  });

  testWidgets('点正常场所 → /places/{token}', (tester) async {
    repo.placePages.add((items: [visiblePlace('a')], nextCursor: null));
    await pump(tester);
    await openSaved(tester);
    await tester.tap(find.byKey(const ValueKey('savedSegPlace')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('collectedPlaceRow_a')));
    await tester.pumpAndSettle();
    expect(find.text('place:a'), findsOneWidget);
    expect(named('collection_item_opened').single, {'tab': 'place', 'is_unavailable': false});
  });

  testWidgets('埋点：Disimpan 报 {tab: post}；切 Tempat 报 {tab: place}；重复点不报；不可见行 is_unavailable=true',
      (tester) async {
    repo.postPages.add((items: [hiddenPost(2)], nextCursor: null));
    await pump(tester);
    await openSaved(tester);
    await tester.tap(find.byKey(const ValueKey('meTabSaved')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('savedSegPost')));
    await tester.pumpAndSettle();
    expect(named('collection_list_viewed'), [
      {'tab': 'post'},
    ]);
    await tester.tap(find.byKey(const ValueKey('collectedPostRow_2')));
    await tester.pumpAndSettle();
    expect(named('collection_item_opened').single, {'tab': 'post', 'is_unavailable': true});
    await tester.tap(find.byKey(const ValueKey('savedRemoveCancel')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('savedSegPlace')));
    await tester.pumpAndSettle();
    expect(named('collection_list_viewed'), [
      {'tab': 'post'},
      {'tab': 'place'},
    ]);
  });
}

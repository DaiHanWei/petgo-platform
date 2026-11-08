import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/content/data/feed_repository.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/features/topic/data/topic_repository.dart';
import 'package:tailtopia/features/topic/domain/topic.dart';
import 'package:tailtopia/features/topic/presentation/topic_page.dart';
import 'package:tailtopia/features/topic/presentation/topic_providers.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import '../support/fake_feed_repository.dart';
import 'fake_topic_repository.dart';

/// L0：话题帖子分页（V1.4.2 social-topic · Story 4.3 · AC3.2）。
class _Guest extends AuthController {
  @override
  AuthState build() => const AuthState.guest();
}

ProviderContainer _container(FakeTopicRepository repo) {
  final c = ProviderContainer(overrides: [
    authControllerProvider.overrideWith(_Guest.new),
    topicRepositoryProvider.overrideWithValue(repo),
    feedRepositoryProvider.overrideWithValue(FakeFeedRepository()),
  ]);
  addTearDown(c.dispose);
  return c;
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  group('controller', () {
    test('Terbaru：游标续拉（nextCursor），hasMore=false 停', () async {
      final repo = FakeTopicRepository(pages: {
        TopicSort.latest: [
          FeedPage(items: [feedItem(1), feedItem(2)], nextCursor: cursorFor(1), hasMore: true),
          FeedPage(items: [feedItem(3)]),
        ],
      });
      final c = _container(repo);
      const key = ('kucinglucu', TopicSort.latest);
      c.listen(topicPostsProvider(key), (_, _) {});
      final first = await c.read(topicPostsProvider(key).future);
      expect(first.items.map((i) => i.id), [1, 2]);
      expect(first.hasMore, isTrue);
      await c.read(topicPostsProvider(key).notifier).loadMore();
      final s = c.read(topicPostsProvider(key)).value!;
      expect(s.items.map((i) => i.id), [1, 2, 3]);
      expect(s.hasMore, isFalse);
      expect(repo.postsCalls, [(TopicSort.latest, null, null), (TopicSort.latest, 'c1', null)]);
      await c.read(topicPostsProvider(key).notifier).loadMore();
      expect(repo.postsCalls, hasLength(2), reason: '没有更多就不再请求');
    });

    test('Populer：页码续拉（page 1 → 2），不用游标', () async {
      final repo = FakeTopicRepository(pages: {
        TopicSort.popular: [
          FeedPage(items: [feedItem(1)], hasMore: true),
          FeedPage(items: [feedItem(2)], hasMore: true),
          FeedPage(items: [feedItem(3)]),
        ],
      });
      final c = _container(repo);
      const key = ('kucinglucu', TopicSort.popular);
      c.listen(topicPostsProvider(key), (_, _) {});
      await c.read(topicPostsProvider(key).future);
      await c.read(topicPostsProvider(key).notifier).loadMore();
      await c.read(topicPostsProvider(key).notifier).loadMore();
      final s = c.read(topicPostsProvider(key)).value!;
      expect(s.items.map((i) => i.id), [1, 2, 3]);
      expect(s.hasMore, isFalse);
      expect(repo.postsCalls, [
        (TopicSort.popular, null, 1),
        (TopicSort.popular, null, 2),
        (TopicSort.popular, null, 3),
      ]);
    });

    test('Populer 页码上限 50（后端超了 422）：第 50 页即便 hasMore=true 也停，不发第 51 页', () async {
      expect(kTopicPopularMaxPage, 50);
      final repo = FakeTopicRepository(pages: {
        TopicSort.popular: [
          for (var p = 1; p <= 51; p++) FeedPage(items: [feedItem(p)], hasMore: true),
        ],
      });
      final c = _container(repo);
      const key = ('kucinglucu', TopicSort.popular);
      c.listen(topicPostsProvider(key), (_, _) {});
      await c.read(topicPostsProvider(key).future);
      for (var n = 0; n < 49; n++) {
        await c.read(topicPostsProvider(key).notifier).loadMore();
      }
      var s = c.read(topicPostsProvider(key)).value!;
      expect(repo.postsCalls.last, (TopicSort.popular, null, 50));
      expect(s.items, hasLength(50));
      expect(s.hasMore, isFalse, reason: '服务端第 50 页仍说 hasMore=true，App 也得停');
      expect(s.nextPage, isNull);
      await c.read(topicPostsProvider(key).notifier).loadMore();
      s = c.read(topicPostsProvider(key)).value!;
      expect(repo.postsCalls, hasLength(50), reason: '不发第 51 页（后端会 422）');
      expect(repo.postsCalls.any((e) => e.$3 == 51), isFalse);
    });

    test('翻页失败：保留已加载、置 loadMoreFailed；重试沿用同一游标', () async {
      final repo = FakeTopicRepository(pages: {
        TopicSort.latest: [
          FeedPage(items: [feedItem(1)], nextCursor: cursorFor(1), hasMore: true),
          FeedPage(items: [feedItem(2)]),
        ],
      })
        ..failOnCall[TopicSort.latest] = 1;
      final c = _container(repo);
      const key = ('kucinglucu', TopicSort.latest);
      c.listen(topicPostsProvider(key), (_, _) {});
      await c.read(topicPostsProvider(key).future);
      await c.read(topicPostsProvider(key).notifier).loadMore();
      var s = c.read(topicPostsProvider(key)).value!;
      expect(s.items.map((i) => i.id), [1]);
      expect(s.loadMoreFailed, isTrue);
      await c.read(topicPostsProvider(key).notifier).loadMore();
      s = c.read(topicPostsProvider(key)).value!;
      expect(s.items.map((i) => i.id), [1, 2]);
      expect(s.loadMoreFailed, isFalse);
      expect(repo.postsCalls.last, (TopicSort.latest, 'c1', null));
    });

    test('举报 / 拉黑后本地移除：removeItem 按帖、removeByAuthor 按作者', () async {
      final repo = FakeTopicRepository(pages: {
        TopicSort.latest: [
          FeedPage(items: [feedItem(1, authorId: 7), feedItem(2, authorId: 8), feedItem(3, authorId: 7)]),
        ],
      });
      final c = _container(repo);
      const key = ('kucinglucu', TopicSort.latest);
      c.listen(topicPostsProvider(key), (_, _) {});
      await c.read(topicPostsProvider(key).future);
      c.read(topicPostsProvider(key).notifier).removeItem(2);
      expect(c.read(topicPostsProvider(key)).value!.items.map((i) => i.id), [1, 3]);
      c.read(topicPostsProvider(key).notifier).removeByAuthor(7);
      expect(c.read(topicPostsProvider(key)).value!.items, isEmpty);
    });
  });

  group('页面', () {
    Future<ProviderContainer> pumpPage(WidgetTester tester, FakeTopicRepository repo) async {
      final c = _container(repo);
      await tester.pumpWidget(UncontrolledProviderScope(
        container: c,
        child: MaterialApp.router(
          locale: const Locale('id'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          routerConfig: GoRouter(
            initialLocation: '/topics/kucinglucu',
            routes: [
              GoRoute(
                path: TopicPage.routePattern,
                builder: (c, s) => TopicPage(name: s.pathParameters['name']!),
              ),
            ],
          ),
        ),
      ));
      await tester.pumpAndSettle();
      return c;
    }

    testWidgets('两页签各自缓存：来回切换不重拉', (tester) async {
      final repo = FakeTopicRepository();
      await pumpPage(tester, repo);
      for (final k in ['topicTabPopular', 'topicTabLatest', 'topicTabPopular', 'topicTabLatest']) {
        await tester.tap(find.byKey(ValueKey(k)));
        await tester.pumpAndSettle();
      }
      expect(repo.postsCalls.where((c) => c.$1 == TopicSort.latest), hasLength(1));
      expect(repo.postsCalls.where((c) => c.$1 == TopicSort.popular), hasLength(1));
      expect(find.text('post 1'), findsOneWidget);
    });

    testWidgets('翻页失败 → 底部 feedLoadMoreRetry；点击重试续拉', (tester) async {
      final repo = FakeTopicRepository(pages: {
        TopicSort.latest: [
          FeedPage(items: [feedItem(1)], nextCursor: cursorFor(1), hasMore: true),
          FeedPage(items: [feedItem(2)]),
        ],
      })
        ..failOnCall[TopicSort.latest] = 1;
      final c = await pumpPage(tester, repo);
      await c.read(topicPostsProvider(('kucinglucu', TopicSort.latest)).notifier).loadMore();
      await tester.pumpAndSettle();
      final retry = find.byKey(const ValueKey('feedLoadMoreRetry'));
      expect(retry, findsOneWidget);
      await tester.tap(retry);
      await tester.pumpAndSettle();
      expect(find.text('post 2'), findsOneWidget);
      expect(retry, findsNothing);
    });

    testWidgets('吸顶：上滑后说明段滚走，页签仍贴在顶栏下方', (tester) async {
      final repo = FakeTopicRepository(
        detail: const TopicDetail(
            name: 'kucinglucu', displayName: 'KucingLucu', official: true, description: 'Deskripsi topik'),
        pages: {
          TopicSort.latest: [FeedPage(items: [for (var i = 1; i <= 12; i++) feedItem(i)])],
        },
      );
      await pumpPage(tester, repo);
      final appBarBottom = tester.getBottomLeft(find.byType(AppBar)).dy;
      expect(find.byKey(const ValueKey('topicDescription')), findsOneWidget);
      await tester.drag(find.text('post 3'), const Offset(0, -600));
      await tester.pumpAndSettle();
      final tabTop = tester.getTopLeft(find.byType(TabBar)).dy;
      expect(tabTop, closeTo(appBarBottom, 1));
      expect(find.text('post 1').hitTestable(), findsNothing);
      expect(find.byKey(const ValueKey('topicDescription')).hitTestable(), findsNothing,
          reason: '说明段随列表滚走');
    });

    testWidgets('NestedScrollView 下滚到底仍会自动续拉（预加载挂在 PrimaryScrollController 上）', (tester) async {
      final repo = FakeTopicRepository(pages: {
        TopicSort.latest: [
          FeedPage(items: [for (var i = 1; i <= 12; i++) feedItem(i)], nextCursor: cursorFor(1), hasMore: true),
          FeedPage(items: [feedItem(13)]),
        ],
      });
      await pumpPage(tester, repo);
      expect(repo.postsCalls, hasLength(1));
      await tester.drag(find.text('post 3'), const Offset(0, -3000));
      await tester.pumpAndSettle();
      expect(repo.postsCalls, hasLength(2));
      expect(repo.postsCalls.last, (TopicSort.latest, 'c1', null));
    });
  });
}

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/feed_repository.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/features/content/presentation/feed_tab_row.dart';
import 'package:tailtopia/features/content/presentation/home_page.dart';
import 'package:tailtopia/features/content/presentation/social_tab.dart';
import 'package:tailtopia/features/social/data/follow_suggestion_repository.dart';
import 'package:tailtopia/features/social/data/following_strip_repository.dart';
import 'package:tailtopia/features/social/domain/follow_list_item.dart';
import 'package:tailtopia/features/topic/data/topic_repository.dart';
import 'package:tailtopia/features/topic/presentation/widgets/home_topic_row.dart';
import 'package:tailtopia/features/topic/presentation/widgets/topic_official_badge.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import '../support/fake_feed_repository.dart';
import 'fake_topic_repository.dart';

/// L0：Discover 首页话题行（V1.4.2 social-topic · Story 4.3 · AC4 · UI C1）。
class _User extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: 1, nickname: 'Me', onboardingCompleted: true),
      );
}

class _StripRepo implements FollowingStripRepository {
  @override
  Future<List<FollowingStripUser>> fetch() async => const [];
}

class _SuggestRepo implements FollowSuggestionRepository {
  @override
  Future<List<FollowListItem>> fetch() async => const [];
}

class _FollowingFeedRepo extends FakeFeedRepository {
  _FollowingFeedRepo(super.items);

  @override
  Future<FeedPage> getFollowingFeed({String? cursor}) async => const FeedPage(items: []);
}

void main() {
  final events = <(String, Map<String, Object>?)>[];

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    events.clear();
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Future<ProviderContainer> pumpHome(
    WidgetTester tester,
    FakeTopicRepository repo, {
    List<FeedItem> feed = const [],
  }) async {
    final c = ProviderContainer(overrides: [
      authControllerProvider.overrideWith(_User.new),
      feedRepositoryProvider.overrideWithValue(_FollowingFeedRepo(feed)),
      topicRepositoryProvider.overrideWithValue(repo),
      followingStripRepositoryProvider.overrideWithValue(_StripRepo()),
      followSuggestionRepositoryProvider.overrideWithValue(_SuggestRepo()),
    ]);
    addTearDown(c.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: c,
      child: MaterialApp.router(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        routerConfig: GoRouter(
          initialLocation: '/home',
          routes: [
            GoRoute(path: '/home', builder: (_, _) => const HomePage()),
            GoRoute(
              path: '/topics/:name',
              builder: (_, s) => Scaffold(body: Text('topic ${s.uri}')),
            ),
          ],
        ),
      ),
    ));
    await tester.pumpAndSettle();
    return c;
  }

  testWidgets('5 项渲染、官方项带 Resmi；在分类 chips 之下', (tester) async {
    tester.view.physicalSize = const Size(1600, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await pumpHome(tester, FakeTopicRepository(homeRow: topics(5, official: {1, 3})));

    final row = find.byKey(const ValueKey('homeTopicRow'));
    expect(row, findsOneWidget);
    for (var i = 1; i <= 5; i++) {
      expect(find.text('#Topik$i'), findsOneWidget);
    }
    expect(find.descendant(of: row, matching: find.byType(TopicOfficialBadge)), findsNWidgets(2));
    expect(find.descendant(of: find.byKey(const ValueKey('homeTopic_topik1')),
        matching: find.byType(TopicOfficialBadge)), findsOneWidget);
    expect(find.descendant(of: find.byKey(const ValueKey('homeTopic_topik2')),
        matching: find.byType(TopicOfficialBadge)), findsNothing);
    // 位置：FeedTabRow 之后（分类标签与顶置卡之间）。
    expect(tester.getTopLeft(row).dy,
        greaterThanOrEqualTo(tester.getBottomLeft(find.byType(FeedTabRow)).dy - 0.5));
    // 横滑、可点高度 44、文字 13 w600 mint600。
    expect(tester.widget<ListView>(find.descendant(of: row, matching: find.byType(ListView))).scrollDirection,
        Axis.horizontal);
    expect(tester.getSize(row).height, HomeTopicRow.itemHeight);
    final style = tester.widget<Text>(find.text('#Topik2')).style!;
    expect(style.fontSize, 13);
    expect(style.fontWeight, FontWeight.w600);
    expect(style.color, AppColors.mint600);
  });

  testWidgets('空表 → 整行不渲染、不占高度', (tester) async {
    await pumpHome(tester, FakeTopicRepository(homeRow: const []));
    expect(find.byKey(const ValueKey('homeTopicRow')), findsNothing);
    expect(tester.getSize(find.byType(HomeTopicRow)).height, 0);
  });

  testWidgets('取数失败 → 整行不渲染、不抛', (tester) async {
    await pumpHome(tester, FakeTopicRepository()..homeRowFails = true);
    expect(find.byKey(const ValueKey('homeTopicRow')), findsNothing);
    expect(tester.getSize(find.byType(HomeTopicRow)).height, 0);
    expect(tester.takeException(), isNull);
  });

  testWidgets('点第 2 项 → /topics/<标准名>?from=home_row + home_topic_row_tapped', (tester) async {
    await pumpHome(tester, FakeTopicRepository(homeRow: topics(3, featured: {1, 2})));
    await tester.tap(find.byKey(const ValueKey('homeTopic_topik2')));
    await tester.pumpAndSettle();
    expect(find.text('topic /topics/topik2?from=home_row'), findsOneWidget);
    final tapped = events.where((e) => e.$1 == 'home_topic_row_tapped').toList();
    expect(tapped, hasLength(1));
    expect(tapped.single.$2, {'topic_id': 'topik2', 'position': 2, 'is_pinned_by_ops': true});
  });

  testWidgets('Following 页签无话题行', (tester) async {
    final c = await pumpHome(tester, FakeTopicRepository(homeRow: topics(3)));
    expect(find.byKey(const ValueKey('homeTopicRow')), findsOneWidget);
    c.read(socialTabProvider.notifier).select(SocialTab.following);
    await tester.pumpAndSettle();
    expect(find.byType(HomeTopicRow), findsNothing);
  });

  testWidgets('🔴 首页下拉刷新一并重取话题行', (tester) async {
    final repo = FakeTopicRepository(homeRow: topics(2));
    await pumpHome(tester, repo, feed: [feedItem(1, body: 'hello')]);
    expect(repo.homeRowCalls, 1);
    await tester.fling(find.text('hello'), const Offset(0, 400), 1000);
    await tester.pumpAndSettle();
    expect(repo.homeRowCalls, 2);
  });
}

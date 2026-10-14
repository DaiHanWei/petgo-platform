import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/content/domain/home_refresh_provider.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/feed_repository.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/features/content/domain/pinned_slot.dart';
import 'package:tailtopia/features/content/presentation/feed_controller.dart';
import 'package:tailtopia/features/content/presentation/following_feed_controller.dart';
import 'package:tailtopia/features/content/presentation/following_tab_view.dart';
import 'package:tailtopia/features/content/presentation/author_moderation_callbacks.dart';
import 'package:tailtopia/features/content/presentation/social_tab.dart';
import 'package:tailtopia/features/content/presentation/widgets/following_empty_view.dart';
import 'package:tailtopia/features/content/presentation/widgets/following_strip.dart';
import 'package:tailtopia/features/social/data/follow_suggestion_repository.dart';
import 'package:tailtopia/features/social/data/following_strip_repository.dart';
import 'package:tailtopia/features/social/domain/follow_list_item.dart';
import 'package:tailtopia/features/social/domain/follow_state.dart';
import 'package:tailtopia/features/social/presentation/follow_list_page.dart';
import 'package:tailtopia/features/user_profile/presentation/public_profile_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.4 following 流 · Task 8：Following 页（头像排 + 帖子流 + 空状态）。
const int _kMe = 1;

class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: _kMe, nickname: 'Me', onboardingCompleted: true),
      );
}

class _FeedRepo implements FeedRepository {
  _FeedRepo(this.following);
  final List<FeedItem> following;
  int followingCalls = 0;

  @override
  Future<FeedPage> getFeed({FeedCategory category = FeedCategory.all, String? cursor, int limit = 20}) async =>
      const FeedPage(items: []);

  @override
  Future<PinnedSlot?> getPinnedSlot() async => null;

  @override
  Future<FeedPage> getFollowingFeed({String? cursor}) async {
    followingCalls++;
    return FeedPage(items: following);
  }
}

class _StripRepo implements FollowingStripRepository {
  _StripRepo(this.users);
  final List<FollowingStripUser> users;

  @override
  Future<List<FollowingStripUser>> fetch() async => users;
}

class _SuggestRepo implements FollowSuggestionRepository {
  _SuggestRepo(this.items);
  final List<FollowListItem> items;

  @override
  Future<List<FollowListItem>> fetch() async => items;
}

FeedItem _post(int id, {int authorId = 7, FollowState follow = const FollowState(iFollow: true, followsMe: false)}) =>
    FeedItem(
      id: id,
      authorId: authorId,
      authorDeleted: false,
      authorNickname: 'Author$authorId',
      authorToken: 'tok$authorId',
      authorFollowState: follow,
      type: 'DAILY',
      body: 'post $id',
      createdAt: DateTime.utc(2026, 10, 1),
    );

FollowingStripUser _user(int id) =>
    FollowingStripUser(userId: id, userToken: 'tok$id', nickname: 'friend$id');

Future<ProviderContainer> _pump(
  WidgetTester tester, {
  required List<FeedItem> posts,
  required List<FollowingStripUser> strip,
  List<FollowListItem> suggestions = const [],
}) async {
  final router = GoRouter(
    initialLocation: '/home',
    routes: [
      GoRoute(path: '/home', builder: (_, _) => const Scaffold(body: FollowingTabView())),
      GoRoute(
        path: FollowListPage.routePattern,
        builder: (_, s) => Scaffold(
            body: Text('follows ${s.pathParameters['userId']} ${s.uri.queryParameters['tab']}')),
      ),
      GoRoute(
        path: PublicProfilePage.routePattern,
        builder: (_, s) => Scaffold(body: Text('profile ${s.pathParameters['userId']}')),
      ),
    ],
  );
  addTearDown(router.dispose);
  final container = ProviderContainer(overrides: [
    authControllerProvider.overrideWith(_Auth.new),
    feedRepositoryProvider.overrideWithValue(_FeedRepo(posts)),
    followingStripRepositoryProvider.overrideWithValue(_StripRepo(strip)),
    followSuggestionRepositoryProvider.overrideWithValue(_SuggestRepo(suggestions)),
  ]);
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(
      routerConfig: router,
      locale: const Locale('en'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
    ),
  ));
  await tester.pumpAndSettle();
  return container;
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  testWidgets('stripShowsUpTo20PlusMore：20 人 → 20 头像 + 末尾 More；点 More → 我的关注列表', (tester) async {
    final events = <String, Map<String, Object>?>{};
    Analytics.debugCaptureSink = (e, p) => events[e] = p;
    addTearDown(() => Analytics.debugCaptureSink = null);

    await _pump(tester, posts: [_post(1)], strip: [for (var i = 100; i < 120; i++) _user(i)]);

    expect(find.byType(FollowingStrip), findsOneWidget);
    expect(find.byType(FollowingEmptyView), findsNothing);
    // 横向列表懒加载：逐个滚到可见再断言，20 个头像全在。
    final list = find.byKey(const ValueKey('followingStripList'));
    for (var i = 100; i < 120; i++) {
      await tester.scrollUntilVisible(find.byKey(ValueKey('followingStripAvatar_$i')), 80,
          scrollable: find.descendant(of: list, matching: find.byType(Scrollable)));
    }
    final more = find.byKey(const ValueKey('followingStripMore'));
    await tester.scrollUntilVisible(more, 80,
        scrollable: find.descendant(of: list, matching: find.byType(Scrollable)));
    expect(more, findsOneWidget);
    expect(find.text('More'), findsOneWidget);
    // More 在最后一个头像之后。
    expect(tester.getTopLeft(more).dx,
        greaterThan(tester.getTopLeft(find.byKey(const ValueKey('followingStripAvatar_119'))).dx));

    await tester.tap(more);
    await tester.pumpAndSettle();
    expect(find.text('follows $_kMe following'), findsOneWidget);
    expect(events['following_strip_tapped'], {'target': 'more'});
  });

  testWidgets('点头像 → 该用户主页', (tester) async {
    await _pump(tester, posts: [_post(1)], strip: [_user(100), _user(101)]);
    await tester.tap(find.byKey(const ValueKey('followingStripAvatar_101')));
    await tester.pumpAndSettle();
    expect(find.text('profile 101'), findsOneWidget);
  });

  testWidgets('strip 为空但有帖 → 不显示头像排（含 More）', (tester) async {
    await _pump(tester, posts: [_post(1)], strip: const []);
    expect(find.byKey(const ValueKey('followingStripMore')), findsNothing);
    expect(find.text('post 1'), findsOneWidget);
  });

  testWidgets('noVisiblePostsShowsEmptyWithoutStrip：关注流首页空、strip 非空 → 只显示空状态', (tester) async {
    await _pump(
      tester,
      posts: const [],
      strip: [_user(100)],
      suggestions: const [
        FollowListItem(userId: 50, deleted: false, userToken: 'tok50', nickname: 'cand50'),
      ],
    );
    expect(find.byType(FollowingEmptyView), findsOneWidget);
    expect(find.byType(FollowingStrip), findsNothing);
    expect(find.byKey(const ValueKey('followingStripAvatar_100')), findsNothing);
    expect(find.text('No posts from friends yet'), findsOneWidget);
    expect(find.text('Follow people you like and their moments and diaries will show up here.'), findsOneWidget);
    expect(find.text('cand50'), findsOneWidget); // 推荐区
    expect(find.text('Explore Discover ›'), findsOneWidget);
  });

  testWidgets('空状态：推荐最多 5 行，Follow 为实心紫；Explore Discover 可见', (tester) async {
    await _pump(
      tester,
      posts: const [],
      strip: const [],
      suggestions: [for (var i = 50; i < 58; i++) FollowListItem(userId: i, deleted: false, userToken: 'tok$i', nickname: 'cand$i')],
    );
    expect(find.byKey(const ValueKey('followSuggestRow_54')), findsOneWidget);
    expect(find.byKey(const ValueKey('followSuggestRow_55')), findsNothing);
    final btn = find.descendant(of: find.byKey(const ValueKey('followSuggestRow_50')), matching: find.byType(FilledButton));
    expect(tester.widget<FilledButton>(btn).style?.backgroundColor?.resolve({}), AppColors.mint);
    expect(find.byKey(const ValueKey('followingExploreDiscover')), findsOneWidget);
  });

  testWidgets('重点击信号（homeScrollTopProvider）→ 空状态滚回顶部', (tester) async {
    final c = await _pump(
      tester,
      posts: const [],
      strip: const [],
      suggestions: [for (var i = 50; i < 58; i++) FollowListItem(userId: i, deleted: false, userToken: 'tok$i', nickname: 'cand$i')],
    );
    final scroll = find.byType(SingleChildScrollView).first;
    tester.state<ScrollableState>(find.descendant(of: scroll, matching: find.byType(Scrollable)).first).position.jumpTo(120);
    await tester.pump();
    expect(tester.state<ScrollableState>(find.descendant(of: scroll, matching: find.byType(Scrollable)).first).position.pixels, 120);
    c.read(homeScrollTopProvider.notifier).bump();
    await tester.pumpAndSettle();
    expect(tester.state<ScrollableState>(find.descendant(of: scroll, matching: find.byType(Scrollable)).first).position.pixels, 0);
  });

  testWidgets('exploreDiscoverSwitchesTab：点 Explore Discover → 页签切回 Discover', (tester) async {
    final c = await _pump(tester, posts: const [], strip: const []);
    c.read(socialTabProvider.notifier).select(SocialTab.following);
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('followingExploreDiscover')));
    await tester.pump();
    expect(c.read(socialTabProvider), SocialTab.discover);
  });

  testWidgets('followingCardsHaveNoFollowButton：关注流卡片 iFollow=true → 无「Follow」', (tester) async {
    await _pump(tester, posts: [_post(1), _post(2, authorId: 8)], strip: [_user(7)]);
    expect(find.text('post 1'), findsOneWidget);
    expect(find.text('Follow'), findsNothing);
    expect(find.text('Follow Back'), findsNothing);
    expect(find.text('Following'), findsNothing); // 本视图不含页签栏，「Following」只可能来自卡片按钮
  });

  testWidgets('对照组：关注流里若出现未关注作者（iFollow=false）→ 卡片会显示「Follow」（证明上一条断言不是空转）',
      (tester) async {
    await _pump(tester, posts: [_post(1, follow: FollowState.none)], strip: const []);
    expect(find.text('Follow'), findsOneWidget);
  });

  testWidgets('详情页计数回写 / 举报 / 拉黑作者：关注流同步（syncFeedCounts / removeFeedPost / onAuthorHidden）',
      (tester) async {
    final c = await _pump(tester, posts: [_post(1), _post(2), _post(3, authorId: 9)], strip: const []);
    final ref = tester.element(find.byType(FollowingTabView)) as WidgetRef;
    // Discover 未建 → 回写不应把它建出来。
    expect(c.exists(feedProvider), isFalse);

    syncFeedCounts(ref, 1, likeCount: 42, liked: true, commentCount: 5);
    final item1 = c.read(followingFeedProvider).value!.items.firstWhere((i) => i.id == 1);
    expect(item1.likeCount, 42);
    expect(item1.liked, isTrue);
    expect(item1.commentCount, 5);
    expect(c.exists(feedProvider), isFalse);

    removeFeedPost(ref, 2);
    expect(c.read(followingFeedProvider).value!.items.map((i) => i.id), [1, 3]);

    onAuthorHidden(ref, 9)();
    expect(c.read(followingFeedProvider).value!.items.map((i) => i.id), [1]);
  });
}

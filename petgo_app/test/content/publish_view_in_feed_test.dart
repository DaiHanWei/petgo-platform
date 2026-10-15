import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/feed_repository.dart';
import 'package:tailtopia/features/content/data/own_post_lookup.dart';
import 'package:tailtopia/features/content/domain/feed_focus.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/features/content/domain/pinned_slot.dart';
import 'package:tailtopia/features/content/presentation/feed_controller.dart';
import 'package:tailtopia/features/content/presentation/home_page.dart';
import 'package:tailtopia/features/content/presentation/publish_result_page.dart';
import 'package:tailtopia/features/content/presentation/social_tab.dart';
import 'package:tailtopia/features/social/data/follow_suggestion_repository.dart';
import 'package:tailtopia/features/social/data/following_strip_repository.dart';
import 'package:tailtopia/features/social/domain/follow_list_item.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// bug 20261012-586：发布成功页「去 Feed 看」→ Social · Discover，刚发的帖紧跟顶置卡、可见、高亮。

class _User extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: 1, nickname: 'Me', onboardingCompleted: true),
      );
}

const int _newPostId = 50;

FeedItem _item(int id, String body, {int authorId = 7}) => FeedItem(
      id: id,
      authorId: authorId,
      authorDeleted: false,
      authorNickname: 'U$id',
      type: 'DAILY',
      body: body,
      createdAt: DateTime.utc(2026, 10, 1),
    );

class _FeedRepo implements FeedRepository {
  _FeedRepo({required this.includeNewPostAt});

  /// 新帖在后端首屏里的位置；null = 首屏里没有（老后端）。
  final int? includeNewPostAt;
  int feedCalls = 0;
  int pinnedCalls = 0;

  @override
  Future<FeedPage> getFeed({FeedCategory category = FeedCategory.all, String? cursor, int limit = 20}) async {
    feedCalls++;
    final items = [for (var i = 1; i <= 20; i++) _item(i, 'other post $i')];
    final at = includeNewPostAt;
    // 首次（发布前就已建好的首屏）不含新帖；重拉后才有。
    if (at != null && feedCalls > 1) items.insert(at, _item(_newPostId, 'my new post', authorId: 1));
    return FeedPage(items: items);
  }

  @override
  Future<PinnedSlot?> getPinnedSlot() async {
    pinnedCalls++;
    return PinnedSlot(
      pinConfigId: 9,
      pinType: 'CONTENT',
      item: _item(100, 'pinned post body ' * 6, authorId: 3),
    );
  }

  @override
  Future<FeedPage> getFollowingFeed({String? cursor}) async => const FeedPage(items: []);
}

class _StripRepo implements FollowingStripRepository {
  @override
  Future<List<FollowingStripUser>> fetch() async => const [];
}

class _SuggestRepo implements FollowSuggestionRepository {
  @override
  Future<List<FollowListItem>> fetch() async => const [];
}

/// [startOnFeed]=true：用户先在 Discover 往下滑了一段，再发帖（成功页压在 Feed 之上）——
/// 回到 Feed 时新帖在视口之外，必须靠定位「滚过去」才看得见（验证定位真的滚了）。
/// false：直接从成功页起步，且发布前停在 Following 页签（验证会被切回 Discover）。
Future<(ProviderContainer, _FeedRepo)> _pump(WidgetTester tester,
    {required int? includeNewPostAt, bool startOnFeed = true}) async {
  final repo = _FeedRepo(includeNewPostAt: includeNewPostAt);
  final c = ProviderContainer(overrides: [
    authControllerProvider.overrideWith(_User.new),
    feedRepositoryProvider.overrideWithValue(repo),
    followingStripRepositoryProvider.overrideWithValue(_StripRepo()),
    followSuggestionRepositoryProvider.overrideWithValue(_SuggestRepo()),
    ownPostLookupProvider.overrideWithValue(
        (id) async => id == _newPostId ? _item(_newPostId, 'my new post', authorId: 1) : null),
  ]);
  addTearDown(c.dispose);
  if (!startOnFeed) c.read(socialTabProvider.notifier).select(SocialTab.following);
  final router = GoRouter(
    initialLocation: startOnFeed ? '/home' : '/publish/done',
    routes: [
      GoRoute(path: '/home', builder: (_, _) => const HomePage()),
      GoRoute(path: '/profile', builder: (_, _) => const Scaffold(body: Text('diary'))),
      GoRoute(
        path: '/publish/done',
        builder: (_, _) => PublishDonePage(
          args: const PublishResultArgs(excerpt: 'my new post', typeLabel: 'Daily', photoCount: 0)
              .withPostId(_newPostId),
        ),
      ),
    ],
  );
  addTearDown(router.dispose);
  // 预先建一次首屏（模拟发布前 Feed 已加载过、里面还没有新帖）。
  await c.read(feedProvider.future);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: c,
    child: MaterialApp.router(
      routerConfig: router,
      locale: const Locale('en'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
    ),
  ));
  await tester.pumpAndSettle();
  if (startOnFeed) {
    await tester.drag(find.text('other post 1'), const Offset(0, -2000));
    await tester.pumpAndSettle();
    expect(find.text('other post 1').hitTestable(), findsNothing, reason: '前置：已滑离首屏顶部');
    router.push('/publish/done');
    await tester.pumpAndSettle();
  }
  return (c, repo);
}

Future<void> _tapViewFeed(WidgetTester tester) async {
  await tester.tap(find.byKey(const ValueKey('publishDoneViewFeed')));
  await tester.pumpAndSettle();
}

/// 断言：新帖卡片紧跟顶置卡（在顶置卡之下、在其它普通卡之上）且整张在屏幕内。
void _expectRightBelowPinnedAndVisible(WidgetTester tester) {
  final pinned = tester.getRect(find.byKey(const ValueKey('feedPinnedCard')));
  final mine = tester.getRect(find.text('my new post'));
  final other = tester.getRect(find.text('other post 1'));
  expect(mine.top, greaterThanOrEqualTo(pinned.bottom), reason: '新帖应在顶置卡下方');
  expect(mine.top, lessThan(other.top), reason: '新帖应是顶置卡后的第一条');
  final screen = tester.getRect(find.byType(HomePage));
  expect(mine.top, greaterThanOrEqualTo(screen.top));
  expect(mine.bottom, lessThanOrEqualTo(screen.bottom), reason: '新帖应滚到可见');
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  testWidgets('新后端：首屏已把新帖排第一 → 切 Discover、紧跟顶置卡、可见、信号清掉', (tester) async {
    final (c, repo) = await _pump(tester, includeNewPostAt: 0);
    final pinnedBefore = repo.pinnedCalls;

    await _tapViewFeed(tester);

    expect(find.byType(HomePage), findsOneWidget);
    expect(c.read(socialTabProvider), SocialTab.discover);
    expect(c.read(feedCategoryProvider), FeedCategory.all);
    expect(repo.pinnedCalls, greaterThan(pinnedBefore), reason: '顶置位须一起重拉');
    _expectRightBelowPinnedAndVisible(tester);
    expect(c.read(pendingFeedFocusProvider), isNull, reason: '一次性信号定位后须清掉');
    // 让高亮定时器走完，避免 pending timer。
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('后端把新帖排在中间 → 本地挪到第一条（紧跟顶置卡）', (tester) async {
    final (c, _) = await _pump(tester, includeNewPostAt: 5);

    await _tapViewFeed(tester);

    _expectRightBelowPinnedAndVisible(tester);
    expect(c.read(feedProvider).value!.items.first.id, _newPostId);
    expect(c.read(feedProvider).value!.items.where((i) => i.id == _newPostId), hasLength(1));
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('老后端：首屏里没有新帖 → 兜底取回并插到顶置卡之后、滚到可见', (tester) async {
    final (c, _) = await _pump(tester, includeNewPostAt: null);

    await _tapViewFeed(tester);

    _expectRightBelowPinnedAndVisible(tester);
    expect(c.read(feedProvider).value!.items.first.id, _newPostId);
    expect(c.read(pendingFeedFocusProvider), isNull);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('发布前停在 Following → 切回 Discover 并定位', (tester) async {
    final (c, _) = await _pump(tester, includeNewPostAt: 0, startOnFeed: false);

    await _tapViewFeed(tester);

    expect(c.read(socialTabProvider), SocialTab.discover);
    _expectRightBelowPinnedAndVisible(tester);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('私密保存：主按钮仍去 Diary，不碰 Feed 定位', (tester) async {
    final repo = _FeedRepo(includeNewPostAt: 0);
    final c = ProviderContainer(overrides: [
      authControllerProvider.overrideWith(_User.new),
      feedRepositoryProvider.overrideWithValue(repo),
    ]);
    addTearDown(c.dispose);
    final router = GoRouter(
      initialLocation: '/publish/done',
      routes: [
        GoRoute(path: '/home', builder: (_, _) => const Scaffold(body: Text('home'))),
        GoRoute(path: '/profile', builder: (_, _) => const Scaffold(body: Text('diary'))),
        GoRoute(
          path: '/publish/done',
          builder: (_, _) => PublishDonePage(
            args: const PublishResultArgs(
                    excerpt: 'x', typeLabel: 'Daily', photoCount: 0, isPrivate: true)
                .withPostId(_newPostId),
          ),
        ),
      ],
    );
    addTearDown(router.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: c,
      child: MaterialApp.router(
        routerConfig: router,
        locale: const Locale('en'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
      ),
    ));
    await tester.pumpAndSettle();

    await _tapViewFeed(tester);

    expect(find.text('diary'), findsOneWidget);
    expect(c.read(pendingFeedFocusProvider), isNull);
  });
}

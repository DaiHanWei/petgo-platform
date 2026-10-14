import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/feed_repository.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/features/content/domain/home_refresh_provider.dart';
import 'package:tailtopia/features/content/domain/pinned_slot.dart';
import 'package:tailtopia/features/content/presentation/feed_tab_row.dart';
import 'package:tailtopia/features/content/presentation/feed_view.dart';
import 'package:tailtopia/features/content/presentation/following_tab_view.dart';
import 'package:tailtopia/features/content/presentation/home_page.dart';
import 'package:tailtopia/features/content/presentation/social_tab.dart';
import 'package:tailtopia/features/social/data/follow_suggestion_repository.dart';
import 'package:tailtopia/features/social/data/following_strip_repository.dart';
import 'package:tailtopia/features/social/domain/follow_list_item.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/login_hard_dialog.dart';

/// V1.3.4 following 流 · Task 8：Social 顶部 Discover / Following 页签。

class _Guest extends AuthController {
  @override
  AuthState build() => const AuthState(status: AuthStatus.guest);
}

class _User extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: 1, nickname: 'Me', onboardingCompleted: true),
      );
}

class _FeedRepo implements FeedRepository {
  int followingCalls = 0;

  /// true → 关注流返回一条帖（FollowingStrip 只在非空流里挂载）。
  bool followingHasItem = false;

  @override
  Future<FeedPage> getFeed({FeedCategory category = FeedCategory.all, String? cursor, int limit = 20}) async =>
      FeedPage(items: [
        FeedItem(
          id: 1,
          authorId: 7,
          authorDeleted: false,
          authorNickname: 'Alice',
          type: 'DAILY',
          body: 'discover post',
          createdAt: DateTime.utc(2026, 10, 1),
        ),
      ]);

  @override
  Future<PinnedSlot?> getPinnedSlot() async => null;

  @override
  Future<FeedPage> getFollowingFeed({String? cursor}) async {
    followingCalls++;
    if (!followingHasItem) return const FeedPage(items: []);
    return FeedPage(items: [
      FeedItem(
        id: 2,
        authorId: 8,
        authorDeleted: false,
        authorNickname: 'Bob',
        type: 'DAILY',
        body: 'following post',
        createdAt: DateTime.utc(2026, 10, 2),
      ),
    ]);
  }
}

class _StripRepo implements FollowingStripRepository {
  static int calls = 0;

  @override
  Future<List<FollowingStripUser>> fetch() async {
    calls++;
    return const [];
  }
}

class _SuggestRepo implements FollowSuggestionRepository {
  @override
  Future<List<FollowListItem>> fetch() async => const [];
}

Future<(ProviderContainer, _FeedRepo)> _pump(WidgetTester tester, {required bool guest}) async {
  final repo = _FeedRepo();
  final c = ProviderContainer(overrides: [
    authControllerProvider.overrideWith(guest ? _Guest.new : _User.new),
    feedRepositoryProvider.overrideWithValue(repo),
    followingStripRepositoryProvider.overrideWithValue(_StripRepo()),
    followSuggestionRepositoryProvider.overrideWithValue(_SuggestRepo()),
  ]);
  addTearDown(c.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: c,
    child: const MaterialApp(
      locale: Locale('en'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: HomePage(),
    ),
  ));
  await tester.pumpAndSettle();
  return (c, repo);
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  testWidgets('discoverIsDefaultAndUnchanged：默认 Discover，分类 chips 与瀑布流仍在', (tester) async {
    final (c, repo) = await _pump(tester, guest: false);
    expect(c.read(socialTabProvider), SocialTab.discover);
    expect(find.byType(SocialTabBar), findsOneWidget);
    expect(find.text('Discover'), findsOneWidget);
    expect(find.text('Following'), findsOneWidget);
    expect(find.byType(FeedTabRow), findsOneWidget);
    expect(find.byType(FeedMasonryView), findsOneWidget);
    expect(find.text('discover post'), findsOneWidget);
    expect(find.byType(FollowingTabView), findsNothing);
    // Discover 下不请求关注流。
    expect(repo.followingCalls, 0);
  });

  testWidgets('guestTapFollowingOpensLogin：游客点 Following → 弹登录引导、页签不切换', (tester) async {
    final (c, repo) = await _pump(tester, guest: true);
    await tester.tap(find.byKey(const ValueKey('socialTab_following')));
    await tester.pumpAndSettle();
    expect(find.byType(LoginHardDialog), findsOneWidget);
    expect(c.read(socialTabProvider), SocialTab.discover);
    expect(find.byType(FollowingTabView), findsNothing);
    expect(repo.followingCalls, 0);
  });

  testWidgets('登录用户点 Following → 切到关注流（social_tab_viewed 上报）；再点 Discover 切回', (tester) async {
    final events = <String, Map<String, Object>?>{};
    Analytics.debugCaptureSink = (e, p) => events[e] = p;
    addTearDown(() => Analytics.debugCaptureSink = null);

    final (c, repo) = await _pump(tester, guest: false);
    await tester.tap(find.byKey(const ValueKey('socialTab_following')));
    await tester.pumpAndSettle();
    expect(c.read(socialTabProvider), SocialTab.following);
    expect(find.byType(FollowingTabView), findsOneWidget);
    expect(find.byType(FeedTabRow), findsNothing);
    expect(repo.followingCalls, greaterThanOrEqualTo(1));
    expect(events['social_tab_viewed'], {'tab': 'following'});

    await tester.tap(find.byKey(const ValueKey('socialTab_discover')));
    await tester.pumpAndSettle();
    expect(c.read(socialTabProvider), SocialTab.discover);
    expect(find.byType(FeedTabRow), findsOneWidget);
  });

  testWidgets('登出后即使状态停在 following 也按 Discover 渲染（游客不请求关注流）', (tester) async {
    final (c, repo) = await _pump(tester, guest: true);
    c.read(socialTabProvider.notifier).select(SocialTab.following);
    await tester.pumpAndSettle();
    expect(find.byType(FollowingTabView), findsNothing);
    expect(find.byType(FeedTabRow), findsOneWidget);
    expect(repo.followingCalls, 0);
  });

  testWidgets('已在 Following 再点页签 → 回顶信号 +1、头像排与关注流都重新拉取', (tester) async {
    final (c, repo) = await _pump(tester, guest: false);
    repo.followingHasItem = true;
    await tester.tap(find.byKey(const ValueKey('socialTab_following')));
    await tester.pumpAndSettle();
    expect(c.read(socialTabProvider), SocialTab.following);
    final feedCallsBefore = repo.followingCalls;
    final stripCallsBefore = _StripRepo.calls;
    final topBefore = c.read(homeScrollTopProvider);

    await tester.tap(find.byKey(const ValueKey('socialTab_following')));
    await tester.pumpAndSettle();

    expect(c.read(socialTabProvider), SocialTab.following);
    expect(c.read(homeScrollTopProvider), isNot(topBefore), reason: '回顶信号应递增');
    expect(repo.followingCalls, greaterThan(feedCallsBefore), reason: '关注流重新拉取');
    expect(_StripRepo.calls, greaterThan(stripCallsBefore), reason: '头像排重新拉取');
  });
}

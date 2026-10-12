import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/social/data/follow_list_repository.dart';
import 'package:tailtopia/features/social/data/follow_repository.dart';
import 'package:tailtopia/features/social/data/follow_suggestion_repository.dart';
import 'package:tailtopia/features/social/domain/follow_list_item.dart';
import 'package:tailtopia/features/social/domain/follow_state.dart';
import 'package:tailtopia/features/social/presentation/blocked_list_skeleton.dart';
import 'package:tailtopia/features/social/presentation/follow_list_page.dart';
import 'package:tailtopia/features/user_profile/data/public_profile_repository.dart';
import 'package:tailtopia/features/user_profile/presentation/public_profile_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.4 follow · Story 2.3 · AC2 ~ AC4：我自己的空关注 / 粉丝列表 → 提示条 + 推荐关注。
const int _kMe = 1;
const String _kMyToken = 'memememememememememememememememe';

class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: _kMe, nickname: 'Me', onboardingCompleted: true),
      );
}

class _ProfileRepo implements PublicProfileRepository {
  _ProfileRepo({required this.self});
  final bool self;

  @override
  Future<PublicProfile> getPublicProfile(int userId) async => PublicProfile(
      postCount: 0, likeCount: 0, isDeactivated: false, self: self, nickname: 'Me', userToken: _kMyToken);
}

/// 每个 Tab 依次返回 [pages] 里的页（只剩一页时一直返回它）。
class _ListRepo implements FollowListRepository {
  _ListRepo(this.pages);
  final Map<FollowListTab, List<FollowListPageData>> pages;

  @override
  Future<FollowListPageData> fetch(String userToken, FollowListTab tab, {String? cursor}) async {
    final list = pages[tab]!;
    return list.length > 1 ? list.removeAt(0) : list.first;
  }
}

/// 依次返回 [results]（只剩一个时一直返回它）；元素是 `List<FollowListItem>`、`Completer` 或要抛的错误。
class _SuggestRepo implements FollowSuggestionRepository {
  _SuggestRepo(this.results);
  final List<Object> results;
  int calls = 0;

  @override
  Future<List<FollowListItem>> fetch() async {
    calls++;
    final r = results.length > 1 ? results.removeAt(0) : results.first;
    if (r is List<FollowListItem>) return r;
    if (r is Completer<List<FollowListItem>>) return r.future;
    throw r;
  }
}

class _FollowRepo implements FollowRepository {
  final List<String> calls = [];

  @override
  Future<FollowResult> follow(String userToken) async {
    calls.add('PUT $userToken');
    return const FollowResult(state: FollowState(iFollow: true, followsMe: false), followerCount: 1);
  }

  @override
  Future<FollowResult> unfollow(String userToken) async {
    calls.add('DELETE $userToken');
    return const FollowResult(state: FollowState.none, followerCount: 0);
  }
}

FollowListItem _cand(int id, {bool followsMe = false}) => FollowListItem(
      userId: id,
      deleted: false,
      userToken: 'tok$id',
      nickname: 'cand$id',
      followState: followsMe ? const FollowState(iFollow: false, followsMe: true) : FollowState.none,
    );

FollowListItem _row(int id) =>
    FollowListItem(userId: id, deleted: false, userToken: 'tok$id', nickname: 'user$id');

FollowListPageData _page(List<FollowListItem> items) => FollowListPageData(items: items);

late AppLocalizations l10n;

Future<void> _pump(
  WidgetTester tester, {
  required _ListRepo lists,
  required _SuggestRepo suggestions,
  _FollowRepo? follows,
  bool self = true,
  FollowListTab tab = FollowListTab.following,
  bool settle = true,
}) async {
  final router = GoRouter(
    initialLocation: FollowListPage.location(_kMe, tab),
    routes: [
      GoRoute(
        path: FollowListPage.routePattern,
        builder: (_, s) => FollowListPage(
          userId: int.parse(s.pathParameters['userId']!),
          initialTab: FollowListTab.fromWire(s.uri.queryParameters['tab']),
        ),
      ),
      GoRoute(
        path: PublicProfilePage.routePattern,
        builder: (_, _) => const Scaffold(body: Text('profile')),
      ),
    ],
  );
  addTearDown(router.dispose);
  final container = ProviderContainer(overrides: [
    publicProfileRepositoryProvider.overrideWithValue(_ProfileRepo(self: self)),
    followListRepositoryProvider.overrideWithValue(lists),
    followSuggestionRepositoryProvider.overrideWithValue(suggestions),
    followRepositoryProvider.overrideWithValue(follows ?? _FollowRepo()),
    authControllerProvider.overrideWith(_Auth.new),
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
  if (settle) {
    await tester.pumpAndSettle();
  } else {
    await tester.pump();
    await tester.pump();
  }
}

Finder _suggestBtn(String tab, int id) => find.byKey(ValueKey('followList_suggest:$tab:$id'));

String _label(WidgetTester tester, String tab, int id) =>
    tester.widget<Text>(find.descendant(of: _suggestBtn(tab, id), matching: find.byType(Text))).data!;

final Finder _title = find.byKey(const ValueKey('followSuggestTitle'));
final Finder _hint = find.byKey(const ValueKey('followListSelfEmptyHint'));

Future<void> _switchTab(WidgetTester tester, String key) async {
  await tester.tap(find.byKey(ValueKey(key)));
  await tester.pumpAndSettle();
}

void main() {
  final events = <(String, Map<String, Object>?)>[];
  setUpAll(() async {
    l10n = await AppLocalizations.delegate.load(const Locale('en'));
  });
  setUp(() {
    events.clear();
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  List<String> names(String event) => [for (final e in events) if (e.$1 == event) e.$1];

  testWidgets('我的 Mengikuti 空 → 提示条 + 小节标题 + 推荐行；我的 Pengikut 空 → 「No followers yet」', (tester) async {
    final suggestions = _SuggestRepo([
      [_cand(7), _cand(8)],
    ]);
    await _pump(tester,
        lists: _ListRepo({
          FollowListTab.following: [_page([])],
          FollowListTab.followers: [_page([])],
        }),
        suggestions: suggestions);

    expect(find.descendant(of: _hint, matching: find.text(l10n.followListEmptySelfFollowing)), findsOneWidget);
    expect(find.descendant(of: _hint, matching: find.byIcon(Icons.group)), findsOneWidget);
    final hint = tester.widget<Container>(_hint);
    expect((hint.decoration! as BoxDecoration).borderRadius, BorderRadius.circular(14));
    expect(tester.widget<Text>(_title).data, l10n.followSuggestedTitle.toUpperCase());
    expect(find.text('cand7'), findsOneWidget);
    expect(find.text('cand8'), findsOneWidget);
    expect(_label(tester, 'following', 7), l10n.followActionFollow);
    // 普通空态（UI 稿 B8）不再出现。
    expect(find.byKey(const ValueKey('followListEmpty_following')), findsNothing);
    expect(find.byIcon(Icons.more_vert), findsNothing);

    await _switchTab(tester, 'followListTabFollowers');
    expect(find.descendant(of: _hint, matching: find.text(l10n.followListEmptyFollowers)), findsOneWidget);
    expect(find.descendant(of: _hint, matching: find.byIcon(Icons.person_add)), findsOneWidget);
    expect(find.byKey(const ValueKey('followSuggestRow_7')), findsOneWidget);

    // AC2.3：两个 Tab 都空 → 共用同一次推荐结果。
    expect(suggestions.calls, 1);
  });

  testWidgets('别人的空列表：不请求推荐、不显示推荐（UI 稿 B8）', (tester) async {
    final suggestions = _SuggestRepo([
      [_cand(7)],
    ]);
    await _pump(tester,
        self: false,
        lists: _ListRepo({
          FollowListTab.following: [_page([])],
          FollowListTab.followers: [_page([])],
        }),
        suggestions: suggestions);
    await _switchTab(tester, 'followListTabFollowers');

    expect(suggestions.calls, 0);
    expect(_hint, findsNothing);
    expect(_title, findsNothing);
    expect(find.text(l10n.followListEmptyFollowers), findsOneWidget);
    expect(names('suggested_follow_shown'), isEmpty);
  });

  testWidgets('我的列表不为空：不请求推荐', (tester) async {
    final suggestions = _SuggestRepo([
      [_cand(7)],
    ]);
    await _pump(tester,
        lists: _ListRepo({
          FollowListTab.following: [_page([_row(3)])],
          FollowListTab.followers: [_page([_row(4)])],
        }),
        suggestions: suggestions);
    await _switchTab(tester, 'followListTabFollowers');

    expect(suggestions.calls, 0);
    expect(_title, findsNothing);
  });

  testWidgets('推荐里点 Follow → 本按钮变 Following、可再点取消，行一直在；埋点 source=suggested', (tester) async {
    final follows = _FollowRepo();
    await _pump(tester,
        lists: _ListRepo({
          FollowListTab.following: [_page([])],
          FollowListTab.followers: [_page([_row(4)])],
        }),
        suggestions: _SuggestRepo([
          [_cand(7), _cand(8, followsMe: true)],
        ]),
        follows: follows);
    // T-25：对方关注我 → 按全局三态显示 Follow Back。
    expect(_label(tester, 'following', 8), l10n.followActionFollowBack);

    await tester.tap(_suggestBtn('following', 7));
    await tester.pumpAndSettle();
    expect(follows.calls, ['PUT tok7']);
    expect(_label(tester, 'following', 7), l10n.followActionFollowing);

    await tester.tap(_suggestBtn('following', 7));
    await tester.pumpAndSettle();
    expect(follows.calls, ['PUT tok7', 'DELETE tok7']);
    expect(_label(tester, 'following', 7), l10n.followActionFollow);
    expect(find.byKey(const ValueKey('followSuggestRow_7')), findsOneWidget);

    await tester.tap(_suggestBtn('following', 8));
    await tester.pumpAndSettle();

    final followed = [for (final e in events) if (e.$1 == 'user_followed') e.$2!];
    expect(followed, hasLength(2));
    expect(followed.every((p) => p['source'] == 'suggested'), isTrue);
    expect(followed[0]['is_follow_back'], isFalse);
    expect(followed[1]['is_follow_back'], isTrue, reason: 'D-6：按点击前 followsMe');
    final unfollowed = [for (final e in events) if (e.$1 == 'user_unfollowed') e.$2!];
    expect(unfollowed.single['source'], 'suggested');
  });

  testWidgets('下拉刷新：重拉推荐；已关注的人被服务端排除后从推荐里消失；列表不再为空则推荐区消失', (tester) async {
    final suggestions = _SuggestRepo([
      [_cand(7), _cand(8)],
      [_cand(8)],
    ]);
    await _pump(tester,
        lists: _ListRepo({
          FollowListTab.following: [_page([]), _page([]), _page([_row(7)])],
          FollowListTab.followers: [_page([_row(4)])],
        }),
        suggestions: suggestions);
    await tester.tap(_suggestBtn('following', 7));
    await tester.pumpAndSettle();

    await tester.drag(find.byKey(const ValueKey('followListSelfEmpty_following')), const Offset(0, 300));
    await tester.pumpAndSettle();
    expect(suggestions.calls, 2);
    expect(find.byKey(const ValueKey('followSuggestRow_7')), findsNothing);
    expect(find.byKey(const ValueKey('followSuggestRow_8')), findsOneWidget);

    await tester.drag(find.byKey(const ValueKey('followListSelfEmpty_following')), const Offset(0, 300));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('followListRow_7')), findsOneWidget);
    expect(_hint, findsNothing);
    expect(_title, findsNothing);
  });

  // ⚠️ 两种结果各一个用例：同一用例里 pumpWidget 两次会留下 Riverpod 的 dispose 定时器。
  for (final (name, result) in <(String, Object)>[('失败', Exception('boom')), ('空数组', const <FollowListItem>[])]) {
    testWidgets('推荐$name → 只剩提示条（T-24）', (tester) async {
      await _pump(tester,
          lists: _ListRepo({
            FollowListTab.following: [_page([])],
            FollowListTab.followers: [_page([])],
          }),
          suggestions: _SuggestRepo([result]));

      expect(_hint, findsOneWidget, reason: '$result');
      expect(_title, findsNothing, reason: '$result');
      expect(find.byType(BlockedListSkeleton), findsNothing, reason: '$result');
      expect(find.byKey(const ValueKey('followListRetry')), findsNothing, reason: '推荐失败不画成列表失败态');
      expect(names('suggested_follow_shown'), isEmpty);
    });
  }

  testWidgets('推荐加载中：提示条照常 + 骨架', (tester) async {
    final pending = Completer<List<FollowListItem>>();
    await _pump(tester,
        settle: false,
        lists: _ListRepo({
          FollowListTab.following: [_page([])],
          FollowListTab.followers: [_page([])],
        }),
        suggestions: _SuggestRepo([pending]));
    await tester.pump();

    expect(_hint, findsOneWidget);
    expect(find.byKey(const ValueKey('followSuggestLoading')), findsOneWidget);
    expect(_title, findsNothing);

    pending.complete([_cand(7)]);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('followSuggestLoading')), findsNothing);
    expect(_title, findsOneWidget);
  });

  testWidgets('suggested_follow_shown：每个 Tab 最多一次、无属性；切 Tab / 刷新不重复报', (tester) async {
    final suggestions = _SuggestRepo([
      [_cand(7)],
    ]);
    await _pump(tester,
        lists: _ListRepo({
          FollowListTab.following: [_page([])],
          FollowListTab.followers: [_page([])],
        }),
        suggestions: suggestions);
    expect(names('suggested_follow_shown'), hasLength(1));

    await tester.drag(find.byKey(const ValueKey('followListSelfEmpty_following')), const Offset(0, 300));
    await tester.pumpAndSettle();
    expect(names('suggested_follow_shown'), hasLength(1), reason: '刷新不重复报');

    await _switchTab(tester, 'followListTabFollowers');
    await _switchTab(tester, 'followListTabFollowing');
    await _switchTab(tester, 'followListTabFollowers');
    final shown = [for (final e in events) if (e.$1 == 'suggested_follow_shown') e];
    expect(shown, hasLength(2));
    expect(shown.every((e) => e.$2 == null || e.$2!.isEmpty), isTrue);

    // AC4.3：我的空 Tab 仍按 Story 2.2 报 follow_list_viewed。
    final viewed = [for (final e in events) if (e.$1 == 'follow_list_viewed') e.$2];
    expect(viewed.first, {'tab': 'following', 'is_self': true, 'is_empty': true});
  });
}

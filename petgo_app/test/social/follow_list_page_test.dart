import 'dart:async';

import 'package:dio/dio.dart';
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

/// V1.3.4 follow · Story 2.2 · AC3 ~ AC7：关注 / 粉丝列表页。
const int _kMe = 1;
const int _kOwner = 50;
const String _kOwnerToken = 'ownerownerownerownerownerowner50';

class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: _kMe, nickname: 'Me', onboardingCompleted: true),
      );
}

/// 2.2 code-review F1：游客 → 登录（同一页面实例里切身份）。
class _GuestThenLogin extends AuthController {
  @override
  AuthState build() => const AuthState.guest();

  void login() => state = const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: _kMe, nickname: 'Me', onboardingCompleted: true),
      );
}

class _ProfileRepo implements PublicProfileRepository {
  _ProfileRepo(this.result);
  Object result;
  int calls = 0;

  @override
  Future<PublicProfile> getPublicProfile(int userId) async {
    calls++;
    final r = result;
    if (r is PublicProfile) return r;
    throw r;
  }
}

class _ListRepo implements FollowListRepository {
  _ListRepo(this.pages);
  final Map<FollowListTab, List<Object>> pages;
  final List<(FollowListTab, String?)> calls = [];

  @override
  Future<FollowListPageData> fetch(String userToken, FollowListTab tab, {String? cursor}) async {
    calls.add((tab, cursor));
    final list = pages[tab]!;
    final r = list.length > 1 ? list.removeAt(0) : list.first;
    if (r is FollowListPageData) return r;
    throw r;
  }
}

/// Story 2.3：我自己的空列表会请求推荐 —— 本文件不测推荐，一律给空数组（推荐区只剩提示条）。
class _NoSuggestions implements FollowSuggestionRepository {
  @override
  Future<List<FollowListItem>> fetch() async => const [];
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
    return const FollowResult(state: FollowState(iFollow: false, followsMe: true), followerCount: 0);
  }
}

PublicProfile _owner({bool self = false}) => PublicProfile(
      postCount: 0, likeCount: 0, isDeactivated: false, self: self, nickname: 'Mikky', userToken: _kOwnerToken);

FollowListItem _row(int id,
        {FollowState s = FollowState.none, bool self = false, bool blocked = false, bool deleted = false}) =>
    FollowListItem(
      userId: id,
      deleted: deleted,
      userToken: deleted ? null : 'tok$id',
      nickname: deleted ? null : 'user$id',
      followState: s,
      self: self,
      blockedByMe: blocked,
    );

FollowListPageData _page(List<FollowListItem> items, {bool hasMore = false, String? next}) =>
    FollowListPageData(items: items, hasMore: hasMore, nextCursor: next);

late AppLocalizations l10n;

class _Nav {
  int? openedProfile;
  late ProviderContainer container;
}

Future<_Nav> _pump(
  WidgetTester tester, {
  required Object profile,
  required _ListRepo lists,
  _FollowRepo? follows,
  FollowListTab tab = FollowListTab.following,
  AuthController Function()? auth,
}) async {
  final nav = _Nav();
  final router = GoRouter(
    initialLocation: FollowListPage.location(_kOwner, tab),
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
        builder: (_, s) {
          nav.openedProfile = int.parse(s.pathParameters['userId']!);
          return const Scaffold(body: Text('profile'));
        },
      ),
    ],
  );
  addTearDown(router.dispose);
  final container = ProviderContainer(overrides: [
    publicProfileRepositoryProvider.overrideWithValue(profile is _ProfileRepo ? profile : _ProfileRepo(profile)),
    followListRepositoryProvider.overrideWithValue(lists),
    followRepositoryProvider.overrideWithValue(follows ?? _FollowRepo()),
    followSuggestionRepositoryProvider.overrideWithValue(_NoSuggestions()),
    authControllerProvider.overrideWith(auth ?? _Auth.new),
  ]);
  addTearDown(container.dispose);
  nav.container = container;
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
  return nav;
}

Finder _btn(String tab, int id) => find.byKey(ValueKey('followList_list:$tab:$_kOwner:$id'));

String _label(WidgetTester tester, String tab, int id) =>
    tester.widget<Text>(find.descendant(of: _btn(tab, id), matching: find.byType(Text))).data!;

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

  testWidgets('标题取主页昵称；?tab=followers 定位到 Pengikut；行没有「⋮」', (tester) async {
    final lists = _ListRepo({
      FollowListTab.following: [_page([])],
      FollowListTab.followers: [_page([_row(7)])],
    });
    await _pump(tester, profile: _owner(), lists: lists, tab: FollowListTab.followers);

    expect(find.text('Mikky'), findsOneWidget);
    expect(find.byKey(const ValueKey('followListRow_7')), findsOneWidget);
    expect(find.byIcon(Icons.more_horiz_rounded), findsNothing);
    expect(lists.calls.first.$1, FollowListTab.followers);
  });

  testWidgets('我自己的 Mengikuti（常驻）：点 Following → 取关后该行保留、按钮变 Follow Back', (tester) async {
    final follows = _FollowRepo();
    final lists = _ListRepo({
      FollowListTab.following: [_page([_row(7, s: const FollowState(iFollow: true, followsMe: true))])],
      FollowListTab.followers: [_page([])],
    });
    await _pump(tester, profile: _owner(self: true), lists: lists, follows: follows);
    expect(_label(tester, 'following', 7), l10n.followActionFollowing);

    await tester.tap(_btn('following', 7));
    await tester.pumpAndSettle();

    expect(follows.calls, ['DELETE tok7']);
    expect(find.byKey(const ValueKey('followListRow_7')), findsOneWidget);
    expect(_label(tester, 'following', 7), l10n.followActionFollowBack);
  });

  testWidgets('别人的列表（非常驻）：已关注 / self / 我拉黑的人 / 注销行都无按钮；对方关注我显示 Follow Back',
      (tester) async {
    final lists = _ListRepo({
      FollowListTab.following: [
        _page([
          _row(7, s: const FollowState(iFollow: true, followsMe: false)),
          _row(_kMe, self: true),
          _row(8, blocked: true),
          _row(9, deleted: true),
          _row(10, s: const FollowState(iFollow: false, followsMe: true)),
          _row(11),
        ])
      ],
      FollowListTab.followers: [_page([])],
    });
    await _pump(tester, profile: _owner(), lists: lists);

    for (final id in [7, _kMe, 8, 9]) {
      expect(_btn('following', id), findsNothing, reason: 'row $id');
    }
    expect(_label(tester, 'following', 10), l10n.followActionFollowBack);
    expect(_label(tester, 'following', 11), l10n.followActionFollow);
    expect(find.text(l10n.feedDeletedUser), findsOneWidget);
  });

  testWidgets('非常驻：点 Follow → 本按钮变 Following（可立即取消）', (tester) async {
    final lists = _ListRepo({
      FollowListTab.following: [_page([_row(11)])],
      FollowListTab.followers: [_page([])],
    });
    await _pump(tester, profile: _owner(), lists: lists);

    await tester.tap(_btn('following', 11));
    await tester.pumpAndSettle();

    expect(_label(tester, 'following', 11), l10n.followActionFollowing);
  });

  testWidgets('点行进对方主页；注销行不可点', (tester) async {
    final lists = _ListRepo({
      FollowListTab.following: [_page([_row(7), _row(9, deleted: true)])],
      FollowListTab.followers: [_page([])],
    });
    final nav = await _pump(tester, profile: _owner(), lists: lists);

    expect(find.byKey(const ValueKey('followListOpen_9')), findsNothing);
    await tester.tap(find.byKey(const ValueKey('followListOpen_7')));
    await tester.pumpAndSettle();
    expect(nav.openedProfile, 7);
  });

  testWidgets('空态两种文案；follow_list_viewed 每个 Tab 只报一次且 is_empty 正确', (tester) async {
    final lists = _ListRepo({
      FollowListTab.following: [_page([])],
      FollowListTab.followers: [_page([_row(7)])],
    });
    await _pump(tester, profile: _owner(self: true), lists: lists);
    // Story 2.3：我自己的空 Mengikuti 换成提示条文案（推荐区见 follow_suggestions_test.dart）。
    expect(find.text(l10n.followListEmptySelfFollowing), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('followListTabFollowers')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('followListTabFollowing')));
    await tester.pumpAndSettle();

    final viewed = events.where((e) => e.$1 == 'follow_list_viewed').map((e) => e.$2).toList();
    expect(viewed, [
      {'tab': 'following', 'is_self': true, 'is_empty': true},
      {'tab': 'followers', 'is_self': true, 'is_empty': false},
    ]);
  });

  testWidgets('Pengikut 空态文案', (tester) async {
    final lists = _ListRepo({
      FollowListTab.following: [_page([])],
      FollowListTab.followers: [_page([])],
    });
    await _pump(tester, profile: _owner(), lists: lists, tab: FollowListTab.followers);
    expect(find.text(l10n.followListEmptyFollowers), findsOneWidget);
  });

  testWidgets('加载中显示拉黑列表同款骨架', (tester) async {
    final gate = Completer<FollowListPageData>();
    final router = GoRouter(
      initialLocation: FollowListPage.location(_kOwner, FollowListTab.following),
      routes: [
        GoRoute(path: FollowListPage.routePattern, builder: (_, s) => const FollowListPage(userId: _kOwner)),
      ],
    );
    addTearDown(router.dispose);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        publicProfileRepositoryProvider.overrideWithValue(_ProfileRepo(_owner())),
        followListRepositoryProvider.overrideWithValue(_GateRepo(gate)),
        authControllerProvider.overrideWith(_Auth.new),
      ],
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
      ),
    ));
    await tester.pump();
    await tester.pump();
    // 2.2 code-review F8：主页信息已到（Tab 栏已出）→ 这个骨架是**列表**的，不是主页加载那一个。
    expect(find.byKey(const ValueKey('followListTabFollowing')), findsOneWidget);
    expect(find.byType(BlockedListSkeleton), findsOneWidget);
    gate.complete(_page([_row(7)]));
    await tester.pumpAndSettle();
    expect(find.byType(BlockedListSkeleton), findsNothing);
    expect(find.byKey(const ValueKey('followListRow_7')), findsOneWidget);
  });

  testWidgets('失败态：不画成空态；点重试只重拉当前 Tab', (tester) async {
    final lists = _ListRepo({
      FollowListTab.following: [DioException(requestOptions: RequestOptions(path: '/x')), _page([_row(7)])],
      FollowListTab.followers: [_page([])],
    });
    await _pump(tester, profile: _owner(), lists: lists);
    expect(find.text(l10n.blockedListLoadFailed), findsOneWidget);
    expect(find.text(l10n.followListEmptyFollowing), findsNothing);

    await tester.tap(find.byKey(const ValueKey('followListRetry')));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('followListRow_7')), findsOneWidget);
    expect(lists.calls.where((c) => c.$1 == FollowListTab.followers), isEmpty);
  });

  testWidgets('主页用户已注销 / 封号 → Pengguna tidak ditemukan', (tester) async {
    await _pump(tester,
        profile: const PublicProfile(postCount: 0, likeCount: 0, isDeactivated: true, self: false),
        lists: _ListRepo({FollowListTab.following: [_page([])], FollowListTab.followers: [_page([])]}));
    expect(find.text(l10n.profileNotFound), findsOneWidget);
  });

  testWidgets('我拉黑了主页用户（403 blocked-user）→ 已拉黑空态，无重试', (tester) async {
    final req = RequestOptions(path: '/x');
    await _pump(tester,
        profile: DioException(
            requestOptions: req,
            response: Response(requestOptions: req, statusCode: 403, data: {
              'type': 'https://petgo/errors/blocked-user',
              'status': 403,
            })),
        lists: _ListRepo({FollowListTab.following: [_page([])], FollowListTab.followers: [_page([])]}));
    expect(find.text(l10n.profileBlockedEmpty), findsOneWidget);
    expect(find.byKey(const ValueKey('followListRetry')), findsNothing);
  });

  testWidgets('滚到底自动加载下一页（带游标）', (tester) async {
    final lists = _ListRepo({
      FollowListTab.following: [
        _page([for (var i = 100; i < 120; i++) _row(i)], hasMore: true, next: 'c2'),
        _page([_row(200)]),
      ],
      FollowListTab.followers: [_page([])],
    });
    await _pump(tester, profile: _owner(), lists: lists);

    await tester.drag(find.byType(ListView).first, const Offset(0, -3000));
    await tester.pumpAndSettle();

    expect(lists.calls.where((c) => c.$1 == FollowListTab.following).map((c) => c.$2), [null, 'c2']);
  });

  // ===== 2.2 code-review F8：补齐未覆盖的 AC 要点 =====

  testWidgets('AC5.1 常驻：取关后行保留，下拉刷新后该行消失', (tester) async {
    final lists = _ListRepo({
      FollowListTab.following: [
        _page([_row(7, s: const FollowState(iFollow: true, followsMe: false)), _row(8, s: const FollowState(iFollow: true, followsMe: false))]),
        _page([_row(8, s: const FollowState(iFollow: true, followsMe: false))]),
      ],
      FollowListTab.followers: [_page([])],
    });
    await _pump(tester, profile: _owner(self: true), lists: lists);
    await tester.tap(_btn('following', 7));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('followListRow_7')), findsOneWidget);

    await tester.drag(find.byKey(const ValueKey('followListRow_8')), const Offset(0, 300));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('followListRow_7')), findsNothing);
    expect(_label(tester, 'following', 8), l10n.followActionFollowing);
  });

  testWidgets('AC5.2 非常驻：在一个 Tab 关注了某人 → 另一个 Tab 里同一个人的按钮立即隐藏', (tester) async {
    final lists = _ListRepo({
      FollowListTab.following: [_page([_row(11)])],
      FollowListTab.followers: [_page([_row(11)])],
    });
    await _pump(tester, profile: _owner(), lists: lists);
    await tester.tap(find.byKey(const ValueKey('followListTabFollowers')));
    await tester.pumpAndSettle();
    expect(_btn('followers', 11), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('followListTabFollowing')));
    await tester.pumpAndSettle();
    await tester.tap(_btn('following', 11));
    await tester.pumpAndSettle();
    expect(_label(tester, 'following', 11), l10n.followActionFollowing);

    await tester.tap(find.byKey(const ValueKey('followListTabFollowers')));
    await tester.pumpAndSettle();
    expect(_btn('followers', 11), findsNothing);
    expect(find.byKey(const ValueKey('followListRow_11')), findsOneWidget);
  });

  testWidgets('AC7.1 下拉刷新 / 加载更多不重复报 follow_list_viewed；加载更多失败 → 底部重试', (tester) async {
    final lists = _ListRepo({
      FollowListTab.following: [
        _page([for (var i = 100; i < 120; i++) _row(i)], hasMore: true, next: 'c2'),
        DioException(requestOptions: RequestOptions(path: '/x')),
        _page([_row(200)]),
        _page([_row(300)]),
      ],
      FollowListTab.followers: [_page([])],
    });
    await _pump(tester, profile: _owner(), lists: lists);

    await tester.drag(find.byType(ListView).first, const Offset(0, -3000));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('followListLoadMoreRetry')), findsOneWidget);
    expect(lists.calls.where((c) => c.$2 == 'c2'), hasLength(1), reason: '失败后不自动重试（惯性滚动也不行）');

    await tester.tap(find.byKey(const ValueKey('followListLoadMoreRetry')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('followListRow_200')), findsOneWidget);

    await tester.drag(find.byType(ListView).first, const Offset(0, 5000));
    await tester.pumpAndSettle();
    await tester.drag(find.byType(ListView).first, const Offset(0, 300));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('followListRow_300')), findsOneWidget);

    expect(events.where((e) => e.$1 == 'follow_list_viewed'), hasLength(1));
  });

  testWidgets('AC4.4 自己那一行无按钮但可点进自己的主页', (tester) async {
    final lists = _ListRepo({
      FollowListTab.following: [_page([_row(_kMe, self: true)])],
      FollowListTab.followers: [_page([])],
    });
    final nav = await _pump(tester, profile: _owner(), lists: lists);

    expect(_btn('following', _kMe), findsNothing);
    await tester.tap(find.byKey(const ValueKey('followListOpen_$_kMe')));
    await tester.pumpAndSettle();
    expect(nav.openedProfile, _kMe);
  });

  testWidgets('code-review F1：游客在列表页登录 → 主页信息与列表按新身份重取', (tester) async {
    final profiles = _ProfileRepo(_owner());
    final lists = _ListRepo({
      FollowListTab.following: [
        _page([_row(_kMe), _row(7)]),
        _page([_row(_kMe, self: true), _row(7, s: const FollowState(iFollow: true, followsMe: false))]),
      ],
      FollowListTab.followers: [_page([])],
    });
    final nav = await _pump(tester, profile: profiles, lists: lists, auth: _GuestThenLogin.new);
    expect(_btn('following', _kMe), findsOneWidget, reason: '游客视角：自己那一行是普通行');
    expect(profiles.calls, 1);

    (nav.container.read(authControllerProvider.notifier) as _GuestThenLogin).login();
    await tester.pumpAndSettle();

    expect(profiles.calls, 2);
    expect(_btn('following', _kMe), findsNothing, reason: '登录后自己那一行无按钮');
    expect(_btn('following', 7), findsNothing, reason: '已关注的人在非常驻列表不显示按钮');
  });

  test('FollowListItem.fromJson：注销行缺键不崩', () {
    final i = FollowListItem.fromJson(const {'userId': 9, 'deleted': true, 'iFollow': false, 'followsMe': false});
    expect(i.deleted, isTrue);
    expect(i.userToken, isNull);
    expect(i.nickname, isNull);
    expect(i.followState, FollowState.none);
    final j = FollowListItem.fromJson(const {
      'userId': 7, 'userToken': 't', 'nickname': 'n', 'deleted': false,
      'iFollow': true, 'followsMe': true, 'blockedByMe': true, 'self': false,
    });
    expect(j.followState, const FollowState(iFollow: true, followsMe: true));
    expect(j.blockedByMe, isTrue);
  });

  test('FollowListTab.fromWire：缺省 / 非法 → following', () {
    expect(FollowListTab.fromWire(null), FollowListTab.following);
    expect(FollowListTab.fromWire('xx'), FollowListTab.following);
    expect(FollowListTab.fromWire('followers'), FollowListTab.followers);
  });
}

class _GateRepo implements FollowListRepository {
  _GateRepo(this.gate);
  final Completer<FollowListPageData> gate;

  @override
  Future<FollowListPageData> fetch(String userToken, FollowListTab tab, {String? cursor}) => gate.future;
}

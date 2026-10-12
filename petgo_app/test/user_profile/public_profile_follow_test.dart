import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/router/route_intent.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_guide_controller.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/social/data/follow_repository.dart';
import 'package:tailtopia/features/social/domain/follow_state.dart';
import 'package:tailtopia/features/user_profile/data/public_profile_pet_repository.dart';
import 'package:tailtopia/features/user_profile/data/public_profile_repository.dart';
import 'package:tailtopia/features/user_profile/data/public_user_posts_repository.dart';
import 'package:tailtopia/features/user_profile/presentation/public_profile_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.4 follow · Story 1.3 · AC4.5 / AC5：他人主页上的关注按钮与 Pengikut 即时 ±1。
const int _kViewerId = 5;
const int _kTargetId = 7;
const String _kToken = 'tok7tok7tok7tok7tok7tok7tok7tok7';

class _ProfileRepo implements PublicProfileRepository {
  _ProfileRepo(this.profile);
  PublicProfile profile;
  int calls = 0;

  @override
  Future<PublicProfile> getPublicProfile(int userId) async {
    calls++;
    return profile;
  }
}

class _EmptyPosts implements PublicUserPostsRepository {
  int calls = 0;

  @override
  Future<PublicUserPostPage> fetch(int userId, {String? cursor}) async {
    calls++;
    return PublicUserPostPage.empty;
  }
}

int _petCalls = 0;

class _Guide extends LoginGuideController {
  _Guide() : super(() async => null);
  int hardDialogs = 0;

  @override
  Future<void> showHardDialog(BuildContext context,
      {RouteIntent? pendingAction, String entrySource = 'other'}) async {
    hardDialogs++;
  }
}

class _FollowRepo implements FollowRepository {
  _FollowRepo({this.error});
  final Object? error;
  final List<String> calls = [];

  @override
  Future<FollowResult> follow(String userToken) async {
    calls.add('PUT');
    if (error != null) throw error!;
    return const FollowResult(state: FollowState(iFollow: true, followsMe: false), followerCount: 31);
  }

  @override
  Future<FollowResult> unfollow(String userToken) async {
    calls.add('DELETE');
    if (error != null) throw error!;
    return const FollowResult(state: FollowState(iFollow: false, followsMe: false), followerCount: 29);
  }
}

class _Auth extends AuthController {
  _Auth(this._initial);
  final AuthState _initial;

  @override
  AuthState build() => _initial;

  @override
  void toGuest() => state = const AuthState.guest();
}

const _loggedIn = AuthState(
  status: AuthStatus.authenticated,
  role: 'USER',
  profile: UserProfile(id: _kViewerId, nickname: 'Me', onboardingCompleted: true),
);

PublicProfile _profile({
  bool iFollow = false,
  bool followsMe = false,
  int followers = 30,
  bool self = false,
  String? token = _kToken,
}) =>
    PublicProfile(
      postCount: 15,
      likeCount: 3,
      isDeactivated: false,
      self: self,
      nickname: 'Mikky',
      userToken: token,
      followingCount: 12,
      followerCount: followers,
      iFollow: iFollow,
      followsMe: followsMe,
    );

Future<ProviderContainer> _pump(
  WidgetTester tester, {
  required _ProfileRepo profiles,
  _FollowRepo? follows,
  AuthState auth = _loggedIn,
  _EmptyPosts? posts,
  _Guide? guide,
}) async {
  tester.view.physicalSize = const Size(1080, 3200);
  tester.view.devicePixelRatio = 2.0;
  addTearDown(tester.view.reset);
  final router = GoRouter(
    initialLocation: '${PublicProfilePage.routeBase}/$_kTargetId',
    routes: [
      GoRoute(
        path: PublicProfilePage.routePattern,
        builder: (_, state) => PublicProfilePage(userId: int.parse(state.pathParameters['userId']!)),
      ),
    ],
  );
  addTearDown(router.dispose);
  final container = ProviderContainer(overrides: [
    publicProfileRepositoryProvider.overrideWithValue(profiles),
    publicUserPostsRepositoryProvider.overrideWithValue(posts ?? _EmptyPosts()),
    publicProfilePetProvider(_kTargetId).overrideWith((ref) async {
      _petCalls++;
      return null;
    }),
    if (guide != null) loginGuideControllerProvider.overrideWithValue(guide),
    followRepositoryProvider.overrideWithValue(follows ?? _FollowRepo()),
    authControllerProvider.overrideWith(() => _Auth(auth)),
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

String _followers(WidgetTester tester) => tester
    .widgetList<Text>(find.descendant(
        of: find.byKey(const ValueKey('profileStatFollowers')), matching: find.byType(Text)))
    .first
    .data!;

String _buttonLabel(WidgetTester tester) => tester
    .widget<Text>(find.descendant(of: find.byKey(const ValueKey('followButton')), matching: find.byType(Text)))
    .data!;

void main() {
  late AppLocalizations l10n;
  setUpAll(() async {
    l10n = await AppLocalizations.delegate.load(const Locale('en'));
  });

  testWidgets('他人视角：有关注按钮，四格显示四个数', (tester) async {
    await _pump(tester, profiles: _ProfileRepo(_profile()));

    expect(find.byKey(const ValueKey('followButton')), findsOneWidget);
    expect(find.byKey(const ValueKey('profileEditButton')), findsNothing);
    expect(_buttonLabel(tester), l10n.followActionFollow);
    expect(_followers(tester), '30');
  });

  testWidgets('三态：对方关注我 → Follow Back；我已关注 → Following', (tester) async {
    await _pump(tester, profiles: _ProfileRepo(_profile(followsMe: true)));
    expect(_buttonLabel(tester), l10n.followActionFollowBack);
  });

  testWidgets('老后端没有 userToken → 不渲染按钮', (tester) async {
    await _pump(tester, profiles: _ProfileRepo(_profile(token: null)));
    expect(find.byKey(const ValueKey('followButton')), findsNothing);
  });

  testWidgets('点关注 → Following、Pengikut +1；再点取关 → Follow、回到 30（T-12）', (tester) async {
    final follows = _FollowRepo();
    await _pump(tester, profiles: _ProfileRepo(_profile()), follows: follows);

    await tester.tap(find.byKey(const ValueKey('followButton')));
    await tester.pumpAndSettle();
    expect(_buttonLabel(tester), l10n.followActionFollowing);
    expect(_followers(tester), '31');

    await tester.tap(find.byKey(const ValueKey('followButton')));
    await tester.pumpAndSettle();
    expect(_buttonLabel(tester), l10n.followActionFollow);
    expect(_followers(tester), '30');
    expect(follows.calls, ['PUT', 'DELETE']);
  });

  testWidgets('已关注进来取关 → Pengikut −1', (tester) async {
    await _pump(tester, profiles: _ProfileRepo(_profile(iFollow: true, followers: 31)));

    await tester.tap(find.byKey(const ValueKey('followButton')));
    await tester.pumpAndSettle();

    expect(_followers(tester), '30');
  });

  testWidgets('失败回滚：按钮与 Pengikut 回到点击前 + 顶部 toast', (tester) async {
    final follows = _FollowRepo(error: DioException(requestOptions: RequestOptions(path: '/x')));
    await _pump(tester, profiles: _ProfileRepo(_profile()), follows: follows);

    await tester.tap(find.byKey(const ValueKey('followButton')));
    await tester.pumpAndSettle();

    expect(_buttonLabel(tester), l10n.followActionFollow);
    expect(_followers(tester), '30');
    expect(find.text(l10n.followFailed), findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('429 → 底部 toast「Too many attempts」，按钮不变', (tester) async {
    final req = RequestOptions(path: '/x');
    final follows = _FollowRepo(
        error: DioException(
            requestOptions: req,
            response: Response(requestOptions: req, statusCode: 429, data: {
              'type': 'https://petgo/errors/rate-limited',
              'status': 429,
            })));
    await _pump(tester, profiles: _ProfileRepo(_profile()), follows: follows);

    await tester.tap(find.byKey(const ValueKey('followButton')));
    await tester.pumpAndSettle();

    expect(_buttonLabel(tester), l10n.followActionFollow);
    expect(find.text(l10n.followRateLimited), findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('T-9：关注返回 404 → 重取主页，显示「用户不存在」', (tester) async {
    final req = RequestOptions(path: '/x');
    final profiles = _ProfileRepo(_profile());
    final follows = _FollowRepo(
        error: DioException(
            requestOptions: req,
            response: Response(requestOptions: req, statusCode: 404, data: {
              'type': 'https://petgo/errors/not-found',
              'status': 404,
            })));
    await _pump(tester, profiles: profiles, follows: follows);
    // 服务端此刻已把对方按不存在处理。
    profiles.profile = const PublicProfile(postCount: 0, likeCount: 0, isDeactivated: true, self: false);

    await tester.tap(find.byKey(const ValueKey('followButton')));
    await tester.pumpAndSettle();

    expect(profiles.calls, 2);
    expect(find.byKey(const ValueKey('profileDeactivated')), findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('游客点关注 → 不发请求；登录后主页按新身份重取（不自动关注）', (tester) async {
    final profiles = _ProfileRepo(_profile());
    final follows = _FollowRepo();
    final posts = _EmptyPosts();
    final guide = _Guide();
    _petCalls = 0;
    final c = await _pump(tester,
        profiles: profiles, follows: follows, auth: const AuthState.guest(), posts: posts, guide: guide);
    expect(profiles.calls, 1);
    final petBefore = _petCalls;
    final postsBefore = posts.calls;

    // 游客点关注：只弹登录，不发请求。
    await tester.tap(find.byKey(const ValueKey('followButton')));
    await tester.pumpAndSettle();
    expect(guide.hardDialogs, 1);
    expect(follows.calls, isEmpty);

    // 模拟登录成功（弹窗关闭、仍停在此页）。
    profiles.profile = _profile(followsMe: true);
    c.read(authControllerProvider.notifier).completeOnboarding(
        const UserProfile(id: _kViewerId, nickname: 'Me', onboardingCompleted: true));
    await tester.pumpAndSettle();

    expect(profiles.calls, 2);
    expect(_petCalls, greaterThan(petBefore), reason: 'AC5.6：宠物区按新身份重取');
    expect(posts.calls, greaterThan(postsBefore), reason: 'AC5.6：帖子区按新身份重取');
    expect(follows.calls, isEmpty, reason: '登录后不自动关注（FR-140.19）');
    expect(_buttonLabel(tester), l10n.followActionFollowBack);
  });
}

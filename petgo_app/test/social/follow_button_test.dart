import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/router/route_intent.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_guide_controller.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/me/data/me_counts_provider.dart';
import 'package:tailtopia/features/social/data/follow_repository.dart';
import 'package:tailtopia/features/social/domain/follow_state.dart';
import 'package:tailtopia/features/social/presentation/widgets/follow_button.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.4 follow · Story 1.3 · AC3.5 / AC5.4 / AC7：关注按钮三态、乐观更新、回滚、toast、门控、埋点。
const int _kTarget = 9;
const String _kToken = 'tok9tok9tok9tok9tok9tok9tok9tok9';

class _LoggedIn extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: 1, nickname: 'Me', onboardingCompleted: true),
      );

  /// 真 toGuest 会去反注册推送 / 登出 IM —— 测试里只要状态切换。
  @override
  void toGuest() => state = const AuthState.guest();
}

class _Guest extends AuthController {
  @override
  AuthState build() => const AuthState.guest();
}

class _Guide extends LoginGuideController {
  _Guide() : super(() async => null);
  int hardDialogs = 0;
  RouteIntent? lastPending;

  @override
  Future<void> showHardDialog(BuildContext context,
      {RouteIntent? pendingAction, String entrySource = 'other'}) async {
    hardDialogs++;
    lastPending = pendingAction;
  }
}

class _Repo implements FollowRepository {
  _Repo({this.error, this.followsMe = false});

  Object? error;
  final bool followsMe;
  final List<String> calls = [];
  Completer<void>? gate;

  Future<FollowResult> _answer(bool iFollow) async {
    if (gate != null) await gate!.future;
    if (error != null) throw error!;
    return FollowResult(
        state: FollowState(iFollow: iFollow, followsMe: followsMe), followerCount: 1);
  }

  @override
  Future<FollowResult> follow(String userToken) {
    calls.add('PUT $userToken');
    return _answer(true);
  }

  @override
  Future<FollowResult> unfollow(String userToken) {
    calls.add('DELETE $userToken');
    return _answer(false);
  }
}

DioException _http(int status, [String? slug]) {
  final req = RequestOptions(path: '/api/v1/users/$_kToken/follow');
  return DioException(
    requestOptions: req,
    response: Response<Map<String, dynamic>>(
      requestOptions: req,
      statusCode: status,
      data: {if (slug != null) 'type': 'https://petgo/errors/$slug', 'status': status},
    ),
  );
}

late AppLocalizations l10n;

Future<ProviderContainer> _pump(
  WidgetTester tester, {
  required _Repo repo,
  FollowState initial = FollowState.none,
  bool loggedIn = true,
  _Guide? guide,
}) async {
  final container = ProviderContainer(overrides: [
    followRepositoryProvider.overrideWithValue(repo),
    authControllerProvider.overrideWith(loggedIn ? _LoggedIn.new : _Guest.new),
    if (guide != null) loginGuideControllerProvider.overrideWithValue(guide),
  ]);
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp(
      locale: const Locale('en'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: Center(
          child: SizedBox(
            width: 300,
            child: FollowButton(
              userId: _kTarget,
              userToken: _kToken,
              initial: initial,
              style: FollowButtonStyle.profileFull,
              source: 'profile',
            ),
          ),
        ),
      ),
    ),
  ));
  await tester.pumpAndSettle();
  return container;
}

String _label(WidgetTester tester) => tester
    .widget<Text>(find.descendant(of: find.byKey(const ValueKey('followButton')), matching: find.byType(Text)))
    .data!;

Future<void> _tap(WidgetTester tester) async {
  await tester.tap(find.byKey(const ValueKey('followButton')));
  await tester.pump();
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

  group('三态文案与外观', () {
    testWidgets('互不关注 →「Follow」，紫色实心', (tester) async {
      await _pump(tester, repo: _Repo());
      expect(_label(tester), l10n.followActionFollow);
      expect(find.byType(FilledButton), findsOneWidget);
      expect(tester.getSize(find.byKey(const ValueKey('followButton'))).height, 40);
    });

    testWidgets('对方关注我 →「Follow Back」，紫色实心', (tester) async {
      await _pump(tester, repo: _Repo(), initial: const FollowState(iFollow: false, followsMe: true));
      expect(_label(tester), l10n.followActionFollowBack);
      expect(find.byType(FilledButton), findsOneWidget);
    });

    testWidgets('我已关注 →「Following」，白底描边，尺寸不变', (tester) async {
      await _pump(tester, repo: _Repo(), initial: const FollowState(iFollow: true, followsMe: false));
      expect(_label(tester), l10n.followActionFollowing);
      final b = tester.widget<OutlinedButton>(find.byType(OutlinedButton));
      expect(b.style!.side!.resolve({})!.color, AppColors.line);
      expect(tester.getSize(find.byKey(const ValueKey('followButton'))), const Size(300, 40));
    });
  });

  group('点击', () {
    testWidgets('关注成功：立即切到 Following，发 PUT，报 user_followed', (tester) async {
      final repo = _Repo();
      final c = await _pump(tester, repo: repo);

      await _tap(tester);
      expect(_label(tester), l10n.followActionFollowing);
      await tester.pumpAndSettle();

      expect(repo.calls, ['PUT $_kToken']);
      expect(c.read(followStateProvider)[_kTarget], const FollowState(iFollow: true, followsMe: false));
      expect(events.where((e) => e.$1 == 'user_followed').map((e) => e.$2), [
        {'target_user_id': _kTarget, 'source': 'profile', 'is_follow_back': false},
      ]);
    });

    testWidgets('回关：is_follow_back 取点击前 followsMe', (tester) async {
      await _pump(tester,
          repo: _Repo(followsMe: true), initial: const FollowState(iFollow: false, followsMe: true));

      await _tap(tester);
      await tester.pumpAndSettle();

      expect(events.single.$2!['is_follow_back'], true);
    });

    testWidgets('取关成功：不弹确认，回到 Follow Back（对方关注我），报 user_unfollowed', (tester) async {
      final repo = _Repo(followsMe: true);
      await _pump(tester, repo: repo, initial: const FollowState(iFollow: true, followsMe: true));

      await _tap(tester);
      await tester.pumpAndSettle();

      expect(find.byType(BottomSheet), findsNothing);
      expect(repo.calls, ['DELETE $_kToken']);
      expect(_label(tester), l10n.followActionFollowBack);
      expect(events.single.$1, 'user_unfollowed');
      expect(events.single.$2!['is_follow_back'], true);
    });

    testWidgets('游客：弹登录、不带 pendingAction、仓储零调用、不埋点', (tester) async {
      final repo = _Repo();
      final guide = _Guide();
      await _pump(tester, repo: repo, loggedIn: false, guide: guide);

      await _tap(tester);
      await tester.pumpAndSettle();

      expect(guide.hardDialogs, 1);
      expect(guide.lastPending, isNull, reason: '登录后不自动关注（FR-140.19）');
      expect(repo.calls, isEmpty);
      expect(events, isEmpty);
      expect(_label(tester), l10n.followActionFollow);
    });

    testWidgets('请求在途时再点直接忽略，只发一次请求', (tester) async {
      final repo = _Repo()..gate = Completer<void>();
      await _pump(tester, repo: repo);

      await _tap(tester);
      await _tap(tester);
      await _tap(tester);
      repo.gate!.complete();
      await tester.pumpAndSettle();

      expect(repo.calls, hasLength(1));
      expect(_label(tester), l10n.followActionFollowing);
    });
  });

  group('失败回滚', () {
    testWidgets('网络失败：回滚到点击前 + 顶部 toast「Couldn\'t follow」，不埋点', (tester) async {
      final repo = _Repo(error: DioException(requestOptions: RequestOptions(path: '/x')));
      final c = await _pump(tester, repo: repo);

      await _tap(tester);
      await tester.pumpAndSettle();

      expect(_label(tester), l10n.followActionFollow);
      expect(c.read(followStateProvider), isEmpty, reason: '点击前没有覆盖 → forget');
      expect(find.text(l10n.followFailed), findsOneWidget);
      expect(events, isEmpty);
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('取关失败：回到 Following + 「Couldn\'t unfollow」', (tester) async {
      final repo = _Repo(error: _http(500));
      await _pump(tester, repo: repo, initial: const FollowState(iFollow: true, followsMe: false));

      await _tap(tester);
      await tester.pumpAndSettle();

      expect(_label(tester), l10n.followActionFollowing);
      expect(find.text(l10n.followUnfollowFailed), findsOneWidget);
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('429：回滚 + 底部 toast「Too many attempts」', (tester) async {
      final repo = _Repo(error: _http(429, 'rate-limited'));
      await _pump(tester, repo: repo);

      await _tap(tester);
      await tester.pumpAndSettle();

      expect(_label(tester), l10n.followActionFollow);
      expect(find.text(l10n.followRateLimited), findsOneWidget);
      expect(find.text(l10n.followFailed), findsNothing);
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('409 follow-blocked 按网络失败处理（T-9）', (tester) async {
      final repo = _Repo(error: _http(409, 'follow-blocked'));
      await _pump(tester, repo: repo);

      await _tap(tester);
      await tester.pumpAndSettle();

      expect(_label(tester), l10n.followActionFollow);
      expect(find.text(l10n.followFailed), findsOneWidget);
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('失败时回滚到点击前已有的覆盖值（不是服务端初值）', (tester) async {
      final repo = _Repo();
      final c = await _pump(tester, repo: repo);
      await _tap(tester);
      await tester.pumpAndSettle(); // 已关注（覆盖 iFollow=true）

      repo.error = _http(500);
      await _tap(tester); // 取关失败
      await tester.pumpAndSettle();

      expect(c.read(followStateProvider)[_kTarget], const FollowState(iFollow: true, followsMe: false));
      expect(_label(tester), l10n.followActionFollowing);
      await tester.pump(const Duration(seconds: 3));
    });
  });

  /// V1.3.4 Story 1.4 · AC6.3：关注成功后我的页四格（meCountsProvider）被失效重取；失败不失效。
  testWidgets('关注成功 → meCountsProvider 重建；失败不重建', (tester) async {
    var builds = 0;
    final repo = _Repo();
    final container = ProviderContainer(overrides: [
      followRepositoryProvider.overrideWithValue(repo),
      authControllerProvider.overrideWith(_LoggedIn.new),
      meCountsProvider.overrideWith((ref, userId) async {
        builds++;
        return const MeCounts(followingCount: 1);
      }),
    ]);
    addTearDown(container.dispose);
    final sub = container.listen(meCountsProvider(1), (_, _) {});
    addTearDown(sub.close);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(
        locale: Locale('en'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: FollowButton(
            userId: _kTarget,
            userToken: _kToken,
            initial: FollowState.none,
            style: FollowButtonStyle.profileFull,
            source: 'profile',
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();
    expect(builds, 1);

    await _tap(tester);
    await tester.pumpAndSettle();
    expect(builds, 2);

    repo.error = _http(500);
    await _tap(tester);
    await tester.pumpAndSettle();
    expect(builds, 2);
    await tester.pump(const Duration(seconds: 3));
  });

  /// code-review 1.3 F2：请求在途时退成游客（401 刷新失败）→ 回滚 / 写回都不得写进新表。
  testWidgets('请求在途切换账号：旧账号的关系不写进新覆盖表', (tester) async {
    final repo = _Repo(error: _http(500))..gate = Completer<void>();
    final c = await _pump(tester, repo: repo, initial: const FollowState(iFollow: true, followsMe: false));

    await _tap(tester); // 取关在途（乐观写入 iFollow=false）
    c.read(authControllerProvider.notifier).toGuest();
    await tester.pump();
    expect(c.read(followStateProvider), isEmpty, reason: '账号切换即重建为空表');

    repo.gate!.complete();
    await tester.pumpAndSettle();
    expect(c.read(followStateProvider), isEmpty, reason: '失败回滚不得把旧账号的覆盖写进游客表');
    await tester.pump(const Duration(seconds: 3));
  });
}

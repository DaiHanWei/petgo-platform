import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/post_likers_repository.dart';
import 'package:tailtopia/features/content/presentation/post_likers_sheet.dart';
import 'package:tailtopia/features/social/data/follow_repository.dart';
import 'package:tailtopia/features/social/domain/follow_list_item.dart';
import 'package:tailtopia/features/social/domain/follow_state.dart';
import 'package:tailtopia/features/social/presentation/widgets/follow_button.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.4 点赞人列表 · Task 3：底部抽屉（半屏 / 全屏 / 关闭）、行与关注按钮、空 / 失败 / 不存在。

FollowListItem _u(int id, {bool self = false}) => FollowListItem(
    userId: id, deleted: false, nickname: 'user$id', userToken: 'tok$id', self: self);

DioException _http(int code) => DioException(
      requestOptions: RequestOptions(path: '/x'),
      response: Response(requestOptions: RequestOptions(path: '/x'), statusCode: code),
    );

class _LikersRepo implements PostLikersRepository {
  _LikersRepo(this.handler);
  final Future<FollowListPageData> Function(String? cursor) handler;
  final cursors = <String?>[];

  @override
  Future<FollowListPageData> getLikers(int postId, {String? cursor}) {
    cursors.add(cursor);
    return handler(cursor);
  }
}

class _FollowRepo implements FollowRepository {
  final calls = <String>[];

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

class _LoggedIn extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: 1, nickname: 'Me', onboardingCompleted: true),
      );
}

late AppLocalizations l10n;
final _sheet = find.byKey(const ValueKey('postLikersSheet'));

Future<ProviderContainer> _open(WidgetTester tester, _LikersRepo repo, {_FollowRepo? follow}) async {
  final container = ProviderContainer(overrides: [
    postLikersRepositoryProvider.overrideWithValue(repo),
    followRepositoryProvider.overrideWithValue(follow ?? _FollowRepo()),
    authControllerProvider.overrideWith(_LoggedIn.new),
  ]);
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp(
      locale: const Locale('en'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: Builder(
          builder: (context) => Center(
            child: TextButton(
              key: const ValueKey('open'),
              onPressed: () => showPostLikersSheet(context, postId: 9, source: 'content_detail'),
              child: const Text('open'),
            ),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.byKey(const ValueKey('open')));
  await tester.pumpAndSettle();
  return container;
}

/// 抽屉当前占屏比例。
double _extent(WidgetTester tester) {
  final screen = tester.view.physicalSize.height / tester.view.devicePixelRatio;
  return tester.getSize(_sheet).height / screen;
}

void main() {
  setUpAll(() async {
    l10n = await AppLocalizations.delegate.load(const Locale('en'));
  });

  testWidgets('sheetStartsHalfExpandsFullCollapsesAndDismisses', (tester) async {
    await _open(tester, _LikersRepo((_) async => FollowListPageData(items: [_u(2), _u(3)])));
    expect(_sheet, findsOneWidget);
    expect(find.text(l10n.postLikersTitle), findsOneWidget);
    expect(_extent(tester), closeTo(0.5, 0.01));

    // 上滑 → 全屏
    await tester.drag(find.text(l10n.postLikersTitle), const Offset(0, -400));
    await tester.pumpAndSettle();
    expect(_extent(tester), closeTo(1.0, 0.01));

    // 全屏下拉 → 回半屏
    await tester.drag(find.text(l10n.postLikersTitle), const Offset(0, 200));
    await tester.pumpAndSettle();
    expect(_extent(tester), closeTo(0.5, 0.01));

    // 半屏再下拉 → 关闭
    await tester.drag(find.text(l10n.postLikersTitle), const Offset(0, 150));
    await tester.pumpAndSettle();
    expect(_sheet, findsNothing);
    expect(find.byKey(const ValueKey('open')), findsOneWidget, reason: '只关抽屉，不多 pop 底下的页面');
  });

  testWidgets('smallDragDownStaysHalf', (tester) async {
    await _open(tester, _LikersRepo((_) async => FollowListPageData(items: [_u(2)])));
    await tester.drag(find.text(l10n.postLikersTitle), const Offset(0, 20));
    await tester.pumpAndSettle();
    expect(_sheet, findsOneWidget);
    expect(_extent(tester), closeTo(0.5, 0.01));
  });

  testWidgets('tapBarrierDismisses', (tester) async {
    await _open(tester, _LikersRepo((_) async => FollowListPageData(items: [_u(2)])));
    await tester.tapAt(const Offset(400, 50));
    await tester.pumpAndSettle();
    expect(_sheet, findsNothing);
  });

  testWidgets('backDismisses', (tester) async {
    await _open(tester, _LikersRepo((_) async => FollowListPageData(items: [_u(2)])));
    final nav = tester.state<NavigatorState>(find.byType(Navigator).first);
    await nav.maybePop();
    await tester.pumpAndSettle();
    expect(_sheet, findsNothing);
  });

  testWidgets('rowsShowFollowButtonExceptSelf', (tester) async {
    await _open(tester, _LikersRepo((_) async => FollowListPageData(items: [_u(2), _u(1, self: true), _u(3)])));
    for (final id in [2, 3]) {
      expect(
          find.descendant(
              of: find.byKey(ValueKey('postLikersRow_$id')), matching: find.byType(FollowButton)),
          findsOneWidget);
    }
    expect(find.byKey(const ValueKey('postLikersRow_1')), findsOneWidget);
    expect(
        find.descendant(
            of: find.byKey(const ValueKey('postLikersRow_1')), matching: find.byType(FollowButton)),
        findsNothing);
    // 顺序照接口（点赞时间倒序），自己不置顶。
    final ys = [for (final id in [2, 1, 3]) tester.getTopLeft(find.byKey(ValueKey('postLikersRow_$id'))).dy];
    expect(ys, orderedEquals([...ys]..sort()));
  });

  testWidgets('followInSheetSyncsGlobalState', (tester) async {
    final events = <(String, Map<String, Object>?)>[];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
    addTearDown(() => Analytics.debugCaptureSink = null);
    final follow = _FollowRepo();
    final c = await _open(tester, _LikersRepo((_) async => FollowListPageData(items: [_u(7)])), follow: follow);

    await tester.tap(find.descendant(
        of: find.byKey(const ValueKey('postLikersRow_7')), matching: find.byType(FollowButton)));
    await tester.pumpAndSettle();
    expect(follow.calls, ['PUT tok7']);
    expect(c.read(followStateProvider)[7]?.iFollow, isTrue, reason: '写入全局关注状态，Feed / 详情同步');
    // 非常驻位置：本按钮保留「Following」可立即取关。
    expect(
        find.descendant(
            of: find.byKey(const ValueKey('postLikersRow_7')),
            matching: find.text(l10n.followActionFollowing)),
        findsOneWidget);
    final followed = events.where((e) => e.$1 == 'user_followed').map((e) => e.$2!['source']);
    expect(followed, ['likers']);
  });

  testWidgets('emptyState', (tester) async {
    await _open(tester, _LikersRepo((_) async => const FollowListPageData(items: [])));
    expect(find.text(l10n.postLikersEmpty), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsNothing);
  });

  testWidgets('errorThenRetry', (tester) async {
    var fail = true;
    await _open(tester, _LikersRepo((_) async {
      if (fail) throw _http(500);
      return FollowListPageData(items: [_u(2)]);
    }));
    expect(find.text(l10n.blockedListLoadFailed), findsOneWidget);
    fail = false;
    await tester.tap(find.byKey(const ValueKey('postLikersRetry')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('postLikersRow_2')), findsOneWidget);
  });

  testWidgets('goneShowsToastAndCloses', (tester) async {
    await _open(tester, _LikersRepo((_) async => throw _http(404)));
    expect(_sheet, findsNothing);
    expect(find.text(l10n.detailGoneTitle), findsOneWidget);
    expect(find.byKey(const ValueKey('open')), findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();
  });

  testWidgets('goneMidListShowsToastAndCloses', (tester) async {
    await _open(
        tester,
        _LikersRepo((cur) async => cur == null
            ? FollowListPageData(items: [for (var i = 10; i < 30; i++) _u(i)], nextCursor: 'c1', hasMore: true)
            : throw _http(404)));
    await tester.drag(find.text(l10n.postLikersTitle), const Offset(0, -400));
    await tester.pumpAndSettle();
    await tester.fling(find.byKey(const ValueKey('postLikersRow_12')), const Offset(0, -2000), 3000);
    await tester.pumpAndSettle();
    expect(_sheet, findsNothing);
    expect(find.text(l10n.detailGoneTitle), findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();
  });

  testWidgets('loadsMoreOnScroll', (tester) async {
    final repo = _LikersRepo((cur) async => cur == null
        ? FollowListPageData(items: [for (var i = 10; i < 30; i++) _u(i)], nextCursor: 'c1', hasMore: true)
        : FollowListPageData(items: [_u(99)]));
    await _open(tester, repo);
    await tester.drag(find.text(l10n.postLikersTitle), const Offset(0, -400));
    await tester.pumpAndSettle();
    await tester.fling(find.byKey(const ValueKey('postLikersRow_12')), const Offset(0, -2000), 3000);
    await tester.pumpAndSettle();
    expect(repo.cursors, [null, 'c1']);
    expect(find.byKey(const ValueKey('postLikersRow_99')), findsOneWidget);
  });
}

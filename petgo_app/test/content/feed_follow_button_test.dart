import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/router/route_intent.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_guide_controller.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/features/social/data/follow_repository.dart';
import 'package:tailtopia/features/social/domain/follow_state.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/masonry_card.dart';

/// V1.3.4 follow · Story 2.1 · AC5 / AC7 / AC8 / AC9：Feed 卡作者行的「+ Ikuti」与跨卡一致。
const int _kMe = 1;
const int _kAuthor = 7;
const String _kToken = 'tok7tok7tok7tok7tok7tok7tok7tok7';

class _LoggedIn extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: _kMe, nickname: 'Me', onboardingCompleted: true),
      );
}

class _Guest extends AuthController {
  @override
  AuthState build() => const AuthState.guest();
}

class _Guide extends LoginGuideController {
  _Guide() : super(() async => null);
  int hardDialogs = 0;

  @override
  Future<void> showHardDialog(BuildContext context,
      {RouteIntent? pendingAction, String entrySource = 'other'}) async {
    hardDialogs++;
  }
}

class _Repo implements FollowRepository {
  _Repo({this.error});
  final Object? error;
  final List<String> calls = [];

  @override
  Future<FollowResult> follow(String userToken) async {
    calls.add('PUT');
    if (error != null) throw error!;
    return const FollowResult(state: FollowState(iFollow: true, followsMe: false), followerCount: 1);
  }

  @override
  Future<FollowResult> unfollow(String userToken) async {
    calls.add('DELETE');
    if (error != null) throw error!;
    return const FollowResult(state: FollowState.none, followerCount: 0);
  }
}

FeedItem _item(int id,
        {int authorId = _kAuthor,
        String? token = _kToken,
        FollowState follow = FollowState.none,
        int epoch = 0,
        String nickname = 'Alice'}) =>
    FeedItem(
      id: id,
      authorId: authorId,
      authorDeleted: false,
      authorNickname: nickname,
      type: 'KNOWLEDGE',
      body: 'post $id',
      createdAt: DateTime.now().subtract(const Duration(hours: 1)),
      authorToken: token,
      authorFollowState: follow,
      followEpoch: epoch,
    );

class _Taps {
  int author = 0;
  int card = 0;
}

late AppLocalizations l10n;

Future<ProviderContainer> _pump(
  WidgetTester tester,
  List<(FeedItem, String)> cards, {
  _Repo? repo,
  bool loggedIn = true,
  _Guide? guide,
  _Taps? taps,
  double width = 400,
}) async {
  tester.view.physicalSize = Size(width * 2, 2400);
  tester.view.devicePixelRatio = 2.0;
  addTearDown(tester.view.reset);
  final container = ProviderContainer(overrides: [
    followRepositoryProvider.overrideWithValue(repo ?? _Repo()),
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
        body: SingleChildScrollView(
          child: Column(children: [
            for (final (item, key) in cards)
              MasonryCard(
                item: item,
                deletedUserLabel: 'deleted',
                followSurfaceKey: key,
                onAuthorTap: () => taps?.author++,
                onTap: () => taps?.card++,
                onMore: () {},
              ),
          ]),
        ),
      ),
    ),
  ));
  await tester.pumpAndSettle();
  return container;
}

Finder _btn(String key) => find.byKey(ValueKey('followInline_$key'));

String _label(WidgetTester tester, String key) => tester
    .widgetList<Text>(find.descendant(of: _btn(key), matching: find.byType(Text)))
    .map((t) => t.data)
    .join(' ');

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

  group('作者行布局（AC5 / AC9.1）', () {
    testWidgets('类型徽章在时间那一行、时间右侧；关注按钮在「…」左边', (tester) async {
      await _pump(tester, [(_item(1), 'feed:item:1')]);
      final time = find.text('1h');
      final timeFinder = time.evaluate().isEmpty ? find.textContaining('1') : time;
      final badge = find.byKey(const ValueKey('feedCardTypeBadge_1'));
      expect(badge, findsOneWidget);
      final badgeRect = tester.getRect(badge);
      final nameRect = tester.getRect(find.text('Alice'));
      expect(badgeRect.top, greaterThan(nameRect.bottom - 1), reason: '徽章不在昵称行');
      expect(timeFinder, findsWidgets);
      expect(tester.getRect(_btn('feed:item:1')).right,
          lessThanOrEqualTo(tester.getRect(find.byKey(const ValueKey('feedCardMore_1'))).left + 0.5));
      expect(_label(tester, 'feed:item:1'), '+ ${l10n.followActionFollow}');
    });

    testWidgets('320 宽 + 长昵称 + 「+ Follow Back」不溢出', (tester) async {
      await _pump(tester,
          [(_item(1, follow: const FollowState(iFollow: false, followsMe: true),
              nickname: 'A very very long nickname for a pet account'), 'feed:item:1')],
          width: 320);
      expect(tester.takeException(), isNull);
      expect(_label(tester, 'feed:item:1'), '+ ${l10n.followActionFollowBack}');
      expect(find.byKey(const ValueKey('feedCardMore_1')), findsOneWidget);
    });

    testWidgets('自己的帖子、已关注作者、无 token（注销 / 封号）都不显示按钮', (tester) async {
      await _pump(tester, [
        (_item(1, authorId: _kMe), 'feed:item:1'),
        (_item(2, follow: const FollowState(iFollow: true, followsMe: false)), 'feed:item:2'),
        (_item(3, token: null), 'feed:item:3'),
      ]);
      expect(_btn('feed:item:1'), findsNothing);
      expect(_btn('feed:item:2'), findsNothing);
      expect(_btn('feed:item:3'), findsNothing);
    });
  });

  group('点击（AC4 / AC9.1）', () {
    testWidgets('点关注不触发进主页 / 进详情；成功上报 user_followed source=feed_card', (tester) async {
      final taps = _Taps();
      final repo = _Repo();
      await _pump(tester, [(_item(1), 'feed:item:1')], repo: repo, taps: taps);

      await tester.tap(_btn('feed:item:1'));
      await tester.pumpAndSettle();

      expect(taps.author, 0);
      expect(taps.card, 0);
      expect(repo.calls, ['PUT']);
      expect(_label(tester, 'feed:item:1'), l10n.followActionFollowing);
      expect(events.where((e) => e.$1 == 'user_followed').map((e) => e.$2), [
        {'target_user_id': _kAuthor, 'source': 'feed_card', 'is_follow_back': false},
      ]);
    });

    testWidgets('游客点按钮 → 登录弹窗、不发请求', (tester) async {
      final repo = _Repo();
      final guide = _Guide();
      await _pump(tester, [(_item(1), 'feed:item:1')], repo: repo, loggedIn: false, guide: guide);

      await tester.tap(_btn('feed:item:1'));
      await tester.pumpAndSettle();

      expect(guide.hardDialogs, 1);
      expect(repo.calls, isEmpty);
    });

    testWidgets('失败 → 回滚 + 提示', (tester) async {
      final repo = _Repo(error: DioException(requestOptions: RequestOptions(path: '/x')));
      await _pump(tester, [(_item(1), 'feed:item:1'), (_item(2), 'feed:item:2')], repo: repo);

      await tester.tap(_btn('feed:item:1'));
      await tester.pumpAndSettle();

      expect(_label(tester, 'feed:item:1'), '+ ${l10n.followActionFollow}');
      expect(_btn('feed:item:2'), findsOneWidget, reason: '回滚后其他卡也恢复');
      expect(find.text(l10n.followFailed), findsOneWidget);
      await tester.pump(const Duration(seconds: 3));
    });
  });

  group('跨卡一致（AC7 / AC9.2 / AC9.3）', () {
    testWidgets('同一作者两张卡：点第一张 → 第一张 Following、第二张立即隐藏；取关后第二张恢复', (tester) async {
      await _pump(tester, [(_item(1), 'feed:item:1'), (_item(2), 'feed:item:2')]);

      await tester.tap(_btn('feed:item:1'));
      await tester.pump();
      expect(_label(tester, 'feed:item:1'), l10n.followActionFollowing);
      expect(_btn('feed:item:2'), findsNothing);
      await tester.pumpAndSettle();

      await tester.tap(_btn('feed:item:1'));
      await tester.pumpAndSettle();
      expect(_label(tester, 'feed:item:1'), '+ ${l10n.followActionFollow}');
      expect(_btn('feed:item:2'), findsOneWidget);
    });

    testWidgets('下拉刷新（新快照 iFollow=true、版本号更新）后两张都没有按钮', (tester) async {
      final c = await _pump(tester, [(_item(1), 'feed:item:1'), (_item(2), 'feed:item:2')]);
      await tester.tap(_btn('feed:item:1'));
      await tester.pumpAndSettle();

      final epoch = c.read(followStateProvider.notifier).currentEpoch;
      const followed = FollowState(iFollow: true, followsMe: false);
      await tester.pumpWidget(UncontrolledProviderScope(
        container: c,
        child: MaterialApp(
          locale: const Locale('en'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Scaffold(
            body: Column(children: [
              for (final id in [1, 2])
                MasonryCard(
                  item: _item(id, follow: followed, epoch: epoch),
                  deletedUserLabel: 'deleted',
                  followSurfaceKey: 'feed:item:$id',
                ),
            ]),
          ),
        ),
      ));
      await tester.pumpAndSettle();

      expect(_btn('feed:item:1'), findsNothing);
      expect(_btn('feed:item:2'), findsNothing);
    });

    testWidgets('顶置卡与普通卡同一帖：surfaceKey 不同；在顶置卡上关注，普通卡隐藏', (tester) async {
      await _pump(tester, [(_item(1), 'feed:pinned:1'), (_item(1), 'feed:item:1')]);
      expect(_btn('feed:pinned:1'), findsOneWidget);
      expect(_btn('feed:item:1'), findsOneWidget);

      await tester.tap(_btn('feed:pinned:1'));
      await tester.pumpAndSettle();

      expect(_label(tester, 'feed:pinned:1'), l10n.followActionFollowing);
      expect(_btn('feed:item:1'), findsNothing);
    });
  });
}

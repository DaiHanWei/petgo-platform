import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/detail_repository.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/notify/data/notification_repository.dart';
import 'package:tailtopia/features/notify/domain/notification_item.dart';
import 'package:tailtopia/features/notify/presentation/notification_center_page.dart';
import 'package:tailtopia/features/social/data/follow_repository.dart';
import 'package:tailtopia/features/social/domain/follow_state.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.4 follow · Story 3.4：通知中心改版（五段分组、后端文案条目、圆点、新图标、回关、跳转、埋点）。
class _Repo extends NotificationRepository {
  _Repo(this.pages) : super(dio: Dio());

  /// 每次拉首页依次返回（只剩一个时一直返回它）。
  final List<List<NotificationItem>> pages;
  final List<String> markReadTokens = [];

  @override
  Future<NotificationPage> list({String? cursor, int limit = 20}) async {
    final items = pages.length > 1 ? pages.removeAt(0) : pages.first;
    return NotificationPage(items: items, hasMore: false);
  }

  @override
  Future<int> unreadCount() async => 0;

  @override
  Future<void> markRead(String token) async => markReadTokens.add(token);

  @override
  Future<void> markAllRead() async {}
}

class _Detail implements DetailRepository {
  _Detail({this.gone = false, this.gate});
  final bool gone;
  final Completer<void>? gate;
  final List<int> probed = [];

  @override
  Future<ContentDetail> getDetail(int id) async {
    probed.add(id);
    if (gate != null) await gate!.future;
    throw ContentLoadError(gone ? ContentLoadErrorKind.gone : ContentLoadErrorKind.network);
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Follow implements FollowRepository {
  final List<String> calls = [];

  @override
  Future<FollowResult> follow(String userToken) async {
    calls.add('PUT $userToken');
    return const FollowResult(state: FollowState(iFollow: true, followsMe: true), followerCount: 1);
  }

  @override
  Future<FollowResult> unfollow(String userToken) async {
    calls.add('DELETE $userToken');
    return const FollowResult(state: FollowState(iFollow: false, followsMe: true), followerCount: 0);
  }
}

class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: 1, nickname: 'Me', onboardingCompleted: true),
      );
}

class _Nav {
  String? pushed;
}

late AppLocalizations l10n;

DateTime _daysAgo(int d) {
  final n = DateTime.now();
  return DateTime(n.year, n.month, n.day - d, 12).toUtc();
}

NotificationItem _followed({
  String token = 'f1',
  bool read = false,
  bool? iFollow = false,
  bool? followsMe = true,
  int userId = 42,
}) =>
    NotificationItem(
      type: 'USER_FOLLOWED',
      deepLinkType: 'USER_FOLLOWED',
      deepLinkToken: token,
      targetRef: '$userId',
      title: 'Rani started following you',
      read: read,
      createdAt: _daysAgo(0),
      actorName: 'Rani',
      actorCount: 1,
      actorUserId: userId,
      actorToken: 'tok$userId',
      actorIFollow: iFollow,
      actorFollowsMe: followsMe,
    );

Future<({ProviderContainer container, _Nav nav})> _pump(
  WidgetTester tester,
  _Repo repo, {
  _Detail? detail,
  _Follow? follows,
}) async {
  final nav = _Nav();
  GoRoute sink(String path) => GoRoute(
        path: path,
        builder: (_, s) {
          nav.pushed = s.uri.toString();
          return const Scaffold(body: Text('target'));
        },
      );
  final router = GoRouter(
    initialLocation: '/notifications',
    routes: [
      GoRoute(path: '/notifications', builder: (_, _) => const NotificationCenterPage()),
      sink('/users/:userId'),
      sink('/content/:id'),
      sink('/places/:token'),
    ],
  );
  addTearDown(router.dispose);
  final container = ProviderContainer(overrides: [
    notificationRepositoryProvider.overrideWithValue(repo),
    detailRepositoryProvider.overrideWithValue(detail ?? _Detail()),
    followRepositoryProvider.overrideWithValue(follows ?? _Follow()),
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
  await tester.pumpAndSettle();
  return (container: container, nav: nav);
}

Finder _followBtn(String token) => find.byKey(ValueKey('followList_notification:$token'));

String _btnLabel(WidgetTester tester, String token) =>
    tester.widget<Text>(find.descendant(of: _followBtn(token), matching: find.byType(Text))).data!;

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

  testWidgets('AC1：五段分组按顺序出现，空组不显示', (tester) async {
    NotificationItem sys(String t, int d) =>
        NotificationItem(type: 'VET_REPLY', deepLinkToken: t, read: true, createdAt: _daysAgo(d));
    await _pump(tester, _Repo([
      [sys('a', 0), sys('b', 1), sys('c', 3), sys('d', 10), sys('e', 40)],
    ]));

    final keys = ['today', 'yesterday', 'last7Days', 'last30Days', 'earlier'];
    final ys = [for (final k in keys) tester.getTopLeft(find.byKey(ValueKey('notificationGroup_$k'))).dy];
    expect(ys, orderedEquals([...ys]..sort()));
    expect(find.text(l10n.notifyGroupYesterday), findsOneWidget);
    expect(find.text(l10n.notifyGroupLast7Days), findsOneWidget);
    expect(find.text(l10n.notifyGroupLast30Days), findsOneWidget);
  });

  testWidgets('AC1：空组不显示', (tester) async {
    await _pump(tester, _Repo([
      [NotificationItem(type: 'VET_REPLY', deepLinkToken: 'a', read: true, createdAt: _daysAgo(0))],
    ]));
    expect(find.byKey(const ValueKey('notificationGroup_today')), findsOneWidget);
    expect(find.byKey(const ValueKey('notificationGroup_yesterday')), findsNothing);
    expect(find.byKey(const ValueKey('notificationGroup_earlier')), findsNothing);
  });

  testWidgets('AC3：后端文案条目只加粗昵称、无正文行；存量点赞仍走本地文案、界面无中文', (tester) async {
    await _pump(tester, _Repo([
      [
        NotificationItem(
          type: 'CONTENT_LIKED',
          deepLinkType: 'CONTENT_LIKED',
          deepLinkToken: 'l1',
          targetRef: '55',
          title: 'Rani and 2 others liked your post',
          read: true,
          createdAt: _daysAgo(0),
          actorName: 'Rani',
          actorCount: 3,
          actorUserId: 9,
          actorToken: 'tok9',
        ),
        NotificationItem(
          type: 'CONTENT_LIKED',
          deepLinkType: 'CONTENT_LIKED',
          deepLinkToken: 'old',
          targetRef: '56',
          title: '有人赞了你的内容',
          body: '点击查看',
          read: true,
          createdAt: _daysAgo(0),
        ),
      ],
    ]));

    final rich = tester.widget<Text>(find.byKey(const ValueKey('notificationTitle_l1')));
    final spans = (rich.textSpan! as TextSpan).children!.cast<TextSpan>();
    final bold = spans.where((s) => s.style?.fontWeight == FontWeight.w700).toList();
    expect(bold.single.text, 'Rani');
    expect(spans.map((s) => s.text).join(), 'Rani and 2 others liked your post');
    expect(rich.style!.fontWeight, FontWeight.w400);
    expect(find.byKey(const ValueKey('notificationBody_l1')), findsNothing, reason: '点赞无正文行');
    // 存量（无 actorName）
    expect(find.text(l10n.notifyTypeContentLiked), findsOneWidget);
    expect(find.textContaining('有人'), findsNothing);
    expect(find.textContaining('点击查看'), findsNothing);
  });

  testWidgets('AC3.4：评论正文单行省略、无「展开」；昵称在标题里找不到时整句常规字重', (tester) async {
    await _pump(tester, _Repo([
      [
        NotificationItem(
          type: 'CONTENT_COMMENTED',
          deepLinkType: 'CONTENT_COMMENTED',
          deepLinkToken: 'c1',
          targetRef: '55',
          title: 'Someone commented on your post',
          body: 'Lucu banget anabulnya ' * 20,
          read: true,
          createdAt: _daysAgo(0),
          actorName: 'Rani',
          actorCount: 1,
          actorUserId: 9,
          actorToken: 'tok9',
        ),
      ],
    ]));

    final body = tester.widget<Text>(find.byKey(const ValueKey('notificationBody_c1')));
    expect(body.maxLines, 1);
    expect(body.overflow, TextOverflow.ellipsis);
    expect(find.text(l10n.notificationExpand), findsNothing);
    final title = tester.widget<Text>(find.byKey(const ValueKey('notificationTitle_c1')));
    expect((title.textSpan! as TextSpan).children, isNull, reason: '找不到昵称 → 整句一个 span');
  });

  testWidgets('AC4：未读圆点在图标右上角（不在条目最右）；已读无圆点', (tester) async {
    await _pump(tester, _Repo([
      [
        NotificationItem(type: 'VET_REPLY', deepLinkToken: 'u1', read: false, createdAt: _daysAgo(0)),
        NotificationItem(type: 'VET_REPLY', deepLinkToken: 'r1', read: true, createdAt: _daysAgo(0)),
      ],
    ]));

    final dot = find.byKey(const ValueKey('notificationUnreadDot_u1'));
    expect(dot, findsOneWidget);
    expect(find.byKey(const ValueKey('notificationUnreadDot_r1')), findsNothing);
    final tile = tester.getRect(find.byKey(const ValueKey('notification_u1')));
    final dotRect = tester.getRect(dot);
    expect(dotRect.left - tile.left, lessThan(70), reason: '贴在 40×40 图标块右上角（左内边距 12）');
    expect(dotRect.top - tile.top, lessThan(16));
    // 右上角而不是左上角：圆点右缘与图标块右缘（左内边距 12 + 宽 40）对齐（越界 2px）。
    expect(dotRect.right - tile.left, closeTo(54, 1));
  });

  testWidgets('AC5：两种新类型图标', (tester) async {
    await _pump(tester, _Repo([
      [
        _followed(read: true, iFollow: true),
        NotificationItem(
          type: 'PLACE_COMMENTED',
          deepLinkType: 'PLACE_COMMENTED',
          deepLinkToken: 'p1',
          targetRef: 'placeTok',
          title: 'Rani commented on the place you added',
          read: true,
          createdAt: _daysAgo(0),
          actorName: 'Rani',
          actorCount: 1,
        ),
      ],
    ]));

    final person = tester.widget<Icon>(find.byIcon(Icons.person_add_rounded));
    expect(person.color, AppColors.mint);
    final pin = tester.widget<Icon>(find.byIcon(Icons.location_on_rounded));
    expect(pin.color, AppColors.checkinIcon);
    Color? blockColor(IconData icon) => (tester
            .widget<Container>(find.ancestor(of: find.byIcon(icon), matching: find.byType(Container)).first)
            .decoration! as BoxDecoration)
        .color;
    expect(blockColor(Icons.person_add_rounded), AppColors.violet100);
    expect(blockColor(Icons.location_on_rounded), AppColors.checkinTint);
  });

  testWidgets('AC6：回关按钮三态', (tester) async {
    await _pump(tester, _Repo([
      [
        _followed(token: 'a', userId: 41),
        _followed(token: 'b', userId: 42, followsMe: false),
        _followed(token: 'c', userId: 43, iFollow: true),
      ],
    ]));

    expect(_btnLabel(tester, 'a'), l10n.followActionFollowBack);
    expect(_btnLabel(tester, 'b'), l10n.followActionFollow);
    expect(_followBtn('c'), findsNothing, reason: '我已关注 → 不显示');
    // code-review F6：隐藏时不留间距 —— 已关注那一行的标题与无按钮的系统行一样宽。
    expect(find.descendant(of: find.byKey(const ValueKey('notification_c')), matching: find.byType(Padding)),
        findsWidgets);
    double contentWidth(String t) => tester
        .getSize(find.ancestor(of: find.byKey(ValueKey('notificationTitle_$t')), matching: find.byType(Expanded)).first)
        .width;
    expect(contentWidth('c'), greaterThan(contentWidth('a')));
    expect(contentWidth('c') - contentWidth('a'), greaterThan(88), reason: '隐藏时按钮与间距都不占位');
  });

  testWidgets('AC6.4：未读行按钮白底，已读行默认浅紫', (tester) async {
    await _pump(tester, _Repo([
      [_followed(token: 'u', userId: 41), _followed(token: 'r', userId: 42, read: true)],
    ]));

    ButtonStyle style(String token) => tester
        .widget<FilledButton>(find.descendant(of: _followBtn(token), matching: find.byType(FilledButton)))
        .style!;
    expect(style('u').backgroundColor!.resolve(<WidgetState>{}), AppColors.card);
    expect(style('u').side!.resolve(<WidgetState>{})!.color, AppColors.lineViolet,
        reason: 'UI 稿 .ni.un .fbtn 白底 + 淡紫描边（code-review F7）');
    expect(style('r').backgroundColor!.resolve(<WidgetState>{}), AppColors.violet100);
    expect(style('r').side, isNull);
  });

  testWidgets('AC6.3 / 6.7 / AC8.2：点回关 → 关注、变 Following 可再点，不跳转、不标已读、不报条目点击',
      (tester) async {
    final follows = _Follow();
    final repo = _Repo([
      [_followed(token: 'f1', userId: 42)],
    ]);
    final r = await _pump(tester, repo, follows: follows);

    await tester.tap(_followBtn('f1'));
    await tester.pumpAndSettle();

    expect(follows.calls, ['PUT tok42']);
    expect(_btnLabel(tester, 'f1'), l10n.followActionFollowing);
    expect(r.nav.pushed, isNull);
    expect(repo.markReadTokens, isEmpty);
    final names = [for (final e in events) e.$1];
    expect(names, contains('notification_follow_back_tapped'));
    expect(names, isNot(contains('app_notification_item_tapped')));
    final followed = events.firstWhere((e) => e.$1 == 'user_followed').$2!;
    expect(followed['source'], 'notification');
    expect(followed['is_follow_back'], isTrue);

    await tester.tap(_followBtn('f1'));
    await tester.pumpAndSettle();
    expect(follows.calls, ['PUT tok42', 'DELETE tok42']);
    expect(events.where((e) => e.$1 == 'notification_follow_back_tapped'), hasLength(1),
        reason: '取关不报回关点击');
  });

  testWidgets('AC6.6：重新进入后按新快照显示 —— 已回关的条目不再显示按钮', (tester) async {
    final repo = _Repo([
      [_followed(token: 'f1', userId: 42)],
      [_followed(token: 'f1', userId: 42, iFollow: true)],
    ]);
    final r = await _pump(tester, repo);
    await tester.tap(_followBtn('f1'));
    await tester.pumpAndSettle();
    expect(_btnLabel(tester, 'f1'), l10n.followActionFollowing);

    // 重新进入：同一个 ProviderContainer（全局覆盖表还在），新的页面实例重拉首页。
    await tester.pumpWidget(UncontrolledProviderScope(
      container: r.container,
      child: const MaterialApp(
        locale: Locale('en'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: NotificationCenterPage(key: ValueKey('again')),
      ),
    ));
    await tester.pumpAndSettle();

    expect(_followBtn('f1'), findsNothing);
  });

  testWidgets('AC7.2 / AC8.1：点新粉丝整行 → 进对方主页（actorUserId），埋点带 actor_count', (tester) async {
    final repo = _Repo([
      [_followed(token: 'f1', userId: 42)],
    ]);
    final r = await _pump(tester, repo);

    await tester.tap(find.byKey(const ValueKey('notification_f1')));
    await tester.pumpAndSettle();

    expect(r.nav.pushed, startsWith('/users/42'));
    expect(repo.markReadTokens, ['f1']);
    final tap = events.firstWhere((e) => e.$1 == 'app_notification_item_tapped').$2!;
    expect(tap['notif_type'], 'user_followed');
    expect(tap['actor_count'], 1);
    // 3.4 code-review F1 / T-21：走 openUserProfile，报主页浏览 from=avatar。
    final viewed = events.firstWhere((e) => e.$1 == 'user_profile_viewed').$2!;
    expect(viewed, {'target_user_id': 42, 'from': 'avatar'});
  });

  testWidgets('AC7.2：actorUserId 缺失时退回 targetRef；非后端文案条目不带 actor_count', (tester) async {
    final r = await _pump(tester, _Repo([
      [
        NotificationItem(
          type: 'USER_FOLLOWED',
          deepLinkType: 'USER_FOLLOWED',
          deepLinkToken: 'f9',
          targetRef: '77',
          read: false,
          createdAt: _daysAgo(0),
        ),
      ],
    ]));

    await tester.tap(find.byKey(const ValueKey('notification_f9')));
    await tester.pumpAndSettle();

    expect(r.nav.pushed, startsWith('/users/77'));
    final tap = events.firstWhere((e) => e.$1 == 'app_notification_item_tapped').$2!;
    expect(tap.containsKey('actor_count'), isFalse);
  });

  testWidgets('探测进行中再点任何一行都忽略：只上报一次条目点击', (tester) async {
    final gate = Completer<void>();
    final r = await _pump(tester, detail: _Detail(gate: gate), _Repo([
      [
        NotificationItem(type: 'CONTENT_LIKED', deepLinkType: 'CONTENT_LIKED', deepLinkToken: 'x1',
            targetRef: '81', read: false, createdAt: _daysAgo(0)),
      ],
    ]));

    await tester.tap(find.byKey(const ValueKey('notification_x1')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('notification_x1')));
    await tester.pump();
    gate.complete();
    await tester.pumpAndSettle();

    expect(events.where((e) => e.$1 == 'app_notification_item_tapped'), hasLength(1));
    expect(r.nav.pushed, '/content/81');
  });

  testWidgets('AC7.1：点场所评论 → 场所详情', (tester) async {
    final r = await _pump(tester, _Repo([
      [
        NotificationItem(
          type: 'PLACE_COMMENTED',
          deepLinkType: 'PLACE_COMMENTED',
          deepLinkToken: 'p1',
          targetRef: 'placeTok',
          title: 'Rani commented on the place you added',
          read: false,
          createdAt: _daysAgo(0),
          actorName: 'Rani',
          actorCount: 1,
        ),
      ],
    ]));

    await tester.tap(find.byKey(const ValueKey('notification_p1')));
    await tester.pumpAndSettle();

    expect(r.nav.pushed, '/places/placeTok');
  });

  testWidgets('AC7.4 / T-43：帖子已删 → toast、不跳转，已读照常标；其它错误照常跳', (tester) async {
    NotificationItem like(String token, String postId) => NotificationItem(
          type: 'CONTENT_LIKED',
          deepLinkType: 'CONTENT_LIKED',
          deepLinkToken: token,
          targetRef: postId,
          read: false,
          createdAt: _daysAgo(0),
        );
    final detail = _Detail(gone: true);
    final repo = _Repo([
      [like('g', '77')],
    ]);
    final r = await _pump(tester, repo, detail: detail);

    await tester.tap(find.byKey(const ValueKey('notification_g')));
    await tester.pump();
    await tester.pump();
    expect(find.text(l10n.detailGoneTitle), findsOneWidget);
    await tester.pumpAndSettle(const Duration(seconds: 4));
    expect(detail.probed, [77]);
    expect(r.nav.pushed, isNull);
    expect(repo.markReadTokens, ['g']);
  });

  testWidgets('AC7.4：探测网络错误 → 照常跳转', (tester) async {
    final r = await _pump(tester, _Repo([
      [
        NotificationItem(
          type: 'CONTENT_LIKED',
          deepLinkType: 'CONTENT_LIKED',
          deepLinkToken: 'n',
          targetRef: '78',
          read: false,
          createdAt: _daysAgo(0),
        ),
      ],
    ]));

    await tester.tap(find.byKey(const ValueKey('notification_n')));
    await tester.pumpAndSettle();

    expect(r.nav.pushed, '/content/78');
  });

  testWidgets('AC2：非今天显示「d MMM, HH:mm」（本地时间）', (tester) async {
    final when = DateTime(2026, 1, 5, 15, 57); // 本地
    await _pump(tester, _Repo([
      [NotificationItem(type: 'VET_REPLY', deepLinkToken: 'x', read: true, createdAt: when.toUtc())],
    ]));
    expect(find.text('5 Jan, 15:57'), findsOneWidget);
  });
}

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/detail_repository.dart';
import 'package:tailtopia/features/notify/data/notification_repository.dart';
import 'package:tailtopia/features/notify/domain/notification_item.dart';
import 'package:tailtopia/features/notify/presentation/notification_center_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.2 评论点赞通知（复用 `CONTENT_LIKED` + 新字段 `commentId`）：
/// - 条目解析 commentId（缺键 = 帖子点赞）；
/// - 通知中心点评论点赞 → `/content/{postId}?focus=comments&commentId={id}`，已读照标、帖子照探；
/// - 帖子点赞落点不变；埋点 notif_type 区分 `comment_like` / `like`。
class _Repo extends NotificationRepository {
  _Repo(this.items) : super(dio: Dio());
  final List<NotificationItem> items;
  final List<String> markReadTokens = [];

  @override
  Future<NotificationPage> list({String? cursor, int limit = 20}) async =>
      NotificationPage(items: items, hasMore: false);

  @override
  Future<int> unreadCount() async => 0;

  @override
  Future<void> markRead(String token) async => markReadTokens.add(token);

  @override
  Future<void> markAllRead() async {}
}

class _Probe extends ContentGoneProbe {
  _Probe({this.gone = false}) : super(Dio());
  final bool gone;
  final List<int> probed = [];

  @override
  Future<bool> isGone(int postId) async {
    probed.add(postId);
    return gone;
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

NotificationItem _like({int? commentId, String token = 'lk1'}) => NotificationItem(
      type: 'CONTENT_LIKED',
      deepLinkType: 'CONTENT_LIKED',
      deepLinkToken: token,
      targetRef: '55',
      title: commentId == null ? 'Rani liked your post' : 'Rani liked your comment',
      body: commentId == null ? null : 'so cute!',
      read: false,
      createdAt: DateTime.now().toUtc(),
      actorName: 'Rani',
      actorCount: 1,
      commentId: commentId,
    );

class _Nav {
  String? pushed;
}

Future<_Nav> _pump(WidgetTester tester, _Repo repo, {_Probe? probe}) async {
  final nav = _Nav();
  final router = GoRouter(
    initialLocation: '/notifications',
    routes: [
      GoRoute(path: '/notifications', builder: (_, _) => const NotificationCenterPage()),
      GoRoute(
        path: '/content/:id',
        builder: (_, s) {
          nav.pushed = s.uri.toString();
          return const Scaffold(body: Text('target'));
        },
      ),
    ],
  );
  addTearDown(router.dispose);
  final container = ProviderContainer(overrides: [
    notificationRepositoryProvider.overrideWithValue(repo),
    contentGoneProbeProvider.overrideWithValue(probe ?? _Probe()),
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
  return nav;
}

Finder _tile(String token) => find.byKey(ValueKey('notification_$token'));

void main() {
  final events = <(String, Map<String, Object>?)>[];
  setUp(() {
    events.clear();
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  group('NotificationItem.commentId 解析', () {
    test('评论点赞带 commentId；帖子点赞缺键 → null', () {
      final c = NotificationItem.fromJson(
          {'type': 'CONTENT_LIKED', 'targetRef': '55', 'commentId': 70, 'read': false});
      expect(c.commentId, 70);
      expect(c.targetRef, '55');
      expect(c.isCommentLike, isTrue);

      final p = NotificationItem.fromJson({'type': 'CONTENT_LIKED', 'targetRef': '55', 'read': false});
      expect(p.commentId, isNull);
      expect(p.isCommentLike, isFalse);

      // 只有 CONTENT_LIKED 才算评论点赞。
      expect(
          NotificationItem.fromJson({'type': 'CONTENT_COMMENTED', 'commentId': 70}).isCommentLike, isFalse);
    });

    test('仍是「后端文案条目」（CONTENT_LIKED 在 serverCopyTypes 里，老 App 照常渲染后端标题）', () {
      expect(NotificationItem.serverCopyTypes, contains('CONTENT_LIKED'));
      expect(_like(commentId: 70).hasServerCopy, isTrue);
    });
  });

  testWidgets('评论点赞：点击 → 先探帖子 → /content/55?focus=comments&commentId=70；已读照标；notif_type=comment_like',
      (tester) async {
    final repo = _Repo([_like(commentId: 70)]);
    final probe = _Probe();
    final nav = await _pump(tester, repo, probe: probe);
    // 后端文案照常显示（标题 + 被赞评论正文）。
    expect(find.textContaining('liked your comment'), findsOneWidget);
    await tester.tap(_tile('lk1'));
    await tester.pumpAndSettle();
    expect(probe.probed, [55]);
    expect(nav.pushed, '/content/55?focus=comments&commentId=70');
    expect(repo.markReadTokens, ['lk1']);
    expect(events.firstWhere((e) => e.$1 == 'app_notification_item_tapped').$2!['notif_type'],
        'comment_like');
  });

  testWidgets('帖子点赞：落点不变（/content/55），notif_type=like', (tester) async {
    final repo = _Repo([_like()]);
    final nav = await _pump(tester, repo);
    await tester.tap(_tile('lk1'));
    await tester.pumpAndSettle();
    expect(nav.pushed, '/content/55');
    expect(repo.markReadTokens, ['lk1']);
    expect(events.firstWhere((e) => e.$1 == 'app_notification_item_tapped').$2!['notif_type'], 'like');
  });

  testWidgets('评论点赞但帖子已删 → toast、不跳转、已读照标（沿用 _postGone 探测）', (tester) async {
    final repo = _Repo([_like(commentId: 70)]);
    final nav = await _pump(tester, repo, probe: _Probe(gone: true));
    await tester.tap(_tile('lk1'));
    await tester.pumpAndSettle();
    expect(nav.pushed, isNull);
    expect(repo.markReadTokens, ['lk1']);
    await tester.pump(const Duration(seconds: 5)); // toast 计时器走完
    await tester.pumpAndSettle();
  });
}

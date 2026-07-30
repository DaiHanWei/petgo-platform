import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/router/deep_link_routes.dart';
import 'package:tailtopia/features/notify/data/notification_repository.dart';
import 'package:tailtopia/features/notify/domain/notification_deep_link.dart';
import 'package:tailtopia/features/notify/domain/notification_item.dart';
import 'package:tailtopia/features/notify/presentation/notification_bell.dart';
import 'package:tailtopia/features/notify/presentation/notification_center_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

class _FakeNotifyRepo extends NotificationRepository {
  _FakeNotifyRepo({this.items = const [], this.unread = 0}) : super(dio: Dio());

  final List<NotificationItem> items;
  int unread;
  final List<String> markReadTokens = [];

  @override
  Future<NotificationPage> list({String? cursor, int limit = 20}) async =>
      NotificationPage(items: items, hasMore: false);

  @override
  Future<int> unreadCount() async => unread;

  @override
  Future<void> markRead(String token) async {
    markReadTokens.add(token);
    if (unread > 0) unread--; // 已读 → 角标递减（库口径）
  }
}

Future<void> _pump(WidgetTester tester, Widget home, _FakeNotifyRepo repo) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [notificationRepositoryProvider.overrideWithValue(repo)],
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('en'),
      home: home,
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('AC2: 空通知 → 空态', (tester) async {
    await _pump(tester, const NotificationCenterPage(), _FakeNotifyRepo());
    expect(find.byKey(const ValueKey('notificationEmpty')), findsOneWidget);
  });

  testWidgets('AC2: 倒序四类条目渲染（未读高亮圆点）', (tester) async {
    await _pump(
      tester,
      const NotificationCenterPage(),
      _FakeNotifyRepo(items: [
        const NotificationItem(type: 'VET_REPLY', title: '兽医已回复', deepLinkToken: 't1', read: false),
        const NotificationItem(type: 'CONTENT_LIKED', title: '收到点赞', deepLinkToken: 't2', read: true),
      ]),
    );
    // 文案按 type 本地化（en locale）：不再渲染后端 title 串。
    expect(find.text('Vet replied'), findsOneWidget);
    expect(find.text('New like'), findsOneWidget);
    expect(find.byKey(const ValueKey('notification_t1')), findsOneWidget);
  });

  testWidgets('🔄 AC2: 新增三类（生日/纪念日/里程碑节点）渲染图标+文案（F2）', (tester) async {
    await _pump(
      tester,
      const NotificationCenterPage(),
      _FakeNotifyRepo(items: [
        const NotificationItem(
            type: 'PET_BIRTHDAY', title: '生日快乐', deepLinkToken: 'b1', read: false),
        const NotificationItem(
            type: 'COMPANION_ANNIVERSARY', title: '陪伴纪念', deepLinkToken: 'a1', read: true),
        const NotificationItem(
            type: 'MILESTONE_NODE', title: '里程碑达成', deepLinkToken: 'm1', read: true),
      ]),
    );
    expect(find.text('Pet birthday'), findsOneWidget);
    expect(find.text('Companion anniversary'), findsOneWidget);
    expect(find.text('Milestone reached'), findsOneWidget);
    // 三类专属图标（notif.html 重做：圆角方形彩色图标块用 rounded 图标）。
    expect(find.byIcon(Icons.cake_rounded), findsOneWidget);
    expect(find.byIcon(Icons.celebration_rounded), findsOneWidget);
    expect(find.byIcon(Icons.emoji_events_rounded), findsOneWidget);
  });

  testWidgets('cm-7: NAME_RESET/AVATAR_RESET 按 targetRef 选 用户/宠物 变体文案', (tester) async {
    await _pump(
      tester,
      const NotificationCenterPage(),
      _FakeNotifyRepo(items: [
        // 用户变体：targetRef=NICKNAME/USER_AVATAR
        const NotificationItem(
            type: 'NAME_RESET', title: 'x', deepLinkToken: 'n1', targetRef: 'NICKNAME', read: false),
        const NotificationItem(
            type: 'AVATAR_RESET', title: 'x', deepLinkToken: 'a1', targetRef: 'USER_AVATAR', read: false),
        // 宠物变体：targetRef=cardToken
        const NotificationItem(
            type: 'NAME_RESET', title: 'x', deepLinkToken: 'n2', targetRef: 'card_abc', read: true),
        const NotificationItem(
            type: 'AVATAR_RESET', title: 'x', deepLinkToken: 'a2', targetRef: 'card_xyz', read: true),
      ]),
    );
    // 用户变体
    expect(find.text('Your username was reset to a default name for violating the Community Guidelines. Set a new one anytime.'), findsOneWidget);
    expect(find.text('Your profile photo was reset to the default for violating the Community Guidelines. Upload a new one anytime.'), findsOneWidget);
    // 宠物变体
    expect(find.text("Your pet's name was reset to a default name for violating the Community Guidelines. Set a new one anytime."), findsOneWidget);
    expect(find.text("Your pet's photo was reset to the default for violating the Community Guidelines. Upload a new one anytime."), findsOneWidget);
    // 审核类专属警示图标（多条 → 至少若干个）。
    expect(find.byIcon(Icons.gpp_maybe_rounded), findsWidgets);
    // 契约：不渲染后端 title 串。
    expect(find.text('x'), findsNothing);
  });

  testWidgets('cm-7: CONTENT_REMOVED 用通用文案（帖子/评论 postId 无法区分）+ 审核类标题本地化', (tester) async {
    await _pump(
      tester,
      const NotificationCenterPage(),
      _FakeNotifyRepo(items: [
        const NotificationItem(
            type: 'CONTENT_REMOVED', title: 'x', deepLinkToken: 'c1', targetRef: '42', read: false),
        const NotificationItem(
            type: 'CONTENT_REVIEW_REJECTED', title: 'x', deepLinkToken: 'c2', read: true),
        const NotificationItem(
            type: 'CONTENT_REVIEW_TIMED_OUT', title: 'x', deepLinkToken: 'c3', read: true),
        const NotificationItem(
            type: 'REPORT_REVIEWED', title: 'x', deepLinkToken: 'c4', read: true),
      ]),
    );
    // 标题本地化（en）——审核类不落兜底串。
    expect(find.text('Content hidden'), findsOneWidget);
    expect(find.text('Post not published'), findsOneWidget);
    expect(find.text("Post couldn't be published"), findsOneWidget);
    expect(find.text('Report reviewed'), findsOneWidget);
    // 通用 CONTENT_REMOVED body。
    expect(find.text('Your content was found to violate the Community Guidelines and has been hidden from others. You can still see it on your page.'), findsOneWidget);
  });

  testWidgets('AC1: 铃铛角标 >0 显示 / 0 隐藏', (tester) async {
    await _pump(tester, const Scaffold(body: NotificationBell()), _FakeNotifyRepo(unread: 5));
    expect(find.byKey(const ValueKey('notificationBell')), findsOneWidget);
    expect(find.byKey(const ValueKey('notificationBadge')), findsOneWidget);
    expect(find.text('5'), findsOneWidget);
  });

  testWidgets('AC1: 无未读 → 角标隐藏', (tester) async {
    await _pump(tester, const Scaffold(body: NotificationBell()), _FakeNotifyRepo(unread: 0));
    expect(find.byKey(const ValueKey('notificationBell')), findsOneWidget);
    expect(find.byKey(const ValueKey('notificationBadge')), findsNothing);
  });

  // ===== AC3（F2b · R2）：里程碑零态 + 推送直跳已读同步 =====

  testWidgets('AC3①: 无里程碑数据 → 不渲染里程碑条目（零态，不报错/无空壳）', (tester) async {
    await _pump(
      tester,
      const NotificationCenterPage(),
      _FakeNotifyRepo(items: [
        const NotificationItem(type: 'VET_REPLY', title: '兽医已回复', deepLinkToken: 't1', read: false),
      ]),
    );
    // 列表正常渲染既有条目，但无 MILESTONE_NODE 数据时绝不出现里程碑图标/空壳。
    expect(find.text('Vet replied'), findsOneWidget);
    expect(find.byIcon(Icons.flag_outlined), findsNothing);
  });

  testWidgets('AC3②: 系统推送直跳（NotificationDeepLink.open）→ 标记已读 + 角标重算 + 目标 location',
      (tester) async {
    final repo = _FakeNotifyRepo(unread: 3);
    String? location;
    await _pump(
      tester,
      Scaffold(
        body: Column(
          children: [
            const NotificationBell(),
            Consumer(builder: (ctx, ref, _) {
              return ElevatedButton(
                key: const ValueKey('simulatePush'),
                // 模拟系统推送 deepLinkToken 直跳（不经列表点击）。
                onPressed: () async {
                  location = await NotificationDeepLink.open(
                    ref, type: 'PET_BIRTHDAY', token: 'pushTok');
                },
                child: const Text('push'),
              );
            }),
          ],
        ),
      ),
      repo,
    );
    // 初始角标 3。
    expect(find.text('3'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('simulatePush')));
    await tester.pumpAndSettle();

    // ② 对应条目标记已读（token 同步翻已读）。
    expect(repo.markReadTokens, contains('pushTok'));
    // 角标 invalidate → 重算为 2（避免推送已读/中心未读不一致）。
    expect(find.text('2'), findsOneWidget);
    // 固定目标类深链直达（生日 → +发布预选成长日历）。
    expect(location, DeepLinkRoutes.publishGrowthCalendar);
  });
}

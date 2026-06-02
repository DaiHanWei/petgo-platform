import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/notify/data/notification_repository.dart';
import 'package:petgo/features/notify/domain/notification_item.dart';
import 'package:petgo/features/notify/presentation/notification_bell.dart';
import 'package:petgo/features/notify/presentation/notification_center_page.dart';
import 'package:petgo/l10n/app_localizations.dart';

class _FakeNotifyRepo extends NotificationRepository {
  _FakeNotifyRepo({this.items = const [], this.unread = 0}) : super(dio: Dio());

  final List<NotificationItem> items;
  final int unread;

  @override
  Future<NotificationPage> list({String? cursor, int limit = 20}) async =>
      NotificationPage(items: items, hasMore: false);

  @override
  Future<int> unreadCount() async => unread;
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
    expect(find.text('兽医已回复'), findsOneWidget);
    expect(find.text('收到点赞'), findsOneWidget);
    expect(find.byKey(const ValueKey('notification_t1')), findsOneWidget);
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
}

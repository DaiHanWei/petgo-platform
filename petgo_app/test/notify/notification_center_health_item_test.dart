import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/router/app_router.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/notify/data/notification_repository.dart';
import 'package:tailtopia/features/notify/domain/notification_item.dart';
import 'package:tailtopia/features/notify/presentation/notification_center_page.dart';
import 'package:tailtopia/features/profile/domain/health_form_dirty.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.5.0 健康记录提醒 Story 3.4 · AC3 / AC5：通知中心健康提醒条目。
class _Repo extends NotificationRepository {
  _Repo(this.items) : super(dio: Dio());
  final List<NotificationItem> items;
  final marked = <String>[];

  @override
  Future<NotificationPage> list({String? cursor, int limit = 20}) async =>
      NotificationPage(items: items, hasMore: false);

  @override
  Future<int> unreadCount() async => items.where((e) => !e.read).length;

  @override
  Future<void> markRead(String token) async => marked.add(token);
}

NotificationItem _health({bool read = false}) => NotificationItem(
      type: 'HEALTH_VACCINE_D_DAY',
      title: 'Dosis ke-2 untuk Mochi hari ini 💉',
      body: 'Vaksin F4 dosis ke-2 jatuh tempo hari ini. Catat setelah selesai ya, biar jadwal berikutnya tersusun.',
      deepLinkType: 'HEALTH_VACCINE_D_DAY',
      deepLinkToken: 'tok-h',
      targetRef: 'tok-h',
      read: read,
      createdAt: DateTime.now(),
    );

Future<(ProviderContainer, _Repo)> _pump(WidgetTester tester, List<NotificationItem> items) async {
  SharedPreferences.setMockInitialValues({});
  final repo = _Repo(items);
  final c = ProviderContainer(overrides: [notificationRepositoryProvider.overrideWithValue(repo)]);
  addTearDown(c.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: c,
    child: MaterialApp.router(
      locale: const Locale('en'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      routerConfig: GoRouter(
        navigatorKey: rootNavigatorKey,
        initialLocation: '/notifications',
        routes: [
          GoRoute(path: '/notifications', builder: (_, _) => const NotificationCenterPage()),
          GoRoute(
              path: '/health-reminder/open',
              builder: (_, s) => Scaffold(body: Text('OPEN ${s.uri.queryParameters['t']} ${s.uri.queryParameters['src']}'))),
        ],
      ),
    ),
  ));
  await tester.pumpAndSettle();
  return (c, repo);
}

Finder _tile() => find.byKey(const ValueKey('notification_tok-h'));

void main() {
  testWidgets('标题 / 正文读后端落库文案（不按 App 语言再拼）；正文最多 2 行；健康盾图标 mint / violet100', (tester) async {
    await _pump(tester, [_health()]);
    expect(find.text('Dosis ke-2 untuk Mochi hari ini 💉'), findsOneWidget);
    final body = tester.widget<Text>(find.textContaining('Vaksin F4 dosis ke-2'));
    expect(body.maxLines, 2);
    final icon = tester.widget<Icon>(find.descendant(of: _tile(), matching: find.byType(Icon)).first);
    expect(icon.icon, Icons.health_and_safety_rounded);
    expect(icon.color, AppColors.mint);
    final block = find.ancestor(of: find.byWidget(icon), matching: find.byType(Container)).first;
    final deco = tester.widget<Container>(block).decoration as BoxDecoration?;
    expect(deco?.color, AppColors.violet100);
  });

  testWidgets('点条目 → 点开路由页 src=inbox', (tester) async {
    await _pump(tester, [_health()]);
    await tester.tap(_tile());
    await tester.pumpAndSettle();
    expect(find.text('OPEN tok-h inbox'), findsOneWidget);
  });

  testWidgets('表单有未保存改动 → 只 toast、不导航、不标已读', (tester) async {
    final (c, repo) = await _pump(tester, [_health()]);
    c.read(healthFormDirtyProvider.notifier).set(true);
    await tester.tap(_tile());
    await tester.pumpAndSettle();
    expect(find.text('Save or close this record first, then open the reminder again.'), findsOneWidget);
    expect(find.textContaining('OPEN'), findsNothing);
    expect(repo.marked, isEmpty);
    await tester.pump(const Duration(seconds: 3));
    // 表单关掉后再点 → 照常打开。
    c.read(healthFormDirtyProvider.notifier).set(false);
    await tester.tap(_tile());
    await tester.pumpAndSettle();
    expect(find.text('OPEN tok-h inbox'), findsOneWidget);
  });
}

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/content/data/detail_repository.dart';
import 'package:tailtopia/features/notify/data/notification_repository.dart';
import 'package:tailtopia/features/notify/domain/notification_item.dart';
import 'package:tailtopia/features/notify/presentation/notification_center_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.0 ① Story 2.3（UI 稿 F1 / F2）：升级通知在通知中心的呈现、跳转与埋点。
class _FakeNotificationRepo extends NotificationRepository {
  _FakeNotificationRepo(this.items) : super(dio: Dio());

  final List<NotificationItem> items;

  @override
  Future<NotificationPage> list({String? cursor, int limit = 20}) async =>
      NotificationPage(items: items, hasMore: false);

  @override
  Future<int> unreadCount() async => items.where((e) => !e.read).length;

  @override
  Future<void> markRead(String token) async {}
}

class _NotGone extends ContentGoneProbe {
  _NotGone() : super(Dio());

  @override
  Future<bool> isGone(int postId) async => false;
}

NotificationItem _item(String? targetRef) => NotificationItem(
      type: 'GROWTH_LEVEL_UP',
      targetRef: targetRef,
      deepLinkType: 'GROWTH_LEVEL_UP',
      deepLinkToken: 'tok-growth-$targetRef',
      read: false,
      createdAt: DateTime.now(),
    );

Future<void> _pump(WidgetTester tester, List<NotificationItem> items) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [
      notificationRepositoryProvider.overrideWithValue(_FakeNotificationRepo(items)),
      contentGoneProbeProvider.overrideWithValue(_NotGone()),
    ],
    child: MaterialApp.router(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      routerConfig: GoRouter(
        initialLocation: '/notifications',
        routes: [
          GoRoute(path: '/notifications', builder: (_, _) => const NotificationCenterPage()),
          GoRoute(path: '/me/growth', builder: (_, _) => const Text('LEVEL_PAGE')),
        ],
      ),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));
  tearDown(() => Analytics.debugCaptureSink = null);

  testWidgets('F1：Naik ke Lv7! / Kamu sekarang Sahabat… + 专属图标', (tester) async {
    await _pump(tester, [_item('7:BUDDY')]);
    expect(find.text('Naik ke Lv7!'), findsOneWidget);
    expect(find.text('Kamu sekarang Sahabat. Lihat keuntungan barumu.'), findsOneWidget);
    expect(find.byIcon(Icons.workspace_premium_rounded), findsOneWidget);
  });

  testWidgets('targetRef 格式不对 → 系统兜底文案，不崩', (tester) async {
    await _pump(tester, [_item('garbage')]);
    expect(tester.takeException(), isNull);
    expect(find.text('Notifikasi sistem'), findsOneWidget);
    expect(find.textContaining('Naik ke'), findsNothing);
  });

  testWidgets('点击 → 等级页，上报 growth_level_page_viewed{source: notification}', (tester) async {
    final seen = <(String, Map<String, Object>?)>[];
    Analytics.debugCaptureSink = (e, p) => seen.add((e, p));
    await _pump(tester, [_item('7:BUDDY')]);
    await tester.tap(find.text('Naik ke Lv7!'));
    await tester.pumpAndSettle();
    expect(find.text('LEVEL_PAGE'), findsOneWidget);
    final viewed = seen.where((e) => e.$1 == 'growth_level_page_viewed').toList();
    expect(viewed.single.$2, {'source': 'notification'});
  });
}

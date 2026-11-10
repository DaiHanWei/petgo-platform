import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/push/push_service.dart';
import 'package:tailtopia/core/router/app_router.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/notify/data/notification_repository.dart';
import 'package:tailtopia/features/notify/domain/notification_item.dart';
import 'package:tailtopia/features/notify/presentation/notification_center_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：券快过期 `COUPON_EXPIRING`（v1.4.2 shop Story 9.3 · AC4 / G9）——通知中心文案 / 图标 / 埋点映射 + 推送点开埋点。
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

NotificationItem _expiring({String? title, String? body, String? targetRef = '3'}) => NotificationItem(
      type: 'COUPON_EXPIRING',
      title: title,
      body: body,
      deepLinkType: 'COUPON_EXPIRING',
      deepLinkToken: 'tok-ce',
      targetRef: targetRef,
      read: false,
      createdAt: DateTime.now(),
    );

Future<void> _pump(WidgetTester tester, List<NotificationItem> items) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [notificationRepositoryProvider.overrideWithValue(_FakeNotificationRepo(items))],
    child: MaterialApp.router(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      routerConfig: GoRouter(
        navigatorKey: rootNavigatorKey,
        initialLocation: '/notifications',
        routes: [
          GoRoute(path: '/notifications', builder: (_, _) => const NotificationCenterPage()),
          GoRoute(path: '/me/coupons', builder: (_, _) => const Scaffold(body: Text('my-coupons'))),
        ],
      ),
    ),
  ));
  await tester.pumpAndSettle();
}

Finder _tile() => find.byKey(const ValueKey('notification_tok-ce'));

void main() {
  late List<(String, Map<String, Object>?)> events;

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  testWidgets('G9：读后端 title / body（正文带日期）+ 金色券图标', (tester) async {
    await _pump(tester, [
      _expiring(title: '3 kupon segera berakhir', body: 'Pakai sebelum 8 Nov 2026. Jangan sampai hangus!'),
    ]);
    final icon = tester.widget<Icon>(find.descendant(of: _tile(), matching: find.byType(Icon)).first);
    expect(icon.icon, Icons.confirmation_number_rounded);
    expect(icon.color, AppColors.gold);
    expect(find.text('3 kupon segera berakhir'), findsOneWidget);
    expect(find.text('Pakai sebelum 8 Nov 2026. Jangan sampai hangus!'), findsOneWidget);
  });

  testWidgets('G9：后端为空 → 标题按 targetRef 张数兜底、正文不带日期兜底', (tester) async {
    await _pump(tester, [_expiring(title: ' ', body: null, targetRef: '2')]);
    expect(find.text('2 kupon segera berakhir'), findsOneWidget);
    expect(find.text('Pakai sebelum hangus!'), findsOneWidget);
  });

  testWidgets('G9：后端为空且 targetRef 不是数字 → 不猜数字', (tester) async {
    await _pump(tester, [_expiring(title: null, body: null, targetRef: null)]);
    expect(find.text('Kupon segera berakhir'), findsOneWidget);
  });

  testWidgets('点条目 → 我的券；通知点击埋点 notif_type=coupon_expiring；列表点击不报推送点开', (tester) async {
    await _pump(tester, [_expiring(title: 'x', body: 'y')]);
    await tester.tap(_tile());
    await tester.pumpAndSettle();
    expect(find.text('my-coupons'), findsOneWidget);
    final types = events.map((e) => e.$2?['notif_type']).whereType<String>().toSet();
    expect(types, contains('coupon_expiring'));
    expect(events.where((e) => e.$1 == 'toko_coupon_expiry_push_opened'), isEmpty);
  });

  group('推送点开埋点 toko_coupon_expiry_push_opened', () {
    test('带 coupon_count（targetRef 张数）', () {
      reportCouponExpiryPushOpened(
          const PushPayload(type: 'COUPON_EXPIRING', token: 't', targetRef: '3'));
      expect(events, hasLength(1));
      expect(events.single.$1, 'toko_coupon_expiry_push_opened');
      expect(events.single.$2, {'coupon_count': 3});
    });

    test('targetRef 解析不出 → 不带 coupon_count', () {
      reportCouponExpiryPushOpened(const PushPayload(type: 'COUPON_EXPIRING', token: 't'));
      expect(events.single.$1, 'toko_coupon_expiry_push_opened');
      expect(events.single.$2 ?? const {}, isNot(contains('coupon_count')));
    });

    test('其他类型不报', () {
      reportCouponExpiryPushOpened(const PushPayload(type: 'GROWTH_LEVEL_UP', token: 't'));
      expect(events, isEmpty);
    });
  });
}

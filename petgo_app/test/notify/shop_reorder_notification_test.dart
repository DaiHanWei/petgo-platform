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
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：通知中心 F22 `SHOP_ORDER_REORDERED`（v1.4.2 shop Story 9.1 · AC6.4）。
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

NotificationItem _reordered({String? title, String? body, String? targetRef = 'newOrdTok'}) =>
    NotificationItem(
      type: 'SHOP_ORDER_REORDERED',
      title: title,
      body: body,
      deepLinkType: 'SHOP_ORDER_REORDERED',
      deepLinkToken: 'tok-ro',
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
          GoRoute(
            path: '/shop/orders/:token',
            builder: (_, s) => Scaffold(body: Text('order:${s.pathParameters['token']}')),
          ),
        ],
      ),
    ),
  ));
  await tester.pumpAndSettle();
}

Finder _tile() => find.byKey(const ValueKey('notification_tok-ro'));

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  testWidgets('F22：读后端整句 title / body（正文含新单号）+ 紫色货车图标', (tester) async {
    await _pump(tester, [
      _reordered(
          title: 'Pesananmu sudah dibuat ulang',
          body: 'Pembayaranmu sudah kami terima. Pesanan TOKO-20261105-ABC123 sedang disiapkan.'),
    ]);
    final icon =
        tester.widget<Icon>(find.descendant(of: _tile(), matching: find.byType(Icon)).first);
    expect(icon.icon, Icons.local_shipping_rounded);
    expect(icon.color, AppColors.mint);
    expect(find.textContaining('TOKO-20261105-ABC123'), findsOneWidget);
  });

  testWidgets('F22：后端 title / body 为空 → 分别回落本地兜底（兜底正文不含单号）', (tester) async {
    await _pump(tester, [_reordered(title: '  ', body: null)]);
    expect(find.text('Pesananmu sudah dibuat ulang'), findsOneWidget);
    expect(find.text('Pembayaranmu sudah kami terima. Pesanan sedang disiapkan.'), findsOneWidget);
  });

  testWidgets('点条目 → 新订单详情 /shop/orders/{targetRef}', (tester) async {
    await _pump(tester, [_reordered(title: 'x', body: 'y')]);
    await tester.tap(_tile());
    await tester.pumpAndSettle();
    expect(find.text('order:newOrdTok'), findsOneWidget);
  });
}

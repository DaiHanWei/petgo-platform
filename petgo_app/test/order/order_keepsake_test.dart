import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/order/data/order_repository.dart';
import 'package:tailtopia/features/order/domain/order_detail.dart';
import 'package:tailtopia/features/order/domain/order_summary.dart';
import 'package:tailtopia/features/order/presentation/order_detail_page.dart';
import 'package:tailtopia/features/order/presentation/order_l10n.dart';
import 'package:tailtopia/features/profile/presentation/pet_insights_page.dart';
import 'package:tailtopia/features/tailsonality/presentation/tailsonality_routes.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.2 Story 3.6 · AC4（L0）：三类枚举 / 标题 / 图标、UNDER_REVIEW 文案与分组、详情「查看」入口路由、闸门参数。
void main() {
  late AppLocalizations l10n;
  setUpAll(() async => l10n = await AppLocalizations.delegate.load(const Locale('id')));

  test('枚举：三类在 ecommerce 之后、unknown 之前；fromCode / toApi 往返', () {
    final names = OrderType.values.map((e) => e.name).toList();
    expect(names.sublist(names.indexOf('ecommerce')),
        ['ecommerce', 'tailsonality', 'passportSnap', 'boardingPass', 'unknown']);
    for (final (code, t) in [
      ('TAILSONALITY', OrderType.tailsonality),
      ('PASSPORT_SNAP', OrderType.passportSnap),
      ('BOARDING_PASS', OrderType.boardingPass),
    ]) {
      expect(OrderType.fromCode(code), t);
      expect(t.toApi(), code);
    }
  });

  test('标题与图标；UNDER_REVIEW 非字面量且归「进行中」', () {
    expect(orderTypeLabel(l10n, OrderType.tailsonality), 'Buka Tailsonality');
    expect(orderTypeLabel(l10n, OrderType.passportSnap), 'Jejak · versi');
    expect(orderTypeLabel(l10n, OrderType.boardingPass), 'Boarding Pass');
    expect(orderTypeIcon(OrderType.tailsonality), Icons.psychology_outlined);
    expect(orderTypeIcon(OrderType.passportSnap), Icons.menu_book_outlined);
    expect(orderTypeIcon(OrderType.boardingPass), Icons.airplane_ticket_outlined);
    expect(orderStatusLabel(l10n, 'UNDER_REVIEW'), 'Sedang dicek');
    expect(orderStatusLabel(l10n, 'UNDER_REVIEW'), isNot('UNDER_REVIEW'));
    expect(OrderStatusGroup.fromStatus('UNDER_REVIEW'), OrderStatusGroup.ongoing);
  });

  OrderDetail detail({String? kind, String? token, String status = 'PAID', String type = 'TAILSONALITY'}) =>
      OrderDetail.fromJson({
        'orderType': type,
        'orderToken': 'kp1',
        'displayNo': 'TSL-20260930-000011',
        'statusCode': status,
        'statusColor': status == 'PAID' ? 'SUCCESS' : 'INFO',
        'amount': 5000,
        'payChannel': 'PAWCOIN',
        'createdAt': '2026-09-30T08:00:00Z',
        'paidAt': '2026-09-30T08:00:00Z',
        'petDeleted': false,
        'targetKind': ?kind,
        'targetToken': ?token,
      });

  test('查看入口路由按 kind；token 为空不出', () {
    expect(keepsakeTargetRoute(detail(kind: 'TAILSONALITY_RESULT', token: 'r1')), TailsonalityRoutes.result('r1'));
    expect(keepsakeTargetRoute(detail(kind: 'PASSPORT_SNAPSHOT', token: 's1')), PetInsightsRoutes.passportVersionFor('s1'));
    expect(keepsakeTargetRoute(detail(kind: 'BOARDING_PASS', token: 'p1')), PetInsightsRoutes.boardingPassFor('p1'));
    expect(keepsakeTargetRoute(detail(kind: 'BOARDING_PASS')), isNull);
    expect(keepsakeTargetRoute(detail(kind: 'WHATEVER', token: 'x')), isNull);
    expect(keepsakeTargetRoute(detail()), isNull);
  });

  Future<void> pumpDetail(WidgetTester tester, OrderDetail d) async {
    final router = GoRouter(initialLocation: '/me/orders/kp1', routes: [
      GoRoute(path: '/me/orders/:token', builder: (c, s) => OrderDetailPage(token: s.pathParameters['token']!)),
      GoRoute(path: TailsonalityRoutes.resultPattern, builder: (c, s) => Text('ts:${s.pathParameters['token']}')),
    ]);
    addTearDown(router.dispose);
    await tester.pumpWidget(ProviderScope(
      retry: (_, _) => null,
      overrides: [orderDetailProvider('kp1').overrideWith((ref) async => d)],
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
      ),
    ));
    await tester.pumpAndSettle();
  }

  testWidgets('详情：有目标出「Lihat hasil tes」并跳结果页；无目标不出入口', (tester) async {
    await pumpDetail(tester, detail(kind: 'TAILSONALITY_RESULT', token: 'r1'));
    expect(find.text('Buka Tailsonality'), findsOneWidget);
    expect(find.text('Lihat hasil tes'), findsOneWidget);
    expect(find.byKey(const ValueKey('orderUnderReviewNote')), findsNothing);
    await tester.tap(find.byKey(const ValueKey('orderKeepsakeEntry')));
    await tester.pumpAndSettle();
    expect(find.text('ts:r1'), findsOneWidget);
  });

  testWidgets('UNDER_REVIEW：说明行 + 状态文案非字面量；无目标不出入口', (tester) async {
    await pumpDetail(tester, detail(status: 'UNDER_REVIEW', type: 'BOARDING_PASS'));
    expect(find.text('Tim kami lagi cek pembayaran ini. Kalau dobel, dana dikembalikan.'), findsOneWidget);
    expect(find.text('Sedang dicek'), findsWidgets);
    expect(find.text('UNDER_REVIEW'), findsNothing);
    expect(find.byKey(const ValueKey('orderKeepsakeEntry')), findsNothing);
  });

  test('列表请求恒带 includeKeepsake=true', () async {
    late RequestOptions sent;
    final dio = Dio()
      ..interceptors.add(InterceptorsWrapper(onRequest: (o, h) {
        sent = o;
        h.resolve(Response(requestOptions: o, statusCode: 200, data: {'items': [], 'hasMore': false}));
      }));
    await ref(dio).fetchOrders();
    expect(sent.queryParameters['includeKeepsake'], true);
    expect(sent.queryParameters['includeEcommerce'], true);
  });
}

OrderRepository ref(Dio dio) => OrderRepository(dio: dio);

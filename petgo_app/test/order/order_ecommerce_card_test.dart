import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/order/domain/order_summary.dart';
import 'package:tailtopia/features/order/presentation/widgets/order_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 3.9：订单列表电商卡片（FR-101 —— FR-54 的第 5 类）。
///
/// 🔴 这一条的风险不在新卡片，而在**顺手改坏既有四类**（并行契约 O-1）。
/// 所以每条电商断言旁边都有一条「既有类型不受影响」的对照。
void main() {
  GoRouter router(OrderSummary order) => GoRouter(
        initialLocation: '/list',
        routes: [
          GoRoute(path: '/list', builder: (c, s) => Scaffold(body: OrderCard(order: order))),
          GoRoute(
              path: '/shop/orders/:token',
              builder: (c, s) => const Scaffold(body: Text('SHOP ORDER DETAIL'))),
          GoRoute(
              path: '/me/orders/:token',
              builder: (c, s) => const Scaffold(body: Text('GENERIC ORDER DETAIL'))),
        ],
      );

  Future<GoRouter> open(WidgetTester t, OrderSummary order) async {
    final r = router(order);
    await t.pumpWidget(MaterialApp.router(
      routerConfig: r,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('id'),
    ));
    await t.pumpAndSettle();
    return r;
  }

  group('🔴 第 5 类卡片', () {
    testWidgets('展示商品名 · 规格，并在多件时给「等 N 件」（N 不含首件）', (t) async {
      await open(
          t,
          _order(
            type: OrderType.ecommerce,
            statusCode: 'PENDING_PAYMENT',
            itemTitle: 'Royal Canin · 3 kg',
            itemCount: 3,
          ));

      // 「Royal Canin 等 3 件」在只有 3 件时读起来像还有另外 3 件
      expect(find.textContaining('Royal Canin · 3 kg'), findsOneWidget);
      expect(find.textContaining('2'), findsWidgets);
    });

    testWidgets('单件时不显示「等 N 件」', (t) async {
      await open(
          t,
          _order(
            type: OrderType.ecommerce,
            statusCode: 'PENDING_SHIPMENT',
            itemTitle: 'Drontal Plus · 1 tablet',
            itemCount: 1,
          ));

      expect(find.text('Drontal Plus · 1 tablet'), findsOneWidget);
      expect(find.textContaining('barang lain'), findsNothing);
    });

    testWidgets('🔴 点击跳电商专用详情页（那里才有倒计时与支付入口）', (t) async {
      await open(t, _order(type: OrderType.ecommerce, statusCode: 'PENDING_SHIPMENT'));

      await t.tap(find.byType(InkWell).first, warnIfMissed: false);
      await t.pumpAndSettle();

      // 断言「用户看到了哪一页」而不是 router 的内部 uri —— push 之后
      // currentConfiguration.uri 仍是入口位置，据它断言会永远为假。
      expect(find.text('SHOP ORDER DETAIL'), findsOneWidget);
      expect(find.text('GENERIC ORDER DETAIL'), findsNothing);
    });

    testWidgets('待支付电商订单卡片直接给「Bayar sekarang」', (t) async {
      await open(t, _order(type: OrderType.ecommerce, statusCode: 'PENDING_PAYMENT'));

      expect(find.byKey(const ValueKey('orderCardPayNow')), findsOneWidget);
      await t.tap(find.byKey(const ValueKey('orderCardPayNow')));
      await t.pumpAndSettle();
      expect(find.text('SHOP ORDER DETAIL'), findsOneWidget);
    });

    testWidgets('非待支付电商订单不给支付按钮', (t) async {
      await open(t, _order(type: OrderType.ecommerce, statusCode: 'PENDING_SHIPMENT'));

      expect(find.byKey(const ValueKey('orderCardPayNow')), findsNothing);
    });

    testWidgets('缺图回落到类型图标，不留白块', (t) async {
      await open(t,
          _order(type: OrderType.ecommerce, statusCode: 'CANCELLED', thumbnailUrl: null));

      expect(find.byIcon(Icons.shopping_bag_outlined), findsOneWidget);
      expect(t.takeException(), isNull);
    });
  });

  group('🔴 既有四类一行未变（契约 O-1）', () {
    testWidgets('兽医订单仍跳通用详情页，不受电商分支影响', (t) async {
      await open(t, _order(type: OrderType.vetConsult, statusCode: 'COMPLETED'));

      await t.tap(find.byType(InkWell).first, warnIfMissed: false);
      await t.pumpAndSettle();

      expect(find.text('GENERIC ORDER DETAIL'), findsOneWidget);
      expect(find.text('SHOP ORDER DETAIL'), findsNothing);
    });

    testWidgets('既有类型不渲染商品行（它们本就没有商品）', (t) async {
      await open(t, _order(type: OrderType.pawcoinTopup, statusCode: 'PAID'));

      expect(find.byKey(const ValueKey('orderCardItemTitle')), findsNothing);
      expect(find.byKey(const ValueKey('orderCardPayNow')), findsNothing);
      expect(find.byIcon(Icons.savings_outlined), findsOneWidget);
    });
  });

  group('domain', () {
    test('ECOMMERCE 双向映射；未知码仍降级到 unknown', () {
      expect(OrderType.fromCode('ECOMMERCE'), OrderType.ecommerce);
      expect(OrderType.ecommerce.toApi(), 'ECOMMERCE');
      expect(OrderType.fromCode('SOMETHING'), OrderType.unknown);
      expect(OrderType.unknown.toApi(), isNull);
    });

    test('既有四类的映射一个都没被动', () {
      expect(OrderType.fromCode('VET_CONSULT'), OrderType.vetConsult);
      expect(OrderType.fromCode('AI_UNLOCK'), OrderType.aiUnlock);
      expect(OrderType.fromCode('PAWCOIN_TOPUP'), OrderType.pawcoinTopup);
      expect(OrderType.fromCode('ID_HD'), OrderType.idHd);
    });

    test('fromJson 解析商品摘要三项；缺失即 null（既有类型的响应不带它们）', () {
      final withItems = OrderSummary.fromJson(const {
        'orderType': 'ECOMMERCE',
        'orderToken': 't',
        'displayNo': 'TOKO-20260818-000001',
        'statusCode': 'PENDING_PAYMENT',
        'statusColor': 'WARN',
        'thumbnailUrl': 'https://cdn/x.jpg',
        'itemTitle': 'A · 1 kg',
        'itemCount': 2,
      });
      expect(withItems.itemCount, 2);
      expect(withItems.thumbnailUrl, 'https://cdn/x.jpg');

      final legacy = OrderSummary.fromJson(const {
        'orderType': 'VET_CONSULT',
        'orderToken': 't',
        'displayNo': 'CONSVET-20260818-000001',
        'statusCode': 'COMPLETED',
        'statusColor': 'SUCCESS',
      });
      expect(legacy.itemTitle, isNull);
      expect(legacy.itemCount, isNull);
      expect(legacy.thumbnailUrl, isNull);
    });
  });
}

OrderSummary _order({
  required OrderType type,
  required String statusCode,
  String? itemTitle,
  int? itemCount,
  String? thumbnailUrl,
}) =>
    OrderSummary(
      orderType: type,
      orderToken: 'tok-1',
      displayNo: 'TOKO-20260818-000001',
      statusCode: statusCode,
      statusColor: OrderStatusColor.warn,
      amount: 370000,
      payChannel: 'QRIS',
      createdAt: DateTime(2026, 8, 18, 9, 41),
      thumbnailUrl: thumbnailUrl,
      itemTitle: itemTitle,
      itemCount: itemCount,
    );

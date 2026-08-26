import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/theme/shop_tokens.dart';
import 'package:tailtopia/features/order/domain/order_summary.dart';
import 'package:tailtopia/features/order/presentation/order_l10n.dart';
import 'package:tailtopia/features/order/presentation/order_list_page_v2.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/l10n/app_localizations_id.dart';

/// 订单列表卡片（v2 版式）—— **2026-08-19 模拟器验收抓到的两个缺陷的护栏**。
///
/// 两个都属于「单测与 analyze 全绿、跑起来才看得见」那一类：
/// 前者落在 `switch` 的兜底分支上（合法），后者落在一个恒不成立的相等判断上（也合法）。
void main() {
  Future<void> open(WidgetTester t, OrderSummary order) async {
    await t.pumpWidget(MaterialApp.router(
      routerConfig: GoRouter(
        initialLocation: '/list',
        routes: [
          GoRoute(path: '/list', builder: (c, s) => Scaffold(body: OrderCardV2(order: order))),
          GoRoute(
              path: '/shop/orders/:token',
              builder: (c, s) => const Scaffold(body: Text('SHOP DETAIL'))),
        ],
      ),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('id'),
    ));
    await t.pumpAndSettle();
  }

  group('🔴 状态文案不得漏出后端枚举', () {
    testWidgets('SHIPPED → `Dikirim`，而不是枚举字面量本身', (t) async {
      await open(t, _order(statusCode: 'SHIPPED', color: OrderStatusColor.info));

      expect(find.text('Dikirim'), findsOneWidget);
      expect(find.text('SHIPPED'), findsNothing,
          reason: '兜底分支 `_ => statusCode` 会把枚举原样显示给用户');
    });

    testWidgets('DELIVERED 同理', (t) async {
      await open(t, _order(statusCode: 'DELIVERED', color: OrderStatusColor.info));

      expect(find.text('DELIVERED'), findsNothing);
    });

    test('映射函数本身：履约两态都有分支（不落兜底）', () {
      // 兜底的特征就是「返回值 == 入参」。用它做判定，比断言具体文案更抗文案改动。
      for (final code in ['SHIPPED', 'DELIVERED', 'PENDING_PAYMENT', 'PENDING_SHIPMENT']) {
        expect(orderStatusLabel(_l10n, code), isNot(code),
            reason: '$code 没有分支 → 落兜底 → 枚举漏到 UI');
      }
    });
  });

  group('🔴 电商待支付单的支付入口', () {
    testWidgets('PENDING_PAYMENT → 有 `Bayar Sekarang`', (t) async {
      await open(t, _order(statusCode: 'PENDING_PAYMENT'));

      expect(find.byKey(const ValueKey('orderPayNowV2_tok-1')), findsOneWidget,
          reason: '电商待支付码是 PENDING_PAYMENT；只判 PENDING 会让这张卡一个按钮都没有');
    });

    testWidgets('PENDING（虚拟单）→ 同样有支付入口，没被改坏', (t) async {
      await open(t, _order(statusCode: 'PENDING'));

      expect(find.byKey(const ValueKey('orderPayNowV2_tok-1')), findsOneWidget);
    });

    testWidgets('已完成 → 不给支付入口', (t) async {
      await open(t, _order(statusCode: 'COMPLETED', color: OrderStatusColor.success));

      expect(find.byKey(const ValueKey('orderPayNowV2_tok-1')), findsNothing);
    });

    testWidgets('待支付金额玫红、已完成金额墨色（三色分工）', (t) async {
      await open(t, _order(statusCode: 'PENDING_PAYMENT'));
      expect(_amountColor(t), ShopColors.accent);

      await open(t, _order(statusCode: 'COMPLETED', color: OrderStatusColor.success));
      expect(_amountColor(t), ShopColors.ink);
    });
  });
}

Color? _amountColor(WidgetTester t) => t
    .widgetList<Text>(find.byType(Text))
    .firstWhere((w) => (w.data ?? '').contains('370.000'))
    .style
    ?.color;

final AppLocalizations _l10n = AppLocalizationsId();

OrderSummary _order({
  required String statusCode,
  OrderStatusColor color = OrderStatusColor.warn,
}) =>
    OrderSummary(
      orderType: OrderType.ecommerce,
      orderToken: 'tok-1',
      displayNo: 'TOKO-20260819-000001',
      statusCode: statusCode,
      statusColor: color,
      amount: 370000,
      payChannel: 'MIXED',
      createdAt: DateTime(2026, 8, 19, 9, 41),
      itemTitle: 'Whiskas Adult Cat · 1.2 kg',
      itemCount: 1,
    );

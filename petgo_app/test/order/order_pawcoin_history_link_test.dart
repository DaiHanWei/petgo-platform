import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/order/domain/order_summary.dart';
import 'package:tailtopia/features/order/presentation/order_list_controller.dart';
import 'package:tailtopia/features/order/presentation/order_list_page_v2.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// bug 20261006-582：订单页 PawCoin 页签只列充值单；分享奖励等在 PawCoin 明细页，需给出入口。
class _FakeOrders extends OrderListController {
  _FakeOrders(this.filter);

  final OrderType? filter;

  @override
  Future<OrderListState> build() async => OrderListState(items: const [], filter: filter);
}

void main() {
  Future<void> pump(WidgetTester tester, OrderType? filter) async {
    final router = GoRouter(routes: [
      GoRoute(path: '/', builder: (c, s) => const OrderListPageV2()),
      GoRoute(path: '/me/pawcoin', builder: (c, s) => const Scaffold(body: Text('pawcoin-history'))),
    ]);
    addTearDown(router.dispose);
    await tester.pumpWidget(ProviderScope(
      overrides: [orderListProvider.overrideWith(() => _FakeOrders(filter))],
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
      ),
    ));
    await tester.pumpAndSettle();
  }

  testWidgets('PawCoin 页签：顶部有「去 PawCoin 明细」入口，点击进入 /me/pawcoin', (tester) async {
    await pump(tester, OrderType.pawcoinTopup);

    final link = find.byKey(const ValueKey('orderPawcoinHistoryLink'));
    expect(link, findsOneWidget);
    expect(find.text('Di sini hanya isi ulang. Hadiah dan pemakaian ada di riwayat PawCoin.'), findsOneWidget);

    await tester.tap(link);
    await tester.pumpAndSettle();
    expect(find.text('pawcoin-history'), findsOneWidget);
  });

  testWidgets('其它页签不显示该入口', (tester) async {
    await pump(tester, null);
    expect(find.byKey(const ValueKey('orderPawcoinHistoryLink')), findsNothing);
  });
}

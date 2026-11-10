import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/theme/shop_tokens.dart';
import 'package:tailtopia/features/shop/data/shop_order_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_order_detail.dart';
import 'package:tailtopia/features/shop/presentation/shop_order_detail_page_v3.dart';
import 'package:tailtopia/features/shop/presentation/shop_order_header.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_surface.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// v1.4.2 shop Story 9.2 AC1：订单详情退款状态头（F13 / F16 / F19 / F25）与入口（F13 / F17 底栏）。
/// 示例数取 UI 稿：币 12.000、QRIS 317.000、补偿 15.850。
Map<String, dynamic> _r({
  String token = 'rf1',
  String scope = 'WHOLE',
  String reason = 'OUT_OF_STOCK',
  required String status,
  int coin = 12000,
  int cash = 317000,
  String? destination,
  String? channel,
  String? last4,
  int fee = 0,
  int compensation = 0,
  String? paidOutAt,
  String? createdAt,
}) =>
    {
      'refundToken': token,
      'scope': scope,
      'reason': reason,
      'status': status,
      'totalRefund': coin + cash,
      'coinRefund': coin,
      'cashRefund': cash,
      'cashDestination': destination,
      'payoutChannel': channel,
      'payoutAccountLast4': last4,
      'payoutFee': fee,
      'compensationAmount': compensation,
      'paidOutAt': paidOutAt,
      'createdAt': createdAt,
      'canChooseDestination': status == 'AWAITING_DESTINATION' || status == 'PAYOUT_FAILED',
      'payoutFailed': status == 'PAYOUT_FAILED',
      'compensate': true,
      'compensationIfPawcoin': 15850,
    };

ShopOrderDetail _d(List<Map<String, dynamic>> refunds,
        {String status = 'REFUNDING', String? cancelReason = 'OUT_OF_STOCK', Map<String, dynamic> extra = const {}}) =>
    ShopOrderDetail.fromJson({
      'orderToken': 'ord1',
      'status': status,
      'cancelReason': cancelReason,
      'refunds': refunds,
      ...extra,
    });

void main() {
  group('纯函数 resolveShopOrderHeader / displayRefundOf', () {
    test('无退款单 / 无状态的退款单 → 不挂退款态（9.1 行为不变）', () {
      expect(resolveShopOrderHeader(_d(const [])).refundStage, isNull);
      expect(resolveShopOrderHeader(_d([{'refundToken': 'x', 'scope': 'WHOLE'}])).refundStage, isNull);
    });

    test('现金段 = 0 的 COMPLETED（等同 COIN_REFUNDED）不改状态头', () {
      final h = resolveShopOrderHeader(_d([_r(status: 'COMPLETED', cash: 0)]));
      expect(h.refundStage, isNull);
      expect(h.kind, ShopHeaderKind.refunding);
    });

    test('多张退款单优先级：PAYOUT_FAILED > AWAITING > PENDING > COMPLETED', () {
      final all = [
        _r(token: 'done', status: 'COMPLETED', destination: 'TO_PAWCOIN'),
        _r(token: 'pend', status: 'PAYOUT_PENDING'),
        _r(token: 'wait', scope: 'LINE', status: 'AWAITING_DESTINATION'),
        _r(token: 'fail', status: 'PAYOUT_FAILED'),
      ];
      expect(displayRefundOf(ShopOrderDetail.fromJson({'orderToken': 'o', 'refunds': all}).refunds)!.refundToken,
          'fail');
      expect(displayRefundOf(_d(all.sublist(0, 3)).refunds)!.refundToken, 'wait');
      expect(displayRefundOf(_d(all.sublist(0, 2)).refunds)!.refundToken, 'pend');
    });

    test('同级取最新创建', () {
      final h = resolveShopOrderHeader(_d([
        _r(token: 'old', scope: 'LINE', status: 'AWAITING_DESTINATION', createdAt: '2026-11-01T01:00:00Z'),
        _r(token: 'new', status: 'AWAITING_DESTINATION', createdAt: '2026-11-03T01:00:00Z'),
        _r(token: 'mid', status: 'AWAITING_DESTINATION', createdAt: '2026-11-02T01:00:00Z'),
      ]));
      expect(h.refund!.refundToken, 'new');
    });

    test('F13：整单缺货 → 全量换文案 + 米黄；其他原因 → 不换文案但仍米黄 + 入口；LINE → 不米黄', () {
      final oos = resolveShopOrderHeader(_d([_r(status: 'AWAITING_DESTINATION')]));
      expect(oos.refundTextMode, ShopRefundTextMode.full);
      expect(oos.warnTone, isTrue);
      expect(oos.showsChooseRefund, isTrue);

      final other = resolveShopOrderHeader(
          _d([_r(status: 'AWAITING_DESTINATION', reason: 'WAYBILL_FAILED')], cancelReason: 'WAYBILL_FAILED'));
      expect(other.refundTextMode, ShopRefundTextMode.none);
      expect(other.warnTone, isTrue);
      expect(other.showsChooseRefund, isTrue);

      final line = resolveShopOrderHeader(_d([_r(scope: 'LINE', status: 'AWAITING_DESTINATION')],
          status: 'PENDING_SHIPMENT', cancelReason: null));
      expect(line.kind, ShopHeaderKind.pendingShipment);
      expect(line.warnTone, isFalse);
      expect(line.showsChooseRefund, isTrue);
    });

    test('F16 / F19：整单全量换；部分取消只换副句（订单仍有效，保留原标题）', () {
      expect(resolveShopOrderHeader(_d([_r(status: 'PAYOUT_PENDING')])).refundTextMode, ShopRefundTextMode.full);
      final line = resolveShopOrderHeader(
          _d([_r(scope: 'LINE', status: 'PAYOUT_PENDING')], status: 'SHIPPED', cancelReason: null));
      expect(line.kind, ShopHeaderKind.shipped);
      expect(line.refundTextMode, ShopRefundTextMode.hintOnly);
    });

    test('F25：任何范围都全量换 + 状态头按钮', () {
      final h = resolveShopOrderHeader(
          _d([_r(scope: 'LINE', status: 'PAYOUT_FAILED')], status: 'PENDING_SHIPMENT', cancelReason: null));
      expect(h.refundTextMode, ShopRefundTextMode.full);
      expect(h.showsReenterAccount, isTrue);
      expect(h.showsChooseRefund, isFalse);
    });

    test('到账额展示：cashRefund − 服务端 payoutFee（AC3.4 先例，唯一一处）', () {
      expect(payoutNetForDisplay(317000, 2500), 314500);
      expect(payoutNetForDisplay(317000, 0), 317000);
    });
  });

  group('详情页渲染', () {
    late GoRouter router;
    Widget host(ShopOrderDetail order) {
      router = GoRouter(initialLocation: '/shop/orders/ord1', routes: [
        GoRoute(
            path: '/shop/orders/:token',
            builder: (c, s) => ShopOrderDetailPageV3(orderToken: s.pathParameters['token']!)),
        GoRoute(
            path: '/shop/orders/:token/refunds/:refundToken',
            builder: (c, s) => Scaffold(body: Text('REFUND ${s.uri}'))),
      ]);
      return ProviderScope(
        overrides: [shopOrderDetailProvider.overrideWith((ref, token) async => order)],
        child: MaterialApp.router(
          routerConfig: router,
          localizationsDelegates: const [
            AppLocalizations.delegate,
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
          ],
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
        ),
      );
    }

    String title(WidgetTester t) => t.widget<Text>(find.byKey(const ValueKey('shopOrderHeaderTitle'))).data!;
    String? hint(WidgetTester t) {
      final f = find.byKey(const ValueKey('shopOrderHeaderHint'));
      return f.evaluate().isEmpty ? null : t.widget<Text>(f).data;
    }

    Finder choose() => find.byKey(const ValueKey('shopOrderChooseRefund'));

    testWidgets('F13 缺货：标题 + 副句（服务端币 / 现金段）+ 米黄底 + 底栏入口 → 退款方式页', (tester) async {
      await tester.pumpWidget(host(_d([_r(status: 'AWAITING_DESTINATION')])));
      await tester.pumpAndSettle();
      expect(title(tester), 'Dibatalkan · stok habis');
      expect(hint(tester),
          'Maaf, pesanan dibatalkan karena stok habis. PawCoin Rp 12.000 sudah dikembalikan. '
          'Pilih cara pengembalian untuk bagian QRIS Rp 317.000.');
      final card = tester.widget<ShopCard>(find.byKey(const ValueKey('shopOrderHeaderCardWarn')));
      expect(card.color, ShopColors.warnBg);
      expect(find.text('Pilih cara pengembalian dana'), findsOneWidget);
      await tester.tap(choose());
      await tester.pumpAndSettle();
      expect(find.text('REFUND /shop/orders/ord1/refunds/rf1'), findsOneWidget);
    });

    testWidgets('F13 其他原因（运单失败）：原标题、无新副句，仍给入口', (tester) async {
      await tester.pumpWidget(host(_d([_r(status: 'AWAITING_DESTINATION', reason: 'WAYBILL_FAILED')],
          cancelReason: 'WAYBILL_FAILED')));
      await tester.pumpAndSettle();
      expect(title(tester), 'Pengembalian diproses');
      expect(hint(tester), isNull);
      expect(choose(), findsOneWidget);
    });

    testWidgets('F17 部分取消待选：保持部分取消样式（白底、原副句），底栏入口', (tester) async {
      await tester.pumpWidget(host(_d([_r(scope: 'LINE', status: 'AWAITING_DESTINATION')],
          status: 'PENDING_SHIPMENT',
          cancelReason: null,
          extra: {
            'cancelledLines': [
              {'lineIndex': 0, 'cancelledQty': 1}
            ]
          })));
      await tester.pumpAndSettle();
      expect(title(tester), 'Menunggu dikirim');
      expect(hint(tester), '1 produk dibatalkan karena stok habis. Produk lain tetap dikirim.');
      expect(find.byKey(const ValueKey('shopOrderHeaderCardWarn')), findsNothing);
      expect(choose(), findsOneWidget);
    });

    testWidgets('F16 退款处理中：渠道短名 + 末 4 位；无底栏', (tester) async {
      await tester.pumpWidget(host(_d([
        _r(status: 'PAYOUT_PENDING', destination: 'TO_BANK', channel: 'GOPAY', last4: '0695', fee: 2500)
      ])));
      await tester.pumpAndSettle();
      expect(title(tester), 'Pengembalian dana diproses');
      expect(hint(tester), 'Estimasi masuk dalam 3 hari kerja ke GoPay •••• 0695.');
      expect(find.byType(ShopBottomBarActions), findsNothing);
    });

    testWidgets('F16 缺末 4 位 → 只显示标题，不拼残句', (tester) async {
      await tester.pumpWidget(
          host(_d([_r(status: 'PAYOUT_PENDING', destination: 'TO_BANK', channel: 'BCA')])));
      await tester.pumpAndSettle();
      expect(title(tester), 'Pengembalian dana diproses');
      expect(hint(tester), isNull);
    });

    testWidgets('F19 银行：到账额 = 服务端 cashRefund − payoutFee、WIB 日期', (tester) async {
      await tester.pumpWidget(host(_d([
        _r(
            status: 'COMPLETED',
            destination: 'TO_BANK',
            channel: 'OVO',
            last4: '0695',
            fee: 2500,
            // 18:30 UTC = 次日 01:30 WIB
            paidOutAt: '2026-11-08T18:30:00Z')
      ], status: 'REFUNDED')));
      await tester.pumpAndSettle();
      expect(title(tester), 'Dana dikembalikan');
      expect(hint(tester), 'Rp 314.500 sudah dikirim ke OVO •••• 0695 pada 9 Nov 2026.');
      expect(find.byType(ShopBottomBarActions), findsNothing);
    });

    testWidgets('F19 PawCoin：现金段与补偿并列，不相加', (tester) async {
      await tester.pumpWidget(host(_d([_r(status: 'COMPLETED', destination: 'TO_PAWCOIN', compensation: 15850)],
          status: 'REFUNDED')));
      await tester.pumpAndSettle();
      expect(title(tester), 'Dana dikembalikan');
      expect(hint(tester), 'Rp 317.000 sudah masuk ke saldo PawCoin');
      expect(find.text('+ bonus Rp 15.850'), findsOneWidget);
      expect(find.textContaining('332.850'), findsNothing);
    });

    testWidgets('F25 打款失败：标题 / 副句 / 状态头按钮 → 预选上次渠道进入', (tester) async {
      await tester.pumpWidget(host(_d([
        _r(status: 'PAYOUT_FAILED', destination: 'TO_BANK', channel: 'BCA', last4: '0695')
      ])));
      await tester.pumpAndSettle();
      expect(title(tester), 'Transfer pengembalian dana gagal');
      expect(hint(tester), 'Silakan isi ulang rekening penerima, atau ubah jadi PawCoin.');
      expect(choose(), findsNothing);
      await tester.tap(find.byKey(const ValueKey('shopOrderRefundReenter')));
      await tester.pumpAndSettle();
      expect(find.text('REFUND /shop/orders/ord1/refunds/rf1?retry=1&channel=BCA'), findsOneWidget);
    });

    testWidgets('多张退款单：只展示优先级最高的一张（失败 > 待选）', (tester) async {
      await tester.pumpWidget(host(_d([
        _r(token: 'a', scope: 'LINE', status: 'AWAITING_DESTINATION'),
        _r(token: 'b', status: 'PAYOUT_FAILED', channel: 'BCA'),
      ])));
      await tester.pumpAndSettle();
      expect(title(tester), 'Transfer pengembalian dana gagal');
      expect(choose(), findsNothing);
    });
  });
}

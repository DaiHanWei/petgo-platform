import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/pay_channel_picker.dart';

/// V1.4.0 multi-pet Story 2.4 · AC2.1 / AC3.1（D5 拍板）：`PayChannelPicker` 的可选数量步进器 / 条目区。
/// 不传时与改动前一致（既有 5 处调用的 picker 测试全绿即证明；这里另断言不出现新增控件）。
void main() {
  late AppLocalizations l10n;
  setUpAll(() async => l10n = await AppLocalizations.delegate.load(const Locale('id')));

  Future<List<HdPayChannel?>> pump(
    WidgetTester tester, {
    PayQuantityConfig? quantity,
    Widget? itemsArea,
    int? itemsTotal,
    int balance = 8000,
    int price = 5000,
  }) async {
    final popped = <HdPayChannel?>[];
    await tester.pumpWidget(MaterialApp(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Builder(
        builder: (context) => Scaffold(
          body: Center(
            child: TextButton(
              key: const ValueKey('open'),
              onPressed: () async {
                popped.add(await showModalBottomSheet<HdPayChannel>(
                  context: context,
                  isScrollControlled: true,
                  builder: (_) => PayChannelPicker(
                    header: const SizedBox.shrink(),
                    title: 'T',
                    body: 'B',
                    price: AsyncData(price),
                    onRetryPrice: () {},
                    balance: balance,
                    confirmLabel: (p) => 'pay:${p ?? '-'}',
                    quantity: quantity,
                    itemsArea: itemsArea,
                    itemsTotal: itemsTotal,
                    itemsLineLabel: itemsArea == null ? null : 'items',
                  ),
                ));
              },
              child: const Text('open'),
            ),
          ),
        ),
      ),
    ));
    await tester.tap(find.byKey(const ValueKey('open')));
    await tester.pumpAndSettle();
    return popped;
  }

  testWidgets('不传 quantity / itemsArea：无步进器、无总价行，金额 = 价格本身', (tester) async {
    await pump(tester);
    expect(find.byKey(const ValueKey('payTotalRow')), findsNothing);
    expect(find.byKey(const ValueKey('payQtyPlus')), findsNothing);
    expect(find.text('pay:5000'), findsOneWidget);
    expect(find.text('Rp5.000'), findsOneWidget, reason: 'QRIS 行');
  });

  testWidgets('数量步进器：上下限、总价行、PawCoin 余额判断 / QRIS 金额 / 主按钮随数量变化', (tester) async {
    final changes = <int>[];
    final popped = await pump(tester,
        quantity: PayQuantityConfig(
          label: 'Jumlah slot',
          max: 3,
          lineLabel: (n) => '$n × Rp5.000',
          onChanged: changes.add,
        ));
    expect(find.text('Jumlah slot'), findsOneWidget);
    expect(find.text('1 × Rp5.000'), findsOneWidget);
    expect(find.text('pay:5000'), findsOneWidget);
    // 余额 8000 ≥ 5000：PawCoin 可选（无「去充值」）。
    expect(find.text(l10n.triageUnlockTopupFirst), findsNothing);
    expect(find.text(l10n.idCardHdPawcoinSub('8.000', '5.000')), findsOneWidget);

    // 下限 1：减号无效。
    await tester.tap(find.byKey(const ValueKey('payQtyMinus')));
    await tester.pump();
    expect(changes, isEmpty);

    await tester.tap(find.byKey(const ValueKey('payQtyPlus')));
    await tester.pump();
    expect(changes, [2]);
    expect(find.text('2 × Rp5.000'), findsOneWidget);
    expect(find.text('Rp10.000'), findsNWidgets(2), reason: '总价行 + QRIS 行');
    expect(find.text('pay:10000'), findsOneWidget);
    // 总价 10000 > 余额 8000：PawCoin 置灰 + 去充值，默认改选 QRIS。
    expect(find.text(l10n.triageUnlockTopupFirst), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('payQtyPlus')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('payQtyPlus')));
    await tester.pump();
    expect(changes, [2, 3], reason: '上限 3');
    expect(find.text('3 × Rp5.000'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('hdPayConfirm')));
    await tester.pumpAndSettle();
    expect(popped, [HdPayChannel.qris]);
  });

  testWidgets('code review #2：先选中 PawCoin，数量加到超过余额 → 回落 QRIS（确认结果与界面一致）', (tester) async {
    final popped = await pump(tester,
        balance: 8000,
        quantity: PayQuantityConfig(label: 'Jumlah slot', max: 3, lineLabel: (n) => '$n'));
    // 余额够 → 显式点选 PawCoin。
    await tester.tap(find.text('PawCoin'));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('payQtyPlus')));
    await tester.pump();
    expect(find.text(l10n.triageUnlockTopupFirst), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('hdPayConfirm')));
    await tester.pumpAndSettle();
    expect(popped, [HdPayChannel.qris]);
  });

  testWidgets('数量减回余额以内 → 显式选过的 PawCoin 恢复', (tester) async {
    final popped = await pump(tester,
        balance: 8000,
        quantity: PayQuantityConfig(label: 'Jumlah slot', max: 3, lineLabel: (n) => '$n'));
    await tester.tap(find.text('PawCoin'));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('payQtyPlus')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('payQtyMinus')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('hdPayConfirm')));
    await tester.pumpAndSettle();
    expect(popped, [HdPayChannel.pawcoin]);
  });

  testWidgets('条目区：金额取 itemsTotal；为 0 → 主按钮禁用', (tester) async {
    await pump(tester, itemsArea: const Text('rows'), itemsTotal: 0);
    expect(find.text('rows'), findsOneWidget);
    expect(find.text('items'), findsOneWidget);
    expect(tester.widget<FilledButton>(find.byKey(const ValueKey('hdPayConfirm'))).onPressed, isNull);
  });

  testWidgets('条目区：itemsTotal 15000 → 主按钮 / QRIS 按 15000', (tester) async {
    await pump(tester, itemsArea: const Text('rows'), itemsTotal: 15000, balance: 20000);
    expect(find.text('pay:15000'), findsOneWidget);
    expect(find.text('Rp15.000'), findsNWidgets(2));
    expect(find.text(l10n.idCardHdPawcoinSub('20.000', '15.000')), findsOneWidget);
  });
}

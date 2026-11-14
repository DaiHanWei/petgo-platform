import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/shared/widgets/pay_method_cards.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.5.0 gift-pack Story 2-2 · AC1（L0，结构断言）：两张付款卡四态。
void main() {
  late List<PayMethod> selects;
  late int topUps;
  setUp(() {
    selects = [];
    topUps = 0;
  });

  Future<void> pump(WidgetTester tester,
      {int balance = 50000, PayMethod? selected, String? directDisabledReason}) async {
    await tester.pumpWidget(MaterialApp(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: SizedBox(
          width: 400,
          child: PayMethodCards(
            coinAmount: 5000,
            cashAmount: 5000,
            balance: balance,
            selected: selected,
            onSelect: selects.add,
            onTopUp: () => topUps++,
            directDisabledReason: directDisabledReason,
          ),
        ),
      ),
    ));
  }

  Finder inCard(String which, Finder f) =>
      find.descendant(of: find.byKey(ValueKey('payMethodCard-$which')), matching: f);

  testWidgets('enough balance: amounts, grey balance, tap selects', (tester) async {
    await pump(tester);
    expect(inCard('pawcoin', find.text('5.000 PawCoin')), findsOneWidget);
    expect(inCard('pawcoin', find.text('Saldo 50.000')), findsOneWidget);
    expect(inCard('direct', find.text('Rp5.000')), findsOneWidget);
    expect(inCard('direct', find.text('QRIS')), findsOneWidget);
    expect(inCard('direct', find.text('Bayar langsung')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('payMethodCard-pawcoin')));
    await tester.tap(find.byKey(const ValueKey('payMethodCard-direct')));
    expect(selects, [PayMethod.pawcoin, PayMethod.direct]);
  });

  testWidgets('short balance: red short line + top up, not selectable, tap tops up', (tester) async {
    await pump(tester, balance: 3000, selected: PayMethod.pawcoin);
    expect(inCard('pawcoin', find.text('Saldo 3.000 · kurang 2.000')), findsOneWidget);
    expect(inCard('pawcoin', find.text('Isi saldo dulu →')), findsOneWidget);
    expect(inCard('pawcoin', find.byKey(const ValueKey('payMethodCheck'))), findsNothing);
    await tester.tap(find.byKey(const ValueKey('payMethodCard-pawcoin')));
    expect(topUps, 1);
    expect(selects, isEmpty);
  });

  testWidgets('direct disabled: reason replaces amount, not selectable', (tester) async {
    await pump(tester, directDisabledReason: 'Minimal Rp10.000', selected: PayMethod.direct);
    expect(inCard('direct', find.text('Minimal Rp10.000')), findsOneWidget);
    expect(inCard('direct', find.text('Rp5.000')), findsNothing);
    expect(inCard('direct', find.byKey(const ValueKey('payMethodCheck'))), findsNothing);
    await tester.tap(find.byKey(const ValueKey('payMethodCard-direct')));
    expect(selects, isEmpty);
  });

  testWidgets('selected shows check on that card only', (tester) async {
    await pump(tester, selected: PayMethod.direct);
    expect(inCard('direct', find.byKey(const ValueKey('payMethodCheck'))), findsOneWidget);
    expect(inCard('pawcoin', find.byKey(const ValueKey('payMethodCheck'))), findsNothing);
  });
}

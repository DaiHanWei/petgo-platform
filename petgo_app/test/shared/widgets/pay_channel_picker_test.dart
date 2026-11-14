import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/pay_channel_picker.dart';

/// L0：V1.5.0 gift-pack Story 6-1 · AC1 —— 通用付费抽屉的付款方式区改为左右两张卡（`PayMethodCards`）：
/// 默认选中、余额不足卡（红字 + 去充值）、选中样式、直接付款禁用、价格加载中、推荐行 / 免费次数整块替换插槽。
void main() {
  late List<Object?> results;
  late List<String> topUps;

  setUp(() {
    results = [];
    topUps = [];
  });

  Future<void> open(
    WidgetTester tester, {
    AsyncValue<int> price = const AsyncValue.data(5000),
    int balance = 10000,
    String? qrisDisabledReason,
    Widget? recommendation,
    Widget? creditOverride,
    Widget? itemsArea,
    int? itemsTotal,
  }) async {
    final router = GoRouter(routes: [
      GoRoute(
        path: '/',
        builder: (context, _) => Scaffold(
          body: Center(
            child: ElevatedButton(
              key: const ValueKey('open'),
              onPressed: () async {
                final r = await showModalBottomSheet<HdPayChannel>(
                  context: context,
                  isScrollControlled: true,
                  builder: (_) => PayChannelPicker(
                    header: null,
                    title: 'T',
                    body: 'B',
                    price: price,
                    onRetryPrice: () {},
                    balance: balance,
                    confirmLabel: (p) => 'Pay ${p ?? '-'}',
                    qrisDisabledReason: qrisDisabledReason,
                    onTopUp: () => topUps.add('topup'),
                    recommendation: recommendation,
                    creditOverride: creditOverride,
                    itemsArea: itemsArea,
                    itemsTotal: itemsTotal,
                    itemsLineLabel: itemsArea == null ? null : 'L',
                  ),
                );
                results.add(r);
              },
              child: const Text('open'),
            ),
          ),
        ),
      ),
    ]);
    await tester.pumpWidget(MaterialApp.router(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      routerConfig: router,
    ));
    await tester.tap(find.byKey(const ValueKey('open')));
    await tester.pumpAndSettle();
  }

  Finder check(String card) => find.descendant(
      of: find.byKey(ValueKey('payMethodCard-$card')), matching: find.byKey(const ValueKey('payMethodCheck')));

  FilledButton confirm(WidgetTester tester) => tester.widget<FilledButton>(find.byKey(const ValueKey('hdPayConfirm')));

  testWidgets('余额够 → 默认 PawCoin（勾在 PawCoin 卡）；确认返回 pawcoin', (tester) async {
    await open(tester, balance: 10000);
    expect(find.byKey(const ValueKey('payMethodCards')), findsOneWidget);
    expect(check('pawcoin'), findsOneWidget);
    expect(check('direct'), findsNothing);
    expect(find.text('5.000 PawCoin'), findsOneWidget);
    expect(find.text('Saldo 10.000'), findsOneWidget);
    expect(find.text('Rp5.000'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('hdPayConfirm')));
    await tester.pumpAndSettle();
    expect(results, [HdPayChannel.pawcoin]);
  });

  testWidgets('点直接付款卡 → 选中移过去；确认返回 qris', (tester) async {
    await open(tester, balance: 10000);
    await tester.tap(find.byKey(const ValueKey('payMethodCard-direct')));
    await tester.pumpAndSettle();
    expect(check('direct'), findsOneWidget);
    expect(check('pawcoin'), findsNothing);
    await tester.tap(find.byKey(const ValueKey('hdPayConfirm')));
    await tester.pumpAndSettle();
    expect(results, [HdPayChannel.qris]);
  });

  testWidgets('余额不足 → 默认直接付款；PawCoin 卡红字「Saldo · kurang」+「Isi saldo dulu →」，点卡 = 关抽屉 + onTopUp',
      (tester) async {
    await open(tester, balance: 1200);
    expect(check('direct'), findsOneWidget);
    expect(find.byKey(const ValueKey('payMethodShort')), findsOneWidget);
    expect(find.text('Saldo 1.200 · kurang 3.800'), findsOneWidget);
    expect(find.byKey(const ValueKey('payMethodTopUp')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('payMethodCard-pawcoin')));
    await tester.pumpAndSettle();
    expect(topUps, ['topup']);
    expect(results, [null], reason: '抽屉已关闭');
  });

  testWidgets('条目区一个都没勾 + 余额不足单价 → PawCoin 卡出「余额不足 / 去充值」，默认直接付款（6-1 review #1）',
      (tester) async {
    await open(tester, balance: 3000, itemsArea: const SizedBox(key: ValueKey('items')), itemsTotal: 0);
    expect(check('direct'), findsOneWidget);
    expect(find.byKey(const ValueKey('payMethodShort')), findsOneWidget);
    expect(find.byKey(const ValueKey('payMethodTopUp')), findsOneWidget);
    expect(confirm(tester).onPressed, isNull, reason: '没勾 → 确认置灰');
  });

  testWidgets('直接付款被禁用 + 余额不足 → 两者都不可选、主按钮置灰', (tester) async {
    await open(tester, balance: 100, qrisDisabledReason: 'Minimal Rp10.000');
    expect(find.text('Minimal Rp10.000'), findsOneWidget);
    expect(check('pawcoin'), findsNothing);
    expect(check('direct'), findsNothing);
    expect(confirm(tester).onPressed, isNull);
  });

  testWidgets('价格加载中 → 金额「…」、主按钮置灰、不判余额不足', (tester) async {
    await open(tester, price: const AsyncValue.loading(), balance: 100);
    expect(find.text('… PawCoin'), findsOneWidget);
    expect(find.text('Rp…'), findsOneWidget);
    expect(find.byKey(const ValueKey('payMethodShort')), findsNothing);
    expect(confirm(tester).onPressed, isNull);
  });

  testWidgets('推荐行插槽：插在两张卡与主按钮之间', (tester) async {
    await open(tester, recommendation: const Text('REKOMENDASI', key: ValueKey('rec')));
    final rec = tester.getCenter(find.byKey(const ValueKey('rec'))).dy;
    expect(rec, greaterThan(tester.getCenter(find.byKey(const ValueKey('payMethodCards'))).dy));
    expect(rec, lessThan(tester.getCenter(find.byKey(const ValueKey('hdPayConfirm'))).dy));
  });

  testWidgets('免费次数插槽：整块替换「付款方式区 + 推荐行 + 主按钮」', (tester) async {
    await open(tester,
        recommendation: const Text('REKOMENDASI'),
        creditOverride: const Text('PAKAI JATAH', key: ValueKey('credit')));
    expect(find.byKey(const ValueKey('credit')), findsOneWidget);
    expect(find.byKey(const ValueKey('payMethodCards')), findsNothing);
    expect(find.text('REKOMENDASI'), findsNothing);
    expect(find.byKey(const ValueKey('hdPayConfirm')), findsNothing);
  });
}

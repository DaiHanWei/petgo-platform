import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/pawcoin/data/pawcoin_repository.dart';
import 'package:tailtopia/features/pawcoin/domain/pawcoin_transaction.dart';
import 'package:tailtopia/features/gift/presentation/gift_orders_page.dart';
import 'package:tailtopia/features/pawcoin/presentation/pawcoin_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：PawCoin 余额与流水页。余额渲染、正负色/符号、空态、错误态+重试（AC2/3/4）。
class _FakePawCoinRepo extends PawCoinRepository {
  _FakePawCoinRepo({this.page, this.throwErr = false}) : super(dio: Dio());

  final PawCoinWalletPage? page;
  final bool throwErr;

  @override
  Future<PawCoinWalletPage> fetch({String? cursor, int limit = 20}) async {
    if (throwErr) throw DioException(requestOptions: RequestOptions(path: '/x'));
    return page!;
  }
}

Future<void> _pump(WidgetTester tester, _FakePawCoinRepo repo) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [pawCoinRepositoryProvider.overrideWithValue(repo)],
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('en'),
      home: const PawCoinPage(),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('renders balance and ledger with signed colors', (tester) async {
    final page = PawCoinWalletPage(
      balance: 120000,
      items: [
        PawCoinTxnItem(delta: 50000, type: 'TOPUP', createdAt: DateTime(2026, 7, 1)),
        PawCoinTxnItem(delta: -5000, type: 'SPEND', createdAt: DateTime(2026, 7, 2)),
      ],
      hasMore: false,
    );
    await _pump(tester, _FakePawCoinRepo(page: page));

    expect(find.byKey(const ValueKey('pawcoinBalance')), findsOneWidget);
    expect(find.text('120.000'), findsWidgets); // 余额 + 汇率副行
    expect(find.text('+50.000'), findsOneWidget); // 入账绿 +
    expect(find.text('-5.000'), findsOneWidget); // 消费红 -
    expect(find.text('Top-up'), findsOneWidget); // 类型按 code 本地化
    expect(find.text('Spent'), findsOneWidget);
  });

  testWidgets('shows warm empty state when no transactions', (tester) async {
    final page = PawCoinWalletPage(balance: 0, items: const [], hasMore: false);
    await _pump(tester, _FakePawCoinRepo(page: page));

    expect(find.byKey(const ValueKey('pawcoinEmpty')), findsOneWidget);
  });

  testWidgets('shows explicit error + retry, never silent empty', (tester) async {
    await _pump(tester, _FakePawCoinRepo(throwErr: true));

    expect(find.byKey(const ValueKey('pawcoinError')), findsOneWidget);
    expect(find.byKey(const ValueKey('pawcoinRetry')), findsOneWidget);
    // 关键：错误态不是空态。
    expect(find.byKey(const ValueKey('pawcoinEmpty')), findsNothing);
  });

  // ===== V1.4.0 gift Story 2.3 · AC2 / AC3 =====

  Future<GoRouter> pumpRouted(WidgetTester tester, PawCoinWalletPage page, {Locale locale = const Locale('id')}) async {
    final router = GoRouter(routes: [
      GoRoute(path: '/', builder: (c, s) => const PawCoinPage()),
      GoRoute(
          path: GiftOrdersPage.routePath,
          builder: (c, s) => const Scaffold(body: Text('gift-orders-stub'))),
    ]);
    await tester.pumpWidget(ProviderScope(
      overrides: [pawCoinRepositoryProvider.overrideWithValue(_FakePawCoinRepo(page: page))],
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: locale,
      ),
    ));
    await tester.pumpAndSettle();
    return router;
  }

  testWidgets('送礼记录入口卡在余额卡（含 Isi Saldo）与流水标题之间，点击 push /me/gift-orders', (tester) async {
    final page = PawCoinWalletPage(
      balance: 1000,
      items: [PawCoinTxnItem(delta: 1000, type: 'TOPUP', createdAt: DateTime(2026, 7, 1))],
      hasMore: false,
    );
    final router = await pumpRouted(tester, page);

    final entry = find.byKey(const ValueKey('pawcoinGiftOrdersEntry'));
    expect(entry, findsOneWidget);
    expect(find.descendant(of: entry, matching: find.text('Riwayat Hadiah')), findsOneWidget);
    expect(find.descendant(of: entry, matching: find.text('Hadiah yang kamu kirim')), findsOneWidget);
    expect(find.descendant(of: entry, matching: find.byIcon(Icons.redeem_rounded)), findsOneWidget);

    final isiSaldoBottom = tester.getBottomLeft(find.byKey(const ValueKey('pawcoinIsiSaldo'))).dy;
    final entryTop = tester.getTopLeft(entry).dy;
    final entryBottom = tester.getBottomLeft(entry).dy;
    final ctx = tester.element(entry);
    final titleTop = tester.getTopLeft(find.text(AppLocalizations.of(ctx).pawcoinTxnListTitle)).dy;
    expect(entryTop, greaterThan(isiSaldoBottom));
    expect(titleTop, greaterThan(entryBottom));

    await tester.tap(entry);
    await tester.pumpAndSettle();
    expect(find.text('gift-orders-stub'), findsOneWidget);
    expect(router.routerDelegate.currentConfiguration.last.matchedLocation, GiftOrdersPage.routePath);
  });

  testWidgets('流水先看 refType：GIFT →「Kirim hadiah」+ 收据图标；GIFT_REFUND（type = TOPUP）→「Hadiah dikembalikan」+ replay',
      (tester) async {
    final page = PawCoinWalletPage(
      balance: 0,
      items: [
        PawCoinTxnItem(delta: -10000, type: 'SPEND', refType: 'GIFT', createdAt: DateTime(2026, 10, 2)),
        PawCoinTxnItem(delta: 10000, type: 'TOPUP', refType: 'GIFT_REFUND', createdAt: DateTime(2026, 10, 1)),
      ],
      hasMore: false,
    );
    await pumpRouted(tester, page);

    expect(find.text('Kirim hadiah'), findsOneWidget);
    expect(find.text('Hadiah dikembalikan'), findsOneWidget);
    expect(find.byIcon(Icons.receipt_long_outlined), findsOneWidget);
    expect(find.byIcon(Icons.replay), findsOneWidget);
    // 退回不能显示成「Isi saldo」+ 加号。
    expect(find.byIcon(Icons.add), findsNothing);
    expect(find.text('-10.000'), findsOneWidget);
    expect(find.text('+10.000'), findsOneWidget);
  });

  testWidgets('其他流水（无 refType / 非送礼 refType）文案与图标不变', (tester) async {
    // 入口卡插入后流水下移：加高视口，让四行都在屏内（ListView 懒构建）。
    tester.view.physicalSize = const Size(800, 2000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    final page = PawCoinWalletPage(
      balance: 0,
      items: [
        PawCoinTxnItem(delta: 50000, type: 'TOPUP', createdAt: DateTime(2026, 7, 4)),
        PawCoinTxnItem(delta: -5000, type: 'SPEND', refType: 'SHOP_ORDER', createdAt: DateTime(2026, 7, 3)),
        PawCoinTxnItem(delta: 5000, type: 'REFUND', createdAt: DateTime(2026, 7, 2)),
        PawCoinTxnItem(delta: 1000, type: 'BONUS', createdAt: DateTime(2026, 7, 1)),
      ],
      hasMore: false,
    );
    await pumpRouted(tester, page, locale: const Locale('en'));

    expect(find.text('Top-up'), findsOneWidget);
    expect(find.text('Spent'), findsOneWidget);
    expect(find.text('Refund'), findsOneWidget);
    expect(find.text('Bonus'), findsOneWidget);
    expect(find.text('Gift sent'), findsNothing);
    expect(find.text('Gift returned'), findsNothing);
    expect(find.byIcon(Icons.add), findsOneWidget);
    expect(find.byIcon(Icons.receipt_long_outlined), findsOneWidget);
    expect(find.byIcon(Icons.replay), findsOneWidget);
    expect(find.byIcon(Icons.card_giftcard_outlined), findsOneWidget);
  });
}

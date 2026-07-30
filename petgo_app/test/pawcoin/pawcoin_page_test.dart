import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/pawcoin/data/pawcoin_repository.dart';
import 'package:tailtopia/features/pawcoin/domain/pawcoin_transaction.dart';
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
}

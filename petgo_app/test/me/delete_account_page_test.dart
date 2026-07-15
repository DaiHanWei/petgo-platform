import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/me/presentation/delete_account_page.dart';
import 'package:tailtopia/features/pawcoin/data/pawcoin_repository.dart';
import 'package:tailtopia/features/pawcoin/domain/pawcoin_transaction.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：注销页 PawCoin 作废二次告知（Story 1.6，FR-50D）。有余额→带 koin 数；余额 0 / 加载失败→通用作废串。
/// 关键：告知是既定事实，不可因余额加载失败而消失。
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
      home: const DeleteAccountPage(),
    ),
  ));
  await tester.pumpAndSettle();
}

const String _generic = 'Your PawCoin balance will be permanently forfeited';

void main() {
  testWidgets('shows PawCoin forfeit with balance when balance > 0', (tester) async {
    await _pump(tester,
        _FakePawCoinRepo(page: PawCoinWalletPage(balance: 5000, items: const [], hasMore: false)));

    expect(find.textContaining('5000 koin'), findsOneWidget);
    expect(find.textContaining('permanently forfeited'), findsOneWidget);
  });

  testWidgets('shows generic forfeit notice when balance is zero', (tester) async {
    await _pump(tester,
        _FakePawCoinRepo(page: PawCoinWalletPage(balance: 0, items: const [], hasMore: false)));

    expect(find.text(_generic), findsOneWidget);
  });

  testWidgets('forfeit notice never disappears even if balance load fails', (tester) async {
    await _pump(tester, _FakePawCoinRepo(throwErr: true));

    // 余额加载失败 → 回落通用作废串（告知不依赖余额可用性）。
    expect(find.text(_generic), findsOneWidget);
  });
}

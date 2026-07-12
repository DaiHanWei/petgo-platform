import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/pawcoin/data/pawcoin_repository.dart';
import 'package:tailtopia/features/pawcoin/domain/topup.dart';
import 'package:tailtopia/features/pawcoin/presentation/recharge_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：PawCoin 充值页。档位/渠道选择、下单进支付态、失败态（余额不变）、暂停态、加载错误态（AC3-6）。
class _FakeRechargeRepo extends PawCoinRepository {
  _FakeRechargeRepo({this.options, this.optionsThrow = false, this.pollResult = 'PENDING'})
      : super(dio: Dio());

  final TopupOptions? options;
  final bool optionsThrow;
  final String pollResult;

  @override
  Future<TopupOptions> fetchTopupOptions() async {
    if (optionsThrow) throw DioException(requestOptions: RequestOptions(path: '/x'));
    return options!;
  }

  @override
  Future<TopupResult> createTopup(
          {required String tierId, required String channel, required String idemKey}) async =>
      TopupResult(intentToken: 'tok-1', channel: channel, amount: 10000, coins: 10000, payload: 'stub-qr://x');

  @override
  Future<String> pollStatus(String intentToken) async => pollResult;
}

TopupOptions _opts({bool paused = false}) => TopupOptions(
      tiers: const [
        TopupTierOption(id: '10k', amount: 10000, coins: 10000),
        TopupTierOption(id: '50k', amount: 50000, coins: 50000),
      ],
      paused: paused,
    );

Future<void> _pump(WidgetTester tester, _FakeRechargeRepo repo) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [pawCoinRepositoryProvider.overrideWithValue(repo)],
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('en'),
      home: const RechargePage(),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('renders tiers and submits to paying view', (tester) async {
    await _pump(tester, _FakeRechargeRepo(options: _opts()));

    expect(find.byKey(const ValueKey('rechargeTier-10k')), findsOneWidget);
    expect(find.byKey(const ValueKey('rechargeTier-50k')), findsOneWidget);
    expect(find.byKey(const ValueKey('rechargeChannel-QRIS')), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('rechargeSubmit')));
    await tester.pump(); // 下单 async
    await tester.pump();

    expect(find.byKey(const ValueKey('rechargePaying')), findsOneWidget); // 进支付态（QRIS stub QR）
    // 注：轮询 Timer 在运行，测试结束 dispose 会 cancel（无泄漏）。
  });

  testWidgets('shows pause state when paused', (tester) async {
    await _pump(tester, _FakeRechargeRepo(options: _opts(paused: true)));
    expect(find.byKey(const ValueKey('rechargePause')), findsOneWidget);
    expect(find.byKey(const ValueKey('rechargeTier-10k')), findsNothing); // 暂停不渲染档位
  });

  testWidgets('shows load error when options fail', (tester) async {
    await _pump(tester, _FakeRechargeRepo(optionsThrow: true));
    expect(find.byKey(const ValueKey('rechargeLoadError')), findsOneWidget);
  });

  testWidgets('polling FAILED shows fail state (balance unchanged)', (tester) async {
    await _pump(tester, _FakeRechargeRepo(options: _opts(), pollResult: 'FAILED'));

    await tester.tap(find.byKey(const ValueKey('rechargeSubmit')));
    await tester.pump();
    await tester.pump();
    // 轮询触发（3s periodic）→ FAILED → 失败态，Timer 已 cancel
    await tester.pump(const Duration(seconds: 3));
    await tester.pump();

    expect(find.byKey(const ValueKey('rechargeFail')), findsOneWidget);
    expect(find.text('Your balance is unchanged — nothing was recorded.'), findsOneWidget);
  });
}

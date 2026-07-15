import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/consult/data/consult_repository.dart';
import 'package:tailtopia/features/consult/domain/consult_pay_result.dart';
import 'package:tailtopia/features/consult/domain/consult_request.dart';
import 'package:tailtopia/features/consult/presentation/vet_timed_pay_page.dart';
import 'package:tailtopia/features/pawcoin/presentation/pawcoin_controller.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 假 repo：状态轮询恒返 ACCEPTED（不触发跃迁），支付不被调用。
class _FakeConsultRepo extends ConsultRepository {
  _FakeConsultRepo() : super(dio: Dio());

  @override
  Future<ConsultRequestStatus> requestStatus(String token) async =>
      ConsultRequestStatus(state: ConsultRequestState.acceptedAwaitPay,
          payDeadlineAt: DateTime.now().add(const Duration(seconds: 90)));

  @override
  Future<ConsultPayResult> payRequest(String token, String channel) async =>
      const ConsultPayResult(mode: 'DONE');
}

/// 假 PawCoin 控制器：余额 0（不足），同步返回不碰 dio（避免 HTTP 超时 timer 残留）。
class _FakePawController extends PawCoinController {
  @override
  Future<PawCoinState> build() async => const PawCoinState(balance: 0, items: []);
}

void main() {
  /// L0 widget。Story 3.5 限时支付屏渲染：倒计时盒 + 3 渠道 + 支付按钮全程可用。
  /// 余额默认 0（未 override pawCoinProvider，AsyncError→balance 0）→ PawCoin 不足行 + 去充值可见。
  testWidgets('renders countdown, channels, always-available pay button + insufficient', (tester) async {
    tester.platformDispatcher.localesTestValue = const [Locale('id')];
    final container = ProviderContainer(overrides: [
      consultRepositoryProvider.overrideWithValue(_FakeConsultRepo()),
      pawCoinProvider.overrideWith(_FakePawController.new),
    ]);
    addTearDown(container.dispose);

    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: VetTimedPayPage(
          requestToken: 'req-1',
          payDeadlineAt: DateTime.now().add(const Duration(seconds: 90)),
        ),
      ),
    ));
    await tester.pump();

    expect(find.byKey(const ValueKey('vetPayCountdown')), findsOneWidget);
    expect(find.byKey(const ValueKey('vetPayChannel_PAWCOIN')), findsOneWidget);
    expect(find.byKey(const ValueKey('vetPayChannel_QRIS')), findsOneWidget);
    // DANA 已取消（2026-07-13 产品决策）→ 无 DANA 渠道。
    expect(find.byKey(const ValueKey('vetPayChannel_DANA')), findsNothing);
    // 支付按钮存在且可用（全程可用）。
    final payBtn = tester.widget<FilledButton>(find.byKey(const ValueKey('vetPayButton')));
    expect(payBtn.onPressed, isNotNull);
    // PawCoin 默认选中 + 余额 0 → 不足行 + 去充值。
    expect(find.byKey(const ValueKey('vetPayInsufficient')), findsOneWidget);
    expect(find.byKey(const ValueKey('vetPayTopup')), findsOneWidget);

    // 切到 QRIS → 不足行消失（现金无余额约束）。
    await tester.tap(find.byKey(const ValueKey('vetPayChannel_QRIS')));
    await tester.pump();
    expect(find.byKey(const ValueKey('vetPayInsufficient')), findsNothing);

    // 卸载以取消周期 timer（避免 pending timer 报错）。
    await tester.pumpWidget(const SizedBox());
  });
}

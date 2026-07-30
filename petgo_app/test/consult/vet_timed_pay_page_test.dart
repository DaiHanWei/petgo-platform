import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
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

  @override
  Future<int> vetConsultPrice() async => 50000; // 同步返回不碰 dio（避免 timer 残留）

  bool cancelCalled = false;

  @override
  Future<void> cancelRequest(String token) async {
    cancelCalled = true;
  }
}

/// 假 PawCoin 控制器：余额 0（不足），同步返回不碰 dio（避免 HTTP 超时 timer 残留）。
class _FakePawController extends PawCoinController {
  @override
  Future<PawCoinState> build() async => const PawCoinState(balance: 0, items: []);
}

/// 假 repo：QRIS 支付返回 PAYMENT_REQUIRED + 二维码载荷（验证展示二维码 + 取消按钮）。
class _QrisPayRepo extends ConsultRepository {
  _QrisPayRepo() : super(dio: Dio());

  @override
  Future<ConsultRequestStatus> requestStatus(String token) async =>
      ConsultRequestStatus(state: ConsultRequestState.acceptedAwaitPay,
          payDeadlineAt: DateTime.now().add(const Duration(seconds: 90)));

  @override
  Future<ConsultPayResult> payRequest(String token, String channel) async =>
      const ConsultPayResult(mode: 'PAYMENT_REQUIRED', payload: 'STUB-QR-PAYLOAD');

  @override
  Future<int> vetConsultPrice() async => 50000; // 同步返回不碰 dio（避免 timer 残留）
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

  /// L0 widget（问题1/2）：选 QRIS 支付 → 展示二维码（本地生成）+ 主动取消按钮。
  testWidgets('QRIS 支付后展示二维码 + 取消按钮', (tester) async {
    tester.platformDispatcher.localesTestValue = const [Locale('id')];
    final container = ProviderContainer(overrides: [
      consultRepositoryProvider.overrideWithValue(_QrisPayRepo()),
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

    // 选 QRIS → 点支付 → 进现金二维码态。
    await tester.tap(find.byKey(const ValueKey('vetPayChannel_QRIS')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('vetPayButton')));
    await tester.pump(); // payRequest microtask
    await tester.pump();

    // 二维码 + 取消按钮出现（不再是纯 spinner）。
    expect(find.byKey(const ValueKey('vetPayQr')), findsOneWidget);
    expect(find.byKey(const ValueKey('vetPayCancel')), findsOneWidget);

    await tester.pumpWidget(const SizedBox());
  });

  /// 0730 拍板：待支付按系统返回键=主动取消老请求（删请求+释放兽医），
  /// 重新发起走全新排队——不再是 UX-DR14「离开后服务端重播」（否则老请求
  /// 残留占用态，重新发起命中 alreadyActive 直跳付款、跳过排队）。
  testWidgets('待支付按返回键 → 调 cancelRequest 主动取消 → 回 /triage', (tester) async {
    tester.platformDispatcher.localesTestValue = const [Locale('id')];
    final repo = _FakeConsultRepo();
    final router = GoRouter(
      routes: [
        GoRoute(
          path: '/',
          builder: (_, _) => VetTimedPayPage(
            requestToken: 'req-1',
            payDeadlineAt: DateTime.now().add(const Duration(seconds: 90)),
          ),
        ),
        GoRoute(path: '/triage', builder: (_, _) => const Scaffold(body: Text('triage'))),
      ],
    );

    await tester.pumpWidget(ProviderScope(
      overrides: [
        consultRepositoryProvider.overrideWithValue(repo),
        pawCoinProvider.overrideWith(_FakePawController.new),
      ],
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
      ),
    ));
    await tester.pump();

    // 模拟系统返回键（PopScope canPop:false → onPopInvokedWithResult）。
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();

    expect(repo.cancelCalled, isTrue); // 老请求被主动取消
    expect(find.text('triage'), findsOneWidget); // 已离开付款页
    // 消费取消提示 toast 的自动消退 Timer（2600ms），避免残留 timer 判失败。
    await tester.pump(const Duration(milliseconds: 2700));
    await tester.pumpAndSettle();
  });
}

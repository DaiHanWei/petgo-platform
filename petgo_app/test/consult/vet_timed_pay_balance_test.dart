import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/consult/data/consult_repository.dart';
import 'package:tailtopia/features/consult/domain/consult_pay_result.dart';
import 'package:tailtopia/features/consult/domain/consult_request.dart';
import 'package:tailtopia/features/consult/domain/consult_session.dart';
import 'package:tailtopia/features/consult/presentation/vet_timed_pay_page.dart';
import 'package:tailtopia/features/pawcoin/domain/pawcoin_transaction.dart';
import 'package:tailtopia/features/pawcoin/presentation/pawcoin_controller.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// bug 20260721-322（vet_timed_pay 同类隐患）：付款时用同步 `.value?.balance ?? 0`
/// 读 PawCoin 余额；provider 还在 Loading（用户没进过钱包页/加载慢）时读到 0 →
/// 误判「PawCoin 不足」→ 付款按钮把有钱用户错误地跳去充值页。
/// 期望：付款前 await 真实余额（await .future），足额则正常发起付款、不误跳充值。

/// build 挂在 Completer 上，可控地把 provider 卡在 AsyncLoading（复现付款竞态）。
class _SlowPawCoin extends PawCoinController {
  _SlowPawCoin(this.completer);
  final Completer<PawCoinState> completer;
  @override
  Future<PawCoinState> build() => completer.future;
}

/// 记录 payRequest / pauseRequest 是否被调用的假 repo。
class _FakeConsultRepo extends ConsultRepository {
  _FakeConsultRepo() : super(dio: Dio());
  bool payCalled = false;
  bool pauseCalled = false;

  @override
  Future<ConsultRequestStatus> requestStatus(String token) async =>
      ConsultRequestStatus(
        state: ConsultRequestState.acceptedAwaitPay,
        payDeadlineAt: DateTime.now().add(const Duration(minutes: 5)),
      );

  @override
  Future<ConsultPayResult> payRequest(String token, String channel) async {
    payCalled = true;
    // 返回现金态（PAYMENT_REQUIRED）→ 页面进「等待到账」，不触发导航，便于断言。
    return const ConsultPayResult(mode: 'PAYMENT_REQUIRED', payload: 'qr');
  }

  @override
  Future<void> pauseRequest(String token) async {
    pauseCalled = true;
  }

  @override
  Future<ConsultSession?> active() async => null;
}

void main() {
  testWidgets(
      'bug 322(vet): PawCoin 未加载完就付款，也用真实余额判断（足额→发起付款，不误跳充值）',
      (tester) async {
    final completer = Completer<PawCoinState>();
    final repo = _FakeConsultRepo();
    final router = GoRouter(
      routes: [
        GoRoute(
          path: '/',
          builder: (_, _) => VetTimedPayPage(
            requestToken: 'tok',
            payDeadlineAt: DateTime.now().add(const Duration(minutes: 5)),
          ),
        ),
        GoRoute(
            path: '/me/pawcoin/recharge',
            builder: (_, _) => const Scaffold(body: Text('recharge'))),
        GoRoute(
            path: '/triage',
            builder: (_, _) => const Scaffold(body: Text('triage'))),
      ],
    );

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          pawCoinProvider.overrideWith(() => _SlowPawCoin(completer)),
          consultRepositoryProvider.overrideWithValue(repo),
        ],
        child: MaterialApp.router(
          routerConfig: router,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
        ),
      ),
    );
    await tester.pump(); // provider 仍 Loading（completer 未 complete）

    // 余额未加载完就点付款（竞态窗口）。
    await tester.tap(find.byKey(const ValueKey('vetPayButton')));
    await tester.pump();

    // 现在余额加载完成为 200000（≥ 价）。
    completer.complete(
        const PawCoinState(balance: 200000, items: <PawCoinTxnItem>[]));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    // 修复后：付款前 await 真实余额 200000 ≥ 价(50k) → 发起付款，不跳充值。
    // 修复前：同步读 0 → 误判不足 → pauseRequest + 跳充值，payRequest 从未调用（断言失败）。
    expect(repo.payCalled, isTrue);
    expect(repo.pauseCalled, isFalse);

    // 清理：卸载页面取消周期 timer。
    await tester.pumpWidget(const SizedBox());
  });
}

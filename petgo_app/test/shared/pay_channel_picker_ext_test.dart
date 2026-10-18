import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/pay_channel_picker.dart';

/// V1.4.0 gift Story 1.3 · AC3（L0）：`PayChannelPicker` 四个可选参数 + 「无选中」规则；
/// 不传新参数时行为与改动前一致（默认 PawCoin / 余额不足默认 QRIS / 充值 pop + push）。
void main() {
  late List<Object?> results;
  late List<String> pushed;

  setUp(() {
    results = [];
    pushed = [];
  });

  Future<void> open(
    WidgetTester tester, {
    int price = 5000,
    int balance = 10000,
    String? qrisDisabledReason,
    String? qrisDisabledHint,
    Future<bool> Function(HdPayChannel)? onConfirm,
    VoidCallback? onTopUp,
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
                    header: const SizedBox(key: ValueKey('hdr')),
                    title: 'T',
                    body: 'B',
                    price: AsyncValue.data(price),
                    onRetryPrice: () {},
                    balance: balance,
                    confirmLabel: (p) => 'Pay $p',
                    qrisDisabledReason: qrisDisabledReason,
                    qrisDisabledHint: qrisDisabledHint,
                    onConfirm: onConfirm,
                    onTopUp: onTopUp,
                  ),
                );
                results.add(r);
              },
              child: const Text('open'),
            ),
          ),
        ),
      ),
      GoRoute(
        path: '/me/pawcoin/recharge',
        builder: (_, _) {
          pushed.add('/me/pawcoin/recharge');
          return const Scaffold(body: Text('recharge'));
        },
      ),
    ]);
    await tester.pumpWidget(ProviderScope(
      child: MaterialApp.router(
        routerConfig: router,
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
      ),
    ));
    await tester.tap(find.byKey(const ValueKey('open')));
    await tester.pumpAndSettle();
  }

  bool confirmEnabled(WidgetTester tester) =>
      tester.widget<FilledButton>(find.byKey(const ValueKey('hdPayConfirm'))).onPressed != null;

  group('不传新参数 = 改动前行为', () {
    testWidgets('余额够 → 默认 PawCoin，确认 pop(PAWCOIN)', (tester) async {
      await open(tester);
      expect(find.byKey(const ValueKey('payQrisDisabled')), findsNothing);
      expect(find.byKey(const ValueKey('payQrisDisabledHint')), findsNothing);
      await tester.tap(find.byKey(const ValueKey('hdPayConfirm')));
      await tester.pumpAndSettle();
      expect(results, [HdPayChannel.pawcoin]);
    });

    testWidgets('余额不足 → 默认 QRIS；「先充值」pop + push 充值页', (tester) async {
      await open(tester, balance: 100);
      await tester.tap(find.byKey(const ValueKey('hdPayConfirm')));
      await tester.pumpAndSettle();
      expect(results, [HdPayChannel.qris]);

      results.clear();
      await tester.tap(find.byKey(const ValueKey('open')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Isi saldo dulu →'));
      await tester.pumpAndSettle();
      expect(results, [null]);
      expect(pushed, ['/me/pawcoin/recharge']);
    });
  });

  group('qrisDisabledReason / qrisDisabledHint（C2 / C4）', () {
    testWidgets('QRIS 行不可点、副标题替换、提示出现；余额够仍默认 PawCoin', (tester) async {
      await open(tester,
          price: 5000,
          balance: 10000,
          qrisDisabledReason: 'Minimal Rp10.000',
          qrisDisabledHint: 'Isi PawCoin biar kirim hadiah lebih praktis.');
      expect(find.byKey(const ValueKey('payQrisDisabled')), findsOneWidget);
      expect(find.text('Minimal Rp10.000'), findsOneWidget);
      expect(find.text('Rp5.000'), findsNothing, reason: 'QRIS 副标题被禁用原因取代');
      expect(find.byKey(const ValueKey('payQrisDisabledHint')), findsOneWidget);
      // 点 QRIS 行无效：确认后仍是 PawCoin。
      await tester.tap(find.text('QRIS'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('hdPayConfirm')));
      await tester.pumpAndSettle();
      expect(results, [HdPayChannel.pawcoin]);
    });

    testWidgets('QRIS 禁用 + 余额不足 → 无选中、确认置灰', (tester) async {
      await open(tester, price: 5000, balance: 100, qrisDisabledReason: 'Minimal Rp10.000');
      expect(confirmEnabled(tester), isFalse);
      expect(find.byIcon(Icons.check_circle), findsNothing);
    });
  });

  group('onConfirm（C5 / C8）', () {
    testWidgets('返回 false → 抽屉不关、按钮恢复；返回 true → 关闭并返回渠道', (tester) async {
      var answer = Completer<bool>();
      final calls = <HdPayChannel>[];
      await open(tester, onConfirm: (ch) {
        calls.add(ch);
        return answer.future;
      });
      await tester.tap(find.byKey(const ValueKey('hdPayConfirm')));
      await tester.pump();
      expect(find.byKey(const ValueKey('payConfirmBusy')), findsOneWidget, reason: '扣款中按钮转圈');
      expect(find.text('Pay 5000'), findsNothing);
      // 转圈期间：渠道行不可点、再点确认不重复发起。
      await tester.tap(find.text('QRIS'));
      await tester.tap(find.byKey(const ValueKey('hdPayConfirm')));
      await tester.pump();
      expect(calls, [HdPayChannel.pawcoin]);

      answer.complete(false);
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('hdPayConfirm')), findsOneWidget, reason: '抽屉保留可重试');
      expect(find.text('Pay 5000'), findsOneWidget);
      expect(results, isEmpty);

      answer = Completer<bool>();
      await tester.tap(find.byKey(const ValueKey('hdPayConfirm')));
      await tester.pump();
      answer.complete(true);
      await tester.pumpAndSettle();
      expect(results, [HdPayChannel.pawcoin]);
      expect(calls, [HdPayChannel.pawcoin, HdPayChannel.pawcoin]);
    });

    testWidgets('扣款中下滑 / 点遮罩都关不掉（保持打开）；空闲时下滑照常关闭', (tester) async {
      final answer = Completer<bool>();
      await open(tester, onConfirm: (_) => answer.future);
      await tester.tap(find.byKey(const ValueKey('hdPayConfirm')));
      await tester.pump();
      // 下滑（BottomSheet 的拖拽关闭不经 PopScope，须另挡）。
      await tester.drag(find.text('T'), const Offset(0, 600));
      await tester.pump(const Duration(milliseconds: 500));
      expect(find.byKey(const ValueKey('payConfirmBusy')), findsOneWidget, reason: '扣款中下滑不关');
      // 点遮罩。
      await tester.tapAt(const Offset(10, 10));
      await tester.pump(const Duration(milliseconds: 500));
      expect(find.byKey(const ValueKey('payConfirmBusy')), findsOneWidget, reason: '扣款中点遮罩不关');
      expect(results, isEmpty);

      answer.complete(false);
      await tester.pumpAndSettle();
      await tester.drag(find.text('T'), const Offset(0, 600));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('hdPayConfirm')), findsNothing, reason: '空闲时下滑照常关闭');
      expect(results, [null]);
    });

    testWidgets('选 QRIS 后确认 → 回调拿到 QRIS', (tester) async {
      final calls = <HdPayChannel>[];
      await open(tester, onConfirm: (ch) async {
        calls.add(ch);
        return true;
      });
      await tester.tap(find.text('QRIS'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('hdPayConfirm')));
      await tester.pumpAndSettle();
      expect(calls, [HdPayChannel.qris]);
      expect(results, [HdPayChannel.qris]);
    });
  });

  testWidgets('onTopUp：余额不足点「先充值」→ pop 后回调，不自己 push', (tester) async {
    var topUps = 0;
    await open(tester, balance: 100, onTopUp: () => topUps++);
    await tester.tap(find.text('Isi saldo dulu →'));
    await tester.pumpAndSettle();
    expect(topUps, 1);
    expect(results, [null]);
    expect(pushed, isEmpty);
  });
}

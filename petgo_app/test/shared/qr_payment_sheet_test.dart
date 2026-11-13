import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/qr_payment_sheet.dart';

/// L0：通用二维码支付面板（AI 解锁 / 身份证HD 复用）渲染二维码 + 取消关闭。
void main() {
  testWidgets('渲染二维码 + 取消按钮，点取消关闭返回 false', (tester) async {
    tester.platformDispatcher.localesTestValue = const [Locale('id')];
    tester.view.physicalSize = const Size(800, 1600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    bool? outcome;
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: Builder(
          builder: (ctx) => ElevatedButton(
            onPressed: () async {
              outcome = await showQrPaymentSheet(ctx, payload: 'QR-DATA', pollPaid: () async => false);
            },
            child: const Text('open'),
          ),
        ),
      ),
    ));

    await tester.tap(find.text('open'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500)); // sheet 滑入完成

    expect(find.byKey(const ValueKey('qrPayImage')), findsOneWidget);
    expect(find.byKey(const ValueKey('qrPayCancel')), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('qrPayCancel')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500)); // 关闭动画

    expect(find.byKey(const ValueKey('qrPayImage')), findsNothing);
    expect(outcome, isFalse);
  });

  // ================================================================
  // Story 1-3 AC7：新能力走可选参数，默认关闭
  //
  // 🔴 上面那条既有用例**一个字没改** —— 它就是「默认行为未变」的活证据。
  //    若哪天为了让它通过而不得不改它，说明默认行为被动了，回去重做 AC7。
  // ================================================================

  testWidgets('AC7 · 不传 onAborted 时，abort 仍按原样 resolve false（其余 3 个调用点靠这条）',
      (tester) async {
    tester.platformDispatcher.localesTestValue = const [Locale('id')];
    tester.view.physicalSize = const Size(800, 1600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    bool? outcome;
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: Builder(
          builder: (ctx) => ElevatedButton(
            onPressed: () async {
              outcome = await showQrPaymentSheet(ctx,
                  payload: 'QR-DATA',
                  // 老式的无参 const 构造必须仍可用。
                  pollPaid: () async => throw const QrPaymentAborted());
            },
            child: const Text('open'),
          ),
        ),
      ),
    ));

    await tester.tap(find.text('open'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    expect(find.byKey(const ValueKey('qrPayImage')), findsOneWidget);

    await tester.pump(const Duration(seconds: 3)); // 轮询 tick → abort
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    expect(find.byKey(const ValueKey('qrPayImage')), findsNothing);
    expect(outcome, isFalse, reason: '返回类型仍是 Future<bool>，语义未变');
  });

  testWidgets('AC7 · 传了 onAborted：abort 回调一次并带上类别', (tester) async {
    tester.platformDispatcher.localesTestValue = const [Locale('id')];
    tester.view.physicalSize = const Size(800, 1600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final List<String?> aborts = <String?>[];
    bool? outcome;
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: Builder(
          builder: (ctx) => ElevatedButton(
            onPressed: () async {
              outcome = await showQrPaymentSheet(ctx,
                  payload: 'QR-DATA',
                  onAborted: (a) => aborts.add(a.category),
                  pollPaid: () async =>
                      throw const QrPaymentAborted('GATEWAY_DECLINED'));
            },
            child: const Text('open'),
          ),
        ),
      ),
    ));

    await tester.tap(find.text('open'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pump(const Duration(seconds: 3));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    expect(aborts, <String?>['GATEWAY_DECLINED'], reason: '恰好回调一次，且带类别');
    expect(outcome, isFalse);
  });

  testWidgets('🔴 AC7/AC5 分界 · 用户点面板取消时 onAborted **不触发**', (tester) async {
    tester.platformDispatcher.localesTestValue = const [Locale('id')];
    tester.view.physicalSize = const Size(800, 1600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    int abortCalls = 0;
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: Builder(
          builder: (ctx) => ElevatedButton(
            onPressed: () async {
              await showQrPaymentSheet(ctx,
                  payload: 'QR-DATA',
                  onAborted: (_) => abortCalls++,
                  pollPaid: () async => false);
            },
            child: const Text('open'),
          ),
        ),
      ),
    ));

    await tester.tap(find.text('open'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    await tester.tap(find.byKey(const ValueKey('qrPayCancel')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    // 「付款已不可能完成」与「我先关掉待会再付」是两件事：混在一起就会把
    // 「关个面板」显示成「订单没了」。这条用例是那道分界线。
    expect(abortCalls, 0);
  });

  // ================================================================
  // 修复线（2026-11-12）：付完款从银行 App 切回来，抽屉仍停在「等待付款」
  // ================================================================

  Future<void> openSheet(WidgetTester tester, Future<bool> Function() poll, void Function(bool?) done) async {
    tester.platformDispatcher.localesTestValue = const [Locale('id')];
    tester.view.physicalSize = const Size(800, 1600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: Builder(
          builder: (ctx) => ElevatedButton(
            onPressed: () async => done(await showQrPaymentSheet(ctx, payload: 'QR-DATA', pollPaid: poll)),
            child: const Text('open'),
          ),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
  }

  testWidgets('后台时挂住的那次查询不再挡路：回到前台立刻重查，到账即关闭返回 true', (tester) async {
    bool? outcome;
    int calls = 0;
    final hang = Completer<bool>(); // 第一次查询永远不返回（模拟后台被限网）
    await openSheet(tester, () {
      calls++;
      return calls == 1 ? hang.future : Future.value(true);
    }, (v) => outcome = v);

    await tester.pump(const Duration(seconds: 3)); // 第一次轮询发出并挂住
    expect(calls, 1);
    await tester.pump(const Duration(seconds: 3)); // 挂住期间的 tick 被闸住，不并发
    expect(calls, 1);

    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed); // 从银行 App 切回
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    expect(calls, 2);
    expect(outcome, isTrue);
    expect(find.byKey(const ValueKey('qrPayImage')), findsNothing);
    hang.complete(false); // 迟到的旧结果被丢弃，不报错
    await tester.pump();
  });

  testWidgets('单次查询超过 8 秒即放弃，下一轮照常查到到账', (tester) async {
    bool? outcome;
    int calls = 0;
    await openSheet(tester, () {
      calls++;
      return calls == 1 ? Completer<bool>().future : Future.value(true);
    }, (v) => outcome = v);

    await tester.pump(const Duration(seconds: 3)); // 第 1 次挂住
    await tester.pump(const Duration(seconds: 8)); // 超时放弃
    await tester.pump(const Duration(seconds: 3)); // 下一轮
    await tester.pump(const Duration(milliseconds: 500));

    expect(calls, greaterThanOrEqualTo(2));
    expect(outcome, isTrue);
  });
}

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
}

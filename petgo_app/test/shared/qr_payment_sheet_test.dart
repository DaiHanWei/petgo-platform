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
}

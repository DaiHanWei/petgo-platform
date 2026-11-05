import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/qr_payment/qr_pay_save.dart';
import 'package:tailtopia/shared/widgets/qr_payment_sheet.dart';

/// v1.4.2 shop Story 7.2 · AC9.3：共用支付面板**不传任何新参数**时行为 = 改动前。
///
/// 🔴 送礼 / AI 解锁 / 身份证高清 / 一次性解锁 / 宠物名额月租五个调用点都不传新参数，
/// 本文件就是它们「不受影响」的证据：哪天为了让这里通过而改了断言，说明默认值被动了。
class _FakeGallery implements QrPayGallery {
  Uint8List? saved;

  @override
  Future<bool> hasAccess() async => true;

  @override
  Future<bool> requestAccess() async => true;

  @override
  Future<void> putImageBytes(Uint8List bytes, {required String name}) async => saved = bytes;

  @override
  Future<bool> openSettings() async => true;
}

void main() {
  setUpAll(() => initializeDateFormatting());

  late AppLocalizations l10n;
  setUpAll(() async => l10n = await AppLocalizations.delegate.load(const Locale('id')));

  Future<void> open(WidgetTester tester, {DateTime? expiresAt, void Function(bool)? onResult}) async {
    tester.platformDispatcher.localesTestValue = const [Locale('id')];
    tester.view.physicalSize = const Size(400, 860);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(MaterialApp(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: Builder(
          builder: (ctx) => ElevatedButton(
            onPressed: () async {
              final r = await showQrPaymentSheet(ctx,
                  payload: 'QR-DATA',
                  pollPaid: () async => false,
                  orderRef: 'PAYGIFT-1',
                  amountIdr: 52000,
                  expiresAt: expiresAt ?? DateTime.now().add(const Duration(minutes: 30)));
              onResult?.call(r);
            },
            child: const Text('open'),
          ),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
  }

  testWidgets('结构：无标题行 / 无 ×、金额标签 qrPayTotalLabel、红色取消在、无「我已付款」、指引平铺 5 步、60% 高',
      (tester) async {
    bool? outcome;
    await open(tester, onResult: (r) => outcome = r);

    expect(find.byKey(const ValueKey('qrPaySheetTitle')), findsNothing);
    expect(find.byKey(const ValueKey('qrPayCloseX')), findsNothing);
    expect(find.text(l10n.qrPayTotalLabel), findsOneWidget);
    expect(find.text(l10n.qrPayViaQrisLabel), findsNothing);
    expect(find.byKey(const ValueKey('qrPayCoinHeld')), findsNothing);
    expect(find.byKey(const ValueKey('qrPayCancel')), findsOneWidget);
    expect(find.text(l10n.vetPayCancel), findsOneWidget);
    expect(find.byKey(const ValueKey('qrPayPaidCheck')), findsNothing);
    expect(find.text(l10n.qrPayPaidCheck), findsNothing);
    expect(find.byKey(const ValueKey('qrPayCodeFailed')), findsNothing);
    // 指引平铺：没有折叠头，logo 与 5 条步骤直接可见。
    expect(find.byKey(const ValueKey('qrPayGuideToggle')), findsNothing);
    final guide = find.byKey(const ValueKey('qrPayGuide'));
    expect(guide, findsOneWidget);
    for (final step in [l10n.qrPayStep1, l10n.qrPayStep2, l10n.qrPayStep3, l10n.qrPayStep4]) {
      expect(find.descendant(of: guide, matching: find.text(step)), findsOneWidget);
    }
    expect(find.descendant(of: guide, matching: find.textContaining('Kode QR berlaku sampai')), findsOneWidget);
    // 固定 60% 屏高（含 48 拖拽条）：抽屉内容高 = 60% − 48，顶边在 40% 处。
    expect(find.byKey(const ValueKey('qrPayAdaptive')), findsNothing);
    expect(tester.getTopLeft(find.byType(BottomSheet)).dy, closeTo(860 * 0.4, 1));
    expect(tester.getSize(find.byType(BottomSheet)).height, closeTo(860 * 0.6, 1));
    // 按钮区吸底（docked 默认 true）：不在内部滚动区里。
    final scroll = find.byKey(const ValueKey('qrPayScroll'));
    expect(scroll, findsOneWidget);
    for (final k in const ['qrPaySave', 'qrPayCancel']) {
      expect(find.descendant(of: scroll, matching: find.byKey(ValueKey(k))), findsNothing, reason: '$k 应吸底');
    }
    expect(find.descendant(of: scroll, matching: find.byKey(const ValueKey('qrPayGuide'))), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('qrPayCancel')));
    await tester.pump(const Duration(milliseconds: 500));
    expect(outcome, isFalse);
  });

  testWidgets('存图成功 toast 仍是 cardSavedToGallery（不是电商扩写文案）', (tester) async {
    final g = _FakeGallery();
    qrPayGallery = g;
    addTearDown(() => qrPayGallery = const GalQrPayGallery());
    await open(tester);
    await tester.runAsync(() async {
      await tester.tap(find.byKey(const ValueKey('qrPaySave')));
      await Future<void>.delayed(const Duration(milliseconds: 300));
    });
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(g.saved, isNotNull);
    expect(find.text(l10n.cardSavedToGallery), findsOneWidget);
    expect(find.text(l10n.qrPaySavedOpenWallet), findsNothing);
    await tester.pump(const Duration(seconds: 3));
    await tester.tap(find.byKey(const ValueKey('qrPayCancel')));
    await tester.pump(const Duration(milliseconds: 500));
  });

  testWidgets('过期正文仍是 qrPayExpiredBody；无「我已付款」', (tester) async {
    await open(tester, expiresAt: DateTime.now().add(const Duration(seconds: 2)));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 2100)));
    await tester.pump(const Duration(seconds: 1));
    expect(find.byKey(const ValueKey('qrPayExpiredOverlay')), findsOneWidget);
    expect(find.text(l10n.qrPayExpiredBody), findsOneWidget);
    expect(find.text(l10n.shopOrderExpiredNotice), findsNothing);
    expect(find.byKey(const ValueKey('qrPayPaidCheck')), findsNothing);
    await tester.tap(find.byKey(const ValueKey('qrPayClose')));
    await tester.pump(const Duration(milliseconds: 500));
  });
}

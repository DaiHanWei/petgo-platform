import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/qr_payment/qr_pay_save.dart';
import 'package:tailtopia/shared/widgets/qr_payment_sheet.dart';

/// v1.4.2 shop Story 7.2：共用支付面板的**电商形态**（E1–E7，全部经可选参数打开）。
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

  const screen = Size(800, 1600);

  /// 以电商参数打开面板；返回值经 [onResult] 带出。
  Future<void> open(
    WidgetTester tester, {
    Future<bool> Function()? pollPaid,
    Future<bool> Function()? onPaidCheck,
    int? coinHeldIdr = 12000,
    DateTime? expiresAt,
    bool codeFailed = false,
    Future<String?> Function()? onRetryCode,
    bool Function(Object)? isRetryClosed,
    VoidCallback? onSaved,
    void Function(QrPaymentAborted)? onAborted,
    void Function(bool)? onResult,
  }) async {
    tester.platformDispatcher.localesTestValue = const [Locale('id')];
    tester.view.physicalSize = screen;
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
                  payload: codeFailed ? '' : 'QR-DATA',
                  pollPaid: pollPaid ?? () async => false,
                  orderRef: 'TOKO-20261105-ABC123',
                  amountIdr: 317000,
                  expiresAt: expiresAt ?? DateTime.now().add(const Duration(minutes: 59)),
                  onAborted: onAborted,
                  title: l10n.qrPaySheetTitle,
                  amountLabel: l10n.qrPayViaQrisLabel,
                  coinHeldIdr: coinHeldIdr,
                  showPaidCheck: true,
                  onPaidCheck: onPaidCheck ?? () async => false,
                  notPaidMessage: l10n.qrPayNotPaidYet,
                  hideCancel: true,
                  collapsibleGuide: true,
                  adaptiveHeight: true,
                  savedToastMessage: l10n.qrPaySavedOpenWallet,
                  onSaved: onSaved,
                  expiredBody: l10n.shopOrderExpiredNotice,
                  codeFailed: codeFailed,
                  onRetryCode: onRetryCode,
                  isRetryClosed: isRetryClosed);
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

  Future<void> closeX(WidgetTester tester) async {
    await tester.tap(find.byKey(const ValueKey('qrPayCloseX')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
  }

  testWidgets('E1 结构：标题 + ×、「Bayar via QRIS」、冻结行、无红色取消、「Saya sudah bayar」、指引默认收起',
      (tester) async {
    await open(tester);
    expect(find.text('Pembayaran'), findsOneWidget);
    expect(find.byKey(const ValueKey('qrPayCloseX')), findsOneWidget);
    expect(find.text('Bayar via QRIS'), findsOneWidget);
    expect(find.text(l10n.qrPayTotalLabel), findsNothing);
    expect(find.text('Rp317.000'), findsOneWidget);
    expect(find.text('+ PawCoin Rp 12.000 sudah ditahan'), findsOneWidget);
    expect(find.byKey(const ValueKey('qrPayImage')), findsOneWidget);
    expect(find.byKey(const ValueKey('qrPayCopy')), findsOneWidget);
    expect(find.text(l10n.rechargeWaiting), findsOneWidget);
    expect(find.text('Simpan Kode QR'), findsOneWidget);
    expect(find.text('Saya sudah bayar'), findsOneWidget);
    expect(find.byKey(const ValueKey('qrPayCancel')), findsNothing);
    expect(find.text(l10n.vetPayCancel), findsNothing);
    // 版式：主按钮 → 次按钮 → 折叠头（自上而下）。
    final save = tester.getTopLeft(find.byKey(const ValueKey('qrPaySave'))).dy;
    final check = tester.getTopLeft(find.byKey(const ValueKey('qrPayPaidCheck'))).dy;
    final toggle = tester.getTopLeft(find.byKey(const ValueKey('qrPayGuideToggle'))).dy;
    expect(save < check && check < toggle, isTrue);
    // 指引默认收起，点开出 5 步。
    expect(find.text('Cara bayar & aplikasi yang didukung'), findsOneWidget);
    expect(find.text(l10n.qrPayStep1), findsNothing);
    await tester.tap(find.byKey(const ValueKey('qrPayGuideToggle')));
    await tester.pump();
    for (final step in [l10n.qrPayStep1, l10n.qrPayStep2, l10n.qrPayStep3, l10n.qrPayStep4]) {
      expect(find.text(step), findsOneWidget);
    }
    expect(find.textContaining('Kode QR berlaku sampai'), findsOneWidget);
    await closeX(tester);
  });

  testWidgets('纯 QRIS（coinHeldIdr 为 null / 0）→ 不画冻结行', (tester) async {
    await open(tester, coinHeldIdr: 0);
    expect(find.byKey(const ValueKey('qrPayCoinHeld')), findsNothing);
    await closeX(tester);
  });

  testWidgets('自适应高度：内容少时低于 60% 屏高', (tester) async {
    await open(tester);
    expect(find.byKey(const ValueKey('qrPayAdaptive')), findsOneWidget);
    expect(tester.getSize(find.byType(BottomSheet)).height, lessThan(screen.height * 0.6));
    await closeX(tester);
  });

  testWidgets('× 关闭 → resolve false，onAborted 不触发', (tester) async {
    bool? outcome;
    var aborts = 0;
    await open(tester, onAborted: (_) => aborts++, onResult: (r) => outcome = r);
    await closeX(tester);
    expect(outcome, isFalse);
    expect(aborts, 0);
    expect(find.byKey(const ValueKey('qrPayImage')), findsNothing);
  });

  testWidgets('E2：存图成功 → 扩写 toast + onSaved 一次，面板不关', (tester) async {
    final g = _FakeGallery();
    qrPayGallery = g;
    addTearDown(() => qrPayGallery = const GalQrPayGallery());
    var saved = 0;
    await open(tester, onSaved: () => saved++);
    await tester.runAsync(() async {
      await tester.tap(find.byKey(const ValueKey('qrPaySave')));
      await Future<void>.delayed(const Duration(milliseconds: 300));
    });
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(g.saved, isNotNull);
    expect(saved, 1);
    expect(find.text(l10n.qrPaySavedOpenWallet), findsOneWidget);
    expect(find.text(l10n.cardSavedToGallery), findsNothing);
    expect(find.byKey(const ValueKey('qrPayImage')), findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
    await closeX(tester);
  });

  testWidgets('E3：查询中转圈 + 换文案 + 不可再点（连点只调一次）；E4：false → toast，面板保持', (tester) async {
    final gate = Completer<bool>();
    var calls = 0;
    await open(tester, onPaidCheck: () {
      calls++;
      return gate.future;
    });
    await tester.tap(find.byKey(const ValueKey('qrPayPaidCheck')));
    await tester.pump();
    expect(find.byKey(const ValueKey('qrPayPaidCheckBusy')), findsOneWidget);
    expect(find.text('Mengecek pembayaran…'), findsOneWidget);
    expect(tester.widget<OutlinedButton>(find.byKey(const ValueKey('qrPayPaidCheck'))).onPressed, isNull);
    await tester.tap(find.byKey(const ValueKey('qrPayPaidCheck')), warnIfMissed: false);
    await tester.pump();
    expect(calls, 1);

    gate.complete(false);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.text(l10n.qrPayNotPaidYet), findsOneWidget);
    expect(find.byKey(const ValueKey('qrPayImage')), findsOneWidget, reason: '面板保持');
    expect(find.text('Saya sudah bayar'), findsOneWidget, reason: '按钮恢复');
    await tester.pump(const Duration(seconds: 3));
    await closeX(tester);
  });

  testWidgets('E4：查询抛错 → 同一 toast，面板保持；自动轮询照旧', (tester) async {
    var polls = 0;
    await open(tester,
        pollPaid: () async {
          polls++;
          return false;
        },
        onPaidCheck: () async => throw StateError('net'));
    await tester.tap(find.byKey(const ValueKey('qrPayPaidCheck')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.text(l10n.qrPayNotPaidYet), findsOneWidget);
    expect(find.byKey(const ValueKey('qrPayImage')), findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
    expect(polls, greaterThanOrEqualTo(1));
    await closeX(tester);
  });

  testWidgets('「我已付款」true → 关面板 resolve true', (tester) async {
    bool? outcome;
    await open(tester, onPaidCheck: () async => true, onResult: (r) => outcome = r);
    await tester.tap(find.byKey(const ValueKey('qrPayPaidCheck')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    expect(outcome, isTrue);
    expect(find.byKey(const ValueKey('qrPayImage')), findsNothing);
  });

  testWidgets('轮询先确认到账 → 「我已付款」的迟到结果被忽略（共用 _closing）', (tester) async {
    final gate = Completer<bool>();
    var paid = false;
    bool? outcome;
    var results = 0;
    await open(tester, pollPaid: () async => paid, onPaidCheck: () => gate.future, onResult: (r) {
      results++;
      outcome = r;
    });
    await tester.tap(find.byKey(const ValueKey('qrPayPaidCheck')));
    await tester.pump();
    paid = true;
    await tester.pump(const Duration(seconds: 3));
    await tester.pump(const Duration(milliseconds: 500));
    expect(outcome, isTrue);
    gate.complete(false);
    await tester.pump(const Duration(milliseconds: 300));
    expect(results, 1);
    expect(find.text(l10n.qrPayNotPaidYet), findsNothing, reason: '已到账，不该再弹未到账');
  });

  testWidgets('E5：过期 → 正文换 shopOrderExpiredNotice、无「我已付款」、只剩 Tutup；过期后到账仍 resolve true',
      (tester) async {
    var paid = false;
    bool? outcome;
    await open(tester,
        pollPaid: () async => paid,
        expiresAt: DateTime.now().add(const Duration(seconds: 2)),
        onResult: (r) => outcome = r);
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 2100)));
    await tester.pump(const Duration(seconds: 1));
    expect(find.byKey(const ValueKey('qrPayExpiredOverlay')), findsOneWidget);
    expect(find.text(l10n.rechargeExpiredTitle), findsOneWidget);
    expect(find.text('Waktu pembayaran habis. Pesanan dibatalkan.'), findsOneWidget);
    expect(find.text(l10n.qrPayExpiredBody), findsNothing);
    expect(find.text('00:00'), findsOneWidget);
    expect(find.byKey(const ValueKey('qrPayPaidCheck')), findsNothing);
    expect(find.byKey(const ValueKey('qrPaySave')), findsNothing);
    expect(find.byKey(const ValueKey('qrPayClose')), findsOneWidget);
    expect(find.byKey(const ValueKey('qrPayGuideToggle')), findsOneWidget);
    paid = true;
    await tester.pump(const Duration(seconds: 10));
    await tester.pump(const Duration(milliseconds: 500));
    expect(outcome, isTrue);
  });

  testWidgets('E7：codeFailed → 「Kode QR gagal dibuat」、保存禁用、无「我已付款」；Coba lagi 成功切回二维码',
      (tester) async {
    var retries = 0;
    await open(tester, codeFailed: true, onRetryCode: () async {
      retries++;
      return retries == 1 ? null : 'QR-NEW';
    });
    expect(find.text('Kode QR gagal dibuat'), findsOneWidget);
    expect(find.text(l10n.rechargeStubHint), findsNothing, reason: '不是测试环境的 Mode uji 占位');
    expect(tester.widget<FilledButton>(find.byKey(const ValueKey('qrPaySave'))).onPressed, isNull);
    expect(find.byKey(const ValueKey('qrPayPaidCheck')), findsNothing);
    expect(find.text('Rp317.000'), findsOneWidget, reason: '金额照常');
    expect(find.byKey(const ValueKey('qrPayCountdown')), findsOneWidget, reason: '倒计时照走');
    expect(find.textContaining('TOKO-20261105-ABC123'), findsOneWidget, reason: '订单号照常');

    // 第一次重试失败（返回 null）→ 保持 E7
    await tester.tap(find.byKey(const ValueKey('qrPayRetryCode')));
    await tester.pump();
    await tester.pump();
    expect(find.text('Kode QR gagal dibuat'), findsOneWidget);
    // 第二次成功 → 切回 E1 正常态
    await tester.tap(find.byKey(const ValueKey('qrPayRetryCode')));
    await tester.pump();
    await tester.pump();
    expect(retries, 2);
    expect(find.text('Kode QR gagal dibuat'), findsNothing);
    expect(find.byKey(const ValueKey('qrPayImage')), findsOneWidget);
    expect(find.byKey(const ValueKey('qrPayPaidCheck')), findsOneWidget);
    expect(tester.widget<FilledButton>(find.byKey(const ValueKey('qrPaySave'))).onPressed, isNotNull);
    await closeX(tester);
  });

  testWidgets('E7：重试抛错且 isRetryClosed 判定为关窗 → 切到无重试态；未判定的错误保持可重试', (tester) async {
    var calls = 0;
    await open(tester,
        codeFailed: true,
        onRetryCode: () async {
          calls++;
          throw calls == 1 ? StateError('5xx') : ArgumentError('closed');
        },
        isRetryClosed: (e) => e is ArgumentError);
    await tester.tap(find.byKey(const ValueKey('qrPayRetryCode')));
    await tester.pump();
    await tester.pump();
    expect(find.byKey(const ValueKey('qrPayRetryCode')), findsOneWidget, reason: '普通失败仍可重试');
    await tester.tap(find.byKey(const ValueKey('qrPayRetryCode')));
    await tester.pump();
    await tester.pump();
    expect(calls, 2);
    expect(find.text('Kode QR gagal dibuat'), findsOneWidget);
    expect(find.byKey(const ValueKey('qrPayRetryCode')), findsNothing);
    await closeX(tester);
  });

  testWidgets('E7：不传 isRetryClosed（默认）→ 重试抛任何错都保持可重试', (tester) async {
    await open(tester, codeFailed: true, onRetryCode: () async => throw ArgumentError('x'));
    await tester.tap(find.byKey(const ValueKey('qrPayRetryCode')));
    await tester.pump();
    await tester.pump();
    expect(find.byKey(const ValueKey('qrPayRetryCode')), findsOneWidget);
    await closeX(tester);
  });

  testWidgets('E7：onRetryCode 为 null（超 10 分钟不再出码）→ 不画「Coba lagi」', (tester) async {
    await open(tester, codeFailed: true);
    expect(find.text('Kode QR gagal dibuat'), findsOneWidget);
    expect(find.byKey(const ValueKey('qrPayRetryCode')), findsNothing);
    await closeX(tester);
  });

  testWidgets('E7：失败态下轮询照常，到账仍 resolve true', (tester) async {
    var paid = false;
    bool? outcome;
    await open(tester, codeFailed: true, pollPaid: () async => paid, onResult: (r) => outcome = r);
    paid = true;
    await tester.pump(const Duration(seconds: 3));
    await tester.pump(const Duration(milliseconds: 500));
    expect(outcome, isTrue);
  });
}

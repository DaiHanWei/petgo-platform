import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/qr_payment/qr_pay_parts.dart';
import 'package:tailtopia/shared/widgets/qr_payment/qr_pay_save.dart';
import 'package:tailtopia/shared/widgets/qr_payment_sheet.dart';

/// L0：V1.4.1 QRIS 支付页（稿 ui-1-4-1-qr-payment-integrated-v1 A1–A5 / S1）。
class _FakeGallery implements QrPayGallery {
  _FakeGallery({this.access = true, this.grant = false});
  bool access;
  bool grant;
  int requests = 0;
  int settings = 0;
  Uint8List? saved;

  @override
  Future<bool> hasAccess() async => access;

  @override
  Future<bool> requestAccess() async {
    requests++;
    return grant;
  }

  @override
  Future<void> putImageBytes(Uint8List bytes, {required String name}) async => saved = bytes;

  @override
  Future<bool> openSettings() async {
    settings++;
    return true;
  }
}

void main() {
  setUpAll(() => initializeDateFormatting());

  group('到期时刻 / 倒计时文案', () {
    test('当天只显示 HH:mm；跨天带 d MMM（印尼语月份缩写）；保存图固定带年', () {
      final now = DateTime(2026, 10, 29, 11, 24);
      expect(qrPayDeadlineText(DateTime(2026, 10, 29, 12, 24), 'id', now: now), '12:24');
      expect(qrPayDeadlineText(DateTime(2026, 10, 30, 0, 30), 'id', now: now), '30 Okt, 00:30');
      expect(qrPayDeadlineText(DateTime(2026, 10, 29, 12, 24), 'id', now: now, withYear: true),
          '29 Okt 2026, 12:24');
    });

    test('倒计时 mm:ss，≥1h 为 h:mm:ss，负值按 0', () {
      expect(qrPayCountdownText(const Duration(minutes: 59, seconds: 43)), '59:43');
      expect(qrPayCountdownText(const Duration(hours: 1, seconds: 5)), '1:00:05');
      expect(qrPayCountdownText(const Duration(seconds: -3)), '00:00');
    });
  });

  Future<void> pumpHost(WidgetTester tester, Future<void> Function(BuildContext) onOpen) async {
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
          builder: (ctx) => ElevatedButton(onPressed: () => onOpen(ctx), child: const Text('open')),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
  }

  testWidgets('A1：抽屉 60% 屏高；金额 + 倒计时 + 二维码 + 可复制订单号 + 付款 App + 5 条步骤 + 吸底按钮',
      (tester) async {
    await pumpHost(tester, (ctx) async {
      await showQrPaymentSheet(ctx,
          payload: 'QR-DATA',
          pollPaid: () async => false,
          orderRef: 'PAYGIFT-20261029-000042',
          amountIdr: 52000,
          expiresAt: DateTime.now().add(const Duration(minutes: 59, seconds: 43, milliseconds: 500)));
    });

    // 抽屉总高（含拖拽条）= 60% 屏高 ⇒ 顶边落在 40% 处。
    expect(tester.getTopLeft(find.byType(BottomSheet)).dy, closeTo(860 * 0.4, 1));
    expect(find.text('Rp52.000'), findsOneWidget);
    expect(find.text('59:43'), findsOneWidget);
    expect(find.byKey(const ValueKey('qrPayImage')), findsOneWidget);
    expect(find.textContaining('PAYGIFT-20261029-000042'), findsOneWidget);
    expect(find.byKey(const ValueKey('qrPayCopy')), findsOneWidget);
    expect(find.byType(Image), findsNWidgets(kQrPayLogos.length));
    // logo 是并排小标签（稿 `.lg`），不能每个撑满一行。
    for (final img in find.byType(Image).evaluate()) {
      expect(tester.getSize(find.byWidget(img.widget)).width, lessThan(120));
    }
    final first = tester.getTopLeft(find.byType(Image).at(0));
    final second = tester.getTopLeft(find.byType(Image).at(1));
    expect(second.dy, first.dy, reason: '前两个 logo 同一行');
    expect(find.text('+ lainnya'), findsOneWidget);
    expect(find.text('Cara bayar'), findsOneWidget);
    expect(find.textContaining('Kode QR berlaku sampai'), findsOneWidget);
    expect(find.text('Simpan Kode QR'), findsOneWidget);
    expect(find.text('Batalkan Pembayaran'), findsOneWidget);
    // 原「Pindai QR untuk membayar」提示并入步骤 2，不再单独显示。
    expect(find.text('Pindai QR untuk membayar'), findsNothing);
    await tester.tap(find.byKey(const ValueKey('qrPayCancel')));
    await tester.pump(const Duration(milliseconds: 500));
  });

  testWidgets('不传金额 / 过期时刻：对应行不画（灰度兼容）', (tester) async {
    await pumpHost(tester, (ctx) => showQrPaymentSheet(ctx, payload: 'QR', pollPaid: () async => false));
    expect(find.byKey(const ValueKey('qrPayAmount')), findsNothing);
    expect(find.byKey(const ValueKey('qrPayCountdown')), findsNothing);
    expect(find.textContaining('Kode QR berlaku sampai'), findsNothing);
    expect(find.text('Simpan Kode QR'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('qrPayCancel')));
    await tester.pump(const Duration(milliseconds: 500));
  });

  testWidgets('A4：倒计时归零 → 二维码蒙白提示过期、停轮询、只剩「Tutup」，点了 resolve false', (tester) async {
    var polls = 0;
    bool? outcome;
    await pumpHost(tester, (ctx) async {
      outcome = await showQrPaymentSheet(ctx,
          payload: 'QR',
          pollPaid: () async {
            polls++;
            return false;
          },
          amountIdr: 1000,
          expiresAt: DateTime.now().add(const Duration(seconds: 2)));
    });
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 2100)));
    await tester.pump(const Duration(seconds: 1));
    await tester.pump(const Duration(seconds: 1));
    expect(find.byKey(const ValueKey('qrPayExpiredOverlay')), findsOneWidget);
    expect(find.text('00:00'), findsOneWidget);
    expect(find.text('Kedaluwarsa'), findsOneWidget);
    expect(find.text('Menunggu pembayaran…'), findsNothing);
    expect(find.byKey(const ValueKey('qrPaySave')), findsNothing);
    expect(find.byKey(const ValueKey('qrPayCancel')), findsNothing);
    final before = polls;
    await tester.pump(const Duration(seconds: 6));
    expect(polls, before, reason: '过期后停轮询');
    await tester.tap(find.byKey(const ValueKey('qrPayClose')));
    await tester.pump(const Duration(milliseconds: 500));
    expect(outcome, isFalse);
  });

  testWidgets('A3：没有相册权限且申请被拒 → App 内对话框（只换正文），点「Buka Pengaturan」跳设置', (tester) async {
    final g = _FakeGallery(access: false, grant: false);
    qrPayGallery = g;
    addTearDown(() => qrPayGallery = const GalQrPayGallery());
    await pumpHost(tester, (ctx) => showQrPaymentSheet(ctx, payload: 'QR', pollPaid: () async => false));
    await tester.tap(find.byKey(const ValueKey('qrPaySave')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(g.requests, 1);
    expect(find.byKey(const ValueKey('qrPayPermissionDialog')), findsOneWidget);
    expect(find.text('Akses foto diperlukan untuk menyimpan kode QR. Aktifkan di Pengaturan.'), findsOneWidget);
    await tester.tap(find.byType(FilledButton).last);
    await tester.pump(const Duration(milliseconds: 300));
    expect(g.settings, 1);
    expect(g.saved, isNull);
    await tester.tap(find.byKey(const ValueKey('qrPayCancel')));
    await tester.pump(const Duration(milliseconds: 500));
  });

  testWidgets('S1：保存出来的是 1080 宽、带信息的 PNG', (tester) async {
    final bytes = await tester.runAsync(() => renderQrPaymentImage(
          payload: '00020101021226',
          amountText: 'Rp52.000',
          orderLine: 'Nomor Pesanan: TP2610290842',
          validLine: 'Berlaku sampai 29 Okt 2026, 12:24',
        ));
    expect(bytes, isNotNull);
    final codec = await tester.runAsync(() => ui.instantiateImageCodec(bytes!));
    final frame = await tester.runAsync(() => codec!.getNextFrame());
    expect(frame!.image.width, kQrPaySaveWidth.toInt());
    expect(frame.image.height, greaterThan(1080), reason: '品牌 + 二维码 + 三行信息，高于宽');
  });
}

import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/shop/data/shop_order_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_order_detail.dart';
import 'package:tailtopia/features/shop/domain/shop_return.dart';
import 'package:tailtopia/features/shop/refund/refund_destination_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// v1.4.2 shop Story 9.2 AC2 / AC3 / AC4：退款方式页（F14 / F15 / F23 / F25）。
/// 示例数取 UI 稿：币 12.000、QRIS 317.000、补偿 15.850（服务端给的值原样显示）。
class _Call {
  _Call(this.destination, this.channel, this.account, this.holder);
  final CashDestination destination;
  final PayoutChannel? channel;
  final String? account;
  final String? holder;
}

class _FakeRepo extends ShopOrderRepository {
  _FakeRepo({this.error}) : super(dio: Dio());

  Object? error;
  final calls = <_Call>[];
  Completer<void>? gate;

  @override
  Future<ShopOrderRefundSummary> chooseRefundDestination(String orderToken, String refundToken,
      {required CashDestination destination,
      PayoutChannel? channel,
      String? account,
      String? accountHolderName}) async {
    calls.add(_Call(destination, channel, account, accountHolderName));
    if (gate != null) await gate!.future;
    if (error != null) throw error!;
    return ShopOrderRefundSummary(refundToken: refundToken, scope: 'WHOLE');
  }
}

DioException _dio(int status, [String? type]) {
  final ro = RequestOptions(path: '/x');
  return DioException(
    requestOptions: ro,
    response: Response(
        requestOptions: ro,
        statusCode: status,
        data: {'type': 'https://api.tailtopia.id/problems/${type ?? 'validation'}', 'status': status}),
  );
}

Map<String, dynamic> _refund({
  String status = 'AWAITING_DESTINATION',
  bool canChoose = true,
  bool compensate = true,
  int compensationIfPawcoin = 15850,
  int coin = 12000,
  int cash = 317000,
  String reason = 'OUT_OF_STOCK',
}) =>
    {
      'refundToken': 'rf1',
      'scope': 'WHOLE',
      'reason': reason,
      'status': status,
      'totalRefund': coin + cash,
      'coinRefund': coin,
      'cashRefund': cash,
      'canChooseDestination': canChoose,
      'compensate': compensate,
      'compensationIfPawcoin': compensationIfPawcoin,
    };

ShopOrderDetail _order(List<Map<String, dynamic>> refunds) => ShopOrderDetail.fromJson({
      'orderToken': 'ord1',
      'status': 'REFUNDING',
      'cancelReason': 'OUT_OF_STOCK',
      'refunds': refunds,
    });

void main() {
  late List<(String, Map<String, Object>?)> events;
  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Future<_FakeRepo> pump(WidgetTester tester,
      {List<Map<String, dynamic>>? refunds, _FakeRepo? repo, String query = ''}) async {
    final r = repo ?? _FakeRepo();
    var loads = 0;
    final router = GoRouter(
      initialLocation: '/',
      routes: [
        GoRoute(path: '/', builder: (c, s) => const Scaffold(body: Text('DETAIL'))),
        GoRoute(
          path: '/r',
          builder: (c, s) => RefundDestinationPage(
            orderToken: 'ord1',
            refundToken: 'rf1',
            presetChannel: PayoutChannel.fromApi(s.uri.queryParameters['channel']),
            isRetry: s.uri.queryParameters['retry'] == '1',
          ),
        ),
      ],
    );
    await tester.pumpWidget(ProviderScope(
      overrides: [
        shopOrderRepositoryProvider.overrideWithValue(r),
        shopOrderDetailProvider.overrideWith((ref, token) async {
          loads++;
          return _order(refunds ?? [_refund()]);
        }),
      ],
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: const [
          AppLocalizations.delegate,
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
      ),
    ));
    router.push('/r$query');
    await tester.pumpAndSettle();
    expect(loads, greaterThan(0));
    return r;
  }

  Finder submit() => find.byKey(const ValueKey('refundDestSubmit'));
  String totalText(WidgetTester t) =>
      t.widget<Text>(find.byKey(const ValueKey('refundDestTotal'))).data!;

  Future<void> fillBank(WidgetTester tester, {String account = '2119090695'}) async {
    await tester.tap(find.byKey(const ValueKey('refundDestToBank')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('refundField_holder')), ' Budi ');
    await tester.enterText(find.byKey(const ValueKey('refundField_account')), account);
    await tester.pump();
  }

  Future<void> tapSubmit(WidgetTester tester) async {
    await tester.ensureVisible(submit());
    await tester.tap(submit());
    await tester.pumpAndSettle();
  }

  group('F14 有补偿', () {
    testWidgets('币段告知 + QRIS 卡标题 + 「+ bonus」；不预选 → Kirim 禁用', (tester) async {
      final repo = await pump(tester);
      expect(find.text('Cara pengembalian dana'), findsOneWidget);
      expect(find.text('Bagian PawCoin'), findsOneWidget);
      expect(find.text('Rp 12.000 · sudah kembali'), findsOneWidget);
      expect(find.text('Bagian QRIS Rp 317.000 dikembalikan ke'), findsOneWidget);
      expect(find.text('+ bonus Rp 15.850'), findsOneWidget);
      expect(find.text('Masuk seketika ke saldo PawCoin'), findsOneWidget);
      expect(find.text('Diproses maks. 3 hari kerja · BCA gratis, OVO/GoPay biaya Rp 2.500'), findsOneWidget);
      expect(find.byKey(const ValueKey('refundDestPawcoinWhy')), findsNothing, reason: '未选 PawCoin');
      expect(totalText(tester), 'Rp 317.000');

      await tester.tap(submit());
      await tester.pump();
      expect(repo.calls, isEmpty, reason: '钱的去向必须用户主动选');
      expect(events.where((e) => e.$1 == 'toko_refund_method_viewed').single.$2,
          {'reason': 'out_of_stock', 'is_retry': false});
    });

    testWidgets('选 PawCoin → 补偿说明块出现；合计仍是现金段（不与补偿相加）', (tester) async {
      await pump(tester);
      await tester.tap(find.byKey(const ValueKey('refundDestToPawcoin')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('refundDestPawcoinWhy')), findsOneWidget);
      expect(totalText(tester), 'Rp 317.000');
      expect(find.textContaining('332.850'), findsNothing, reason: 'P-1：App 不把现金段与补偿相加');
    });

    testWidgets('PawCoin 提交：不带渠道与账号；成功 toast + pop 回详情；埋点 destination', (tester) async {
      final repo = await pump(tester);
      await tester.tap(find.byKey(const ValueKey('refundDestToPawcoin')));
      await tester.pumpAndSettle();
      await tapSubmit(tester);
      expect(repo.calls.single.destination, CashDestination.toPawcoin);
      expect(repo.calls.single.channel, isNull);
      expect(repo.calls.single.account, isNull);
      expect(find.text('DETAIL'), findsOneWidget, reason: 'pop 回订单详情');
      expect(find.text('Cara pengembalian tersimpan'), findsOneWidget);
      expect(events.where((e) => e.$1 == 'toko_refund_method_submitted').single.$2,
          {'reason': 'out_of_stock', 'is_retry': false, 'destination': 'to_pawcoin'});
      await tester.pump(const Duration(seconds: 3));
    });
  });

  testWidgets('F23 无补偿（迟到款）：无 bonus、选 PawCoin 也无说明块', (tester) async {
    await pump(tester,
        refunds: [_refund(reason: 'LATE_PAYMENT', compensate: false, compensationIfPawcoin: 0, coin: 0)]);
    expect(find.textContaining('bonus'), findsNothing);
    expect(find.text('Bagian PawCoin'), findsNothing, reason: '币段为 0 不显示告知卡');
    await tester.tap(find.byKey(const ValueKey('refundDestToPawcoin')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('refundDestPawcoinWhy')), findsNothing);
  });

  testWidgets('compensate=true 但服务端预估 0 → 不承诺 bonus', (tester) async {
    await pump(tester, refunds: [_refund(compensationIfPawcoin: 0)]);
    expect(find.textContaining('bonus'), findsNothing);
  });

  group('F15 线下打款', () {
    testWidgets('三渠道都开放；OVO 显示手续费 2.500 与到账额', (tester) async {
      await pump(tester);
      await tester.tap(find.byKey(const ValueKey('refundDestToBank')));
      await tester.pumpAndSettle();
      for (final ch in PayoutChannel.values) {
        expect(find.byKey(ValueKey('refundChannelV2_${ch.name}')), findsOneWidget);
      }
      expect(tester.widget<Text>(find.byKey(const ValueKey('refundDestFee'))).data, 'Rp 0');
      expect(totalText(tester), 'Rp 317.000');
      await tester.tap(find.byKey(const ValueKey('refundChannelV2_ovo')));
      await tester.pumpAndSettle();
      expect(tester.widget<Text>(find.byKey(const ValueKey('refundDestFee'))).data, 'Rp 2.500');
      expect(totalText(tester), 'Rp 314.500');
      expect(find.text('+ bonus Rp 15.850'), findsOneWidget, reason: 'bonus 只挂在 PawCoin 选项上');
    });

    testWidgets('复审：现金段 ≤ 渠道费（OVO 2.500）→ 合计「—」+ 提前提示 + Kirim 禁用；切回 BCA 恢复', (tester) async {
      final repo = await pump(tester, refunds: [_refund(cash: 2500, coin: 0)]);
      await fillBank(tester);
      await tester.tap(find.byKey(const ValueKey('refundChannelV2_ovo')));
      await tester.pumpAndSettle();
      expect(totalText(tester), '—');
      expect(find.textContaining('Rp -'), findsNothing, reason: '不得显示负数');
      expect(find.byKey(const ValueKey('refundDestBelowFee')), findsOneWidget);
      await tapSubmit(tester);
      expect(repo.calls, isEmpty, reason: '必然 422 的请求不发');

      await tester.tap(find.byKey(const ValueKey('refundChannelV2_bca')));
      await tester.pumpAndSettle();
      expect(totalText(tester), 'Rp 2.500');
      expect(find.byKey(const ValueKey('refundDestBelowFee')), findsNothing);
      await tapSubmit(tester);
      expect(repo.calls.single.channel, PayoutChannel.bca);
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('校验不过不发请求', (tester) async {
      final repo = await pump(tester);
      await fillBank(tester, account: '123');
      await tapSubmit(tester);
      expect(repo.calls, isEmpty);
      expect(find.text('Nomor rekening harus 10-13 digit.'), findsOneWidget);
    });

    testWidgets('提交带 channel / trim 后的 account / holder；埋点不含账号与姓名', (tester) async {
      final repo = await pump(tester);
      await fillBank(tester);
      await tester.tap(find.byKey(const ValueKey('refundChannelV2_gopay')));
      await tester.pumpAndSettle();
      await tapSubmit(tester);
      final c = repo.calls.single;
      expect(c.destination, CashDestination.toBank);
      expect(c.channel, PayoutChannel.gopay);
      expect(c.account, '2119090695');
      expect(c.holder, 'Budi');
      final props = events.where((e) => e.$1 == 'toko_refund_method_submitted').single.$2!;
      expect(props, {'reason': 'out_of_stock', 'is_retry': false, 'destination': 'to_bank', 'channel': 'gopay'});
      expect(props.values.join(' '), isNot(contains('2119090695')));
      expect(props.values.join(' '), isNot(contains('Budi')));
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('提交中转圈、不可重复点', (tester) async {
      final repo = _FakeRepo()..gate = Completer<void>();
      await pump(tester, repo: repo);
      await fillBank(tester);
      await tester.ensureVisible(submit());
      await tester.tap(submit());
      await tester.pump();
      await tester.tap(submit(), warnIfMissed: false);
      await tester.pump();
      expect(repo.calls, hasLength(1));
      expect(find.descendant(of: submit(), matching: find.byType(CircularProgressIndicator)), findsOneWidget);
      repo.gate!.complete();
      await tester.pumpAndSettle();
      await tester.pump(const Duration(seconds: 3));
    });
  });

  group('错误三支', () {
    testWidgets('409 → toast + pop 回详情', (tester) async {
      await pump(tester, repo: _FakeRepo(error: _dio(409, 'refund-destination-locked')));
      await tester.tap(find.byKey(const ValueKey('refundDestToPawcoin')));
      await tester.pumpAndSettle();
      await tapSubmit(tester);
      expect(find.text('DETAIL'), findsOneWidget);
      expect(find.text('Gagal menyimpan. Coba lagi.'), findsOneWidget);
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('422 校验 → 账号框下红字，留在本页', (tester) async {
      await pump(tester, repo: _FakeRepo(error: _dio(422)));
      await fillBank(tester);
      await tapSubmit(tester);
      expect(find.text('DETAIL'), findsNothing);
      expect(find.text('Nomor rekening harus 10-13 digit.'), findsOneWidget);
    });

    testWidgets('422 refund-below-payout-fee → 专用 toast，留在本页', (tester) async {
      await pump(tester, repo: _FakeRepo(error: _dio(422, 'refund-below-payout-fee')));
      await fillBank(tester);
      await tapSubmit(tester);
      expect(find.text('DETAIL'), findsNothing);
      expect(find.textContaining('tidak cukup untuk biaya'), findsOneWidget);
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('其他错误 → toast，留在本页可重试', (tester) async {
      final repo = _FakeRepo(error: _dio(500, 'internal'));
      await pump(tester, repo: repo);
      await tester.tap(find.byKey(const ValueKey('refundDestToPawcoin')));
      await tester.pumpAndSettle();
      await tapSubmit(tester);
      expect(find.text('DETAIL'), findsNothing);
      expect(find.text('Gagal menyimpan. Coba lagi.'), findsOneWidget);
      repo.error = null;
      await tapSubmit(tester);
      expect(repo.calls, hasLength(2));
      await tester.pump(const Duration(seconds: 3));
    });
  });

  testWidgets('F25 重填：预选银行 + 上次渠道，账号清空；埋点 is_retry', (tester) async {
    await pump(tester,
        refunds: [_refund(status: 'PAYOUT_FAILED')], query: '?retry=1&channel=OVO');
    expect(find.byKey(const ValueKey('refundField_account')), findsOneWidget, reason: '已预选银行，表单展开');
    expect(tester.widget<TextField>(find.byKey(const ValueKey('refundField_account'))).controller!.text, isEmpty);
    expect(tester.widget<Text>(find.byKey(const ValueKey('refundDestFee'))).data, 'Rp 2.500');
    expect(events.where((e) => e.$1 == 'toko_refund_method_viewed').single.$2!['is_retry'], isTrue);
  });

  testWidgets('canChooseDestination=false → 不可提交（无 Kirim）+ 说明态', (tester) async {
    await pump(tester, refunds: [_refund(status: 'PAYOUT_PENDING', canChoose: false)]);
    expect(find.byKey(const ValueKey('refundDestUnavailable')), findsOneWidget);
    expect(submit(), findsNothing);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('找不到该退款单 → 不可提交', (tester) async {
    await pump(tester, refunds: [{..._refund(), 'refundToken': 'other'}]);
    expect(find.byKey(const ValueKey('refundDestUnavailable')), findsOneWidget);
    expect(submit(), findsNothing);
    await tester.pump(const Duration(seconds: 3));
  });
}

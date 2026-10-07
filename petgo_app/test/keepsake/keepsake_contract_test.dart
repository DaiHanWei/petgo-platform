import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/keepsake/domain/keepsake_pricing.dart';
import 'package:tailtopia/features/keepsake/domain/keepsake_purchase_result.dart';
import 'package:tailtopia/features/profile/data/id_card_repository.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card/hd_paywall_sheet.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/pay_channel_picker.dart';

/// V1.3.2 Story 3.2 · AC6（L0）：四价契约（无兜底）、购买响应解析、KTP 抽屉抽成通用组件后逐字不变。
void main() {
  group('KeepsakePricing', () {
    const full = {
      'price': 10000,
      'passportPageUnlockPrice': 2000,
      'passportBoardingUnlockPrice': 1000,
      'tailsonalityUnlockPrice': 5000,
    };

    test('四价全在即解析', () {
      final p = KeepsakePricing.fromJson(full);
      expect([p.ktpHd, p.passportSnapshot, p.boardingPass, p.tailsonality], [10000, 2000, 1000, 5000]);
    });

    test('任一缺失 / 0 / 负数 / 非数字即抛（无本地兜底价）', () {
      for (final k in full.keys) {
        expect(() => KeepsakePricing.fromJson(Map.of(full)..remove(k)), throwsFormatException, reason: 'missing $k');
        expect(() => KeepsakePricing.fromJson({...full, k: 0}), throwsFormatException, reason: 'zero $k');
        expect(() => KeepsakePricing.fromJson({...full, k: -1}), throwsFormatException, reason: 'neg $k');
        expect(() => KeepsakePricing.fromJson({...full, k: '5000'}), throwsFormatException, reason: 'str $k');
      }
    });
  });

  test('KeepsakePurchaseResult：与 HdPurchaseResult 同口径 + purchaseToken', () {
    final r = KeepsakePurchaseResult.fromJson({
      'unlocked': false,
      'payment': {'token': 'pi', 'displayNo': 'PAYTS-1'},
      'payload': '000201',
      'purchaseToken': 'kp',
    });
    expect([r.unlocked, r.paymentToken, r.paymentRef, r.payload, r.purchaseToken],
        [false, 'pi', 'PAYTS-1', '000201', 'kp']);
    final u = KeepsakePurchaseResult.fromJson({'unlocked': true});
    expect(u.unlocked, isTrue);
    expect(u.purchaseToken, isNull);
    expect(KeepsakePurchaseResult.fromJson({}).unlocked, isFalse);
  });

  test('formatIdrAmount：点分千分位', () {
    expect(formatIdrAmount(5000), '5.000');
    expect(formatIdrAmount(1234567), '1.234.567');
    expect(formatIdrAmount(999), '999');
  });

  group('KTP HdPaywallSheet 回归（抽成 PayChannelPicker 后视觉 / 文案 / key 不变）', () {
    Future<void> pump(WidgetTester tester, {required Future<int> Function() price, int balance = 0}) async {
      await tester.pumpWidget(ProviderScope(
        retry: (_, _) => null,
        overrides: [idCardHdPriceProvider.overrideWith((ref) => price())],
        child: MaterialApp(
          locale: const Locale('id'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Scaffold(
            body: SingleChildScrollView(
                child: HdPaywallSheet(petName: 'Momo', cardNo: 'TT123', balance: balance)),
          ),
        ),
      ));
      await tester.pumpAndSettle();
    }

    testWidgets('头部卡、标题、确认按钮文案与 key', (tester) async {
      await pump(tester, price: () async => 10000, balance: 20000);
      expect(find.text('Momo · No. TT123'), findsOneWidget);
      expect(find.text('Bayar & Unduh HD'), findsOneWidget);
      final btn = tester.widget<FilledButton>(find.byKey(const ValueKey('hdPayConfirm')));
      expect(btn.onPressed, isNotNull);
      // 余额足 → PawCoin 默认选中（行尾勾）。
      expect(find.byIcon(Icons.check_circle), findsOneWidget);
      expect(find.text('Rp10.000'), findsOneWidget);
    });

    testWidgets('余额不足：PawCoin 行显示充值、默认 QRIS', (tester) async {
      await pump(tester, price: () async => 10000, balance: 100);
      expect(find.text('Isi saldo dulu →'), findsOneWidget);
    });

    testWidgets('价格失败：hdPriceRetry + 确认禁用', (tester) async {
      await pump(tester, price: () async => throw Exception('x'));
      expect(find.byKey(const ValueKey('hdPriceRetry')), findsOneWidget);
      expect(tester.widget<FilledButton>(find.byKey(const ValueKey('hdPayConfirm'))).onPressed, isNull);
    });
  });

  test('HdPayChannel 线上值不变', () {
    expect(HdPayChannel.pawcoin.wire, 'PAWCOIN');
    expect(HdPayChannel.qris.wire, 'QRIS');
  });
}

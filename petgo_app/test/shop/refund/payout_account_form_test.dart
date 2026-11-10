import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/shop/domain/shop_return.dart';
import 'package:tailtopia/features/shop/refund/payout_account_form.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// v1.4.2 shop Story 9.2 AC3.5：从 `RefundMethodPageV2` 抽出的收款账户表单（行为不变）。
void main() {
  late PayoutAccountFormController c;

  setUp(() => c = PayoutAccountFormController());
  tearDown(() => c.dispose());

  Widget host() => MaterialApp(
        localizationsDelegates: const [
          AppLocalizations.delegate,
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
        home: Scaffold(
          body: Column(children: [
            PayoutAccountForm(controller: c),
            const TextField(key: ValueKey('other')),
          ]),
        ),
      );

  testWidgets('三渠道胶囊都在，默认 BCA；点 OVO 切换', (tester) async {
    await tester.pumpWidget(host());
    for (final ch in PayoutChannel.values) {
      expect(find.byKey(ValueKey('refundChannelV2_${ch.name}')), findsOneWidget);
    }
    expect(find.text('Transfer BCA'), findsOneWidget);
    expect(c.channel, PayoutChannel.bca);
    await tester.tap(find.byKey(const ValueKey('refundChannelV2_ovo')));
    await tester.pump();
    expect(c.channel, PayoutChannel.ovo);
    expect(find.text('Setelah dikirim, rekening penerima tidak bisa diubah.'), findsOneWidget);
  });

  testWidgets('失焦即校验：账号不足 10 位 → 框下红字', (tester) async {
    await tester.pumpWidget(host());
    await tester.tap(find.byKey(const ValueKey('refundField_account')));
    await tester.enterText(find.byKey(const ValueKey('refundField_account')), '12345');
    await tester.tap(find.byKey(const ValueKey('other')));
    await tester.pump();
    expect(find.text('Nomor rekening harus 10-13 digit.'), findsOneWidget);
  });

  testWidgets('账号只收数字', (tester) async {
    await tester.pumpWidget(host());
    await tester.enterText(find.byKey(const ValueKey('refundField_account')), '12ab34');
    expect(c.account, '1234');
  });

  testWidgets('validateAll：两项都空 → 两条错误一次显示，返回 false；填好 → true（trim）', (tester) async {
    await tester.pumpWidget(host());
    final l10n = AppLocalizations.of(tester.element(find.byType(PayoutAccountForm)));
    expect(c.validateAll(l10n), isFalse);
    await tester.pump();
    expect(find.byKey(const ValueKey('refundFieldError')), findsNWidgets(2));
    expect(find.text('Isi nama pemilik rekening dulu ya.'), findsOneWidget);
    expect(find.text('Isi nomor rekening dulu ya.'), findsOneWidget);

    await tester.enterText(find.byKey(const ValueKey('refundField_holder')), '  Budi  ');
    await tester.enterText(find.byKey(const ValueKey('refundField_account')), '2119090695');
    expect(c.validateAll(l10n), isTrue);
    await tester.pump();
    expect(find.byKey(const ValueKey('refundFieldError')), findsNothing);
    expect(c.holder, 'Budi');
    expect(c.account, '2119090695');
  });

  testWidgets('复审：户名 / 账号按 UTF-8 字节限 150（与后端 PAYOUT_FIELD_MAX_BYTES 一致）', (tester) async {
    expect(kPayoutFieldMaxBytes, 150);
    await tester.pumpWidget(host());
    await tester.enterText(find.byKey(const ValueKey('refundField_holder')), 'a' * 150);
    expect(c.holderController.text.length, 150);
    // 多字节：50 个 3 字节字符 = 150 字节可收；再多一个即拒收（保留旧值）。
    await tester.enterText(find.byKey(const ValueKey('refundField_holder')), '你' * 50);
    expect(c.holderController.text, '你' * 50);
    await tester.enterText(find.byKey(const ValueKey('refundField_holder')), '你' * 51);
    expect(c.holderController.text, '你' * 50);
    await tester.enterText(find.byKey(const ValueKey('refundField_account')), '1' * 151);
    expect(c.accountController.text, isEmpty, reason: '超限整次输入被拒');
  });

  testWidgets('预设渠道（F25 重填）', (tester) async {
    c.dispose();
    c = PayoutAccountFormController(PayoutChannel.gopay);
    await tester.pumpWidget(host());
    expect(c.channel, PayoutChannel.gopay);
    expect(c.account, isEmpty);
  });
}

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/data/id_card_repository.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card/hd_paywall_sheet.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：V1.5.0 gift-pack Story 6-1 · AC2 —— KTP 抽屉头卡 = 这款卡的缩略图 +「{宠物名} · {卡种名}」+ 预览说明；
/// 不传 card 时仍是原紫色头卡（其他调用处不变，见 keepsake_contract_test）。
void main() {
  Future<void> pump(WidgetTester tester, Widget sheet) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ProviderScope(
      retry: (_, _) => null,
      overrides: [idCardHdPriceProvider.overrideWith((ref) async => 5000)],
      child: MaterialApp(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(body: SingleChildScrollView(child: sheet)),
      ),
    ));
    await tester.pumpAndSettle();
  }

  testWidgets('传 card → 缩略图头卡 + 宠物名 · 卡种名 + 预览说明', (tester) async {
    await pump(tester,
        HdPaywallSheet(balance: 10000, card: IdCard(id: 1, serialId: 1, name: 'Momo', cardType: 'SIM')));
    expect(find.byKey(const ValueKey('hdPaywallCardThumb')), findsOneWidget);
    expect(find.text('Momo · SIM'), findsOneWidget);
    expect(find.text('Pratinjau · Resolusi rendah + watermark'), findsOneWidget);
    expect(find.text('🐾'), findsNothing);
  });

  testWidgets('不传 card → 原紫色头卡', (tester) async {
    await pump(tester, const HdPaywallSheet(petName: 'Momo', cardNo: 'TT123', balance: 10000));
    expect(find.byKey(const ValueKey('hdPaywallCardThumb')), findsNothing);
    expect(find.text('Momo · No. TT123'), findsOneWidget);
  });
}

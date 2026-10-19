import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/profile/domain/pet_header_info.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_info_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：Diary 统计卡中间格 = 礼物总价值（V1.4.0 gift Story 2.2 · AC2 · UI 稿 A1 / A2）。
void main() {
  Future<void> pump(WidgetTester tester, {int? value, VoidCallback? onOpenGifts}) async {
    await tester.pumpWidget(MaterialApp(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: PetInfoCard(
          profile: const PetHeaderInfo(name: 'Mochi', petType: 'CAT'),
          happyCount: 12,
          giftTotalValue: value,
          onOpenGifts: onOpenGifts,
          milestoneCount: 6,
        ),
      ),
    ));
    await tester.pump();
  }

  Finder cell() => find.byKey(const ValueKey('petInfoGiftStat'));

  testWidgets('Rp53.000 → 「Rp53rb」：数字与单位分开的 TextSpan，单位字号 = 数字 × 0.6；标签 Hadiah，无 Konsultasi',
      (tester) async {
    await pump(tester, value: 53000);
    final rich = tester.widget<Text>(find.byKey(const ValueKey('petInfoGiftValue')));
    final spans = (rich.textSpan! as TextSpan).children!.cast<TextSpan>();
    expect(spans.map((s) => s.text).toList(), ['Rp', '53', 'rb']);
    final number = spans[1].style!;
    // V1.4.0 L2 还原度（稿 `.pic-s`）：数字 16；不可点时与其余两格同为深色。
    expect(number.fontSize, 16);
    expect(spans[0].style!.fontSize, closeTo(16 * 0.6, 0.001));
    expect(spans[2].style!.fontSize, closeTo(16 * 0.6, 0.001));
    for (final s in spans) {
      expect(s.style!.color, AppColors.ink);
    }
    expect(find.text('Rp53rb'), findsOneWidget);
    expect(find.text('Hadiah'), findsOneWidget);
    expect(find.text('Konsultasi'), findsNothing);
  });

  testWidgets('不足 Rp1.000 时无单位 span（Rp0）', (tester) async {
    await pump(tester, value: 0);
    final rich = tester.widget<Text>(find.byKey(const ValueKey('petInfoGiftValue')));
    expect((rich.textSpan! as TextSpan).children!.map((s) => (s as TextSpan).text).toList(), ['Rp', '0']);
  });

  testWidgets('onOpenGifts 非 null：整格可点（热区 ≥ 44）、标签后有「›」', (tester) async {
    var taps = 0;
    await pump(tester, value: 188000, onOpenGifts: () => taps++);
    expect(find.text('Hadiah ›'), findsOneWidget);
    // 稿 `.pic-s span.tap b`：可点的礼物金额紫色强调。
    final rich = tester.widget<Text>(find.byKey(const ValueKey('petInfoGiftValue')));
    expect((rich.textSpan! as TextSpan).children!.first.style!.color, AppColors.mint);
    expect(find.descendant(of: cell(), matching: find.byType(InkWell)), findsNothing,
        reason: 'InkWell 本身就是带 key 的那一格');
    expect(tester.widget(cell()), isA<InkWell>());
    expect(tester.getSize(cell()).height, greaterThanOrEqualTo(44));
    await tester.tap(cell());
    expect(taps, 1);
  });

  testWidgets('onOpenGifts 为 null：不可点、无「›」', (tester) async {
    await pump(tester, value: 188000);
    expect(find.text('Hadiah'), findsOneWidget);
    expect(find.text('Hadiah ›'), findsNothing);
    expect(tester.widget(cell()), isNot(isA<InkWell>()));
    expect(find.descendant(of: cell(), matching: find.byType(InkWell)), findsNothing);
  });

  testWidgets('giftTotalValue == null（统计未就绪）→ 占位「·」', (tester) async {
    await pump(tester);
    expect(find.descendant(of: cell(), matching: find.text('·')), findsOneWidget);
    expect(find.byKey(const ValueKey('petInfoGiftValue')), findsNothing);
  });
}

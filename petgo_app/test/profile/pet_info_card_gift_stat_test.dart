import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/pet_header_info.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_info_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：V1.5.0 gift-pack Story 2-4 · AC3.3 —— Diary 统计条去掉「Hadiah」格（礼物改为下方礼物行）。
/// 原 V1.4.0 gift 2.2「中间格 = 礼物总价值」的用例随之下线。
void main() {
  testWidgets('统计条无 Hadiah 格（即使传了 giftTotalValue / onOpenGifts）', (tester) async {
    await tester.pumpWidget(MaterialApp(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: PetInfoCard(
          profile: const PetHeaderInfo(name: 'Mochi', petType: 'CAT'),
          happyCount: 12,
          giftTotalValue: 53000,
          onOpenGifts: () {},
          milestoneCount: 6,
        ),
      ),
    ));
    await tester.pump();
    expect(find.byKey(const ValueKey('petInfoGiftStat')), findsNothing);
    expect(find.byKey(const ValueKey('petInfoGiftValue')), findsNothing);
    expect(find.text('Hadiah'), findsNothing);
    expect(find.text('12'), findsOneWidget);
    expect(find.text('6'), findsOneWidget);
  });
}

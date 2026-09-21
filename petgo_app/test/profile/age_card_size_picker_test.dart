import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/presentation/age_card_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// UI 稿 P4：狗的体型页是「先选、再点 Lanjutkan」两步 ——
/// 改前点一下档位就直接跳走，手滑点错一档只能退回来重选（2026-09-21 对稿修正）。
void main() {
  Future<void> pumpDog(WidgetTester tester) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [
        petProfileProvider.overrideWith((ref) async => PetProfile(
              id: 1,
              name: 'Rex',
              cardToken: 'T',
              petType: 'DOG',
              birthday: DateTime(2023, 1, 1),
            )),
      ],
      child: const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: Locale('en'),
        home: AgeCardPage(),
      ),
    ));
    await tester.pumpAndSettle();
  }

  FilledButton continueButton(WidgetTester tester) =>
      tester.widget<FilledButton>(find.byKey(const ValueKey('ageCardSizeContinue')));

  testWidgets('没选档位时 Lanjutkan 禁用，点档位不会直接跳走', (tester) async {
    await pumpDog(tester);
    final l10n = await AppLocalizations.delegate.load(const Locale('en'));

    expect(find.text(l10n.ageCardSizePickerTitle), findsOneWidget);
    expect(continueButton(tester).onPressed, isNull);

    await tester.tap(find.byKey(const ValueKey('ageCardSize_medium')));
    await tester.pumpAndSettle();

    // 仍停在选择页：只是选中，没有跳到预览。
    expect(find.byType(AgeCardPreviewPage), findsNothing);
    expect(continueButton(tester).onPressed, isNotNull);
  });

  testWidgets('选中后点 Lanjutkan 才进预览', (tester) async {
    await pumpDog(tester);

    await tester.tap(find.byKey(const ValueKey('ageCardSize_large')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('ageCardSizeContinue')));
    await tester.pumpAndSettle();

    expect(find.byType(AgeCardPreviewPage), findsOneWidget);
  });
}

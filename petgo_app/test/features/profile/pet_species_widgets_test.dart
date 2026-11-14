import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/gift/domain/gift_catalog.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_header_info.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_info_card.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_species_avatar.dart';
import 'package:tailtopia/features/social/domain/followed_pet.dart';
import 'package:tailtopia/features/social/presentation/widgets/followed_pet_row.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：V1.5.0 pet-species Story 3.1 · AC5 / AC6 —— 模型解析 species、显示调用点按细分类型（兔子 🐰 Kelinci）。
void main() {
  Future<void> pump(WidgetTester tester, Widget child) async {
    await tester.pumpWidget(MaterialApp(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(body: child),
    ));
    await tester.pump();
  }

  testWidgets('Diary 档案卡：兔子第二行 Kelinci · Holland Lop、占位 🐰', (tester) async {
    await pump(
        tester,
        const PetInfoCard(
          profile: PetHeaderInfo(name: 'Mochi', petType: 'OTHER', species: 'RABBIT', breed: 'Holland Lop'),
          happyCount: 1,
          giftTotalValue: 0,
          milestoneCount: 0,
        ));
    expect(find.textContaining('Kelinci · Holland Lop'), findsOneWidget);
    expect(find.text('🐰'), findsOneWidget);
  });

  testWidgets('档案卡：旧后端无 species → 按大类（Lainnya）', (tester) async {
    await pump(
        tester,
        const PetInfoCard(
          profile: PetHeaderInfo(name: 'Mochi', petType: 'OTHER'),
          happyCount: 1,
          giftTotalValue: 0,
          milestoneCount: 0,
        ));
    expect(find.textContaining('Lainnya'), findsOneWidget);
    expect(find.text('🐾'), findsOneWidget);
  });

  testWidgets('PetSpeciesAvatar 无头像按细分类型 emoji', (tester) async {
    await pump(tester, const PetSpeciesAvatar(avatarUrl: null, petType: 'OTHER', species: 'HAMSTER', size: 40));
    expect(find.text('🐹'), findsOneWidget);
  });

  test('模型解析 species / speciesChangeableFrom', () {
    final p = PetProfile.fromJson(const {
      'id': 1,
      'name': 'Mochi',
      'cardToken': 't',
      'petType': 'OTHER',
      'species': 'RABBIT',
      'speciesChangeableFrom': '2027-02-22',
    });
    expect(p.species, 'RABBIT');
    expect(p.speciesChangeableFrom, DateTime(2027, 2, 22));
    expect(p.header.species, 'RABBIT');
    expect(p.copyWith(name: 'X').species, 'RABBIT');
    final item = PetListItem.fromJson(const {'id': 1, 'name': 'M', 'species': 'CIVET'});
    expect(item.species, 'CIVET');
    expect(item.speciesChangeableFrom, isNull);
    expect(FollowedPet.fromJson(const {'petId': 1, 'name': 'M', 'species': 'FISH'}).species, 'FISH');
    expect(GiftRecipient.fromJson(const {'petId': 1, 'name': 'M', 'species': 'BIRD'}).species, 'BIRD');
    expect(FollowedPetRow.placeholderEmoji('OTHER', species: 'BIRD'), '🐦');
  });
}

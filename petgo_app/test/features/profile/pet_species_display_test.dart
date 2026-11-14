import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/profile/domain/pet_age.dart';
import 'package:tailtopia/features/profile/domain/pet_species_display.dart';
import 'package:tailtopia/features/profile/presentation/id_card/ktp_fields.dart';
import 'package:tailtopia/features/profile/presentation/id_card/new_card_text.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_form_fields.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_species_avatar.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：V1.5.0 pet-species Story 3.1 · AC3 —— 统一显示函数与旧函数转调。
void main() {
  late AppLocalizations id;
  late AppLocalizations en;

  setUpAll(() async {
    id = await AppLocalizations.delegate.load(const Locale('id'));
    en = await AppLocalizations.delegate.load(const Locale('en'));
  });

  test('RABBIT', () {
    final v = petSpeciesView(species: 'RABBIT', petType: 'OTHER');
    expect(v.emoji, '🐰');
    expect(v.label(id), 'Kelinci');
    expect(v.label(en), 'Rabbit');
    expect(v.cardNameId.toUpperCase(), 'KELINCI');
    expect(v.tint, AppColors.mintTint); // 底色仍按大类
  });

  test('CIVET 显示「其他」、卡面 Hewan', () {
    final v = petSpeciesView(species: 'CIVET', petType: 'OTHER');
    expect(v.emoji, '🐾');
    expect(v.label(id), 'Lainnya');
    expect(v.cardNameId, 'Hewan');
    expect(v.pickerType.wire, 'OTHER');
  });

  test('兜底：species 为空按大类；不认识的新物种不崩', () {
    final dog = petSpeciesView(petType: 'DOG');
    expect('${dog.emoji} ${dog.label(id)}', '🐶 Anjing');
    expect(dog.tint, AppColors.goldTint);
    final unknown = petSpeciesView(species: 'UNKNOWN_NEW', petType: 'OTHER');
    expect('${unknown.emoji} ${unknown.label(id)}', '🐾 Lainnya');
    expect(petSpeciesView().code.wire, 'OTHER');
  });

  test('旧函数保留签名并转调', () {
    expect(petTypeDisplay(id, 'OTHER', species: 'RABBIT'), '🐰 Kelinci');
    expect(petTypeDisplay(id, 'CAT'), '🐱 Kucing');
    expect(petSpeciesLabel(id, 'OTHER', species: 'HAMSTER'), 'Hamster');
    expect(petSpeciesLabel(id, null), isNull);
    expect(petSpeciesLabel(id, 'WEIRD'), isNull);
    expect(petSpeciesEmoji('OTHER', species: 'FISH'), '🐟');
    expect(petSpeciesEmoji('CAT'), '🐱');
    expect(petSpeciesTint('CAT', species: 'CAT'), AppColors.violet100);
    expect(KtpDefaults.spesies('OTHER', species: 'TURTLE'), 'KURA-KURA');
    expect(KtpDefaults.spesies('CAT'), 'KUCING');
    expect(KtpDefaults.spesies(null), 'HEWAN');
    expect(NewCardDefaults.spesies('OTHER', species: 'DUCK'), 'Bebek/Entok');
    expect(NewCardDefaults.spesies('OTHER', species: 'OTTER'), 'Hewan');
  });
}

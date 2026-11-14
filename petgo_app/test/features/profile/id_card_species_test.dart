import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/features/profile/domain/id_card_style.dart';
import 'package:tailtopia/features/profile/domain/pet_species.dart';
import 'package:tailtopia/features/profile/presentation/id_card/id_card_form.dart';
import 'package:tailtopia/features/profile/presentation/id_card/ktp_fields.dart';

/// L0：V1.5.0 pet-species Story 3.4 · AC3 / AC4.2 —— 卡面物种按细分类型、卡号物种段按大类、旧卡不变、必填判定。
void main() {
  test('兔子：卡面 KELINCI，卡号预览物种段 00（大类 OTHER）', () {
    final rabbit = PetSpeciesCode.rabbit;
    expect(KtpDefaults.spesies(rabbit.groupWire, species: rabbit.wire), 'KELINCI');
    expect(speciesCodeFor(rabbit.groupWire), '00');
    expect(buildPreviewCardNo(petType: rabbit.groupWire, placeholderWhenNoBirthday: true), contains('00'));
  });

  test('旧卡（无 species、petType=CAT）显示 KUCING', () {
    final old = IdCard.fromJson(const {'id': 1, 'petType': 'CAT', 'cardType': 'KTP'});
    expect(old.species, isNull);
    expect(KtpDefaults.spesies(old.petType, species: old.species), 'KUCING');
  });

  test('请求体带 species 与大类 petType；必填：species 或 petType 至少一个', () {
    const req = CreateIdCardRequest(name: 'Momo', petType: 'OTHER', species: 'RABBIT');
    expect(req.toJson(), containsPair('species', 'RABBIT'));
    expect(req.toJson(), containsPair('petType', 'OTHER'));
    expect(const CreateIdCardRequest(name: 'M').toJson().containsKey('species'), isFalse);
    const onlySpecies = IdCardFormDraft(name: 'M', species: 'RABBIT');
    const none = IdCardFormDraft(name: 'M');
    expect(idCardMissingFields(IdCardStyle.passport, onlySpecies), isNot(contains(IdCardFormField.petType)));
    expect(idCardMissingFields(IdCardStyle.passport, none), contains(IdCardFormField.petType));
  });
}

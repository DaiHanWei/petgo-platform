import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/id_card_style.dart';
import 'package:tailtopia/features/profile/presentation/id_card/id_card_form.dart';
import 'package:tailtopia/l10n/app_localizations_en.dart';
import 'package:tailtopia/l10n/app_localizations_id.dart';

/// L0（V1.3.4 ktp Story 1.7，AD-4 / D-17 / T-14）：必填判定与缺项提示拼接。
void main() {
  final full = IdCardFormDraft(
    name: 'Oyen',
    petType: 'CAT',
    breed: 'Domestik',
    gender: 'MALE',
    birthday: DateTime(2026, 3, 13),
  );
  IdCardFormDraft without(Set<IdCardFormField> drop) => IdCardFormDraft(
        name: drop.contains(IdCardFormField.name) ? '  ' : full.name,
        petType: drop.contains(IdCardFormField.petType) ? null : full.petType,
        breed: drop.contains(IdCardFormField.breed) ? ' ' : full.breed,
        gender: drop.contains(IdCardFormField.gender) ? null : full.gender,
        birthday: drop.contains(IdCardFormField.birthday) ? null : full.birthday,
      );

  test('全部填齐 → 8 款都无缺项', () {
    for (final s in IdCardStyle.values) {
      expect(idCardMissingFields(s, full), isEmpty, reason: '$s');
    }
  });

  test('KTP / 居住证品种必填；Paspor / SIM / 学生卡品种不在字段集，不算缺', () {
    final noBreed = without({IdCardFormField.breed});
    expect(idCardMissingFields(IdCardStyle.ktp, noBreed), [IdCardFormField.breed]);
    expect(idCardMissingFields(IdCardStyle.kitas, noBreed), [IdCardFormField.breed]);
    for (final s in [IdCardStyle.passport, IdCardStyle.sim, IdCardStyle.studentBintang, IdCardStyle.studentKlasik]) {
      expect(idCardMissingFields(s, noBreed), isEmpty, reason: '$s');
    }
  });

  test('五套字段集 × 各必填缺失组合，按表单顺序返回', () {
    const required = [
      IdCardFormField.name,
      IdCardFormField.petType,
      IdCardFormField.breed,
      IdCardFormField.gender,
      IdCardFormField.birthday,
    ];
    for (final s in IdCardStyle.values) {
      final req = [for (final f in required) if (idCardFieldRequired(s, f) && s.formFields.contains(f)) f];
      // 所有子集
      for (var mask = 0; mask < (1 << req.length); mask++) {
        final drop = {for (var i = 0; i < req.length; i++) if (mask & (1 << i) != 0) req[i]};
        final expected = [for (final f in req) if (drop.contains(f)) f];
        expect(idCardMissingFields(s, without(drop)), expected, reason: '$s 缺 $drop');
      }
    }
  });

  test('选填字段永不必填', () {
    for (final f in [
      IdCardFormField.birthCity,
      IdCardFormField.address,
      IdCardFormField.occupation,
      IdCardFormField.maritalStatus,
      IdCardFormField.school,
      IdCardFormField.faculty,
    ]) {
      for (final s in IdCardStyle.values) {
        expect(idCardFieldRequired(s, f), isFalse);
      }
    }
  });

  test('表单字段顺序固定（不在字段集的跳过）', () {
    expect(idCardOrderedFields(IdCardStyle.passport), [
      IdCardFormField.name,
      IdCardFormField.petType,
      IdCardFormField.gender,
      IdCardFormField.birthday,
      IdCardFormField.birthCity,
    ]);
    expect(idCardOrderedFields(IdCardStyle.ktp).length, 9);
  });

  group('T-14 缺项提示拼接', () {
    final id = AppLocalizationsId();
    final en = AppLocalizationsEn();
    test('无缺项 → null', () => expect(idCardMissingText(id, const []), isNull));
    test('1 项', () {
      expect(idCardMissingText(id, [IdCardFormField.birthday]), 'Lengkapi tanggal lahir dulu');
      expect(idCardMissingText(en, [IdCardFormField.birthday]), 'Fill in date of birth first');
    });
    test('2 项用 dan / and（UI 稿 C4 示例）', () {
      expect(idCardMissingText(id, [IdCardFormField.petType, IdCardFormField.gender]),
          'Lengkapi jenis hewan dan jenis kelamin dulu');
      expect(idCardMissingText(en, [IdCardFormField.petType, IdCardFormField.gender]),
          'Fill in species and gender first');
    });
    test('3 项以上逗号 + 末项 dan / and', () {
      final all = [
        IdCardFormField.name,
        IdCardFormField.petType,
        IdCardFormField.breed,
        IdCardFormField.gender,
        IdCardFormField.birthday,
      ];
      expect(idCardMissingText(id, all),
          'Lengkapi nama hewan, jenis hewan, ras, jenis kelamin dan tanggal lahir dulu');
      expect(idCardMissingText(en, all), 'Fill in pet name, species, breed, gender and date of birth first');
    });
  });
}

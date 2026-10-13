import 'package:flutter/foundation.dart';

import '../../../../l10n/app_localizations.dart';
import '../../domain/id_card_style.dart';

/// 建卡表单草稿（V1.3.4 ktp Story 1.7，会话级、不落库）。建卡页与「Ubah info」抽屉共用。
/// 文本字段空串 = 未填；[petType] / [gender] / [birthday] null = 未选。
@immutable
class IdCardFormDraft {
  const IdCardFormDraft({
    this.name = '',
    this.petType,
    this.breed = '',
    this.gender,
    this.birthday,
    this.birthCity = '',
    this.address = '',
    this.occupation = '',
    this.maritalStatus = '',
    this.school = '',
    this.faculty = '',
  });

  final String name;

  /// wire 值 CAT / DOG / OTHER。
  final String? petType;
  final String breed;

  /// wire 值 MALE / FEMALE / UNKNOWN；null = 未选（档案无性别时不预选）。
  final String? gender;
  final DateTime? birthday;
  final String birthCity;
  final String address;
  final String occupation;
  final String maritalStatus;
  final String school;
  final String faculty;
}

/// 字段在表单中的固定顺序（AC3.1）；不在卡款字段集内的跳过。
const List<IdCardFormField> kIdCardFormFieldOrder = [
  IdCardFormField.name,
  IdCardFormField.petType,
  IdCardFormField.breed,
  IdCardFormField.gender,
  IdCardFormField.birthday,
  IdCardFormField.birthCity,
  IdCardFormField.address,
  IdCardFormField.occupation,
  IdCardFormField.maritalStatus,
  IdCardFormField.school,
  IdCardFormField.faculty,
];

/// 该卡款按表单顺序排好的字段。
List<IdCardFormField> idCardOrderedFields(IdCardStyle style) =>
    [for (final f in kIdCardFormFieldOrder) if (style.formFields.contains(f)) f];

/// 该卡款该字段是否必填：名字 / 物种 / 性别 / 生日恒必填；KTP / 居住证另加品种（AD-4 / D-17）。
bool idCardFieldRequired(IdCardStyle style, IdCardFormField field) => switch (field) {
      IdCardFormField.name ||
      IdCardFormField.petType ||
      IdCardFormField.gender ||
      IdCardFormField.birthday =>
        true,
      IdCardFormField.breed => style.cardType == 'KTP' || style.cardType == 'KITAS',
      _ => false,
    };

/// 缺失的必填项（按表单顺序）。建卡页置灰与提示、表单红字共用；与后端 422 规则一致，后端仍是最终防线。
List<IdCardFormField> idCardMissingFields(IdCardStyle style, IdCardFormDraft d) => [
      for (final f in idCardOrderedFields(style))
        if (idCardFieldRequired(style, f) && !_filled(f, d)) f,
    ];

bool _filled(IdCardFormField f, IdCardFormDraft d) => switch (f) {
      IdCardFormField.name => d.name.trim().isNotEmpty,
      IdCardFormField.petType => d.petType != null,
      IdCardFormField.breed => d.breed.trim().isNotEmpty,
      IdCardFormField.gender => d.gender != null,
      IdCardFormField.birthday => d.birthday != null,
      _ => true,
    };

/// 建卡页缺项提示（T-14）：「Lengkapi {项} dulu」/「Fill in {fields} first」；
/// 1 项「A」、2 项「A dan B」、3 项以上「A, B dan C」。无缺项返回 null。
String? idCardMissingText(AppLocalizations l10n, List<IdCardFormField> missing) {
  if (missing.isEmpty) return null;
  final names = [for (final f in missing) _missingName(l10n, f)];
  final joined = names.length == 1
      ? names.single
      : '${names.sublist(0, names.length - 1).join(', ')} ${l10n.idCardMissingAnd} ${names.last}';
  return l10n.idCardCreateMissing(joined);
}

String _missingName(AppLocalizations l10n, IdCardFormField f) => switch (f) {
      IdCardFormField.name => l10n.idCardMissingName,
      IdCardFormField.petType => l10n.idCardMissingPetType,
      IdCardFormField.breed => l10n.idCardMissingBreed,
      IdCardFormField.gender => l10n.idCardMissingGender,
      IdCardFormField.birthday => l10n.idCardMissingBirthday,
      // 选填项不会进缺项列表；若日后改了必填规则漏配名称，宁可报错也不把枚举名显示给用户。
      IdCardFormField.birthCity ||
      IdCardFormField.address ||
      IdCardFormField.occupation ||
      IdCardFormField.maritalStatus ||
      IdCardFormField.school ||
      IdCardFormField.faculty =>
        throw StateError('缺项名称未配置：$f'),
    };

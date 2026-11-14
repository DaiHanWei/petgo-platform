import 'dart:ui' show Color;

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import 'pet_species.dart';

/// 宠物类型的统一显示（V1.5.0 pet-species Story 3.1 · 架构 D-17 / G-2）。
///
/// 🔴 **唯一入口**：所有显示宠物类型名 / emoji / 卡面物种 / 头像底色的地方只调 [petSpeciesView]。
/// `species`（细分类型）能解析用它；否则按 `petType`（大类）兜底；都没有 → OTHER（新版后端加了 App 不认识的物种也不崩）。
/// 业务逻辑（健康提醒、送礼排序、推荐分池……）继续读 `petType`，不经这里。
PetSpeciesView petSpeciesView({String? species, String? petType}) {
  final code = PetSpeciesCode.fromWire(species) ?? _fromGroup(petType);
  return PetSpeciesView._(code);
}

PetSpeciesCode _fromGroup(String? petType) => switch (petType) {
      'CAT' => PetSpeciesCode.cat,
      'DOG' => PetSpeciesCode.dog,
      _ => PetSpeciesCode.other,
    };

/// 一只宠物类型的显示数据。
class PetSpeciesView {
  const PetSpeciesView._(this.code);

  final PetSpeciesCode code;

  /// 宫格项（CIVET → OTHER）。
  PetSpeciesCode get pickerType => code.pickerType;

  /// 15 项各自 emoji；「其他」组物种与 OTHER 均 🐾。
  String get emoji => typeOf(code).emoji;

  /// 界面语言的类型名；「其他」组物种显示 Lainnya / Other（PRD 3.1 AC3：宠物页类型显示「其他」）。
  String label(AppLocalizations l10n) => switch (code.pickerType) {
        PetSpeciesCode.cat => l10n.petTypeCat,
        PetSpeciesCode.dog => l10n.petTypeDog,
        PetSpeciesCode.rabbit => l10n.petTypeRabbit,
        PetSpeciesCode.hamster => l10n.petTypeHamster,
        PetSpeciesCode.bird => l10n.petTypeBird,
        PetSpeciesCode.chicken => l10n.petTypeChicken,
        PetSpeciesCode.fish => l10n.petTypeFish,
        PetSpeciesCode.lizard => l10n.petTypeLizard,
        PetSpeciesCode.turtle => l10n.petTypeTurtle,
        PetSpeciesCode.sugarGlider => l10n.petTypeSugarGlider,
        PetSpeciesCode.snake => l10n.petTypeSnake,
        PetSpeciesCode.duck => l10n.petTypeDuck,
        PetSpeciesCode.frog => l10n.petTypeFrog,
        PetSpeciesCode.guineaPig => l10n.petTypeGuineaPig,
        _ => l10n.petTypeOther,
      };

  /// 卡面固定印尼语名（不随界面语言变）：Kucing / Anjing / Kelinci / …；「其他」组与 OTHER → Hewan。
  /// KTP / 学生卡大写由调用方 `toUpperCase()`。
  String get cardNameId => code.pickerType == PetSpeciesCode.other ? 'Hewan' : typeOf(code).nameId;

  /// 头像占位底色：仍按大类（UI F1「不新增颜色」）。
  Color get tint => switch (code.groupWire) {
        'CAT' => AppColors.violet100,
        'DOG' => AppColors.goldTint,
        _ => AppColors.mintTint,
      };

  /// 「emoji 名」（建档 / 编辑页类型框）。
  String display(AppLocalizations l10n) => '$emoji ${label(l10n)}';
}

import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../shared/widgets/app_image.dart';
import '../../domain/pet_species_display.dart';

/// 无头像时的物种 emoji 占位（与 Diary 档案卡 `PetInfoCard` 同口径）。V1.5.0 pet-species：转调 [petSpeciesView]，
/// 有 [species] 按细分类型（🐰 🐹 …），否则按大类（猫 🐱 / 狗 🐶 / 其它与未知 🐾）。
String petSpeciesEmoji(String? petType, {String? species}) =>
    petSpeciesView(species: species, petType: petType).emoji;

/// 占位底色（多宠物 UI 稿 `.pet-av` / `.pav-c`：emoji + 浅色底）；仍按大类（UI F1 不新增颜色）。
Color petSpeciesTint(String? petType, {String? species}) =>
    petSpeciesView(species: species, petType: petType).tint;

/// 宠物圆头像：有 [avatarUrl] 渲染真图，否则按物种画 emoji 占位（bug 593：此前多宠物各处恒为灰色爪印）。
class PetSpeciesAvatar extends StatelessWidget {
  const PetSpeciesAvatar({
    super.key,
    required this.avatarUrl,
    required this.petType,
    required this.size,
    this.thumbWidth = 120,
    this.species,
  });

  final String? avatarUrl;
  final String? petType;

  /// 细分类型（V1.5.0 pet-species）；null 时按 [petType] 大类显示。
  final String? species;

  /// 圆直径。
  final double size;
  final int thumbWidth;

  @override
  Widget build(BuildContext context) {
    final hasAvatar = avatarUrl != null && avatarUrl!.isNotEmpty;
    return CircleAvatar(
      radius: size / 2,
      backgroundColor: hasAvatar ? AppColors.border : petSpeciesTint(petType, species: species),
      backgroundImage: AppImage.provider(avatarUrl, thumbWidth: thumbWidth),
      child: hasAvatar
          ? null
          : Text(petSpeciesEmoji(petType, species: species), style: TextStyle(fontSize: size * 0.46, height: 1)),
    );
  }
}

import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../shared/widgets/app_image.dart';

/// 无头像时的物种 emoji 占位（与 Diary 档案卡 `PetInfoCard` 同口径）：猫 🐱 / 狗 🐶 / 其它与未知 🐾。
String petSpeciesEmoji(String? petType) => switch (petType) {
      'CAT' => '🐱',
      'DOG' => '🐶',
      _ => '🐾',
    };

/// 占位底色（多宠物 UI 稿 `.pet-av` / `.pav-c`：emoji + 浅色底）。
Color petSpeciesTint(String? petType) => switch (petType) {
      'CAT' => AppColors.violet100,
      'DOG' => AppColors.goldTint,
      _ => AppColors.mintTint,
    };

/// 宠物圆头像：有 [avatarUrl] 渲染真图，否则按物种画 emoji 占位（bug 593：此前多宠物各处恒为灰色爪印）。
class PetSpeciesAvatar extends StatelessWidget {
  const PetSpeciesAvatar({
    super.key,
    required this.avatarUrl,
    required this.petType,
    required this.size,
    this.thumbWidth = 120,
  });

  final String? avatarUrl;
  final String? petType;

  /// 圆直径。
  final double size;
  final int thumbWidth;

  @override
  Widget build(BuildContext context) {
    final hasAvatar = avatarUrl != null && avatarUrl!.isNotEmpty;
    return CircleAvatar(
      radius: size / 2,
      backgroundColor: hasAvatar ? AppColors.border : petSpeciesTint(petType),
      backgroundImage: AppImage.provider(avatarUrl, thumbWidth: thumbWidth),
      child: hasAvatar
          ? null
          : Text(petSpeciesEmoji(petType), style: TextStyle(fontSize: size * 0.46, height: 1)),
    );
  }
}

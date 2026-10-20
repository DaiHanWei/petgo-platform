import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../profile/data/pet_list_repository.dart';
import '../../profile/domain/pet_age.dart';
import '../../profile/presentation/widgets/pet_species_avatar.dart';

/// 名额管理页 Hewanku 行 / D13 改选行共用：头像 40 + 名字 + 「物种 · 年龄」+ 右侧 [trailing]。
///
/// [showLock]：只读宠物头像灰化 + 锁标（名额管理页 D3；改选抽屉里不画，选择本身就在决定只读与否）。
/// ⚠️ [PetListItem.name] 只渲染，不写日志 / 埋点（§5）。
class PetSlotPetRow extends StatelessWidget {
  const PetSlotPetRow(
      {super.key, required this.pet, required this.trailing, this.showLock = true, this.avatarSize = 40});

  final PetListItem pet;

  /// 头像直径（名额页 Hewanku 稿为 36，改选抽屉沿用 40）。
  final double avatarSize;
  final Widget trailing;
  final bool showLock;

  static const ColorFilter _greyscale = ColorFilter.matrix(<double>[
    0.2126, 0.7152, 0.0722, 0, 0, //
    0.2126, 0.7152, 0.0722, 0, 0, //
    0.2126, 0.7152, 0.0722, 0, 0, //
    0, 0, 0, 0.75, 0,
  ]);

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final locked = showLock && pet.isReadOnly;
    Widget avatar = PetSpeciesAvatar(avatarUrl: pet.avatarUrl, petType: pet.petType, size: avatarSize);
    if (locked) avatar = ColorFiltered(colorFilter: _greyscale, child: avatar);
    final meta = [
      ?petSpeciesLabel(l10n, pet.petType),
      ?formatPetAge(l10n, pet.birthday)?.replaceAll(' ', ' '),
    ].join(' · ');
    return Row(
      children: [
        Stack(
          clipBehavior: Clip.none,
          children: [
            avatar,
            if (locked)
              Positioned(
                left: -3,
                bottom: -2,
                child: Container(
                  key: ValueKey('petSlotPetLock-${pet.id}'),
                  width: 18,
                  height: 18,
                  decoration: BoxDecoration(
                    color: AppColors.ink2,
                    shape: BoxShape.circle,
                    border: Border.all(color: Colors.white, width: 2),
                  ),
                  child: const Icon(Icons.lock, size: 9, color: Colors.white),
                ),
              ),
          ],
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(pet.name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w700,
                      color: locked ? AppColors.muted : AppColors.ink)),
              if (meta.isNotEmpty) ...[
                const SizedBox(height: 2),
                Text(meta, style: const TextStyle(fontSize: 12, color: AppColors.ink2)),
              ],
            ],
          ),
        ),
        const SizedBox(width: 8),
        trailing,
      ],
    );
  }
}

import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../l10n/app_localizations.dart';
import '../../domain/pet_age.dart';
import '../../domain/pet_profile.dart';

/// 宠物基本信息卡（Story 2.4 · F2）：头像/名字/品种/年龄/介绍 + 状态快捷编辑入口。
/// 分享名片 FAB 此处仅占位（动效/分享逻辑 2.7）；编辑入口占位留 2.8。
class PetInfoCard extends StatelessWidget {
  const PetInfoCard({
    super.key,
    required this.profile,
    required this.onEditStatus,
    required this.onEditProfile,
  });

  final PetProfile profile;
  final VoidCallback onEditStatus;
  final VoidCallback onEditProfile;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final age = computePetAge(profile.birthday);
    return Card(
      key: const ValueKey('petInfoCard'),
      margin: const EdgeInsets.only(bottom: AppSpacing.md),
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            CircleAvatar(
              radius: 32,
              backgroundColor: AppColors.surface,
              backgroundImage: profile.avatarUrl == null ? null : NetworkImage(profile.avatarUrl!),
              child: profile.avatarUrl == null ? const Icon(Icons.pets) : null,
            ),
            const SizedBox(width: AppSpacing.md),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(profile.name, style: Theme.of(context).textTheme.titleMedium),
                  if (profile.breed != null)
                    Text(profile.breed!, style: TextStyle(color: AppColors.textTertiary)),
                  if (profile.birthday != null)
                    Text(l10n.growthArchiveAge(age.years, age.months),
                        style: TextStyle(color: AppColors.textTertiary, fontSize: 12)),
                  if (profile.intro != null) ...[
                    const SizedBox(height: AppSpacing.xs),
                    Text(profile.intro!),
                  ],
                ],
              ),
            ),
            Column(
              children: [
                IconButton(
                  key: const ValueKey('editProfileButton'),
                  icon: const Icon(Icons.edit_outlined),
                  tooltip: l10n.petProfileEditEntry,
                  onPressed: onEditProfile,
                ),
                IconButton(
                  key: const ValueKey('editStatusButton'),
                  icon: const Icon(Icons.tune),
                  tooltip: l10n.growthArchiveChangeStatus,
                  onPressed: onEditStatus,
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

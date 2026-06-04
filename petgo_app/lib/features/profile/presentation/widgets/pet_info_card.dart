import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/design/emoji_avatar.dart';
import '../../domain/pet_age.dart';
import '../../domain/pet_profile.dart';

/// 宠物名片 hero（Story 2.4 · F2 · PetGo Prototype 换肤）。
///
/// 居中头像 + 名字 + 品种·年龄 + 一句话介绍 + 「Hari bareng」天数；右上角编辑/状态入口。
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
    final daysTogether =
        profile.birthday == null ? null : DateTime.now().difference(profile.birthday!).inDays;
    final emoji = (profile.avatarUrl == null || profile.avatarUrl!.isEmpty) ? '🐱' : '🐾';
    return Container(
      key: const ValueKey('petInfoCard'),
      margin: const EdgeInsets.only(bottom: 4),
      child: Column(
        children: [
          // 右上角入口（编辑档案 / 改状态）。
          Row(
            mainAxisAlignment: MainAxisAlignment.end,
            children: [
              _circleBtn(
                  const ValueKey('editProfileButton'), Icons.edit_outlined, onEditProfile,
                  tooltip: l10n.petProfileEditEntry),
              const SizedBox(width: 8),
              _circleBtn(const ValueKey('editStatusButton'), Icons.tune, onEditStatus,
                  tooltip: l10n.growthArchiveChangeStatus),
            ],
          ),
          EmojiAvatar(emoji: emoji, size: 96, tone: AppColors.card),
          const SizedBox(height: 12),
          Text(profile.name,
              style: const TextStyle(
                  fontSize: 25, fontWeight: FontWeight.w900, letterSpacing: -0.4)),
          const SizedBox(height: 2),
          Text(
            [
              if (profile.breed != null) profile.breed!,
              if (profile.birthday != null) l10n.growthArchiveAge(age.years, age.months),
            ].join(' · '),
            style: const TextStyle(
                fontSize: 14, color: AppColors.mint700, fontWeight: FontWeight.w700),
          ),
          if (profile.intro != null && profile.intro!.isNotEmpty) ...[
            const SizedBox(height: 10),
            Text('“${profile.intro!}”',
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 14.5, color: AppColors.ink2, height: 1.45)),
          ],
          if (daysTogether != null) ...[
            const SizedBox(height: 16),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 12),
              decoration: BoxDecoration(
                color: AppColors.card,
                borderRadius: BorderRadius.circular(18),
                boxShadow: const [
                  BoxShadow(color: Color(0x0D2B2A27), offset: Offset(0, 2), blurRadius: 8),
                ],
              ),
              child: Column(
                children: [
                  Text('$daysTogether',
                      style: const TextStyle(fontSize: 19, fontWeight: FontWeight.w900)),
                  const Text('Hari bareng',
                      style: TextStyle(fontSize: 11.5, color: AppColors.muted, fontWeight: FontWeight.w600)),
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _circleBtn(Key key, IconData icon, VoidCallback onTap, {required String tooltip}) {
    return Tooltip(
      message: tooltip,
      child: Material(
        color: AppColors.card,
        shape: const CircleBorder(),
        child: InkWell(
          key: key,
          customBorder: const CircleBorder(),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(9),
            child: Icon(icon, size: 20, color: AppColors.ink),
          ),
        ),
      ),
    );
  }
}

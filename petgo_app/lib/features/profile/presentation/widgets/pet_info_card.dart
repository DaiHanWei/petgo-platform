import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../domain/pet_age.dart';
import '../../domain/pet_profile.dart';

/// 宠物护照卡（Story 2.4 · paspor.html 1:1 还原）。
///
/// 横向 pet-top（大头像 + 名字/品种·年龄/一句话简介）+ 卡内三列统计
/// （Momen Bahagia / Konsultasi / Milestone）。白底 rounded-20 + 柔阴影。
class PetInfoCard extends StatelessWidget {
  const PetInfoCard({
    super.key,
    required this.profile,
    this.happyCount,
    this.consultCount,
    this.milestoneCount,
  });

  final PetProfile profile;

  /// 统计三列（archiveStatsProvider 未就绪时传 null，显占位「·」）。
  final int? happyCount;
  final int? consultCount;
  final int? milestoneCount;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final age = computePetAge(profile.birthday);
    final emoji = (profile.avatarUrl == null || profile.avatarUrl!.isEmpty) ? '🐱' : '🐾';
    final sub = [
      if (profile.breed != null && profile.breed!.isNotEmpty) profile.breed!,
      if (profile.birthday != null) l10n.growthArchiveAge(age.years, age.months),
    ].join(' · ');

    return Container(
      key: const ValueKey('petInfoCard'),
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(20),
        boxShadow: const [
          BoxShadow(color: Color(0x142B2A27), offset: Offset(0, 6), blurRadius: 20),
        ],
      ),
      child: Column(
        children: [
          // pet-top：横向 大头像(62) + 名字/品种·年龄/简介
          Row(
            children: [
              Container(
                width: 62,
                height: 62,
                alignment: Alignment.center,
                decoration: const BoxDecoration(
                  shape: BoxShape.circle,
                  gradient: LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [AppColors.mint500, AppColors.mint],
                  ),
                ),
                child: Text(emoji, style: const TextStyle(fontSize: 26)),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(profile.name,
                        style: const TextStyle(
                            fontSize: 19, fontWeight: FontWeight.w700, color: AppColors.ink)),
                    if (sub.isNotEmpty) ...[
                      const SizedBox(height: 2),
                      Text(sub,
                          style: const TextStyle(fontSize: 12, color: AppColors.ink2)),
                    ],
                    if (profile.intro != null && profile.intro!.isNotEmpty) ...[
                      const SizedBox(height: 3),
                      Text('“${profile.intro!}”',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                              fontSize: 11,
                              color: AppColors.muted,
                              fontStyle: FontStyle.italic)),
                    ],
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),
          // statsrow：violet-50 底 rounded-12，三列 + 竖分隔线
          Container(
            decoration: BoxDecoration(
              color: AppColors.cream2,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Row(
              children: [
                _statCol(happyCount, l10n.petInfoStatHappyMoments),
                _divider(),
                _statCol(consultCount, l10n.petInfoStatConsult),
                _divider(),
                _statCol(milestoneCount, l10n.petInfoStatMilestone),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _statCol(int? n, String label) => Expanded(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 10),
          child: Column(
            children: [
              Text(n?.toString() ?? '·',
                  style: const TextStyle(
                      fontSize: 17, fontWeight: FontWeight.w700, color: AppColors.mint)),
              const SizedBox(height: 1),
              Text(label,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 10, color: AppColors.ink2)),
            ],
          ),
        ),
      );

  Widget _divider() =>
      Container(width: 1, height: 40, color: AppColors.line);
}

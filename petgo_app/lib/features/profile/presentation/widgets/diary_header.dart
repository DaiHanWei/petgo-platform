import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../../../../core/theme/colors.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../l10n/app_localizations.dart';
import '../../domain/pet_profile.dart';
import 'pet_info_card.dart';

/// Diary 页头**共用组件**（V1.1.2 Story 2.2 · FR-80/82）。
///
/// 自上而下：标题行（Paspor {name} + 身份证图标按钮 + 编辑铅笔）→ 宠物信息卡（含个性签名行与三列统计）
/// → 健康记录入口卡 → 里程碑进度条。
///
/// ⚠️ **游客态与真实态复用同一份**：Story 3.3 要求真实时间线页头与游客态一致。写成页面内联布局
/// 会迫使两处各实现一遍，随时间漂移 —— 与五类条目渲染组件（[TimelineItemTile]）同类问题。
///
/// ⚠️ **入口可点性由调用方以参数控制**：真实态注入各页跳转；游客态注入建档引导
/// （不得注入 `/profile/health`、`/profile/id-card` 等受控页 —— 游客点了会在种草页中间撞上登录框）。
///
/// 结构以**现网实现为准**，不照 UI 稿 A1/A3 的入口卡画法：健康记录是实心卡（标题 + 副文案），
/// 身份证是 38×38 纯图标按钮（无标题、无编号）。稿子里「Kartu Identitas #00842」那种带编号的卡片
/// 不实现——现网无此形态，且编号可空（老档案未申请时为 null），照稿实现会多出未定义状态。
class DiaryHeader extends StatelessWidget {
  const DiaryHeader({
    super.key,
    required this.profile,
    this.happyCount,
    this.consultCount,
    this.milestoneCompleted,
    this.milestoneTotal,
    this.onEditProfile,
    this.onOpenIdCard,
    this.onOpenHealth,
    this.onOpenMilestones,
  });

  final PetProfile profile;

  /// 三列统计（未就绪传 null → 显占位「·」，沿用现状）。
  final int? happyCount;
  final int? consultCount;

  /// 里程碑进度；任一为 null → 不渲染进度条（沿用现状：统计未就绪时该条不出现）。
  final int? milestoneCompleted;
  final int? milestoneTotal;

  final VoidCallback? onEditProfile;
  final VoidCallback? onOpenIdCard;
  final VoidCallback? onOpenHealth;
  final VoidCallback? onOpenMilestones;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        // 标题行（paspor.html appbar）：Paspor {name} + 身份证 + 编辑铅笔
        Row(
          children: [
            Expanded(
              child: Text(l10n.growthArchivePassportTitle(profile.name),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                      fontSize: 19, fontWeight: FontWeight.w700, color: AppColors.ink)),
            ),
            _iconBtn(
              key: const ValueKey('diaryIdCardButton'),
              onTap: onOpenIdCard,
              child: const Icon(Icons.badge_outlined, size: 20, color: AppColors.ink),
            ),
            const SizedBox(width: 8),
            _iconBtn(
              key: const ValueKey('editProfileButton'),
              onTap: onEditProfile,
              child: SvgPicture.asset(
                'assets/brand/ic_edit.svg',
                width: 18,
                height: 18,
                colorFilter: const ColorFilter.mode(AppColors.ink, BlendMode.srcIn),
              ),
            ),
          ],
        ),
        const SizedBox(height: 14),
        PetInfoCard(
          profile: profile,
          happyCount: happyCount,
          consultCount: consultCount,
          milestoneCount: milestoneCompleted,
        ),
        const SizedBox(height: 11),
        _healthEntryCard(l10n),
        const SizedBox(height: 11),
        if (milestoneCompleted != null && milestoneTotal != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: _milestoneBar(l10n, milestoneCompleted!, milestoneTotal!),
          ),
      ],
    );
  }

  /// appbar 图标按钮（ibtn：白底 rounded-11 + 柔阴影）。[onTap] 为空 → 不可点（游客态由调用方决定）。
  Widget _iconBtn({required Key key, required VoidCallback? onTap, required Widget child}) =>
      Material(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(11),
        elevation: 0,
        child: InkWell(
          key: key,
          borderRadius: BorderRadius.circular(11),
          onTap: onTap,
          child: Container(
            width: 38,
            height: 38,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: AppColors.card,
              borderRadius: BorderRadius.circular(11),
              // 原型 .ibtn 阴影：0 2px 8px rgba(22,34,51,.07)（蓝灰，非棕）。
              boxShadow: const [
                BoxShadow(color: Color(0x12162233), offset: Offset(0, 2), blurRadius: 8),
              ],
            ),
            child: child,
          ),
        ),
      );

  /// 健康记录入口卡（0718：普通实心卡）。图标为圆环对勾，无 tinted 方块底。
  Widget _healthEntryCard(AppLocalizations l10n) => Container(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(14),
          boxShadow: const [
            BoxShadow(color: Color(0x0D2B2A27), offset: Offset(0, 2), blurRadius: 8),
          ],
        ),
        child: Material(
          color: AppColors.card,
          borderRadius: BorderRadius.circular(14),
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            key: const ValueKey('diaryHealthEntry'),
            onTap: onOpenHealth,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
              child: Row(
                children: [
                  const Icon(Icons.check_circle_outline, size: 26, color: AppColors.mint),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(l10n.diaryHealthEntryTitle,
                            style: const TextStyle(
                                fontSize: 15,
                                fontWeight: FontWeight.w600,
                                color: AppColors.ink)),
                        const SizedBox(height: 2),
                        Text(l10n.diaryHealthEntrySub,
                            style: const TextStyle(
                                fontSize: 12, color: AppColors.textSecondary)),
                      ],
                    ),
                  ),
                  const Icon(Icons.chevron_right, color: AppColors.muted),
                ],
              ),
            ),
          ),
        ),
      );

  /// 里程碑进度卡（msbar）：「🏆 Pencapaian {name}」+ "X / N" 紫色 + 进度槽。
  Widget _milestoneBar(AppLocalizations l10n, int completed, int total) {
    final ratio = total == 0 ? 0.0 : completed / total;
    return GestureDetector(
      key: const ValueKey('archiveMilestoneBar'),
      onTap: onOpenMilestones,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 11),
        decoration: BoxDecoration(
          color: AppColors.card,
          borderRadius: BorderRadius.circular(12),
          boxShadow: const [
            BoxShadow(color: Color(0x0D2B2A27), offset: Offset(0, 2), blurRadius: 8),
          ],
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Expanded(
                  child: Text('🏆 ${l10n.growthArchiveAchievements(profile.name)}',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                          fontWeight: FontWeight.w600, fontSize: 12, color: AppColors.ink)),
                ),
                Text('$completed / $total',
                    style: const TextStyle(
                        fontWeight: FontWeight.w600, fontSize: 12, color: AppColors.mint)),
              ],
            ),
            const SizedBox(height: 7),
            ClipRRect(
              borderRadius: BorderRadius.circular(999),
              child: LinearProgressIndicator(
                value: ratio,
                minHeight: 5,
                backgroundColor: AppColors.cream2,
                color: AppColors.mint,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

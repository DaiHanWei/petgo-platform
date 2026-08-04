import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../domain/pet_profile.dart';
import 'pet_info_card.dart';

/// Diary 页头**共用组件**（V1.1.2 Story 2.2 · FR-80/82）。
///
/// 自上而下：标题行（Paspor {name} + 编辑铅笔）→ 宠物信息卡（含个性签名行与三列统计）
/// → 入口区两栏（健康记录 · 身份证）→ 里程碑进度条。
///
/// ⚠️ **游客态与真实态复用同一份**：Story 3.3 要求真实时间线页头与游客态一致。写成页面内联布局
/// 会迫使两处各实现一遍，随时间漂移 —— 与五类条目渲染组件（[TimelineItemTile]）同类问题。
///
/// ⚠️ **入口可点性由调用方以参数控制**：真实态注入各页跳转；游客态注入建档引导
/// （不得注入 `/profile/health`、`/profile/id-card` 等受控页 —— 游客点了会在种草页中间撞上登录框）。
///
/// 入口区按 **UI 稿 A3 的 `entry-grid`** 布局（2026-08-04 用户对稿反馈后修正）：
/// **两栏等宽卡**（1fr 1fr，间距 10）—— 左「健康记录」、右「身份证」。
/// - 身份证入口**从标题行挪到这里**（原先是标题行里的 38×38 图标按钮，位置与稿子不符）；
///   **图标沿用现网的徽章图标**（`Icons.badge_outlined`），只按稿调整位置与尺寸。
/// - 健康记录卡**样式不变**（白底 r14 + 柔阴影 + 圆环对勾 + 同一套文案），
///   只把宽度改为半栏、内部改为稿子的纵向排布（图标 → 标题 → 副文案）。
/// - ⚠️ **仍不渲染 `#00842` 编号**：编号可空（老档案未申请时为 null），照稿渲染会多出
///   「没有编号时显示什么」的未定义状态。副文案改用「点击查看」，与时间线证件卡同一 key。
class DiaryHeader extends StatelessWidget {
  const DiaryHeader({
    super.key,
    required this.profile,
    this.happyCount,
    this.consultCount,
    this.milestoneCompleted,
    this.milestoneTotal,
    this.healthRecordCount,
    this.titleAction,
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

  /// 结构化健康记录条数。为 0 时健康入口副文案改「还没有记录」（A4 近空态）；
  /// **null = 未知**（统计未就绪 / 游客示例态）→ 沿用固定副文案，不冒充空态。
  final int? healthRecordCount;

  /// 标题行里编辑按钮**左侧**的附加动作（真实态注入分享名片按钮；游客态传 null）。
  /// 2026-08-04 用户要求把分享从右下悬浮 FAB 挪到这里 —— FAB 会盖住时间线 / 日历的内容。
  final Widget? titleAction;

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
            if (titleAction != null) ...[
              titleAction!,
              const SizedBox(width: 8),
            ],
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
        _entryGrid(l10n),
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

  /// 入口区两栏（A3 `entry-grid`：1fr 1fr、间距 10）。等高由 [IntrinsicHeight] 保证 ——
  /// 两卡文案行数不同（健康记录副文案较长会折行），不拉等高会一高一低。
  Widget _entryGrid(AppLocalizations l10n) => IntrinsicHeight(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(
              child: _entryCard(
                key: const ValueKey('diaryHealthEntry'),
                onTap: onOpenHealth,
                icon: Icons.check_circle_outline,
                iconColor: AppColors.mint,
                title: l10n.diaryHealthEntryTitle,
                // A4 近空态：一条记录都没有时，别用「疫苗 · 驱虫 · 病历」这种
                // 读起来像「里面已经有东西」的描述；顺带这条短文案不再折两行撑高整行。
                sub: healthRecordCount == 0
                    ? l10n.diaryHealthEntrySubEmpty
                    : l10n.diaryHealthEntrySub,
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: _entryCard(
                // key 沿用（原为标题行的图标按钮）——入口语义未变，只是位置从标题行挪到入口区。
                key: const ValueKey('diaryIdCardButton'),
                onTap: onOpenIdCard,
                icon: Icons.badge_outlined,
                iconColor: AppColors.mint,
                title: l10n.idCardTitle,
                sub: l10n.timelineIdCardTapToView,
              ),
            ),
          ],
        ),
      );

  /// 入口卡（A3 `entry-card`）：白底 r14 + 柔阴影 + 纵向「图标 → 标题 → 副文案」。
  /// 样式沿用现网健康记录卡（颜色 / 圆角 / 阴影 / 文案），只按稿改为半栏宽 + 纵向排布。
  Widget _entryCard({
    required Key key,
    required VoidCallback? onTap,
    required IconData icon,
    required Color iconColor,
    required String title,
    required String sub,
  }) =>
      DecoratedBox(
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
            key: key,
            onTap: onTap,
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(icon, size: 26, color: iconColor),
                  const SizedBox(height: 7),
                  Text(title,
                      style: const TextStyle(
                          fontSize: 13,
                          height: 1.25,
                          fontWeight: FontWeight.w600,
                          color: AppColors.ink)),
                  const SizedBox(height: 3),
                  Text(sub,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                          fontSize: 10.5, height: 1.3, color: AppColors.textTertiary)),
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

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/count_badge.dart';
import '../../domain/pet_header_info.dart';
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
    this.giftTotalValue,
    this.onOpenGifts,
    this.milestoneCompleted,
    this.milestoneTotal,
    this.milestoneUncelebrated = 0,
    this.healthRecordCount,
    this.titleAction,
    this.petCardAction,
    this.onEditProfile,
    this.onOpenIdCard,
    this.onOpenHealth,
    this.onOpenMilestones,
    this.insightsEntryAnchor,
    this.readOnly = false,
    this.hideEditButton = false,
    this.avatarKey,
    this.belowTitle,
    this.showTitleRow = true,
  });

  /// 是否画「Diary {name}」标题行（含右侧分享 / 编辑按钮）。
  ///
  /// V1.4.1 Diary 页头修复 ⑤：作者态有宠物切换器时由页面传 false —— 宠物名在切换器高亮和宠物卡里
  /// 已各出现一次，标题行多余；分享 / 编辑按钮由页面挪到切换器同一行（见 [DiaryIconButton]）。
  /// 游客页 / 访客页不传，行为不变。
  final bool showTitleRow;

  /// 标题行与宠物信息卡之间的插槽（V1.4.0 multi-pet B4：只读横幅放这里，稿 `.dh` 之后、`.pic` 之前）。
  /// 默认 null = 改动前外观。
  final Widget? belowTitle;

  /// 当前宠物只读（V1.4.0 multi-pet Story 2.4 · AC5.2 / B4）：**只**隐藏编辑铅笔；入口两栏、里程碑、分享照常。
  /// 与访客态 [readOnly]（整块移除入口网格）是两回事，默认 false = 改动前外观。
  final bool hideEditButton;

  /// 宠物信息卡头像的位置锚点，原样透传给 [PetInfoCard]（V1.4.0 gift Story 1.3 送出动效落点）。
  /// 默认 null 行为不变。
  final GlobalKey? avatarKey;

  /// 综合入口卡的**位置锚点**（V1.3.0 Story 5.4）。
  ///
  /// 迁移引导蒙层要知道「高亮框画在哪儿」，而那只能从真实布局里量。
  /// ⚠️ 它**只用来量位置**，不改这张卡的任何行为 —— 三态判定仍只有 `readOnly` 一处（AD-A24.4）。
  final GlobalKey? insightsEntryAnchor;

  /// 只读态（V1.1.6 Story 2.3 访客视图）。
  ///
  /// 🛡 为 true 时**不渲染**编辑铅笔与入口网格两张卡（健康记录 / 身份证）。
  ///
  /// ⚠️ 必须是「**不渲染**」而不是「不可点」：
  /// 把 `onEditProfile` 传 null 只会让铅笔变灰、图标仍在，看上去像坏了；
  /// 而身份证入口跳的是**当前登录用户自己的**身份证页，访客点进去必然出错 ——
  /// AD-2 Rule 5 因此要求两张卡**整块移除**，「留个锁」不算数。
  final bool readOnly;

  final PetHeaderInfo profile;

  /// 三列统计（未就绪传 null → 显占位「·」，沿用现状）。
  final int? happyCount;

  /// 中间格：礼物总价值（V1.4.0 gift Story 2.2；原 consultCount）。原样透传 [PetInfoCard]。
  final int? giftTotalValue;

  /// 点礼物格进礼物页。⚠️ 与访客只读三态判定无关：访客态同样可点（UI 稿 A1）。
  final VoidCallback? onOpenGifts;

  /// 里程碑进度；任一为 null → 不渲染进度条（沿用现状：统计未就绪时该条不出现）。
  final int? milestoneCompleted;
  final int? milestoneTotal;

  /// 「已完成且未庆祝」条目数（V1.3.0 Story 1.5 · AD-A2.2）：>0 时在里程碑进度卡右上角出红点。
  ///
  /// ⚠️ 档案 Tab **只显示角标、永不弹全屏庆祝**（AD-A2.1）—— 补弹只挂里程碑列表页。
  /// 两处都弹的话，用户从这里点进列表页会被连弹两次。
  final int milestoneUncelebrated;

  /// 结构化健康记录条数。为 0 时健康入口副文案改「还没有记录」（A4 近空态）；
  /// **null = 未知**（统计未就绪 / 游客示例态）→ 沿用固定副文案，不冒充空态。
  final int? healthRecordCount;

  /// 标题行里编辑按钮**左侧**的附加动作（真实态注入分享名片按钮；游客态传 null）。
  /// 2026-08-04 用户要求把分享从右下悬浮 FAB 挪到这里 —— FAB 会盖住时间线 / 日历的内容。
  final Widget? titleAction;

  /// 宠物卡右上角控件（作者态「只看 Diary」开关，2026-10-06）。
  final Widget? petCardAction;

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
        if (showTitleRow)
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
              if (!readOnly && !hideEditButton)
                DiaryIconButton(
                  inkKey: const ValueKey('editProfileButton'),
                  onTap: onEditProfile,
                  child: const DiaryEditIcon(),
                ),
            ],
          ),
        if (belowTitle != null) ...[
          if (showTitleRow) const SizedBox(height: 10),
          belowTitle!,
          const SizedBox(height: 12),
        ] else if (showTitleRow)
          const SizedBox(height: 14),
        PetInfoCard(
          profile: profile,
          happyCount: happyCount,
          giftTotalValue: giftTotalValue,
          onOpenGifts: onOpenGifts,
          milestoneCount: milestoneCompleted,
          topRight: petCardAction,
          avatarKey: avatarKey,
        ),
        // 🛡 入口网格（健康记录 / 身份证）整块移除，不是「留个锁」——
        // 健康记录不对访客开放；身份证入口跳的是当前登录用户自己的卡，点进去必然出错。
        if (!readOnly) ...[
          const SizedBox(height: 11),
          _entryGrid(l10n),
        ],
        const SizedBox(height: 11),
        if (milestoneCompleted != null && milestoneTotal != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: _milestoneBar(l10n, milestoneCompleted!, milestoneTotal!),
          ),
      ],
    );
  }

  /// 入口区（V1.4.1 Diary 页头修复 ①）：**一张白卡、上下两行**——健康记录 → 分隔线 → 「Kenali Hewanmu」聚合入口。
  ///
  /// 取代 V1.4.0 L2 还原度时改出的左右两栏无图标文字卡（那版照着 1.4.0 背景示意画的，1.4.1 稿已作废）。
  /// 卡：白底 r14 + 柔阴影、无描边；行间 1px 分隔线左缩进 54（对齐文字）。
  Widget _entryGrid(AppLocalizations l10n) => DecoratedBox(
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
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              _entryRow(
                key: const ValueKey('diaryHealthEntry'),
                onTap: onOpenHealth,
                icon: Icons.check_circle_outline,
                title: l10n.diaryHealthEntryTitle,
                // A4 近空态：一条记录都没有时，别用「疫苗 · 驱虫 · 病历」这种
                // 读起来像「里面已经有东西」的描述。
                sub: healthRecordCount == 0
                    ? l10n.diaryHealthEntrySubEmpty
                    : l10n.diaryHealthEntrySub,
              ),
              const Padding(
                padding: EdgeInsets.only(left: 54),
                child: Divider(height: 1, thickness: 1, color: AppColors.line2),
              ),
              // 引导蒙层的锚点挂在**外层**：给行本身换 key 会动到它自己的
              // ValueKey('diaryIdCardButton')，那把既有测试与埋点对照一起弄断。
              KeyedSubtree(
                key: insightsEntryAnchor,
                child: _entryRow(
                  // key 沿用（V1.3.0 Story 5.1 起指向「Know Your Pet」聚合页，身份证是其中一张卡）。
                  key: const ValueKey('diaryIdCardButton'),
                  onTap: onOpenIdCard,
                  // UI 稿 P1：综合入口用星芒图标（爪印与「宠物」语义重复、看不出是聚合入口）。
                  icon: Icons.auto_awesome_outlined,
                  title: l10n.petInsightsTitle,
                  // 副文案沿用现成 key，**不新写**（AC1）。
                  sub: l10n.timelineIdCardTapToView,
                ),
              ),
            ],
          ),
        ),
      );

  /// 入口行：32×32 mintTint 图标块 → 标题 + 单行副标题 → 右箭头；整行可点。
  ///
  /// ⚠️ 仍不渲染 `#00842` 编号（编号可空，见类注释）。
  Widget _entryRow({
    required Key key,
    required VoidCallback? onTap,
    required IconData icon,
    required String title,
    required String sub,
  }) {
    return InkWell(
      key: key,
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        child: Row(
          children: [
            Container(
              width: 32,
              height: 32,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: AppColors.mintTint,
                borderRadius: BorderRadius.circular(9),
              ),
              child: Icon(icon, size: 18, color: AppColors.mint),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                          fontSize: 13, height: 1.3, fontWeight: FontWeight.w600, color: AppColors.ink)),
                  const SizedBox(height: 2),
                  Text(sub,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 11, height: 1.35, color: AppColors.muted)),
                ],
              ),
            ),
            const SizedBox(width: 8),
            const Icon(Icons.chevron_right, size: 16, color: AppColors.muted),
          ],
        ),
      ),
    );
  }

  /// 里程碑进度卡（msbar）：「🏆 Pencapaian {name}」+ "X / N" 紫色 + 进度槽。
  ///
  /// V1.3.0 Story 1.5：有未庆祝条目时右上角挂一枚红色角标（复用通知铃铛那颗 [CountBadge]，
  /// 不另画）。**卡片本身的布局与视觉一字不改** —— 角标由外层 Stack 叠上去，不挤占内容。
  Widget _milestoneBar(AppLocalizations l10n, int completed, int total) {
    final bar = _milestoneBarCard(l10n, completed, total);
    if (milestoneUncelebrated <= 0) {
      return bar;
    }
    return Stack(
      clipBehavior: Clip.none,
      children: [
        bar,
        Positioned(
          right: -4,
          top: -4,
          child: CountBadge(
            key: const ValueKey('milestoneUncelebratedBadge'),
            count: milestoneUncelebrated,
          ),
        ),
      ],
    );
  }

  Widget _milestoneBarCard(AppLocalizations l10n, int completed, int total) {
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
                        fontWeight: FontWeight.w600,
                        fontSize: 12,
                        fontFeatures: [FontFeature.tabularFigures()],
                        color: AppColors.mint)),
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

/// 编辑铅笔图标（`ic_edit.svg`，18、ink 色）。标题行与作者态切换器行共用。
class DiaryEditIcon extends StatelessWidget {
  const DiaryEditIcon({super.key});

  @override
  Widget build(BuildContext context) => SvgPicture.asset(
        'assets/brand/ic_edit.svg',
        width: 18,
        height: 18,
        colorFilter: const ColorFilter.mode(AppColors.ink, BlendMode.srcIn),
      );
}

/// Diary 页头图标按钮（ibtn：38×38 白底 rounded-11 + 柔阴影）。[onTap] 为空 → 不可点。
///
/// V1.4.1 ⑤ 起作者态的分享 / 编辑按钮放在切换器那一行，由页面直接用它；[inkKey] 落在 InkWell 上（沿用既有 key）。
class DiaryIconButton extends StatelessWidget {
  const DiaryIconButton({super.key, this.inkKey, required this.onTap, required this.child});

  /// 落在 InkWell 上的 key（沿用既有测试 / 埋点对照的 key，如 `editProfileButton`）。
  final Key? inkKey;
  final VoidCallback? onTap;
  final Widget child;

  @override
  Widget build(BuildContext context) => Material(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(11),
        elevation: 0,
        child: InkWell(
          key: inkKey,
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
}

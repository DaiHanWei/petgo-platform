import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/customer_service_sheet.dart';
import '../../../shared/widgets/initial_avatar.dart';
import '../../../shared/widgets/post_grid_tile.dart';
import '../../auth/domain/auth_state.dart';
import '../../auth/domain/login_response.dart';
import '../../profile/data/profile_repository.dart';
import '../../profile/data/timeline_repository.dart';
import '../../profile/domain/pet_age.dart';
import '../../profile/domain/pet_profile.dart';
import '../data/my_posts_repository.dart';
import 'profile_edit_sheet.dart';

/// 「我的」页面（Story 7.1，FR-20 · F8 信息架构重组）。
///
/// 🔄 PRD V1.0.0 修订（F8 · 2026-06-08）：顶栏右上「帮助反馈」+「设置」双图标；语言/退出/注销三项
/// 收进二级设置页（点设置图标进入）；主页面主体只承载「人 + 宠物」内容——用户信息 / 宠物卡片或引导卡
/// （AC5 三态，人 60%/宠物 40%）/ 宠物状态 / 我的发布。语言(7.2)/退出注销(7.3) 仅放入口。
class MePage extends ConsumerWidget {
  const MePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final auth = ref.watch(authControllerProvider);
    final profile = auth.profile;
    return Scaffold(
      backgroundColor: AppColors.base,
      // 原型 profil.html：无大标题，右上仅 headset + gear 双 ibtn（白底圆角+阴影）。
      appBar: AppBar(
        backgroundColor: AppColors.base,
        scrolledUnderElevation: 0,
        automaticallyImplyLeading: false,
        // 左上角品牌标：与首页同一 Tailtopia wordmark（bug 20260702-206：用 logo 替代文字标题）。
        centerTitle: false,
        titleSpacing: 20,
        title: SvgPicture.asset(
          'assets/brand/logo.svg',
          height: 28,
          semanticsLabel: l10n.appTitle,
        ),
        actions: [
          // 帮助反馈图标（PDP 数据主体权利可达路径承载之一）。
          _IconBtn(
            valueKey: 'meHelp',
            icon: Icons.support_agent_outlined,
            tooltip: l10n.meHelp,
            onTap: () => showCustomerServiceSheet(context),
          ),
          const SizedBox(width: 8),
          // 设置图标 → 二级设置页（语言/退出/注销）。PDP 注销入口经此可达。
          _IconBtn(
            valueKey: 'meSettings',
            icon: Icons.settings_outlined,
            tooltip: l10n.meSettings,
            onTap: () => context.push('/me/settings'),
          ),
          const SizedBox(width: AppSpacing.screenEdge),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(AppSpacing.screenEdge),
        children: [
          // ① profhead：用户信息 + 宠物 mini 同卡（原型 p-profil profhead）。
          _ProfileHeadCard(
            profile: profile,
            onEdit: () => openProfileEditSheet(context, ref),
            onAvatarTap: () => changeAvatar(context, ref),
          ),
          const SizedBox(height: AppSpacing.lg),
          // AKTIVITAS 入口组（0711 profil-entries）：订单为主入口（PawCoin 已并入订单列表 header
          // 余额格，退款经订单详情可达），紫虚线卡 + BARU 徽章突出 V1.1 新增。
          _ActivitySection(onOrders: () => context.push('/me/orders')),
          const SizedBox(height: AppSpacing.lg),
          // ② 我的发布（原型 Postinganku）：小标题 + 裸 2 列网格（无卡边框）。
          // 注：宠物状态/改状态/编辑档案区块已按设计（原型 p-profil 无此块）移除。
          Padding(
            padding: const EdgeInsets.only(
              left: AppSpacing.xs,
              bottom: AppSpacing.sm,
            ),
            child: Text(
              l10n.meMyPostsTitle.toUpperCase(),
              style: AppTypography.caption.copyWith(
                letterSpacing: 0.6,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          _MyPostsList(),
        ],
      ),
    );
  }

}

/// AC5 宠物区位三态分支（人 60%/宠物 40%）。
///
/// - 状态 A 且已建档 → [_PetCard]（宠物头像 + 名字 + 最近一条快乐时刻首图），点击跳成长档案 Tab。
/// - 状态 A 未建档 → [_PetGuideCard]「给你的宠物创建专属档案」，点击进 FR-11 创建流程。
/// - 状态 B / C → 不显示（宠物卡片与引导卡均不渲染）。
class _PetZone extends ConsumerWidget {
  const _PetZone();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final profile = ref.watch(authControllerProvider).profile;
    // PLANNING/ENTHUSIAST 不显示
    if (profile?.petStatus != 'HAS_PET') return const SizedBox.shrink();
    // 「已建档」以真实档案为准，不信任登录响应里可能 stale 的 hasPetProfile：
    // 后端 /auth/* 登录响应恒返回 hasPetProfile=false，仅 /me 与 /pet-profiles 才是权威，
    // 否则老用户登录后即便有档案，/me 仍会错误显示「创建宠物档案」引导卡（用户反馈）。
    final petAsync = ref.watch(petProfileProvider);
    return petAsync.when(
      data: (pet) => pet == null ? const _PetGuideCard() : const _PetCard(),
      loading: () => const SizedBox.shrink(),
      // 拉取失败不误导地催建档，留空待重试。
      error: (_, _) => const SizedBox.shrink(),
    );
  }
}

/// AC5「给你的宠物创建专属档案」引导卡（状态 A 未建档）。
class _PetGuideCard extends StatelessWidget {
  const _PetGuideCard();

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return GestureDetector(
      key: const ValueKey('mePetGuideCard'),
      onTap: () => context.push('/profile/create'),
      child: Container(
        padding: const EdgeInsets.all(AppSpacing.md),
        decoration: BoxDecoration(
          color: AppColors.accentGrowth.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: AppColors.accentGrowth.withValues(alpha: 0.3),
          ),
        ),
        child: Row(
          children: [
            const Icon(Icons.pets, color: AppColors.accentGrowth),
            const SizedBox(width: AppSpacing.md),
            Expanded(
              child: Text(
                l10n.mePetCardCreateTitle,
                style: AppTypography.body.copyWith(
                  color: AppColors.accentGrowth,
                ),
              ),
            ),
            const Icon(Icons.chevron_right, color: AppColors.accentGrowth),
          ],
        ),
      ),
    );
  }
}

/// AC5 宠物卡片（状态 A 已建档）·原型 petmini：宠物头像 + 名字 + 元数据（种类 · 年龄 · momen 数）+「Lihat →」。
class _PetCard extends ConsumerWidget {
  const _PetCard();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final petAsync = ref.watch(petProfileProvider);
    return petAsync.maybeWhen(
      data: (pet) {
        if (pet == null) return const SizedBox.shrink();
        final meta = _petMeta(context, ref, pet, l10n);
        return GestureDetector(
          key: const ValueKey('mePetCard'),
          onTap: () => context.go('/profile'), // 跳成长档案 Tab
          child: Container(
            padding: const EdgeInsets.all(11),
            decoration: BoxDecoration(
              color: AppColors.mintTint2, // 原型 petmini 紫浅底 #F8F6FF
              borderRadius: BorderRadius.circular(13),
            ),
            child: Row(
              children: [
                InitialAvatar(
                  avatarUrl: pet.avatarUrl,
                  nickname: pet.name,
                  radius: 21,
                ),
                const SizedBox(width: 11),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        pet.name,
                        style: AppTypography.body.copyWith(
                          fontWeight: FontWeight.w700,
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      if (meta.isNotEmpty) ...[
                        const SizedBox(height: 2),
                        Text(
                          meta,
                          key: const ValueKey('mePetCardMeta'),
                          style: AppTypography.caption.copyWith(
                            color: AppColors.textSecondary,
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ],
                    ],
                  ),
                ),
                const SizedBox(width: AppSpacing.sm),
                Text(
                  '${l10n.meViewArchive} →',
                  style: const TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w700,
                    color: AppColors.mint,
                  ),
                ),
              ],
            ),
          ),
        );
      },
      orElse: () => const SizedBox.shrink(),
    );
  }

  /// 元数据「种类 · 年龄 · momen 数」：种类由 petType、年龄由 birthday 计算、momen 数取 archiveStats。
  String _petMeta(
    BuildContext context,
    WidgetRef ref,
    PetProfile pet,
    AppLocalizations l10n,
  ) {
    final species = switch (pet.petType) {
      'CAT' => l10n.petTypeCat,
      'DOG' => l10n.petTypeDog,
      'OTHER' => l10n.petTypeOther,
      _ => null,
    };
    final momen = ref
        .watch(archiveStatsProvider)
        .asData
        ?.value
        .happyMomentCount;
    return [
      ?species,
      // 与档案页同一出口：不满 1 个月按天表达，避免「0th 0bln」。
      ?formatPetAge(l10n, pet.birthday),
      // bug 20260722-355：GROWTH_MOMENT 帖即「Diary」（与档案头部/网格徽章同口径），
      // 文案由「moments」改「diary」，>99 显示「99+」，跟随实际记录数变化。
      if (momen != null) l10n.meDiaryCount(momen > 99 ? '99+' : '$momen'),
    ].join(' · ');
  }
}

/// 原型 .ibtn：38×38 白底圆角11 带阴影的图标按钮（profil 顶部 headset/gear）。
class _IconBtn extends StatelessWidget {
  const _IconBtn({
    required this.valueKey,
    required this.icon,
    required this.tooltip,
    required this.onTap,
  });

  final String valueKey;
  final IconData icon;
  final String tooltip;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip,
      child: InkWell(
        key: ValueKey(valueKey),
        onTap: onTap,
        borderRadius: BorderRadius.circular(11),
        child: Container(
          width: 38,
          height: 38,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: AppColors.surface,
            borderRadius: BorderRadius.circular(11),
            boxShadow: const [
              BoxShadow(
                color: Color(0x12162233),
                blurRadius: 8,
                offset: Offset(0, 2),
              ),
            ],
          ),
          child: Icon(icon, size: 18, color: AppColors.ink2),
        ),
      ),
    );
  }
}

/// AKTIVITAS 入口组（0718：普通实心卡，去 0711 的紫虚线+BARU）：section label + 实心卡「Pesanan Saya」。
/// PawCoin 入口已迁至订单列表 header 余额格；退款经订单详情可达 —— 故本组仅一张订单主入口卡。
class _ActivitySection extends StatelessWidget {
  const _ActivitySection({required this.onOrders});

  final VoidCallback onOrders;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // 分组标题（0718 去 BARU 徽章）。
        Padding(
          padding: const EdgeInsets.only(left: AppSpacing.xs, bottom: AppSpacing.sm),
          child: Text(
            l10n.meActivitySection,
            style: AppTypography.caption.copyWith(
              letterSpacing: 0.6,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
        // 实心卡「Pesanan Saya · Termasuk saldo PawCoin」→ 订单中心（图标去 tinted 方块底）。
        Container(
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
              key: const ValueKey('meOrders'),
              onTap: onOrders,
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
                child: Row(
                  children: [
                    const Icon(Icons.credit_card_outlined, size: 26, color: AppColors.mint),
                    const SizedBox(width: AppSpacing.md),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(l10n.orderMyTitle,
                              style: const TextStyle(
                                  fontSize: 15,
                                  fontWeight: FontWeight.w600,
                                  color: AppColors.ink)),
                          const SizedBox(height: 2),
                          Text(l10n.meOrdersEntrySub,
                              style: AppTypography.caption
                                  .copyWith(color: AppColors.textSecondary)),
                        ],
                      ),
                    ),
                    const Icon(Icons.chevron_right, color: AppColors.muted),
                  ],
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

/// profhead 卡（原型 p-profil）：白卡 + 阴影，内含「用户行（渐变头像 + 名/邮箱 + Edit）」+
/// 状态 A 时下挂「宠物 mini」（[_PetZone]，紫浅底行）。B/C 仅用户行。
class _ProfileHeadCard extends StatelessWidget {
  const _ProfileHeadCard({required this.profile, required this.onEdit, this.onAvatarTap});

  final UserProfile? profile;
  final VoidCallback onEdit;
  final VoidCallback? onAvatarTap; // Story B：点头像换图(上传)

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final name = profile?.nickname ?? profile?.displayName ?? '';
    final email = profile?.email ?? '';
    final showPet = profile?.petStatus == 'HAS_PET';
    // 原型 profhead：背景与页面同为白，无可见卡片/阴影（白叠白 + 6% 阴影≈无），故不加卡片装饰；
    // 仅内部 petmini 用紫浅底区分。
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            // 原型 avlg 62（渐变 + 首字母）。Story B：点击换头像(上传) + 右下角相机角标。
            GestureDetector(
              key: const ValueKey('meAvatarUpload'),
              onTap: onAvatarTap,
              child: Stack(
                children: [
                  InitialAvatar(avatarUrl: profile?.avatarUrl, nickname: name, radius: 31),
                  Positioned(
                    right: 0,
                    bottom: 0,
                    child: Container(
                      padding: const EdgeInsets.all(4),
                      decoration: BoxDecoration(
                        color: AppColors.accentGrowth,
                        shape: BoxShape.circle,
                        border: Border.all(color: AppColors.surface, width: 2),
                      ),
                      child: const Icon(Icons.photo_camera, size: 12, color: Colors.white),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    name,
                    style: AppTypography.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  // 一句话个性签名（bug 20260721-327）。
                  if ((profile?.signature ?? '').isNotEmpty) ...[
                    const SizedBox(height: 3),
                    Text(
                      profile!.signature!,
                      key: const ValueKey('meSignature'),
                      style: AppTypography.caption,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                  if (email.isNotEmpty) ...[
                    const SizedBox(height: 2),
                    Text(
                      email,
                      style: AppTypography.caption,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ],
              ),
            ),
            const SizedBox(width: AppSpacing.sm),
            // 原型 editbtn：圆角矩形(8) + 1.5px 浅紫描边(#C2B0EC) + 紫字，无图标。
            OutlinedButton(
              key: const ValueKey('meEditNickname'),
              onPressed: onEdit,
              style: OutlinedButton.styleFrom(
                foregroundColor: AppColors.accentGrowth,
                side: const BorderSide(
                  color: AppColors.dashedViolet,
                  width: 1.5,
                ),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(8),
                ),
                // 原型 padding:6 12；给文字与描边留足内边距（去掉 compact 压缩）。
                padding: const EdgeInsets.symmetric(
                  horizontal: 14,
                  vertical: 8,
                ),
                minimumSize: Size.zero,
                tapTargetSize: MaterialTapTargetSize.shrinkWrap,
              ),
              child: Text(
                l10n.meEditButton,
                style: const TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          ],
        ),
        // 宠物 mini（状态 A：已建档=宠物卡 / 未建档=引导卡；B/C 不挂）。
        if (showPet) ...[const SizedBox(height: 14), const _PetZone()],
      ],
    );
  }
}


/// 「我的发布」列表（原型 Postinganku）：2 列裸网格；一格的样子见 `PostGridTile`。
class _MyPostsList extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final posts = ref.watch(myPostsProvider);
    return posts.when(
      loading: () => const Padding(
        padding: EdgeInsets.all(AppSpacing.lg),
        child: SizedBox(height: 1),
      ),
      error: (_, _) => const SizedBox(height: 1),
      data: (items) {
        if (items.isEmpty) {
          return Padding(
            padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
            child: Text(
              l10n.meNoPosts,
              key: const ValueKey('meNoPosts'),
              style: AppTypography.caption,
            ),
          );
        }
        // 2 列方形裸网格（原型 pgrid，无卡边框）：封面图（无图→彩块）+ 左上类型 badge；保留后端 created_at 倒序。
        return GridView.count(
          crossAxisCount: 2,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          mainAxisSpacing: 7,
          crossAxisSpacing: 7,
          children: [
            for (final p in items)
              // V1.3.0 batch-b1 Story 2.2：这一格抽成了公共组件，与他人公开主页共用
              // （复制一份的话，圆角 / badge 配色 / 首图降级会各改各的）。
              PostGridTile(
                postId: p.id,
                type: p.type,
                firstImageUrl: p.firstImageUrl,
                isPrivate: p.isPrivate,
                onTap: () => context.push('/content/${p.id}'),
              ),
          ],
        );
      },
    );
  }
}

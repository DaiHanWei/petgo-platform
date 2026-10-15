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
import '../../../shared/widgets/profile_stats_row.dart';
import '../../auth/domain/auth_state.dart';
import '../../auth/domain/login_response.dart';
import '../../profile/data/profile_repository.dart';
import '../../profile/data/timeline_repository.dart';
import '../../profile/domain/pet_profile.dart';
import '../../profile/presentation/widgets/pet_avatar_item.dart';
import '../../social/domain/follow_list_item.dart';
import '../../social/presentation/follow_list_page.dart';
import '../data/me_counts_provider.dart';
import '../data/my_posts_repository.dart';
import 'profile_edit_sheet.dart';

/// 「我的」页面（Story 7.1，FR-20 · F8 信息架构重组）。
///
/// V1.3.4 follow Story 1.4（UI 稿 A1「居中名片」）：头像 → 昵称 → 签名 → 四格（Postingan / Suka /
/// Mengikuti / Pengikut）→ 整宽 Edit Profil → Hewanku → Aktivitas → Postinganku。邮箱不再展示（接口仍保留）。
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
          // ① 居中名片：头像 / 昵称 / 签名（V1.3.4 Story 1.4 · AC2）。
          _ProfileHeadCard(
            profile: profile,
            onAvatarTap: () => changeAvatar(context, ref),
          ),
          const SizedBox(height: AppSpacing.md),
          // ② 四格（AC2.2）。Mengikuti / Pengikut 本 story 不可点（Story 2.2 接关注列表）。
          _MeStats(profile: profile),
          const SizedBox(height: AppSpacing.md),
          // ③ 整宽「Edit Profil」（AC2.4，UI 稿 `.wbtn.ghost`）。
          _EditProfileButton(onEdit: () => openProfileEditSheet(context, ref)),
          // ④ Hewanku（AC3）：只在 HAS_PET 时出现（含未建档的引导卡，T-13）。
          const _PetZone(),
          const SizedBox(height: AppSpacing.lg),
          // AKTIVITAS 入口组（0711 profil-entries）：订单为主入口（PawCoin 已并入订单列表 header
          // 余额格，退款经订单详情可达）。
          _ActivitySection(onOrders: () => context.push('/me/orders')),
          const SizedBox(height: AppSpacing.lg),
          // ⑤ 我的发布（原型 Postinganku）：小节标题（表头不显示数量，数量在四格）+ 裸 2 列网格。
          Padding(
            padding: const EdgeInsets.only(bottom: AppSpacing.sm),
            child: _SectionTitle(l10n.meMyPostsTitle),
          ),
          _MyPostsList(),
        ],
      ),
    );
  }

}

/// 小节标题（V1.3.4 Story 1.4）：12 / w700 / 大写 / letterSpacing 0.6 / `muted` ——
/// 与他人主页的 Hewan / Postingan 同款。
class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Text(
        text.toUpperCase(),
        style: const TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.w700,
          letterSpacing: 0.6,
          color: AppColors.muted,
        ),
      );
}

/// 我的页四格（V1.3.4 Story 1.4 · AC1 / AC2.2）。
///
/// 数据源以 `GET /me`（[meCountsProvider]）为准（T-6）；它首次加载中 / 失败时回落到登录态里的
/// 同名字段（冷启动 restoreSession 走 `/me`，通常已有值）；两者都没有 → 数字位「–」（T-14，不闪 0）。
/// 刷新中保留上次的值（Riverpod 3 的 `value` 在 refresh / 出错时仍给上一份数据）。
class _MeStats extends ConsumerWidget {
  const _MeStats({required this.profile});

  final UserProfile? profile;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // 按当前用户 id 取实例（换账号不带上一个账号的旧值，见 meCountsProvider 注释）。
    final counts = ref.watch(meCountsProvider(profile?.id)).value ?? MeCounts.fromProfile(profile);
    // V1.3.4 Story 2.2 · AC2：Mengikuti / Pengikut 两格进我的关注 / 粉丝列表；id 未知时不可点。
    final id = profile?.id;
    return ProfileStatsRow(
      postCount: counts.postCount,
      likeCount: counts.likeCount,
      followingCount: counts.followingCount,
      followerCount: counts.followerCount,
      onFollowingTap:
          id == null ? null : () => context.push(FollowListPage.location(id, FollowListTab.following)),
      onFollowersTap:
          id == null ? null : () => context.push(FollowListPage.location(id, FollowListTab.followers)),
    );
  }
}

/// 整宽「Edit Profil」（V1.3.4 Story 1.4 · AC2.4，UI 稿 `.wbtn.ghost`）：白底 + 1.5px 浅紫描边 + 紫字。
/// 与他人主页「自己看自己」的 Edit Profil 同款（D-11）。点了仍是既有的资料编辑抽屉。
class _EditProfileButton extends StatelessWidget {
  const _EditProfileButton({required this.onEdit});

  final VoidCallback onEdit;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return SizedBox(
      width: double.infinity,
      height: 40,
      child: OutlinedButton(
        key: const ValueKey('meEditNickname'),
        onPressed: onEdit,
        style: OutlinedButton.styleFrom(
          backgroundColor: AppColors.surface,
          foregroundColor: AppColors.mint,
          side: const BorderSide(color: AppColors.dashedViolet, width: 1.5),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          padding: EdgeInsets.zero,
        ),
        child: Text(
          l10n.meEditProfileTitle,
          style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
        ),
      ),
    );
  }
}

/// AC5 宠物区位三态分支（人 60%/宠物 40%）。
///
/// - 状态 A 且已建档 → [_PetCard]（圆形头像一排，与他人主页同款），点击跳成长档案 Tab。
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
    final l10n = AppLocalizations.of(context);
    // V1.3.4 Story 1.4 · AC3：独立小节「Hewanku」+ 内容；引导卡也属于宠物区，同样带标题（T-13）。
    Widget section(Widget child) => Padding(
          padding: const EdgeInsets.only(top: AppSpacing.lg),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _SectionTitle(l10n.mePetsSection),
              const SizedBox(height: AppSpacing.sm),
              child,
            ],
          ),
        );
    return petAsync.when(
      data: (pet) => section(pet == null ? const _PetGuideCard() : const _PetCard()),
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

/// AC5 宠物区（状态 A 已建档）—— 圆形头像一排，与他人主页「Hewan」**同一个组件** [PetAvatarItem]。
///
/// bug 20261015-590（2026-10-15 拍板）：原为整宽行卡（UI 稿 A1 `.petr`），与他人主页圆形头像不一致；
/// 统一成圆形头像，为多宠物预留。diary 角标数仍取 `archiveStatsProvider.happyMomentCount`
/// （bug 20260722-355：GROWTH_MOMENT 帖即「Diary」，>99 显示「99+」）；点头像仍跳成长档案 Tab。
///
/// 组件按列表做（本版本数据源仍是单只 `petProfileProvider`，包成长度 0 / 1 的列表），不做多宠物取数。
class _PetCard extends ConsumerWidget {
  const _PetCard();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final petAsync = ref.watch(petProfileProvider);
    return petAsync.maybeWhen(
      data: (pet) {
        if (pet == null) return const SizedBox.shrink();
        final pets = <PetProfile>[pet];
        final diary = ref.watch(archiveStatsProvider).asData?.value.happyMomentCount;
        return Align(
          alignment: Alignment.centerLeft,
          child: SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            // 角标向右上溢出 6 / 4，留出位置不被裁（与他人主页一致）。
            padding: const EdgeInsets.only(top: 4, right: 6),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                for (final p in pets)
                  PetAvatarItem(
                    key: const ValueKey('mePetCard'),
                    name: p.name,
                    avatarUrl: p.avatarUrl,
                    petType: p.petType,
                    birthday: p.birthday,
                    tailsonalityBadge: p.tailsonalityBadge,
                    diaryCount: diary,
                    diaryBadgeKey: const ValueKey('mePetDiaryTag'),
                    tailsonalityKey: const ValueKey('mePetTailsonality'),
                    metaKey: const ValueKey('mePetCardMeta'),
                    onTap: () => context.go('/profile'), // 跳成长档案 Tab
                  ),
              ],
            ),
          ),
        );
      },
      orElse: () => const SizedBox.shrink(),
    );
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
        // 分组标题（0718 去 BARU 徽章；V1.3.4 统一为小节标题样式）。
        Padding(
          padding: const EdgeInsets.only(bottom: AppSpacing.sm),
          child: _SectionTitle(l10n.meActivitySection),
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

/// 居中名片头部（V1.3.4 Story 1.4 · AC2，UI 稿 A1）：头像 84（右下相机角标，点了换头像）→ 昵称 → 签名。
///
/// ⚠️ **邮箱不再展示**（PRD OQ-6 / UI 稿 A1）；`UserProfile.email` 字段与 `/me` 接口不动。
/// 宠物区已移出为独立小节「Hewanku」（[_PetZone]）。
class _ProfileHeadCard extends StatelessWidget {
  const _ProfileHeadCard({required this.profile, this.onAvatarTap});

  final UserProfile? profile;
  final VoidCallback? onAvatarTap; // Story B：点头像换图(上传)

  @override
  Widget build(BuildContext context) {
    final name = profile?.nickname ?? profile?.displayName ?? '';
    final signature = profile?.signature ?? '';
    return Column(
      children: [
        // Story B：点击换头像(上传) + 右下角相机角标。
        GestureDetector(
          key: const ValueKey('meAvatarUpload'),
          onTap: onAvatarTap,
          child: Stack(
            children: [
              InitialAvatar(avatarUrl: profile?.avatarUrl, nickname: name, radius: 42),
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
                  child: const Icon(Icons.photo_camera, size: 13, color: Colors.white),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 10),
        Text(
          name,
          style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: AppColors.ink),
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          textAlign: TextAlign.center,
        ),
        // 一句话个性签名（bug 20260721-327）。
        if (signature.isNotEmpty) ...[
          const SizedBox(height: 4),
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 280),
            child: Text(
              signature,
              key: const ValueKey('meSignature'),
              style: const TextStyle(fontSize: 13, color: AppColors.ink2),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.center,
            ),
          ),
        ],
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

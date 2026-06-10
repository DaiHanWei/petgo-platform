import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_image.dart';
import '../../../shared/widgets/post_cover.dart';
import '../../auth/data/me_repository.dart';
import '../../auth/domain/auth_state.dart';
import '../../profile/data/profile_repository.dart';
import '../data/my_posts_repository.dart';

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
      appBar: AppBar(
        title: Text(l10n.tabMe),
        actions: [
          // 帮助反馈图标（PDP 数据主体权利可达路径承载之一）。
          IconButton(
            key: const ValueKey('meHelp'),
            icon: const Icon(Icons.help_outline),
            tooltip: l10n.meHelp,
            onPressed: () {
              ScaffoldMessenger.of(context)
                ..clearSnackBars()
                ..showSnackBar(SnackBar(content: Text(l10n.helpComingSoon)));
            },
          ),
          // 设置图标 → 二级设置页（语言/退出/注销）。PDP 注销入口经此可达。
          IconButton(
            key: const ValueKey('meSettings'),
            icon: const Icon(Icons.settings_outlined),
            tooltip: l10n.meSettings,
            onPressed: () => context.push('/me/settings'),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(AppSpacing.screenEdge),
        children: [
          // ① 用户信息（主视觉，人 60%）：头像 + 昵称 + 邮箱 + 编辑。
          _UserInfoCard(
            avatarUrl: profile?.avatarUrl,
            nickname: profile?.nickname ?? profile?.displayName ?? '',
            email: profile?.email,
            onEdit: () => _editNickname(context, ref, profile?.nickname ?? ''),
          ),
          const SizedBox(height: AppSpacing.md),
          // ② 宠物区位（次视觉，宠物 40%，AC5 三态）：A+已建档=宠物卡片 / A 未建档=引导卡 / B·C=不显示。
          const _PetZone(),
          const SizedBox(height: AppSpacing.lg),
          // ③ 宠物状态（A/B/C + 修改入口；状态 A 显示编辑档案入口）。
          _SectionCard(
            title: l10n.mePetStatusTitle,
            children: [
              ListTile(
                key: const ValueKey('mePetStatus'),
                title: Align(
                  alignment: Alignment.centerLeft,
                  child: _PetStatusChip(label: petStatusLabel(profile?.petStatus, l10n)),
                ),
                trailing: TextButton(
                  onPressed: () => context.push('/onboarding/pet-status'),
                  child: Text(l10n.meChangeStatus),
                ),
              ),
              if (profile?.hasPetProfile ?? false)
                ListTile(
                  key: const ValueKey('meEditPetProfile'),
                  title: Text(l10n.meEditPetProfile),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => context.push('/profile/edit'),
                ),
            ],
          ),
          const SizedBox(height: AppSpacing.lg),
          // ④ 我的发布（三类混合倒序）。
          _SectionCard(
            title: l10n.meMyPostsTitle,
            children: [_MyPostsList()],
          ),
        ],
      ),
    );
  }

  Future<void> _editNickname(BuildContext context, WidgetRef ref, String current) async {
    final l10n = AppLocalizations.of(context);
    final controller = TextEditingController(text: current);
    final newName = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l10n.meEditNickname),
        content: TextField(
          key: const ValueKey('nicknameField'),
          controller: controller,
          maxLength: 20, // 客户端预校验（体验层），服务端权威 ≤20
          autofocus: true,
        ),
        actions: [
          FilledButton(
            onPressed: () => Navigator.of(ctx).pop(controller.text.trim()),
            child: Text(l10n.consultRateSubmit),
          ),
        ],
      ),
    );
    if (newName == null || newName.isEmpty || !context.mounted) return;
    try {
      final updated = await ref.read(meRepositoryProvider).updateNickname(newName);
      ref.read(authControllerProvider.notifier).applyProfile(updated);
    } catch (_) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
          ..clearSnackBars()
          ..showSnackBar(SnackBar(content: Text(l10n.meNicknameSaveFailed)));
      }
    }
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
    if (profile?.petStatus != 'HAS_PET') return const SizedBox.shrink(); // PLANNING/ENTHUSIAST 不显示
    if (!(profile?.hasPetProfile ?? false)) return const _PetGuideCard(); // HAS_PET 未建档 → 引导卡
    return const _PetCard(); // A + 已建档 → 宠物卡片
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
          border: Border.all(color: AppColors.accentGrowth.withValues(alpha: 0.3)),
        ),
        child: Row(
          children: [
            const Icon(Icons.pets, color: AppColors.accentGrowth),
            const SizedBox(width: AppSpacing.md),
            Expanded(
              child: Text(l10n.mePetCardCreateTitle,
                  style: AppTypography.body.copyWith(color: AppColors.accentGrowth)),
            ),
            const Icon(Icons.chevron_right, color: AppColors.accentGrowth),
          ],
        ),
      ),
    );
  }
}

/// AC5 宠物卡片（状态 A 已建档）：宠物头像 + 名字 + 最近一条快乐时刻首图。
///
/// 「最近一条快乐时刻首图」从已加载的 [myPostsProvider]（经 content service）派生——
/// 取首条 `GROWTH_MOMENT` 的首图，避免 auth→content 后端跨模块耦合、无新增网络调用。
class _PetCard extends ConsumerWidget {
  const _PetCard();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final petAsync = ref.watch(petProfileProvider);
    return petAsync.maybeWhen(
      data: (pet) {
        if (pet == null) return const SizedBox.shrink();
        final happyImg = _recentHappyMomentImage(ref);
        return GestureDetector(
          key: const ValueKey('mePetCard'),
          onTap: () => context.go('/profile'), // 跳成长档案 Tab
          child: Container(
            padding: const EdgeInsets.all(AppSpacing.md),
            decoration: BoxDecoration(
              color: AppColors.surface,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: AppColors.border),
            ),
            child: Row(
              children: [
                _InitialAvatar(avatarUrl: pet.avatarUrl, nickname: pet.name, radius: 20),
                const SizedBox(width: AppSpacing.md),
                Expanded(
                  child: Text(pet.name,
                      style: AppTypography.body.copyWith(fontWeight: FontWeight.w600),
                      maxLines: 1, overflow: TextOverflow.ellipsis),
                ),
                if (happyImg != null) ...[
                  const SizedBox(width: AppSpacing.sm),
                  ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: AppImage.widget(happyImg,
                        key: const ValueKey('mePetCardHappyImage'),
                        height: 44, width: 44, fit: BoxFit.cover,
                        errorBuilder: (_, _, _) => const SizedBox.shrink()),
                  ),
                ],
                const SizedBox(width: AppSpacing.xs),
                const Icon(Icons.chevron_right, color: AppColors.textTertiary),
              ],
            ),
          ),
        );
      },
      orElse: () => const SizedBox.shrink(),
    );
  }

  /// 从我的发布派生最近一条快乐时刻首图（首条 GROWTH_MOMENT 的首图）；无则 null。
  String? _recentHappyMomentImage(WidgetRef ref) {
    final posts = ref.watch(myPostsProvider);
    return posts.maybeWhen(
      data: (items) {
        for (final p in items) {
          if (p.type == 'GROWTH_MOMENT' &&
              p.firstImageUrl != null &&
              p.firstImageUrl!.isNotEmpty) {
            return p.firstImageUrl;
          }
        }
        return null;
      },
      orElse: () => null,
    );
  }
}

class _UserInfoCard extends StatelessWidget {
  const _UserInfoCard({
    required this.avatarUrl,
    required this.nickname,
    required this.email,
    required this.onEdit,
  });

  final String? avatarUrl;
  final String nickname;
  final String? email;
  final VoidCallback onEdit;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.border),
      ),
      child: Row(
        children: [
          _InitialAvatar(avatarUrl: avatarUrl, nickname: nickname, radius: 28),
          const SizedBox(width: AppSpacing.lg),
          // 昵称 + 邮箱（邮箱仅本人 /me 可见，PII 不外泄）。
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(nickname, style: AppTypography.title, maxLines: 1, overflow: TextOverflow.ellipsis),
                if (email != null && email!.isNotEmpty) ...[
                  const SizedBox(height: 2),
                  Text(email!, style: AppTypography.caption, maxLines: 1, overflow: TextOverflow.ellipsis),
                ],
              ],
            ),
          ),
          const SizedBox(width: AppSpacing.sm),
          // 描边「Edit」胶囊（对齐设计稿 S17，替代裸铅笔图标）。
          OutlinedButton.icon(
            key: const ValueKey('meEditNickname'),
            onPressed: onEdit,
            style: OutlinedButton.styleFrom(
              foregroundColor: AppColors.accentGrowth,
              side: const BorderSide(color: AppColors.border),
              shape: const StadiumBorder(),
              padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md, vertical: AppSpacing.xs),
              visualDensity: VisualDensity.compact,
            ),
            icon: const Icon(Icons.edit_outlined, size: 16),
            label: Text(l10n.meEditButton),
          ),
        ],
      ),
    );
  }
}

/// 头像：有 URL 用网络图，否则彩色圆 + 昵称首字母（对齐设计稿 S17）。
class _InitialAvatar extends StatelessWidget {
  const _InitialAvatar({required this.avatarUrl, required this.nickname, required this.radius});

  final String? avatarUrl;
  final String nickname;
  final double radius;

  /// 首字母底色调色板（柔和、与品牌协调）。按昵称哈希取一色，保证同名同色。
  static const List<Color> _palette = [
    AppColors.accentGrowth,
    AppColors.accentConsult,
    AppColors.triageGreen,
    AppColors.triageYellow,
    AppColors.likeHeart,
  ];

  @override
  Widget build(BuildContext context) {
    if (avatarUrl != null && avatarUrl!.isNotEmpty) {
      return CircleAvatar(radius: radius, backgroundImage: AppImage.provider(avatarUrl));
    }
    final trimmed = nickname.trim();
    if (trimmed.isEmpty) {
      return CircleAvatar(
        radius: radius,
        backgroundColor: AppColors.divider,
        child: const Icon(Icons.person, color: AppColors.textTertiary),
      );
    }
    final initial = trimmed.characters.first.toUpperCase();
    final color = _palette[trimmed.codeUnits.fold<int>(0, (a, b) => a + b) % _palette.length];
    return CircleAvatar(
      radius: radius,
      backgroundColor: color,
      child: Text(
        initial,
        style: TextStyle(fontSize: radius * 0.8, fontWeight: FontWeight.w700, color: AppColors.onAccent),
      ),
    );
  }
}

/// 宠物状态友好标签胶囊（对齐设计稿 S17，替代原始枚举码 A/B/C）。
class _PetStatusChip extends StatelessWidget {
  const _PetStatusChip({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md, vertical: AppSpacing.xs),
      decoration: BoxDecoration(
        color: AppColors.accentGrowth.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text('🐾 $label',
          style: AppTypography.caption.copyWith(color: AppColors.accentGrowth)),
    );
  }
}

/// 「我的发布」缩略图卡（对齐设计稿 S17）：封面图（无图→类型彩块）+ 正文首行标题。
class _MyPostCard extends StatelessWidget {
  const _MyPostCard({required this.post, required this.onTap});

  final MyPost post;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final hasImage = post.firstImageUrl != null && post.firstImageUrl!.isNotEmpty;
    final caption = (post.text != null && post.text!.trim().isNotEmpty)
        ? post.text!.trim()
        : l10n.meNoPostCaption;
    return GestureDetector(
      key: ValueKey('myPost_${post.id}'),
      onTap: onTap,
      child: SizedBox(
        width: 110,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(10),
              child: SizedBox(
                height: 90,
                width: 110,
                child: hasImage
                    ? AppImage.widget(
                        post.firstImageUrl!,
                        fit: BoxFit.cover,
                        errorBuilder: (context, error, stack) =>
                            PostCoverPlaceholder(type: post.type, emojiSize: 32),
                      )
                    : PostCoverPlaceholder(type: post.type, emojiSize: 32),
              ),
            ),
            const SizedBox(height: AppSpacing.xs),
            Text(caption,
                style: AppTypography.caption, maxLines: 2, overflow: TextOverflow.ellipsis),
          ],
        ),
      ),
    );
  }
}

/// 宠物状态枚举 → 本地化标签（HAS_PET=有宠物/PLANNING=计划养/ENTHUSIAST=爱好者；未知→'-'）。
String petStatusLabel(String? s, AppLocalizations l10n) {
  switch (s) {
    case 'HAS_PET':
      return l10n.petStatusA;
    case 'PLANNING':
      return l10n.petStatusB;
    case 'ENTHUSIAST':
      return l10n.petStatusC;
    default:
      return '-';
  }
}

class _SectionCard extends StatelessWidget {
  const _SectionCard({required this.title, required this.children});

  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(bottom: AppSpacing.sm, left: AppSpacing.xs),
          child: Text(title, style: AppTypography.caption),
        ),
        Material(
          color: AppColors.surface,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
            side: const BorderSide(color: AppColors.border),
          ),
          clipBehavior: Clip.antiAlias,
          child: Column(children: children),
        ),
      ],
    );
  }
}

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
            padding: const EdgeInsets.all(AppSpacing.lg),
            child: Text(l10n.meNoPosts,
                key: const ValueKey('meNoPosts'), style: AppTypography.caption),
          );
        }
        // 横向缩略图卡（对齐设计稿 S17）：封面图（无图→彩块）+ 正文首行标题。
        return SizedBox(
          height: 162,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(
                horizontal: AppSpacing.md, vertical: AppSpacing.sm),
            itemCount: items.length,
            separatorBuilder: (_, _) => const SizedBox(width: AppSpacing.md),
            itemBuilder: (context, i) {
              final p = items[i];
              return _MyPostCard(
                post: p,
                onTap: () => context.push('/content/${p.id}'),
              );
            },
          ),
        );
      },
    );
  }
}

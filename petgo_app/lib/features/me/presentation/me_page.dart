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
import '../../profile/data/timeline_repository.dart';
import '../../profile/domain/pet_age.dart';
import '../../profile/domain/pet_profile.dart';
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
      // 原型 profil.html：无大标题，右上仅 headset + gear 双 ibtn（白底圆角+阴影）。
      appBar: AppBar(
        backgroundColor: AppColors.base,
        scrolledUnderElevation: 0,
        automaticallyImplyLeading: false,
        actions: [
          // 帮助反馈图标（PDP 数据主体权利可达路径承载之一）。
          _IconBtn(
            valueKey: 'meHelp',
            icon: Icons.support_agent_outlined,
            tooltip: l10n.meHelp,
            onTap: () {
              ScaffoldMessenger.of(context)
                ..clearSnackBars()
                ..showSnackBar(SnackBar(content: Text(l10n.helpComingSoon)));
            },
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
          // ① 用户信息（主视觉，人 60%）：头像 + 昵称 + 邮箱 + 编辑。
          _UserInfoCard(
            avatarUrl: profile?.avatarUrl,
            nickname: profile?.nickname ?? profile?.displayName ?? '',
            email: profile?.email,
            onEdit: () => _editProfile(context, ref),
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

  /// 编辑资料底抽屉（原型 profil-edit-sheet）：头像区（展示 + Ganti Foto 占位）+ 昵称可编辑 + 邮箱只读 + 保存/取消。
  ///
  /// 决策 #6（2026-06-18）：头像上传触媒体流较复杂，本期降级——保留头像展示 + 「Ganti Foto」入口提示「待接入」，
  /// 不阻塞 sheet 视觉还原；仅昵称走 updateNickname 落库。
  Future<void> _editProfile(BuildContext context, WidgetRef ref) async {
    final l10n = AppLocalizations.of(context);
    final profile = ref.read(authControllerProvider).profile;
    final controller =
        TextEditingController(text: profile?.nickname ?? profile?.displayName ?? '');
    final newName = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      backgroundColor: AppColors.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (ctx) => Padding(
        padding: EdgeInsets.only(
          left: 22,
          right: 22,
          top: 20,
          bottom: MediaQuery.of(ctx).viewInsets.bottom + 32,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Center(
              child: Container(
                width: 36,
                height: 4,
                decoration: BoxDecoration(
                    color: AppColors.line, borderRadius: BorderRadius.circular(999)),
              ),
            ),
            const SizedBox(height: 18),
            Text(l10n.meEditProfileTitle,
                style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
            const SizedBox(height: 20),
            // 头像区（展示 + Ganti Foto 占位，头像上传本期降级）。
            Center(
              child: Column(
                children: [
                  _InitialAvatar(
                      avatarUrl: profile?.avatarUrl,
                      nickname: profile?.nickname ?? '',
                      radius: 38),
                  const SizedBox(height: 8),
                  TextButton(
                    key: const ValueKey('meEditPhoto'),
                    onPressed: () {
                      ScaffoldMessenger.of(ctx)
                        ..clearSnackBars()
                        ..showSnackBar(SnackBar(content: Text(l10n.helpComingSoon)));
                    },
                    child: Text(l10n.meEditPhotoChange,
                        style: const TextStyle(fontSize: 12, color: AppColors.mint)),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 14),
            // 昵称（可编辑）。
            Text(l10n.meEditNicknameLabel.toUpperCase(),
                style: const TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 0.4,
                    color: AppColors.textSecondary)),
            const SizedBox(height: 6),
            TextField(
              key: const ValueKey('nicknameField'),
              controller: controller,
              maxLength: 20, // 客户端预校验（体验层），服务端权威 ≤20
              autofocus: true,
              decoration: InputDecoration(
                counterText: '',
                filled: true,
                fillColor: AppColors.surface,
                contentPadding: const EdgeInsets.symmetric(horizontal: 15, vertical: 13),
                enabledBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: const BorderSide(color: AppColors.mint, width: 1.5),
                ),
                focusedBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: const BorderSide(color: AppColors.mint, width: 1.5),
                ),
              ),
            ),
            const SizedBox(height: 14),
            // 邮箱（只读）。
            Text(l10n.meEditEmailLabel.toUpperCase(),
                style: const TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 0.4,
                    color: AppColors.textSecondary)),
            const SizedBox(height: 6),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 13),
              decoration: BoxDecoration(
                color: AppColors.cream2,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: AppColors.line, width: 1.5),
              ),
              child: Text(profile?.email ?? '',
                  style: const TextStyle(fontSize: 14, color: AppColors.textTertiary)),
            ),
            const SizedBox(height: 22),
            FilledButton(
              key: const ValueKey('meEditSaveButton'),
              onPressed: () => Navigator.of(ctx).pop(controller.text.trim()),
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.mint,
                foregroundColor: AppColors.onAccent,
                padding: const EdgeInsets.symmetric(vertical: 14),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              ),
              child: Text(l10n.meEditSave,
                  style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
            ),
            const SizedBox(height: 10),
            OutlinedButton(
              onPressed: () => Navigator.of(ctx).pop(),
              style: OutlinedButton.styleFrom(
                foregroundColor: AppColors.textSecondary,
                side: const BorderSide(color: AppColors.line, width: 1.5),
                padding: const EdgeInsets.symmetric(vertical: 13),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              ),
              child: Text(l10n.meEditCancel, style: const TextStyle(fontSize: 14)),
            ),
          ],
        ),
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
                _InitialAvatar(avatarUrl: pet.avatarUrl, nickname: pet.name, radius: 21),
                const SizedBox(width: 11),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(pet.name,
                          style: AppTypography.body.copyWith(fontWeight: FontWeight.w700),
                          maxLines: 1, overflow: TextOverflow.ellipsis),
                      if (meta.isNotEmpty) ...[
                        const SizedBox(height: 2),
                        Text(meta,
                            key: const ValueKey('mePetCardMeta'),
                            style: AppTypography.caption.copyWith(color: AppColors.textSecondary),
                            maxLines: 1, overflow: TextOverflow.ellipsis),
                      ],
                    ],
                  ),
                ),
                const SizedBox(width: AppSpacing.sm),
                Text('${l10n.meViewArchive} →',
                    style: const TextStyle(
                        fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.mint)),
              ],
            ),
          ),
        );
      },
      orElse: () => const SizedBox.shrink(),
    );
  }

  /// 元数据「种类 · 年龄 · momen 数」：种类由 petType、年龄由 birthday 计算、momen 数取 archiveStats。
  String _petMeta(BuildContext context, WidgetRef ref, PetProfile pet, AppLocalizations l10n) {
    final species = switch (pet.petType) {
      'CAT' => l10n.petTypeCat,
      'DOG' => l10n.petTypeDog,
      'OTHER' => l10n.petTypeOther,
      _ => null,
    };
    final momen = ref.watch(archiveStatsProvider).asData?.value.happyMomentCount;
    final age = computePetAge(pet.birthday);
    return [
      ?species,
      if (pet.birthday != null) l10n.growthArchiveAge(age.years, age.months),
      if (momen != null) l10n.meMomenCount(momen),
    ].join(' · ');
  }
}

/// 原型 .ibtn：38×38 白底圆角11 带阴影的图标按钮（profil 顶部 headset/gear）。
class _IconBtn extends StatelessWidget {
  const _IconBtn({required this.valueKey, required this.icon, required this.tooltip, required this.onTap});

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
              BoxShadow(color: Color(0x12162233), blurRadius: 8, offset: Offset(0, 2)),
            ],
          ),
          child: Icon(icon, size: 18, color: AppColors.ink2),
        ),
      ),
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
        // 原型 profhead box-shadow（非边框）。
        boxShadow: const [
          BoxShadow(color: Color(0x0F162233), blurRadius: 12, offset: Offset(0, 2)),
        ],
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

/// 「我的发布」网格缩略图（原型 pthumb）：方形封面（无图→类型彩块）+ 左上角类型 badge。
class _MyPostCard extends StatelessWidget {
  const _MyPostCard({required this.post, required this.onTap});

  final MyPost post;
  final VoidCallback onTap;

  /// 类型 → (badge 文案, 文字色, 底色)：Momen 绿 / Tips 黄 / Cerita 紫（原型 b-happy/b-tips/b-story）。
  static (String, Color, Color) _badgeStyle(String type, AppLocalizations l10n) {
    switch (type) {
      case 'GROWTH_MOMENT':
        return (l10n.mePostTypeMomen, AppColors.momenBadgeText, AppColors.momenBadgeBg);
      case 'KNOWLEDGE':
        return (l10n.mePostTypeTips, AppColors.tipsBadgeText, AppColors.goldTint);
      default: // DAILY
        return (l10n.mePostTypeCerita, AppColors.mint, AppColors.skyTint);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final hasImage = post.firstImageUrl != null && post.firstImageUrl!.isNotEmpty;
    final (label, fg, bg) = _badgeStyle(post.type, l10n);
    return GestureDetector(
      key: ValueKey('myPost_${post.id}'),
      onTap: onTap,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(11),
        child: Stack(
          fit: StackFit.expand,
          children: [
            hasImage
                ? AppImage.widget(
                    post.firstImageUrl!,
                    fit: BoxFit.cover,
                    errorBuilder: (context, error, stack) =>
                        PostCoverPlaceholder(type: post.type, emojiSize: 30),
                  )
                : PostCoverPlaceholder(type: post.type, emojiSize: 30),
            Positioned(
              top: 5,
              left: 5,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(5)),
                child: Text(label,
                    style: TextStyle(fontSize: 9, fontWeight: FontWeight.w700, color: fg)),
              ),
            ),
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
        // 2 列方形网格（原型 pgrid）：封面图（无图→彩块）+ 左上类型 badge；保留后端 created_at 倒序，不重排。
        return Padding(
          padding: const EdgeInsets.all(AppSpacing.md),
          child: GridView.count(
            crossAxisCount: 2,
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            mainAxisSpacing: 7,
            crossAxisSpacing: 7,
            children: [
              for (final p in items)
                _MyPostCard(post: p, onTap: () => context.push('/content/${p.id}')),
            ],
          ),
        );
      },
    );
  }
}

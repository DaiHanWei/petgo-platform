import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/network/dio_client.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/post_cover.dart';
import '../../auth/data/me_repository.dart';
import '../../auth/domain/auth_state.dart';
import '../data/my_posts_repository.dart';

/// 「我的」页面（Story 7.1，FR-20）。五大区块：用户信息 / 宠物状态 / 我的发布 / 账号设置 / 帮助。
///
/// 语言设置(7.2)、退出登录/账号注销(7.3) 仅放入口（路由占位）。宠物状态/档案编辑复用既有流。
/// 头像替换走 STS 直传（L2）；本页落昵称编辑 + 我的发布 + 各入口。
class MePage extends ConsumerWidget {
  const MePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final auth = ref.watch(authControllerProvider);
    final profile = auth.profile;
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(title: Text(l10n.tabMe)),
      body: ListView(
        padding: const EdgeInsets.all(AppSpacing.screenEdge),
        children: [
          // ① 用户信息（头像 + 昵称 + 邮箱 + 编辑）。
          _UserInfoCard(
            avatarUrl: profile?.avatarUrl,
            nickname: profile?.nickname ?? profile?.displayName ?? '',
            email: profile?.email,
            onEdit: () => _editNickname(context, ref, profile?.nickname ?? ''),
          ),
          const SizedBox(height: AppSpacing.lg),
          // ② 宠物状态（A/B/C + 修改入口；状态 A 显示编辑档案入口）。
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
          // ③ 我的发布（三类混合倒序）。
          _SectionCard(
            title: l10n.meMyPostsTitle,
            children: [_MyPostsList()],
          ),
          const SizedBox(height: AppSpacing.lg),
          // ④ 账号设置（语言 / 退出登录 / 账号注销）。
          _SectionCard(
            title: l10n.meAccountSettings,
            children: [
              _entry(context, const ValueKey('meLanguage'), Icons.language, l10n.meLanguage,
                  () => context.push('/me/language')),
              _entry(context, const ValueKey('meLogout'), Icons.logout, l10n.meLogout,
                  () => _logout(context, ref)),
              _entry(context, const ValueKey('meDeleteAccount'), Icons.delete_outline,
                  l10n.meDeleteAccount, () => _deleteAccount(context, ref)),
            ],
          ),
          const SizedBox(height: AppSpacing.lg),
          // ⑤ 帮助与反馈（PDP 数据主体权利可达路径承载）。
          _SectionCard(
            title: l10n.meHelp,
            children: [
              _entry(context, const ValueKey('meHelp'), Icons.help_outline, l10n.meHelp, () {
                ScaffoldMessenger.of(context)
                  ..clearSnackBars()
                  ..showSnackBar(SnackBar(content: Text(l10n.helpComingSoon)));
              }),
            ],
          ),
        ],
      ),
    );
  }

  Widget _entry(BuildContext context, Key key, IconData icon, String label, VoidCallback onTap) {
    return ListTile(
      key: key,
      leading: Icon(icon, color: AppColors.textSecondary),
      title: Text(label),
      trailing: const Icon(Icons.chevron_right),
      onTap: onTap,
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

  /// 退出登录（Story 7.3 AC1）：确认 → 清本地态回游客 → 留首页。<b>不删任何数据</b>。
  Future<void> _logout(BuildContext context, WidgetRef ref) async {
    final l10n = AppLocalizations.of(context);
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l10n.logoutConfirmTitle),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(false), child: Text(l10n.consultCancel)),
          FilledButton(
            key: const ValueKey('logoutConfirmYes'),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: Text(l10n.logoutConfirmYes),
          ),
        ],
      ),
    );
    if (ok != true || !context.mounted) return;
    await ref.read(authRepositoryProvider).logout();
    ref.read(authControllerProvider.notifier).toGuest();
    if (context.mounted) context.go('/home');
  }

  /// 账号注销（Story 7.3 AC2）：双重确认（① 不可恢复警示 → ② 输入「确认注销」短语）→ DELETE /me → 回游客。
  Future<void> _deleteAccount(BuildContext context, WidgetRef ref) async {
    final l10n = AppLocalizations.of(context);
    // 第一步：庄重警示「删除后数据不可恢复」。
    final cont = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l10n.deleteAccountWarnTitle),
        content: Text(l10n.deleteAccountWarnBody),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(false), child: Text(l10n.consultCancel)),
          FilledButton(
            key: const ValueKey('deleteAccountContinue'),
            style: FilledButton.styleFrom(backgroundColor: AppColors.danger),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: Text(l10n.deleteAccountContinue),
          ),
        ],
      ),
    );
    if (cont != true || !context.mounted) return;

    // 第二步：要求输入确认短语（高危，防误触发不可逆删除）。
    final phrase = l10n.deleteAccountConfirmPhrase;
    final controller = TextEditingController();
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          title: Text(l10n.deleteAccountConfirmTitle),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(phrase),
              TextField(
                key: const ValueKey('deleteConfirmField'),
                controller: controller,
                decoration: InputDecoration(hintText: l10n.deleteAccountConfirmHint),
                onChanged: (_) => setState(() {}),
              ),
            ],
          ),
          actions: [
            TextButton(onPressed: () => Navigator.of(ctx).pop(false), child: Text(l10n.consultCancel)),
            FilledButton(
              key: const ValueKey('deleteAccountConfirmYes'),
              style: FilledButton.styleFrom(backgroundColor: AppColors.danger),
              // 仅当输入与确认短语完全一致才可点（防误删）。
              onPressed: controller.text.trim() == phrase ? () => Navigator.of(ctx).pop(true) : null,
              child: Text(l10n.deleteAccountConfirmYes),
            ),
          ],
        ),
      ),
    );
    if (confirmed != true || !context.mounted) return;
    try {
      await ref.read(authRepositoryProvider).deleteAccount(phrase);
      ref.read(authControllerProvider.notifier).toGuest();
      if (context.mounted) context.go('/home');
    } catch (_) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
          ..clearSnackBars()
          ..showSnackBar(SnackBar(content: Text(l10n.deleteAccountFailed)));
      }
    }
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
      return CircleAvatar(radius: radius, backgroundImage: NetworkImage(avatarUrl!));
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
                    ? Image.network(
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

/// 宠物状态枚举 → 本地化标签（A=有宠物/B=计划养/C=爱好者；未知→'-'）。
String petStatusLabel(String? s, AppLocalizations l10n) {
  switch (s) {
    case 'A':
      return l10n.petStatusA;
    case 'B':
      return l10n.petStatusB;
    case 'C':
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

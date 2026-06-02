import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
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
          // ① 用户信息（头像 + 昵称 + 编辑）。
          _UserInfoCard(
            avatarUrl: profile?.avatarUrl,
            nickname: profile?.nickname ?? profile?.displayName ?? '',
            onEdit: () => _editNickname(context, ref, profile?.nickname ?? ''),
          ),
          const SizedBox(height: AppSpacing.lg),
          // ② 宠物状态（A/B/C + 修改入口；状态 A 显示编辑档案入口）。
          _SectionCard(
            title: l10n.mePetStatusTitle,
            children: [
              ListTile(
                key: const ValueKey('mePetStatus'),
                title: Text(profile?.petStatus ?? '-'),
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
                  () => context.push('/me/logout')),
              _entry(context, const ValueKey('meDeleteAccount'), Icons.delete_outline,
                  l10n.meDeleteAccount, () => context.push('/me/delete-account')),
            ],
          ),
          const SizedBox(height: AppSpacing.lg),
          // ⑤ 帮助与反馈（PDP 数据主体权利可达路径承载）。
          _SectionCard(
            title: l10n.meHelp,
            children: [
              _entry(context, const ValueKey('meHelp'), Icons.help_outline, l10n.meHelp,
                  () => context.push('/me/help')),
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
}

class _UserInfoCard extends StatelessWidget {
  const _UserInfoCard({required this.avatarUrl, required this.nickname, required this.onEdit});

  final String? avatarUrl;
  final String nickname;
  final VoidCallback onEdit;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.border),
      ),
      child: Row(
        children: [
          CircleAvatar(
            radius: 28,
            backgroundColor: AppColors.divider,
            backgroundImage: (avatarUrl != null && avatarUrl!.isNotEmpty)
                ? NetworkImage(avatarUrl!)
                : null,
            child: (avatarUrl == null || avatarUrl!.isEmpty)
                ? const Icon(Icons.person, color: AppColors.textTertiary)
                : null,
          ),
          const SizedBox(width: AppSpacing.lg),
          Expanded(child: Text(nickname, style: AppTypography.title)),
          IconButton(
            key: const ValueKey('meEditNickname'),
            icon: const Icon(Icons.edit_outlined),
            onPressed: onEdit,
          ),
        ],
      ),
    );
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
        return Column(
          children: [
            for (final p in items)
              ListTile(
                key: ValueKey('myPost_${p.id}'),
                title: Text(p.text ?? '#${p.id}', maxLines: 1, overflow: TextOverflow.ellipsis),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => context.push('/content/${p.id}'),
              ),
          ],
        );
      },
    );
  }
}

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/network/dio_client.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../auth/domain/auth_state.dart';

/// 二级「设置」页（Story 7.1 · F8 信息架构重组）。
///
/// 🔄 PRD V1.0.0 修订（F8 · 2026-06-08）：语言设置 / 退出登录 / 账号注销三项从「我的」主页区块
/// 挪入本二级页（点「我的」右上角设置图标进入），主页面只承载「人 + 宠物」内容。
/// 语言逻辑在 7.2、退出/注销逻辑在 7.3——本页放入口 + 退出/注销交互（PDP 数据主体权利可达）。
class SettingsPage extends ConsumerWidget {
  const SettingsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(title: Text(l10n.meSettings)),
      body: ListView(
        padding: const EdgeInsets.all(AppSpacing.screenEdge),
        children: [
          Material(
            color: AppColors.surface,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(12),
              side: const BorderSide(color: AppColors.border),
            ),
            clipBehavior: Clip.antiAlias,
            child: Column(
              children: [
                _entry(const ValueKey('meLanguage'), Icons.language, l10n.meLanguage,
                    () => context.push('/me/language')),
                // 兽医登录入口（单 App 双角色）：从已登录用户态切到兽医工作台。
                // 路由对非兽医放行 /vet/login；登录成功后 applyVetLogin → redirect 收口 /vet/workbench。
                _entry(const ValueKey('meVetLogin'), Icons.medical_services_outlined,
                    l10n.vetLoginLink, () => context.push('/vet/login')),
                _entry(const ValueKey('meLogout'), Icons.logout, l10n.meLogout,
                    () => _logout(context, ref)),
                _entry(const ValueKey('meDeleteAccount'), Icons.delete_outline,
                    l10n.meDeleteAccount, () => _deleteAccount(context, ref)),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _entry(Key key, IconData icon, String label, VoidCallback onTap) {
    return ListTile(
      key: key,
      leading: Icon(icon, color: AppColors.textSecondary),
      title: Text(label),
      trailing: const Icon(Icons.chevron_right),
      onTap: onTap,
    );
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

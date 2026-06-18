import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/network/dio_client.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../auth/domain/auth_state.dart';

/// 二级「设置」页（Story 7.1 · F8 · settings.html 1:1 还原）。
///
/// 四分组：AKUN（编辑档案/通知/语言）· TAMPILAN（深色模式，V1 仅浅色故为占位开关）·
/// PRIVASI & KEAMANAN（公开档案/隐私政策/条款）· ZONA BAHAYA（退出/注销，红字）。
/// 语言逻辑在 7.2、退出/注销逻辑在 7.3（双重确认 + 短语校验，PDP 数据主体权利可达）。
class SettingsPage extends ConsumerStatefulWidget {
  const SettingsPage({super.key});

  @override
  ConsumerState<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends ConsumerState<SettingsPage> {
  // V1 占位开关（无后端持久化）：通知默认开、深色跟随系统（V1 仅浅色）、公开档案默认开。
  bool _notif = true;
  bool _darkMode = false;
  bool _petPublic = true;

  static const Color _danger = AppColors.popRed;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.base,
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
          children: [
            // 顶栏：圆角方钮返回 + Pengaturan 大标题
            Row(
              children: [
                _backBtn(),
                const SizedBox(width: 14),
                const Text('Pengaturan',
                    style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700, color: AppColors.ink)),
              ],
            ),
            const SizedBox(height: 22),

            _sectionTitle('AKUN'),
            _card([
              _navRow('Edit Profil', onTap: () => context.push('/profile/edit'),
                  key: const ValueKey('meEditProfile')),
              _divider(),
              _toggleRow('Notifikasi', _notif, (v) => setState(() => _notif = v),
                  key: const ValueKey('meNotifToggle')),
              _divider(),
              _navRow('Bahasa', value: 'Bahasa Indonesia',
                  onTap: () => context.push('/me/language'), key: const ValueKey('meLanguage')),
            ]),
            const SizedBox(height: 22),

            _sectionTitle('TAMPILAN'),
            _card([
              _toggleRow('Mode Gelap', _darkMode, (v) => setState(() => _darkMode = v),
                  subtitle: 'Mengikuti sistem', key: const ValueKey('meDarkModeToggle')),
            ]),
            const SizedBox(height: 22),

            _sectionTitle('PRIVASI & KEAMANAN'),
            _card([
              _toggleRow('Profil Hewan (publik)', _petPublic, (v) => setState(() => _petPublic = v),
                  key: const ValueKey('mePetPublicToggle')),
              _divider(),
              _navRow('Kebijakan Privasi', onTap: () => _soon(context)),
              _divider(),
              _navRow('Syarat & Ketentuan', onTap: () => _soon(context)),
            ]),
            const SizedBox(height: 22),

            _sectionTitle('ZONA BAHAYA'),
            _card([
              _navRow('Keluar (Logout)', danger: true, onTap: () => _logout(context, ref),
                  key: const ValueKey('meLogout')),
              _divider(),
              _navRow('Hapus Akun', danger: true, onTap: () => _deleteAccount(context, ref),
                  key: const ValueKey('meDeleteAccount')),
            ]),
            const SizedBox(height: 24),
            const Center(
              child: Text('TailTopia v1.0.0 · Build 100',
                  style: TextStyle(fontSize: 12, color: AppColors.muted)),
            ),
          ],
        ),
      ),
    );
  }

  Widget _backBtn() => Material(
        color: AppColors.cream2,
        borderRadius: BorderRadius.circular(12),
        child: InkWell(
          key: const ValueKey('settingsBack'),
          borderRadius: BorderRadius.circular(12),
          onTap: () => context.canPop() ? context.pop() : context.go('/me'),
          child: const SizedBox(
            width: 40,
            height: 40,
            child: Icon(Icons.arrow_back, size: 20, color: AppColors.ink),
          ),
        ),
      );

  Widget _sectionTitle(String text) => Padding(
        padding: const EdgeInsets.only(left: 4, bottom: 10),
        child: Text(text,
            style: const TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w700,
                color: AppColors.muted,
                letterSpacing: 0.6)),
      );

  Widget _card(List<Widget> children) => Container(
        decoration: BoxDecoration(
          color: AppColors.card,
          borderRadius: BorderRadius.circular(16),
          boxShadow: const [
            BoxShadow(color: Color(0x0D2B2A27), offset: Offset(0, 3), blurRadius: 12),
          ],
        ),
        clipBehavior: Clip.antiAlias,
        child: Column(children: children),
      );

  Widget _divider() =>
      const Divider(height: 1, thickness: 1, color: AppColors.line2, indent: 16, endIndent: 16);

  Widget _navRow(String label,
      {String? value, bool danger = false, required VoidCallback onTap, Key? key}) {
    final color = danger ? _danger : AppColors.ink;
    return InkWell(
      key: key,
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
        child: Row(
          children: [
            Expanded(
              child: Text(label,
                  style: TextStyle(
                      fontSize: 15,
                      fontWeight: danger ? FontWeight.w700 : FontWeight.w500,
                      color: color)),
            ),
            if (value != null) ...[
              Text(value, style: const TextStyle(fontSize: 14, color: AppColors.muted)),
              const SizedBox(width: 6),
            ],
            Icon(Icons.chevron_right, size: 20, color: danger ? _danger : AppColors.muted),
          ],
        ),
      ),
    );
  }

  Widget _toggleRow(String label, bool value, ValueChanged<bool> onChanged,
      {String? subtitle, Key? key}) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label,
                    style: const TextStyle(
                        fontSize: 15, fontWeight: FontWeight.w500, color: AppColors.ink)),
                if (subtitle != null) ...[
                  const SizedBox(height: 2),
                  Text(subtitle, style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                ],
              ],
            ),
          ),
          Switch(
            key: key,
            value: value,
            onChanged: onChanged,
            activeThumbColor: AppColors.onAccent,
            activeTrackColor: AppColors.mint,
          ),
        ],
      ),
    );
  }

  void _soon(BuildContext context) {
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(const SnackBar(content: Text('Segera hadir')));
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

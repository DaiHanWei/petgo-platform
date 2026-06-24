import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/config/legal_urls.dart';
import '../../../core/l10n/locale_controller.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/confirm_sheet.dart';
import '../../auth/domain/auth_state.dart';

/// 二级「设置」页（Story 7.1 · F8 · settings.html 1:1 还原）。
///
/// 分组：AKUN（编辑档案/通知/语言）·（TAMPILAN 深色模式下版本再做，暂隐藏）·
/// PRIVASI & KEAMANAN（公开档案/隐私政策/条款）· ZONA BAHAYA（退出/注销，红字）。
/// 语言逻辑在 7.2、退出/注销逻辑在 7.3（双重确认 + 短语校验，PDP 数据主体权利可达）。
class SettingsPage extends ConsumerStatefulWidget {
  const SettingsPage({super.key});

  @override
  ConsumerState<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends ConsumerState<SettingsPage> {
  // V1 占位开关（无后端持久化）：通知默认开、公开档案默认开。（深色模式下版本再做，暂隐藏）
  bool _notif = true;
  bool _petPublic = true;

  static const Color _danger = AppColors.popRed;

  @override
  void initState() {
    super.initState();
    // Debug 截图钩子（仅 debug + flag）：自动进注销整页（P-43，截 delete-account 用）。绝不真删。
    if (kDebugMode && const bool.fromEnvironment('DEV_DELETE_ACCOUNT')) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) context.push('/me/delete-account');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // 语言行右值随当前选择动态显示（null=跟随系统）；与 language_settings_page 同一套映射。
    final localeCode = ref.watch(localeControllerProvider)?.languageCode;
    final langValue = switch (localeCode) {
      'en' => l10n.languageEnglish,
      'id' => l10n.languageIndonesian,
      _ => l10n.languageFollowSystem,
    };
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
                Text(l10n.settingsTitle,
                    style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w700, color: AppColors.ink)),
              ],
            ),
            const SizedBox(height: 22),

            _sectionTitle(l10n.settingsSectionAccount),
            _card([
              _navRow(l10n.meEditProfileTitle, onTap: () => context.push('/profile/edit'),
                  key: const ValueKey('meEditProfile')),
              _divider(),
              _toggleRow(l10n.notificationCenterTitle, _notif, (v) => setState(() => _notif = v),
                  key: const ValueKey('meNotifToggle')),
              _divider(),
              _navRow(l10n.meLanguage, value: langValue,
                  onTap: () => context.push('/me/language'), key: const ValueKey('meLanguage')),
            ]),
            const SizedBox(height: 22),

            // TODO(next): 深色模式（TAMPILAN）暂隐藏，下个版本接入暗色主题后再放出。
            //   需先建 dark token 体系 + 迁移硬编码色 + 逐屏 QA（见 dark mode 评估）。

            _sectionTitle(l10n.settingsSectionPrivacy),
            _card([
              _toggleRow(l10n.settingsPetPublic, _petPublic, (v) => setState(() => _petPublic = v),
                  key: const ValueKey('mePetPublicToggle')),
              _divider(),
              _navRow(l10n.privacyPolicy, onTap: () => _openUrl(kPrivacyUrl),
                  key: const ValueKey('mePrivacyPolicy')),
              _divider(),
              _navRow(l10n.termsOfService, onTap: () => _openUrl(kTermsUrl),
                  key: const ValueKey('meTermsOfService')),
            ]),
            const SizedBox(height: 22),

            _sectionTitle(l10n.settingsSectionDanger),
            _card([
              _navRow(l10n.meLogout, danger: true, onTap: () => _logout(context, ref),
                  key: const ValueKey('meLogout')),
              _divider(),
              _navRow(l10n.meDeleteAccount, danger: true,
                  onTap: () => context.push('/me/delete-account'),
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

  /// 打开法务 H5（隐私政策 / 服务条款）外部浏览器；解析失败静默不崩。
  Future<void> _openUrl(String url) async {
    final uri = Uri.tryParse(url);
    if (uri != null) {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    }
  }

  /// 退出登录（Story 7.3 AC1）：确认 → 清本地态回游客 → 留首页。<b>不删任何数据</b>。
  Future<void> _logout(BuildContext context, WidgetRef ref) async {
    final l10n = AppLocalizations.of(context);
    final ok = await showConfirmSheet(
      context,
      title: l10n.logoutConfirmTitle,
      confirmLabel: l10n.logoutConfirmYes,
      cancelLabel: l10n.consultCancel,
      icon: Icons.logout_rounded,
      confirmKey: const ValueKey('logoutConfirmYes'),
    );
    if (!ok || !context.mounted) return;
    await ref.read(authRepositoryProvider).logout();
    ref.read(authControllerProvider.notifier).toGuest();
    if (context.mounted) context.go('/home');
  }

}

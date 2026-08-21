import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/config/legal_urls.dart';
import '../../../core/l10n/locale_controller.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/push/push_service.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../auth/domain/auth_state.dart';
import '../domain/phone_mask.dart';
import 'phone_edit_sheet.dart';
import '../../../shared/widgets/confirm_sheet.dart';
import '../../notify/data/push_permission_providers.dart';
import '../../social/data/blocked_users_repository.dart';

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

class _SettingsPageState extends ConsumerState<SettingsPage> with WidgetsBindingObserver {
  // V1 占位开关（无后端持久化）：公开档案默认开。（深色模式下版本再做，暂隐藏）
  bool _petPublic = true;

  /// 通知开关（2026-08-07 改为真开关）：**真相源是系统权限**，不是本地存储——
  /// App 无法代替用户开关系统通知，只能如实反映 + 引导。进页面与从系统设置返回时刷新。
  bool _notif = false;

  static const Color _danger = AppColors.popRed;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this); // 从系统设置返回 → 刷新开关真实状态
    _refreshNotifStatus();
    // Debug 截图钩子（仅 debug + flag）：自动进注销整页（P-43，截 delete-account 用）。绝不真删。
    if (kDebugMode && const bool.fromEnvironment('DEV_DELETE_ACCOUNT')) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) context.push('/me/delete-account');
      });
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refreshNotifStatus();
  }

  Future<void> _refreshNotifStatus() async {
    final granted = await isPushPermissionGranted();
    if (!mounted) return;
    setState(() => _notif = granted);
    // 用户刚在系统设置里打开通知 → 立即补注册离线推送（否则要等下次冷启动才生效）。
    if (granted) {
      final isVet = ref.read(authControllerProvider).isVet;
      ref.read(pushServiceProvider).syncRegistration(isVet: isVet);
    }
  }

  /// 通知开关：App 只能**申请**权限、不能代关。
  /// - 开：未申请过 → 弹系统权限窗；已被拒 → 引导去系统设置（此时 App 已在设置列表里）。
  /// - 关：一律引导去系统设置（系统权限只能用户自己撤）。
  Future<void> _onNotifToggle(bool want) async {
    final l10n = AppLocalizations.of(context);
    if (!want) {
      await _showOpenSettingsDialog(l10n);
      await _refreshNotifStatus(); // 用户可能真去关了
      return;
    }
    // request()：notDetermined 会弹系统窗；已 denied/永久拒绝则立即返回 denied（不打扰）。
    final granted = await requestPushPermission();
    if (!mounted) return;
    if (granted) {
      setState(() => _notif = true);
      final isVet = ref.read(authControllerProvider).isVet;
      ref.read(pushServiceProvider).syncRegistration(isVet: isVet);
      return;
    }
    await _showOpenSettingsDialog(l10n);
    await _refreshNotifStatus();
  }

  Future<void> _showOpenSettingsDialog(AppLocalizations l10n) async {
    final go = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        key: const ValueKey('notifOpenSettingsDialog'),
        title: Text(l10n.pushEnableGuideTitle),
        content: Text(l10n.pushEnableGuideBody),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(ctx).pop(false),
              child: Text(l10n.pushSoftGuideLater)),
          TextButton(
              onPressed: () => Navigator.of(ctx).pop(true),
              child: Text(l10n.mediaOpenSettings)),
        ],
      ),
    );
    if (go ?? false) await openPushSettings();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // 语言行右值随当前选择动态显示（null=跟随系统）；与 language_settings_page 同一套映射。
    final localeCode = ref.watch(localeControllerProvider)?.languageCode;
    // watch 而非 read：抽屉里保存/清空后 applyProfile 会刷新它，右值要跟着变。
    final phone = ref.watch(authControllerProvider).profile?.phone;
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
              // ⚠️ 「宠物证件卡 / 健康记录」两项 2026-08-16 从设置页移除：它们是**内容功能**、
              // 不是设置项，正主入口在 Diary 的成长档案页（`growth_archive_page`），这里是重复入口。
              // 路由与页面照旧存在，删的只是这个入口。
              _toggleRow(l10n.notificationCenterTitle, _notif, _onNotifToggle,
                  key: const ValueKey('meNotifToggle')),
              _divider(),
              _navRow(l10n.meLanguage, value: langValue,
                  onTap: () => context.push('/me/language'), key: const ValueKey('meLanguage')),
              _divider(),
              // 手机号常驻入口（Story 7.2 · FR-70）：**不论是否跳过过软引导，这里永远可填**。
              // 右值：未填 → 占位文案；已填 → **脱敏**（UI 稿 05b 屏）。
              // ⚠️ 脱敏只在这一行；点进抽屉展示**完整号码**，否则用户改不了自己的号。
              _navRow(l10n.phoneEditTitle,
                  value: PhoneMask.mask(phone) ?? l10n.phoneNotSet,
                  onTap: () => PhoneEditSheet.open(context, entry: 'me_page'),
                  key: const ValueKey('mePhoneEntry')),
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
              _divider(),
              // 黑名单（V1.1.4 Story 1.5 · FR-94）。右侧计数直接取列表长度——
              // 后端全量返回、不给 total，全量返回时 total 与 length 冗余。
              // 计数为 0 时也照常显示「0」，与同组其他行保持一致的视觉密度。
              _navRow(l10n.blockedListTitle,
                  value: '${ref.watch(blockedUsersProvider).value?.length ?? 0}',
                  onTap: () => context.push('/me/blocked-users'),
                  key: const ValueKey('meBlockedUsers')),
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
            Center(
              // bug 20260721-288：按真实 app 版本动态显示（原硬编码 v1.0.0·Build 100 会随版本漂移）。
              child: FutureBuilder<PackageInfo>(
                future: PackageInfo.fromPlatform(),
                builder: (context, snap) {
                  final info = snap.data;
                  final label = info == null
                      ? 'TailTopia'
                      : 'TailTopia v${info.version} · Build ${info.buildNumber}';
                  return Text(label,
                      style: const TextStyle(fontSize: 12, color: AppColors.muted));
                },
              ),
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

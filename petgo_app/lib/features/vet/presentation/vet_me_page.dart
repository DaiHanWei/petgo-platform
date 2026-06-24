import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/im/im_service.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../auth/domain/auth_state.dart';
import '../data/vet_repository.dart';
import '../domain/vet_online_status.dart';

/// 「我的」Tab（Story 5.2 F2）：兽医信息 + 在线/离线开关 + 登出。
///
/// 在线态用共享 [vetOnlineStatusProvider]（与工作台首页顶栏同源，切换走
/// `PUT /vet/online-status` 乐观更新 + 失败回滚 + IM 联动）。
/// 心跳保活与前后台生命周期由工作台外壳 [VetWorkbenchShell] 统一持有（常驻、与 Tab 无关），
/// 本页只读写在线态、不再各自持有心跳。
class VetMePage extends ConsumerStatefulWidget {
  const VetMePage({super.key});

  @override
  ConsumerState<VetMePage> createState() => _VetMePageState();
}

class _VetMePageState extends ConsumerState<VetMePage> {
  bool _loading = true;
  bool _updating = false;
  String _displayName = '';
  int? _doneCount; // 完成数（history 列表长度）；null=加载中/失败 → 占位「—」

  @override
  void initState() {
    super.initState();
    _loadProfile();
  }

  Future<void> _loadProfile() async {
    final repo = ref.read(vetRepositoryProvider);
    try {
      final me = await repo.me();
      if (!mounted) return;
      setState(() {
        _displayName = me.displayName;
        _loading = false;
      });
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
    // 完成数（history 长度）单独拉，失败不阻塞名字/页面，仅统计占位「—」。
    try {
      final history = await repo.history();
      if (mounted) setState(() => _doneCount = history.length);
    } catch (_) {/* 占位「—」 */}
  }

  void _onUnavailable() {
    final l10n = AppLocalizations.of(context);
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(SnackBar(content: Text(l10n.vetChatToolUnavailable)));
  }

  /// 三态可用状态切换（Online/Sibuk/Offline）：经 [vetAvailabilityProvider] 持久化二元在线态
  /// （Online=接单 / Sibuk·Offline=不接单），Sibuk 为前端占位态（见 CROSS-STORY-DECISIONS F19）。
  /// 失败时 provider 自愈回落权威态；若结果与所选不符则提示。
  Future<void> _selectAvailability(VetAvailability next) async {
    if (_updating) return;
    final l10n = AppLocalizations.of(context);
    setState(() => _updating = true);
    try {
      await ref.read(vetAvailabilityProvider.notifier).select(next);
      if (mounted && ref.read(vetAvailabilityProvider) != next) {
        ScaffoldMessenger.of(context)
          ..clearSnackBars()
          ..showSnackBar(SnackBar(content: Text(l10n.vetStatusUpdateFailed)));
      }
    } finally {
      if (mounted) setState(() => _updating = false);
    }
  }

  Future<void> _logout() async {
    // 登出即登出 IM（下线，不留长连接）。
    await ref.read(imServiceProvider).logout();
    await ref.read(vetRepositoryProvider).logout();
    ref.read(authControllerProvider.notifier).toGuest();
    if (mounted) context.go('/home');
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final online = ref.watch(vetOnlineStatusProvider);
    final availability = ref.watch(vetAvailabilityProvider);
    return Scaffold(
      backgroundColor: AppColors.vetSurface2,
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                _topBar(l10n),
                Expanded(
                  child: ListView(
                    padding: const EdgeInsets.fromLTRB(AppSpacing.md, AppSpacing.md, AppSpacing.md, AppSpacing.xl),
                    children: [
                      _infoCard(l10n, online),
                      const SizedBox(height: AppSpacing.md),
                      _availabilityCard(l10n, availability),
                      const SizedBox(height: AppSpacing.md),
                      _settingsCard(l10n),
                      const SizedBox(height: AppSpacing.md),
                      Center(
                        child: Text(l10n.vetProfileVersion,
                            style: AppTypography.caption.copyWith(color: AppColors.textTertiary)),
                      ),
                    ],
                  ),
                ),
              ],
            ),
    );
  }

  /// 深色顶栏 #2B2540：「Profil Saya」。
  Widget _topBar(AppLocalizations l10n) {
    return Container(
      width: double.infinity,
      color: AppColors.vetTopBar,
      child: SafeArea(
        bottom: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(AppSpacing.lg, AppSpacing.md, AppSpacing.lg, AppSpacing.md),
          child: Text(l10n.vetProfileTitle, style: AppTypography.title.copyWith(color: Colors.white)),
        ),
      ),
    );
  }

  /// 个人信息卡：薄荷头像(首字母+在线点) + 名 + PDHI 认证标 + 3 统计卡。
  Widget _infoCard(AppLocalizations l10n, bool online) {
    final trimmed = _displayName.trim();
    final initial = trimmed.isNotEmpty ? trimmed.substring(0, 1).toUpperCase() : '?';
    return Container(
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: _cardDecoration(),
      child: Column(
        children: [
          Row(
            children: [
              SizedBox(
                width: 60,
                height: 60,
                child: Stack(
                  children: [
                    Container(
                      width: 60,
                      height: 60,
                      alignment: Alignment.center,
                      decoration: const BoxDecoration(color: AppColors.vetPrimary, shape: BoxShape.circle),
                      child: Text(initial,
                          style: AppTypography.headline.copyWith(color: AppColors.vetOnAccent)),
                    ),
                    if (online)
                      Positioned(
                        right: 1,
                        bottom: 1,
                        child: Container(
                          width: 16,
                          height: 16,
                          decoration: BoxDecoration(
                            color: AppColors.vetPrimary,
                            shape: BoxShape.circle,
                            border: Border.all(color: AppColors.surface, width: 2),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(_displayName,
                        key: const ValueKey('vetDisplayName'),
                        style: AppTypography.title.copyWith(color: AppColors.ink)),
                    const SizedBox(height: 2),
                    // 诊所·地点副行（原型结构；无后端字段 → 占位）。
                    const Text('Klinik Hewan Sehat · Jakarta Selatan',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(fontSize: 12, color: Color(0xFF808080))),
                    const SizedBox(height: 5),
                    Row(
                      children: [
                        Icon(Icons.verified_user_outlined, size: 13, color: AppColors.vetPrimary),
                        const SizedBox(width: 4),
                        Text(l10n.vetProfileVerified,
                            style: AppTypography.caption
                                .copyWith(color: AppColors.vetPrimary, fontWeight: FontWeight.w600)),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.md),
          Row(
            children: [
              // 原型 vet-profile.html：三卡各异色（薄荷 / 金 / 紫），与 dashboard 一致。
              _statCard(_doneCount?.toString() ?? '—', l10n.vetDashboardStatDone,
                  bg: AppColors.vetSurface, valueColor: AppColors.vetPrimary),
              const SizedBox(width: AppSpacing.sm),
              _statCard('—', l10n.vetDashboardStatRating,
                  bg: AppColors.goldTint, valueColor: AppColors.gold), // 评分无后端端点 → 占位「—」
              const SizedBox(width: AppSpacing.sm),
              _statCard('—', l10n.vetProfileStatTotal,
                  bg: AppColors.cream2, valueColor: AppColors.mint), // 总数无后端端点 → 占位「—」
            ],
          ),
        ],
      ),
    );
  }

  Widget _statCard(String value, String label, {required Color bg, required Color valueColor}) {
    return Expanded(
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.sm),
        decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(11)),
        child: Column(
          children: [
            Text(value, style: AppTypography.title.copyWith(color: valueColor)),
            const SizedBox(height: 2),
            Text(label,
                textAlign: TextAlign.center,
                style: AppTypography.micro.copyWith(color: AppColors.textTertiary)),
          ],
        ),
      ),
    );
  }

  /// 在线状态分段控件（三态真切）：Online / Sibuk / Offline 均经 [_selectAvailability] 持久化。
  /// Online=接单；Sibuk/Offline=不接单（Sibuk 为前端占位态，F19）。选中段按等级配色。
  Widget _availabilityCard(AppLocalizations l10n, VetAvailability availability) {
    return Container(
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: _cardDecoration(),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(l10n.vetProfileAvailability,
              style: AppTypography.micro.copyWith(color: AppColors.textTertiary, letterSpacing: 0.5)),
          const SizedBox(height: AppSpacing.sm),
          Row(
            children: [
              _statusSeg('🟢', l10n.vetStatusOnline, l10n.vetStatusOnlineSub,
                  selected: availability == VetAvailability.online,
                  accent: AppColors.vetPrimary,
                  valueKey: 'vetStatusOnline',
                  onTap: () => _selectAvailability(VetAvailability.online)),
              const SizedBox(width: AppSpacing.sm),
              _statusSeg('🟡', l10n.vetStatusBusy, l10n.vetStatusBusySub,
                  selected: availability == VetAvailability.busy,
                  accent: AppColors.triageYellow,
                  valueKey: 'vetStatusBusy',
                  onTap: () => _selectAvailability(VetAvailability.busy)),
              const SizedBox(width: AppSpacing.sm),
              _statusSeg('⚫', l10n.vetStatusOffline, l10n.vetStatusOfflineSub,
                  selected: availability == VetAvailability.offline,
                  accent: AppColors.textTertiary,
                  valueKey: 'vetStatusOffline',
                  onTap: () => _selectAvailability(VetAvailability.offline)),
            ],
          ),
        ],
      ),
    );
  }

  Widget _statusSeg(String emoji, String label, String sub,
      {required bool selected,
      required Color accent,
      required String valueKey,
      required VoidCallback onTap}) {
    return Expanded(
      child: InkWell(
        key: ValueKey(valueKey),
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: AppSpacing.md, horizontal: 4),
          decoration: BoxDecoration(
            color: selected ? accent : AppColors.muted.withValues(alpha: 0.12),
            borderRadius: BorderRadius.circular(12),
            // 选中段投影（原型 box-shadow:0 4px 12px rgba(...,.3)）按等级取色。
            boxShadow: selected
                ? [BoxShadow(color: accent.withValues(alpha: 0.3), blurRadius: 12, offset: const Offset(0, 4))]
                : null,
          ),
          child: Column(
            children: [
              Text(emoji, style: const TextStyle(fontSize: 18)),
              const SizedBox(height: 3),
              Text(label,
                  style: AppTypography.caption.copyWith(
                    color: selected ? AppColors.vetOnAccent : AppColors.textSecondary,
                    fontWeight: FontWeight.w700,
                  )),
              const SizedBox(height: 1),
              Text(sub,
                  textAlign: TextAlign.center,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: AppTypography.micro.copyWith(
                    color: selected ? AppColors.vetOnAccent.withValues(alpha: 0.8) : AppColors.textTertiary,
                  )),
            ],
          ),
        ),
      ),
    );
  }

  /// 设置列表：Edit Profil & SIP / Notifikasi（无后端→未提供） + Keluar（真登出）。
  Widget _settingsCard(AppLocalizations l10n) {
    return Container(
      decoration: _cardDecoration(),
      clipBehavior: Clip.antiAlias,
      child: Column(
        children: [
          _settingsRow(l10n.vetProfileEditProfile, onTap: _onUnavailable),
          const Divider(height: 1, color: AppColors.line2),
          _settingsRow(l10n.vetProfileNotif, onTap: _onUnavailable),
          const Divider(height: 1, color: AppColors.line2),
          _settingsRow(l10n.vetLogout, onTap: _logout, danger: true, valueKey: 'vetLogoutButton'),
        ],
      ),
    );
  }

  Widget _settingsRow(String label, {required VoidCallback onTap, bool danger = false, String? valueKey}) {
    final color = danger ? AppColors.coral : AppColors.ink;
    return InkWell(
      key: valueKey != null ? ValueKey(valueKey) : null,
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md, vertical: 13),
        child: Row(
          children: [
            Expanded(
              child: Text(label,
                  style: AppTypography.body
                      .copyWith(color: color, fontWeight: danger ? FontWeight.w500 : FontWeight.w400)),
            ),
            Icon(Icons.chevron_right, size: 18, color: danger ? AppColors.coral : AppColors.textTertiary),
          ],
        ),
      ),
    );
  }

  BoxDecoration _cardDecoration() => BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(color: AppColors.ink.withValues(alpha: 0.06), blurRadius: 12, offset: const Offset(0, 3)),
        ],
      );
}

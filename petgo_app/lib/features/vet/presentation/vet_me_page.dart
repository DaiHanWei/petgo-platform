import 'dart:async';

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
/// `PUT /vet/online-status` 乐观更新 + 失败回滚 + IM 联动）；本页持有心跳与前后台生命周期：
/// 在线时定时心跳续期 TTL，App 退后台/登出停止心跳 → TTL 兜底离线（防幽灵在线）。
class VetMePage extends ConsumerStatefulWidget {
  const VetMePage({super.key});

  @override
  ConsumerState<VetMePage> createState() => _VetMePageState();
}

class _VetMePageState extends ConsumerState<VetMePage> with WidgetsBindingObserver {
  /// 心跳间隔（< 后端 TTL=3min，留掉线宽限）。
  static const Duration _heartbeatInterval = Duration(seconds: 60);

  bool _loading = true;
  bool _updating = false;
  String _displayName = '';
  Timer? _heartbeat;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadName();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _heartbeat?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // 退后台停止心跳（TTL 兜底离线）；回前台且在线则恢复。
    if (state == AppLifecycleState.resumed) {
      if (ref.read(vetOnlineStatusProvider)) _startHeartbeat();
    } else {
      _stopHeartbeat();
    }
  }

  Future<void> _loadName() async {
    try {
      final me = await ref.read(vetRepositoryProvider).me();
      if (!mounted) return;
      setState(() {
        _displayName = me.displayName;
        _loading = false;
      });
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _toggle(bool next) async {
    if (_updating) return;
    final l10n = AppLocalizations.of(context);
    setState(() => _updating = true);
    try {
      await ref.read(vetOnlineStatusProvider.notifier).toggle(next);
    } catch (_) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
        ..clearSnackBars()
        ..showSnackBar(SnackBar(content: Text(l10n.vetStatusUpdateFailed)));
    } finally {
      if (mounted) setState(() => _updating = false);
    }
  }

  void _startHeartbeat() {
    _heartbeat?.cancel();
    _heartbeat = Timer.periodic(_heartbeatInterval, (_) {
      ref.read(vetRepositoryProvider).heartbeat();
    });
  }

  void _stopHeartbeat() {
    _heartbeat?.cancel();
    _heartbeat = null;
  }

  Future<void> _logout() async {
    _stopHeartbeat();
    // 登出即登出 IM（下线，不留长连接）。
    await ref.read(imServiceProvider).logout();
    await ref.read(vetRepositoryProvider).logout();
    ref.read(authControllerProvider.notifier).toGuest();
    if (mounted) context.go('/home');
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // 在线态变化 → 心跳跟随（无论从本页还是工作台顶栏切换）。
    ref.listen<bool>(vetOnlineStatusProvider, (prev, next) {
      if (next) {
        _startHeartbeat();
      } else {
        _stopHeartbeat();
      }
    });
    final online = ref.watch(vetOnlineStatusProvider);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.vetTabMe)),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(AppSpacing.xl),
              children: [
                Text(_displayName, key: const ValueKey('vetDisplayName'), style: AppTypography.headline),
                const SizedBox(height: AppSpacing.section),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md, vertical: AppSpacing.sm),
                  decoration: BoxDecoration(
                    color: AppColors.surface,
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(color: AppColors.border),
                  ),
                  child: Row(
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(l10n.vetOnlineTitle, style: AppTypography.title),
                            const SizedBox(height: 2),
                            Text(
                              online ? l10n.vetOnlineLabel : l10n.vetOfflineLabel,
                              style: AppTypography.caption.copyWith(
                                color: online ? AppColors.vetPrimary : AppColors.textTertiary,
                              ),
                            ),
                          ],
                        ),
                      ),
                      Switch(
                        key: const ValueKey('vetOnlineSwitch'),
                        value: online,
                        activeThumbColor: AppColors.vetPrimary,
                        onChanged: _updating ? null : _toggle,
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: AppSpacing.section),
                OutlinedButton(
                  key: const ValueKey('vetLogoutButton'),
                  onPressed: _logout,
                  child: Text(l10n.vetLogout),
                ),
              ],
            ),
    );
  }
}

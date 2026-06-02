import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../auth/domain/auth_state.dart';
import '../data/vet_repository.dart';

/// 「我的」Tab（Story 5.2 F2）：兽医信息 + 在线/离线开关 + 登出。
///
/// 在线/离线切换写 `PUT /vet/online-status`（乐观更新 + 失败回滚）；在线时定时心跳续期 TTL，
/// App 退后台/登出停止心跳 → TTL 兜底离线（防幽灵在线）。
class VetMePage extends ConsumerStatefulWidget {
  const VetMePage({super.key});

  @override
  ConsumerState<VetMePage> createState() => _VetMePageState();
}

class _VetMePageState extends ConsumerState<VetMePage> with WidgetsBindingObserver {
  /// 心跳间隔（< 后端 TTL=3min，留掉线宽限）。
  static const Duration _heartbeatInterval = Duration(seconds: 60);

  bool _online = false;
  bool _loading = true;
  bool _updating = false;
  String _displayName = '';
  Timer? _heartbeat;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
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
      if (_online) _startHeartbeat();
    } else {
      _stopHeartbeat();
    }
  }

  Future<void> _load() async {
    final repo = ref.read(vetRepositoryProvider);
    try {
      final results = await Future.wait([repo.me(), repo.readOnlineStatus()]);
      if (!mounted) return;
      setState(() {
        _displayName = (results[0] as dynamic).displayName as String;
        _online = results[1] as bool;
        _loading = false;
      });
      if (_online) _startHeartbeat();
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _toggle(bool next) async {
    if (_updating) return;
    final l10n = AppLocalizations.of(context);
    setState(() {
      _online = next; // 乐观更新
      _updating = true;
    });
    try {
      final authoritative = await ref.read(vetRepositoryProvider).setOnline(next);
      if (!mounted) return;
      setState(() => _online = authoritative);
      if (authoritative) {
        _startHeartbeat();
      } else {
        _stopHeartbeat();
      }
    } catch (_) {
      if (!mounted) return;
      setState(() => _online = !next); // 失败回滚
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
    await ref.read(vetRepositoryProvider).logout();
    ref.read(authControllerProvider.notifier).toGuest();
    if (mounted) context.go('/home');
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
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
                              _online ? l10n.vetOnlineLabel : l10n.vetOfflineLabel,
                              style: AppTypography.caption.copyWith(
                                color: _online ? AppColors.accentGrowth : AppColors.textTertiary,
                              ),
                            ),
                          ],
                        ),
                      ),
                      Switch(
                        key: const ValueKey('vetOnlineSwitch'),
                        value: _online,
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

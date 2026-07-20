import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../data/vet_repository.dart';
import '../domain/vet_online_status.dart';
import 'vet_active_page.dart';
import 'vet_history_page.dart';
import 'vet_inbox_page.dart';
import 'vet_income_page.dart';
import 'vet_me_page.dart';

/// 兽医工作台壳（Story 5.2）。**独立**底部 Tab Bar（待接单/进行中/历史/我的），
/// 与用户侧 5-Tab（凸起「+」发布/Feed/档案）物理隔离——此处无发布 FAB、无 Feed/档案入口。
///
/// role 守卫保证只有 role=VET 能进（承接 5.1）。四 Tab 内容多为空态占位，5.3/5.5/5.8 填充。
class VetWorkbenchShell extends ConsumerStatefulWidget {
  const VetWorkbenchShell({super.key});

  @override
  ConsumerState<VetWorkbenchShell> createState() => _VetWorkbenchShellState();
}

class _VetWorkbenchShellState extends ConsumerState<VetWorkbenchShell>
    with WidgetsBindingObserver {
  // Debug-only：--dart-define=DEV_VET_TAB=3 启动即落对应 tab（逐屏视觉验收用）；release 恒 0。
  int _index = kDebugMode ? const int.fromEnvironment('DEV_VET_TAB') : 0;

  // 0718：新增 Pendapatan（收入）Tab，位于 Riwayat 与 Saya 之间（Saya 顺延到 index 4）。
  static const List<Widget> _pages = [
    VetInboxPage(),
    VetActivePage(),
    VetHistoryPage(),
    VetIncomePage(),
    VetMePage(),
  ];

  /// 在线态「最后活跃」刷新心跳。在线态是纯显式的（bug 20260702-216：后端不再靠心跳续命、
  /// 不 TTL 过期），心跳仅让后台展示的 lastSeen 跟随前台活动；停心跳/退后台不会使兽医离线。
  static const Duration _heartbeatInterval = Duration(seconds: 60);
  Timer? _heartbeat;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _heartbeat?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _onResume();
    } else {
      _stopHeartbeat(); // 退后台停心跳（在线态不受影响，兽医仍在线；仅 lastSeen 暂停刷新）
    }
  }

  /// 回前台:在线则补一次心跳刷新 lastSeen + 续上定时心跳;再以服务端真值同步显示态
  /// （在线态纯显式，服务端返回的即兽医上次的选择，不会被误翻 offline）。
  Future<void> _onResume() async {
    if (ref.read(vetOnlineStatusProvider)) {
      await _beat();
      _startHeartbeat();
    }
    await ref.read(vetOnlineStatusProvider.notifier).syncFromServer();
  }

  Future<void> _beat() =>
      ref.read(vetRepositoryProvider).heartbeat().catchError((_) {});

  void _startHeartbeat() {
    _heartbeat?.cancel();
    _heartbeat = Timer.periodic(_heartbeatInterval, (_) => _beat());
  }

  void _stopHeartbeat() {
    _heartbeat?.cancel();
    _heartbeat = null;
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // 在线态变化即跟随心跳（顶栏/「我的」任一处切换均经 vetOnlineStatusProvider 单一源）：
    // 上线 → 立即刷新 lastSeen + 续期定时；离线（显式 goOffline 已置态）→ 停心跳。
    ref.listen<bool>(vetOnlineStatusProvider, (prev, next) {
      if (next) {
        _beat();
        _startHeartbeat();
      } else {
        _stopHeartbeat();
      }
    });
    return Scaffold(
      backgroundColor: AppColors.base,
      body: SafeArea(child: IndexedStack(index: _index, children: _pages)),
      // 原型 vet-dashboard：白底 + 顶细线 + 列式 4 tab，选中紫色 (#845EC9)、未选灰。
      bottomNavigationBar: Container(
        key: const ValueKey('vetBottomNav'),
        decoration: const BoxDecoration(
          color: AppColors.surface,
          border: Border(top: BorderSide(color: AppColors.line)),
        ),
        child: SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceAround,
              children: [
                _VetNavItem(
                    icon: Icons.inbox_outlined, label: l10n.vetTabInbox, selected: _index == 0, onTap: () => _select(0)),
                _VetNavItem(
                    icon: Icons.chat_bubble_outline, label: l10n.vetTabActive, selected: _index == 1, onTap: () => _select(1)),
                _VetNavItem(
                    icon: Icons.history, label: l10n.vetTabHistory, selected: _index == 2, onTap: () => _select(2)),
                _VetNavItem(
                    icon: Icons.payments_outlined, label: l10n.vetTabIncome, selected: _index == 3, onTap: () => _select(3)),
                _VetNavItem(
                    icon: Icons.person_outline, label: l10n.vetTabMe, selected: _index == 4, onTap: () => _select(4)),
              ],
            ),
          ),
        ),
      ),
    );
  }

  void _select(int i) {
    setState(() => _index = i);
    // 切到历史 Tab → 重拉，让刚结束的会话即时出现（bug 20260702-219：历史页 IndexedStack 保活不自动刷新）。
    if (i == 2) ref.read(vetHistoryRefreshProvider.notifier).bump();
  }
}

/// 兽医底栏单项（原型列式）：图标 + 标签，选中紫高亮 + 标签加粗。
class _VetNavItem extends StatelessWidget {
  const _VetNavItem({
    required this.icon,
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final IconData icon;
  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final color = selected ? AppColors.mint : AppColors.textTertiary;
    return Expanded(
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 2),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, size: 24, color: color),
              const SizedBox(height: 3),
              Text(
                label,
                style: AppTypography.micro.copyWith(
                  color: color,
                  fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

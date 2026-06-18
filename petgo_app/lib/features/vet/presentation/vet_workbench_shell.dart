import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import 'vet_active_page.dart';
import 'vet_history_page.dart';
import 'vet_inbox_page.dart';
import 'vet_me_page.dart';

/// 兽医工作台壳（Story 5.2）。**独立**底部 Tab Bar（待接单/进行中/历史/我的），
/// 与用户侧 5-Tab（凸起「+」发布/Feed/档案）物理隔离——此处无发布 FAB、无 Feed/档案入口。
///
/// role 守卫保证只有 role=VET 能进（承接 5.1）。四 Tab 内容多为空态占位，5.3/5.5/5.8 填充。
class VetWorkbenchShell extends StatefulWidget {
  const VetWorkbenchShell({super.key});

  @override
  State<VetWorkbenchShell> createState() => _VetWorkbenchShellState();
}

class _VetWorkbenchShellState extends State<VetWorkbenchShell> {
  // Debug-only：--dart-define=DEV_VET_TAB=3 启动即落对应 tab（逐屏视觉验收用）；release 恒 0。
  int _index = kDebugMode ? const int.fromEnvironment('DEV_VET_TAB') : 0;

  static const List<Widget> _pages = [
    VetInboxPage(),
    VetActivePage(),
    VetHistoryPage(),
    VetMePage(),
  ];

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      body: SafeArea(child: IndexedStack(index: _index, children: _pages)),
      bottomNavigationBar: NavigationBar(
        key: const ValueKey('vetBottomNav'),
        selectedIndex: _index,
        onDestinationSelected: (i) => setState(() => _index = i),
        destinations: [
          NavigationDestination(icon: const Icon(Icons.inbox_outlined), label: l10n.vetTabInbox),
          NavigationDestination(icon: const Icon(Icons.chat_outlined), label: l10n.vetTabActive),
          NavigationDestination(icon: const Icon(Icons.history), label: l10n.vetTabHistory),
          NavigationDestination(icon: const Icon(Icons.person_outline), label: l10n.vetTabMe),
        ],
      ),
    );
  }
}

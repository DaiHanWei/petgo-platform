import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/motion.dart';
import 'bottom_tab_bar.dart';

/// App 主框架外壳（Story 1.2）。
///
/// 承载 5 位底部 Tab Bar（[BottomTabBar] + 中间凸起 [AddTabButton]）与 4 个可导航分支。
/// 内容区切换 [AppMotion.tabFade]=120ms 淡入（[FadeTransition] 由控制器驱动，
/// 不对带 GlobalKey 的 [StatefulNavigationShell] 做 key 重挂，避免 reparent 冲突）。
///
/// ⚠️ Story 1.2 五个 Tab 均可点进占位页（不做门控）；受控 Tab 的未登录门控在 Story 1.5 接线。
class AppShell extends StatefulWidget {
  const AppShell({super.key, required this.navigationShell});

  /// go_router [StatefulShellRoute] 注入的导航壳。
  final StatefulNavigationShell navigationShell;

  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> with SingleTickerProviderStateMixin {
  late final AnimationController _fade = AnimationController(
    vsync: this,
    duration: AppMotion.tabFade,
    value: 1,
  );

  @override
  void didUpdateWidget(covariant AppShell oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.navigationShell.currentIndex != widget.navigationShell.currentIndex) {
      _fade.forward(from: 0); // Tab 切换：内容区 120ms 淡入。
    }
  }

  @override
  void dispose() {
    _fade.dispose();
    super.dispose();
  }

  void _onTabSelected(int index) {
    widget.navigationShell.goBranch(
      index,
      initialLocation: index == widget.navigationShell.currentIndex,
    );
  }

  @override
  Widget build(BuildContext context) {
    final int index = widget.navigationShell.currentIndex;
    return Scaffold(
      backgroundColor: AppColors.base,
      body: FadeTransition(opacity: _fade, child: widget.navigationShell),
      floatingActionButton: AddTabButton(
        activeIndex: index,
        onPressed: () {
          // Story 1.2：「＋」点击仅占位（Publish Compose 属 Epic 2）。点击不崩即可。
          ScaffoldMessenger.of(context).clearSnackBars();
        },
      ),
      floatingActionButtonLocation: FloatingActionButtonLocation.centerDocked,
      bottomNavigationBar: BottomTabBar(
        currentIndex: index,
        onTabSelected: _onTabSelected,
      ),
    );
  }
}

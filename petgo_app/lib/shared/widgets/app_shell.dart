import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/router/route_intent.dart';
import '../../core/theme/colors.dart';
import '../../core/theme/motion.dart';
import '../../features/auth/domain/auth_guard.dart';
import '../../features/content/presentation/publish_compose_page.dart';
import 'bottom_tab_bar.dart';

/// App 主框架外壳（Story 1.2 外观 + Story 1.5 受控 Tab 门控）。
///
/// 5 位底部 Tab Bar + 中间凸起「＋」；内容区切换 [AppMotion.tabFade]=120ms 淡入。
/// 门控（Story 1.5）：仅首页游客可访问；成长档案/[+]/问诊/我的 未登录点击 → 经
/// **单一门控入口** [requireLogin] 弹强弹窗（注入 pendingAction），不切换目的地。
class AppShell extends ConsumerStatefulWidget {
  const AppShell({super.key, required this.navigationShell});

  final StatefulNavigationShell navigationShell;

  @override
  ConsumerState<AppShell> createState() => _AppShellState();
}

class _AppShellState extends ConsumerState<AppShell> with SingleTickerProviderStateMixin {
  late final AnimationController _fade = AnimationController(
    vsync: this,
    duration: AppMotion.tabFade,
    value: 1,
  );

  /// Tab index → 受控路由位置（首页 index 0 游客可达，不门控）。
  static const List<String> _tabLocations = ['/home', '/profile', '/triage', '/me'];

  @override
  void didUpdateWidget(covariant AppShell oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.navigationShell.currentIndex != widget.navigationShell.currentIndex) {
      _fade.forward(from: 0);
    }
  }

  @override
  void dispose() {
    _fade.dispose();
    super.dispose();
  }

  void _goBranch(int index) {
    widget.navigationShell.goBranch(
      index,
      initialLocation: index == widget.navigationShell.currentIndex,
    );
  }

  void _onTabSelected(int index) {
    if (index == AppTab.home.index) {
      _goBranch(index); // 首页：游客可进
      return;
    }
    // 受控 Tab：单一门控入口；未登录弹强弹窗 + 注入 pendingAction（登录后回到该 Tab）。
    requireLogin(
      ref,
      context,
      pendingAction: RouteIntent(location: _tabLocations[index]),
      onAllowed: () => _goBranch(index),
    );
  }

  void _onAddPressed() {
    // 「＋」=发布入口，受控。未登录弹强弹窗；已登录打开 Publish Compose（Story 2.3）。
    requireLogin(
      ref,
      context,
      pendingAction: const RouteIntent(location: '/home'),
      onAllowed: () => PublishComposePage.open(context),
    );
  }

  @override
  Widget build(BuildContext context) {
    final int index = widget.navigationShell.currentIndex;
    return Scaffold(
      backgroundColor: AppColors.base,
      body: FadeTransition(opacity: _fade, child: widget.navigationShell),
      floatingActionButton: AddTabButton(activeIndex: index, onPressed: _onAddPressed),
      floatingActionButtonLocation: FloatingActionButtonLocation.centerDocked,
      bottomNavigationBar: BottomTabBar(currentIndex: index, onTabSelected: _onTabSelected),
    );
  }
}

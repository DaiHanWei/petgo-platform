import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/router/route_intent.dart';
import '../../core/theme/colors.dart';
import '../../core/theme/motion.dart';
import '../../features/auth/domain/auth_guard.dart';
import '../../features/content/presentation/feed_controller.dart';
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
      // 从其它 Tab 切回首页：刷新 feed（keepAlive 缓存，否则看不到新内容/删帖/发布变更）。
      final fromElsewhere = widget.navigationShell.currentIndex != AppTab.home.index;
      _goBranch(index); // 首页：游客可进
      if (fromElsewhere) ref.read(feedProvider.notifier).refresh();
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
      // 与 centerDocked 同位，但忽略 SnackBar 高度：底部出现「sign-in」等错误弹框时
      // 中间「＋」发布按钮保持固定，不被顶起（iOS/Android 一致）。
      floatingActionButtonLocation: const _FixedCenterDockedFabLocation(),
      bottomNavigationBar: BottomTabBar(currentIndex: index, onTabSelected: _onTabSelected),
    );
  }
}

/// 居中贴底栏顶边的 FAB 定位，复刻 [FloatingActionButtonLocation.centerDocked]，
/// **但不把 SnackBar 高度计入**——底部错误弹框出现时「＋」发布按钮固定不动（用户反馈：按钮被顶起）。
/// 仍为底部 sheet 让位（与 centerDocked 行为一致）。
class _FixedCenterDockedFabLocation extends FloatingActionButtonLocation {
  const _FixedCenterDockedFabLocation();

  @override
  Offset getOffset(ScaffoldPrelayoutGeometry geometry) {
    final double fabWidth = geometry.floatingActionButtonSize.width;
    final double fabHeight = geometry.floatingActionButtonSize.height;
    final double fabX = (geometry.scaffoldSize.width - fabWidth) / 2.0;

    // centerDocked 的 Y：FAB 中心落在内容区底边（= bottomNavigationBar 顶边）。
    final double contentBottom = geometry.contentBottom;
    double fabY = contentBottom - fabHeight / 2.0;
    // 关键：不再像 centerDocked 那样因 geometry.snackBarSize 上移。
    final double bottomSheetHeight = geometry.bottomSheetSize.height;
    if (bottomSheetHeight > 0.0) {
      fabY = math.max(geometry.contentTop, math.min(fabY, contentBottom - bottomSheetHeight - fabHeight / 2.0));
    }
    final double maxFabY = geometry.scaffoldSize.height - fabHeight;
    return Offset(fabX, math.min(fabY, maxFabY));
  }
}

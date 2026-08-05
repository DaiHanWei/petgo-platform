import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/router/route_intent.dart';
import '../../core/theme/colors.dart';
import '../../core/theme/motion.dart';
import '../../core/analytics/analytics.dart';
import '../../features/auth/domain/auth_guard.dart';
import '../../features/auth/domain/auth_state.dart';
import '../../features/auth/domain/user_state.dart';
import '../../features/content/domain/home_refresh_provider.dart';
import '../../features/content/domain/content_type.dart';
import '../../features/content/presentation/feed_controller.dart';
import '../../features/profile/data/timeline_repository.dart';
import '../../features/content/presentation/publish_compose_page.dart';
import 'bottom_tab_bar.dart';

/// **免门控 Tab 白名单**（Story 1.1 去索引化 · Story 2.4 放行 Diary）。
///
/// 按 Tab 语义判定，不比较裸索引——重排后裸索引会指向错误的 Tab（AD-3 AC3①）。
///
/// ⚠️ 这是 **Tab 点击门控**（AD-7 Rule 2），与 `app_router.dart` 的**深链门控**是**两处独立机制**，
/// 改法也不同（那边是「前缀默认受控 + 精确例外集」）。放行 Diary 必须两处同改 ——
/// 只改深链那处，游客点 Diary 标签仍会被弹登录框（PRD 只覆盖了深链那处）。
///
/// `Diary` 在此白名单里指的**只是 Diary 主页**：其子页（建档 / 编辑 / 当天详情 / 里程碑列表）
/// 由深链门控继续拦截，游客点进去仍会被 redirect。
/// **Health / [+] / Me 对游客维持受控**，不得顺手加进来。
const Set<AppTab> kUngatedTabs = {AppTab.home, AppTab.profile};

/// App 主框架外壳（Story 1.2 外观 + Story 1.5 受控 Tab 门控）。
///
/// 5 位底部 Tab Bar + 中间凸起「＋」；内容区切换 [AppMotion.tabFade]=120ms 淡入。
/// 门控（Story 1.5）：仅 Social 游客可访问；Diary/[+]/Health/我的 未登录点击 → 经
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

  /// Tab 切换 + Tab 根页浏览埋点（Story 6.1 · T-1/T-2 · AC2）。
  ///
  /// PostHog 的 `$screen` 由路由 observer 产生，而 Tab 切换走 `StatefulShellRoute.goBranch`
  /// （不 push 根路由）→ **不产生 `$screen`**，所以必须自己埋。
  /// `user_state` 取 Story 2.4 落地矩阵的同源判定，避免埋点口径与实际分流对不上。
  ///
  /// ⚠️ **只在真的切过去了才上报**（code-review 2026-08-04）。两条曾经踩过的坑：
  /// 1. 上报早于门控判定 → 游客点 Health/Me 只会弹强登录、页面根本没打开，看板却记了一条浏览
  ///    → 落地页分流被系统性高估，而这正是 AC2 要度量的东西；
  /// 2. 重复点当前 Tab 也报，且 `from_tab == to_tab` → 与 T-11（切视图）明确 no-op 的口径不一致。
  void _reportTabEntered(AppTab from, AppTab tab) {
    Analytics.screen('${tab.analyticsName}_page');
    Analytics.capture('bottom_nav_tab_switched', {
      'from_tab': from.analyticsName,
      'to_tab': tab.analyticsName,
      'user_state': appUserStateOf(ref.read(authControllerProvider)).wire,
    });
  }

  void _onTabSelected(int index) {
    // 按 Tab 语义判定，不比较裸索引（AD-3 AC3①）——重排后裸索引会指向错误的 Tab。
    final AppTab tab = AppTab.values[index];
    final AppTab from = AppTab.values[widget.navigationShell.currentIndex];
    final bool reTap = widget.navigationShell.currentIndex == index;
    if (kUngatedTabs.contains(tab)) {
      if (!reTap) {
        _reportTabEntered(from, tab);
      }
      _goBranch(index); // 免门控：游客可进
      if (tab == AppTab.profile) {
        // 切回 Diary → 刷新时间线 / 日历 / 统计（照 Social 的 feedProvider.refresh() 范式）。
        // Story 3.2 起时间线会镜像里程碑 / 健康记录 / 身份证条目，它们在用户不在本页时也会变化；
        // 不刷新会导致切回后看不到新条目。日历按年月分族，整族失效（含非当前月缓存）。
        ref.invalidate(timelineFirstPageProvider);
        ref.invalidate(archiveStatsProvider);
        ref.invalidate(calendarMonthProvider);
      }
      if (tab == AppTab.home) {
        // 切回 Social（原首页，V1.1.2 曾短暂叫 Discovery）：刷新 feed（keepAlive 缓存，否则看不到新内容/删帖/发布变更）。
        // 已在该 Tab 再次点击 → 额外回到顶部（bug 20260709-278）。
        if (reTap) {
          ref.read(homeScrollTopProvider.notifier).bump();
        }
        ref.read(feedProvider.notifier).refresh();
      }
      return;
    }
    // 受控 Tab：单一门控入口；未登录弹强弹窗 + 注入 pendingAction（登录后回到该 Tab）。
    // 目的地取自枚举内嵌的 location，不再依赖并行数组。
    // 埋点放在 onAllowed 里 —— 被门控拦下时页面没打开，不该记一条浏览。
    requireLogin(
      ref,
      context,
      pendingAction: RouteIntent(location: tab.location),
      onAllowed: () {
        if (!reTap) {
          _reportTabEntered(from, tab);
        }
        _goBranch(index);
      },
    );
  }

  void _onAddPressed() {
    // 「＋」=发布入口，受控。未登录弹强弹窗；已登录打开 Publish Compose（Story 2.3）。
    requireLogin(
      ref,
      context,
      // 登录后回跳落 Social（内容流）。
      //
      // ⚠️ 2026-08-04 code-review 决策 D3 改于此：原先写死 `/profile`（Diary），注释还引着
      // 「FR-78 连带调整①：落地页由 Discovery 改为 Diary」的旧口径。但 Story 7.4 已把
      // **A·未建档**的冷启动落地由 `/profile` 改为 `/home`，而刚注册完的新用户正是这一态 ⇒
      // 会出现「注册成功回跳到空的建档引导页，下次冷启动却落内容流」的两套口径。
      // 现统一为 `/home`：未建档的人一律先看内容流（Story 7.4 的立论——空建档页即时价值低）。
      pendingAction: const RouteIntent(location: '/home'),
      // bug 20260703-244：在「成长档案（Diary）」Tab（底部第 2 个）点创建 → 编辑页默认选「成长日历（Growth）」；
      // 其余 Tab 保持默认第一个 tag（Momen）。Growth 需宠物档案（否则 segment 灰置），无档案则不预选、回落 Momen。
      onAllowed: () {
        final onGrowthTab = AppTab.values[widget.navigationShell.currentIndex] == AppTab.profile;
        final p = ref.read(authControllerProvider).profile;
        final canGrowth = p?.petStatus == 'HAS_PET' && (p?.hasPetProfile ?? false);
        PublishComposePage.open(
            context, preset: (onGrowthTab && canGrowth) ? ContentType.growthMoment : null);
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final int index = widget.navigationShell.currentIndex;
    return Scaffold(
      backgroundColor: AppColors.base,
      // 键盘弹出时不收缩外壳：底栏 + 中间「＋」发布按钮固定在屏幕底部不被顶起（用户反馈）。
      // 各 Tab 根页本身无内联输入框，文字编辑均走 modal bottom sheet（自带 viewInsets 让位），故安全。
      resizeToAvoidBottomInset: false,
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

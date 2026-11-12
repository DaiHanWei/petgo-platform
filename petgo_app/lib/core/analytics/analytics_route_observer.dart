import 'package:flutter/widgets.dart';

import 'analytics.dart';

/// 路由自动页面浏览观察者（analytics-pipeline Story 2.4），取代 `posthog_flutter` 的 `PosthogObserver`。
///
/// **逐条复刻** `posthog_flutter 5.33.0` `PosthogObserver` 的触发时机，不多报也不少报 ——
/// 页面名与触发口径一变，Toko 浏览、漏斗第一步就会与 PostHog 时期断档：
/// - [didPush] → 对被 push 的路由上报；[didReplace] → 对新路由上报；
///   [didPop] → 对返回到的那一页（`previousRoute`）上报；
/// - 只处理 [PageRoute]：对话框、底部弹层这类 `PopupRoute` 不报（发布页 sheet 由
///   `publish_compose_page.dart` 手工 `Analytics.screen('publish_page')` 补）；
/// - 页面名 = `route.settings.name`（go_router 未设 `name` 时即**路由路径模板**，
///   如 `/shop/products/:token`），原样上报；为 null / 空白不报；`'/'` 改写为 `"root ('/')"`；
/// - 只在前台上报：生命周期状态为 null 或 `resumed` 才报，防后台重建产生幽灵浏览。
///
/// ⚠️ **不做「与上一条同名就不报」的去重**：PosthogObserver 没有，加了会让
/// 「详情页 → 返回列表 → 再进详情」少记一次浏览，与 PostHog 时期口径不一致。
///
/// 上报一律经 [Analytics.screen]，与 Tab 切换 / 冷启动落地的手工补报走同一条路径。
class AnalyticsRouteObserver extends NavigatorObserver with WidgetsBindingObserver {
  AnalyticsRouteObserver() {
    WidgetsBinding.instance.addObserver(this);
    _appLifecycleState = WidgetsBinding.instance.lifecycleState;
  }

  AppLifecycleState? _appLifecycleState;

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _appLifecycleState = state;
  }

  bool get _isAppInForeground {
    final state = _appLifecycleState;
    return state == null || state == AppLifecycleState.resumed;
  }

  void _sendScreenView(Route<dynamic>? route) {
    if (route is! PageRoute) return;
    if (!_isAppInForeground) return;
    var name = route.settings.name;
    if (name == null || name.trim().isEmpty) return;
    if (name == '/') name = "root ('/')";
    Analytics.screen(name);
  }

  @override
  void didPush(Route<dynamic> route, Route<dynamic>? previousRoute) {
    super.didPush(route, previousRoute);
    _sendScreenView(route);
  }

  @override
  void didReplace({Route<dynamic>? newRoute, Route<dynamic>? oldRoute}) {
    super.didReplace(newRoute: newRoute, oldRoute: oldRoute);
    _sendScreenView(newRoute);
  }

  @override
  void didPop(Route<dynamic> route, Route<dynamic>? previousRoute) {
    super.didPop(route, previousRoute);
    _sendScreenView(previousRoute);
  }
}

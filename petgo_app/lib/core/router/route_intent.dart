import 'package:flutter/widgets.dart';
import 'package:go_router/go_router.dart';

/// 登录后回跳意图（pendingAction）。由触发方在调用登录引导时注入。
///
/// 两种表达：声明式路由位置 [location]（如 `/triage`），或命令式回调 [onResume]
/// （登录后继续原操作）。两者皆空表示无后续操作（登录后进主框架默认页）。
class RouteIntent {
  const RouteIntent({this.location, this.onResume});

  final String? location;
  final void Function()? onResume;

  bool get isEmpty => location == null && onResume == null;

  /// 执行回跳：优先命令式回调，否则按位置导航。
  void run(BuildContext context) {
    if (onResume != null) {
      onResume!();
    } else if (location != null) {
      context.go(location!);
    }
  }
}

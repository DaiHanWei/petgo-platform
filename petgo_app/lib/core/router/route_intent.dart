/// 登录后回跳意图（pendingAction）的结构预留。
///
/// Story 1.3 仅定义最小结构；完整回跳机制（含新用户先引导后回跳）在 Story 1.4 实现。
class RouteIntent {
  const RouteIntent({this.location, this.onResume});

  /// 登录后要前往的路由位置（如 `/triage`）。
  final String? location;

  /// 或登录后要执行的回调（如继续打开问诊）。
  final void Function()? onResume;

  bool get isEmpty => location == null && onResume == null;
}

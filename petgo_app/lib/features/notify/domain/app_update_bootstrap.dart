import 'package:flutter/foundation.dart';

/// 冷启动版本检查的接线点（V1.3.4 Story 3.2 · AC6.1）。
///
/// `main.dart` 的首帧回调在 ProviderScope 之外、拿不到 ref；所以由 `app.dart`
/// `_TailTopiaAppState.initState` [register] 一个带 ref 的回调，`main.dart` 在启动链
/// **末尾**调 [runColdStartCheck]（同 `PhoneSoftPrompt.register` 的做法）。
class AppUpdateBootstrap {
  AppUpdateBootstrap._();

  static Future<void> Function()? _check;

  /// 注册冷启动检查回调（启动期调用一次；重复注册以最后一次为准）。
  static void register(Future<void> Function() check) => _check = check;

  /// 执行冷启动检查。未注册 → 空转；吞掉一切异常，绝不影响启动（PRD AC5）。
  static Future<void> runColdStartCheck() async {
    final check = _check;
    if (check == null) return;
    try {
      await check();
    } catch (e) {
      debugPrint('[AppUpdate] cold start check failed: $e');
    }
  }

  static Future<void> Function()? _showForced;

  /// 注册「显示强制更新弹窗」入口（V1.3.4 Story 3.4 复审）：由 `app.dart` 根 State 用**常驻 ref**
  /// 注册。页面（如设置页手动检查）判定 forced 时调 [showForcedDialog]，**不得**把页面自己的
  /// ref 传给 `showForcedUpdateDialog` —— 推送 / 深链的 `go()` 替换栈会卸载页面，
  /// 已卸载的 ref 在重弹循环里抛 StateError，强制弹窗不再重弹（可绕过强制更新，PRD AC4）。
  static void registerForcedDialog(Future<void> Function() show) => _showForced = show;

  /// 显示强制更新弹窗。未注册 → 空转（生产由 `app.dart` 启动时注册）。
  static Future<void> showForcedDialog() async {
    final show = _showForced;
    if (show == null) {
      debugPrint('[AppUpdate] forced dialog presenter not registered');
      return;
    }
    await show();
  }

  @visibleForTesting
  static void debugReset() {
    _check = null;
    _showForced = null;
  }
}

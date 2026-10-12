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

  @visibleForTesting
  static void debugReset() => _check = null;
}

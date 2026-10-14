import 'package:flutter/foundation.dart';
import 'package:rive/rive.dart';

/// Rive 原生运行时的全局可用性开关（V1.3.4 签到盖章动画）。
///
/// 初始化失败（抛异常、返回 false、超时；模拟器显卡模式、老设备、原生库缺失）
/// 不得阻断启动：[available] 保持 false，调用方一律走兜底。
class RiveRuntime {
  RiveRuntime._();

  static bool available = false;

  /// [initializer] / [timeout] 仅供测试注入；默认调 `RiveNative.init()`，超时 3s。
  static Future<void> init({
    Future<bool> Function()? initializer,
    Duration timeout = const Duration(seconds: 3),
  }) async {
    try {
      final ok = await (initializer ?? RiveNative.init)().timeout(timeout);
      available = ok;
      if (!ok) debugPrint('RiveRuntime.init returned false, ceremony disabled');
    } catch (e) {
      available = false;
      debugPrint('RiveRuntime.init failed, ceremony disabled: $e');
    }
  }
}

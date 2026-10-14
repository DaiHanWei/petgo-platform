import 'package:flutter/foundation.dart';
import 'package:rive/rive.dart';

/// Rive 原生运行时的全局可用性开关（V1.3.4 签到盖章动画）。
///
/// 初始化失败（模拟器显卡模式、老设备、原生库缺失）不得阻断启动：
/// 吞掉异常、保持 [available] 为 false，调用方一律走兜底。
class RiveRuntime {
  RiveRuntime._();

  static bool available = false;

  /// [initializer] 仅供测试注入；默认调 `RiveNative.init()`。
  static Future<void> init({Future<void> Function()? initializer}) async {
    try {
      if (initializer != null) {
        await initializer();
      } else {
        await RiveNative.init();
      }
      available = true;
    } catch (e) {
      available = false;
      debugPrint('RiveRuntime.init failed, ceremony disabled: $e');
    }
  }
}

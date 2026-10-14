import 'package:flutter/foundation.dart';
import 'package:rive/rive.dart';

/// Rive 原生运行时的全局可用性开关（V1.3.4 签到盖章动画）。
///
/// 启动时只在后台发起初始化（`main()` 里 `unawaited`），**不阻塞首帧**：只有新章签到才用得上 Rive，
/// 不能让所有用户为它等待。真正要播动画时由 [ensureReady] 收口。
/// 初始化失败（抛异常、返回 false、超时；模拟器显卡模式、老设备、原生库缺失）时 [available] 保持 false，
/// 调用方一律走兜底（直接进成功页）。
class RiveRuntime {
  RiveRuntime._();

  static bool available = false;

  static Future<void>? _pending;

  /// 发起初始化（幂等：已在进行 / 已完成则返回同一个 Future）。
  /// [initializer] / [timeout] 仅供测试注入；默认调 `RiveNative.init()`，超时 3s。
  static Future<void> init({
    Future<bool> Function()? initializer,
    Duration timeout = const Duration(seconds: 3),
  }) {
    return _pending ??= _run(initializer ?? RiveNative.init, timeout);
  }

  static Future<void> _run(Future<bool> Function() initializer, Duration timeout) async {
    try {
      final ok = await initializer().timeout(timeout);
      available = ok;
      if (!ok) debugPrint('RiveRuntime.init returned false, ceremony disabled');
    } catch (e) {
      available = false;
      debugPrint('RiveRuntime.init failed, ceremony disabled: $e');
    }
  }

  /// 要播动画时调用：后台初始化通常早已完成，直接返回结果；
  /// 还没完成就最多再等 [wait]（默认 500ms），等不到按不可用处理——不让签到卡在这里。
  /// 从未发起过初始化（如测试直接设置 [available]）时直接返回 [available]。
  static Future<bool> ensureReady({Duration wait = const Duration(milliseconds: 500)}) async {
    final pending = _pending;
    if (pending == null || available) return available;
    try {
      await pending.timeout(wait);
    } catch (_) {
      // 超时：本次不播；后台初始化继续，下次签到可能已就绪。
    }
    return available;
  }

  @visibleForTesting
  static void resetForTest() {
    available = false;
    _pending = null;
  }
}

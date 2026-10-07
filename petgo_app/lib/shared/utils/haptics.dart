import 'package:flutter/services.dart';

/// 庆祝振动通道：原生直接驱动 `Vibrator`，绕过系统「触感反馈」开关（见 android MainActivity.kt）。
const MethodChannel _hapticsChannel = MethodChannel('petgo/haptics');

/// 触发一次短振动；无原生实现（iOS 等）回退系统 HapticFeedback。
///
/// 里程碑庆祝页（Story 8.5）与护照整页落章（V1.3.2 Story 1.3）共用 —— 提到 shared，不复制两份。
Future<void> celebrationVibrate() async {
  try {
    await _hapticsChannel.invokeMethod<void>('vibrate', {'ms': 45});
  } catch (_) {
    try {
      await HapticFeedback.vibrate();
    } catch (_) {}
  }
}

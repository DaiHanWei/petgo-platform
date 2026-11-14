import 'dart:async';
import 'dart:ui' show Offset;

import 'package:flutter/foundation.dart' show ValueChanged, VoidCallback;
import 'package:flutter/gestures.dart' show kDoubleTapSlop;

import 'lightbox_gestures.dart' show kLightboxSingleTapDelay;

/// Feed 图片区单击 / 双击仲裁（V1.5.0 FR-209）。窗口复用大图页的 [kLightboxSingleTapDelay]（220ms），不另定时长。
///
/// 🔴 **不用 `GestureDetector.onDoubleTap`**：同时注册 `onTap` + `onDoubleTap` 时单击要等满 `kDoubleTapTimeout`
/// （300ms）才触发；PRD 定的是 220ms（与大图页一致）。大图页的判定逻辑不动，这里只复用常量。
///
/// 规则：
/// 1. 第一下抬起 → 起 [window] 计时；到期无第二下 → [onSingleTap]（进详情）。
/// 2. 窗口内第二下且距离 ≤ [slop] → 撤销计时、[onDoubleTap]（第二下位置），并进入 [window] 长的「吞点」期：
///    期内所有点击忽略（三连击只赞一次、不进详情）。
/// 3. 窗口内第二下但距离 > [slop] → 第一下**立即**按单击执行，第二下作为新的第一下重新计时。
/// 4. [cancel]：撤销待执行的单击（滚动 / 横滑 / 长按开始时调用）。
///
/// 纯逻辑、无 Widget 依赖；计时用 `Timer`，测试用 `fakeAsync`。
class FeedTapArbiter {
  FeedTapArbiter({
    required this.onSingleTap,
    required this.onDoubleTap,
    this.window = kLightboxSingleTapDelay,
    this.slop = kDoubleTapSlop,
  });

  final VoidCallback onSingleTap;
  final ValueChanged<Offset> onDoubleTap;
  final Duration window;
  final double slop;

  Timer? _pending;
  Offset? _first;
  Timer? _swallow;

  /// 有一个待执行的单击（测试 / 调试用）。
  bool get hasPendingSingleTap => _pending != null;

  void tapUp(Offset globalPosition) {
    if (_swallow != null) return; // 双击后的吞点期：三连击的第三下忽略
    final first = _first;
    if (_pending != null && first != null) {
      _clearPending();
      if ((globalPosition - first).distance <= slop) {
        _swallow = Timer(window, () => _swallow = null);
        onDoubleTap(globalPosition);
        return;
      }
      // 两下离得太远：第一下立即按单击执行，第二下作为新的第一下。
      onSingleTap();
    }
    _first = globalPosition;
    _pending = Timer(window, () {
      _pending = null;
      _first = null;
      onSingleTap();
    });
  }

  /// 撤销待执行的单击（不影响吞点期）。
  void cancel() => _clearPending();

  void dispose() {
    _clearPending();
    _swallow?.cancel();
    _swallow = null;
  }

  void _clearPending() {
    _pending?.cancel();
    _pending = null;
    _first = null;
  }
}

import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';

/// 轻量 toast（root Overlay 浮层）——全 App 短提示统一入口，替代 SnackBar。
///
/// 相比 SnackBar：挂在 **root Overlay** 而非 Scaffold，**永不影响布局**（不会顶起底部「＋」发布按钮），
/// 可盖在任意页面/弹窗之上。深色胶囊 + 淡入淡出，约 2.6s 自动消失；**单实例**：新 toast 自动替换旧的。
///
/// 用法：`showAppToast(context, '文案')`。若提示在 `await` 之后触发且不确定 context 是否还挂载，
/// 先在 await 前 `final overlay = Overlay.of(context, rootOverlay: true)`，再 `showAppToastOnOverlay(overlay, ...)`。

OverlayEntry? _current;
Timer? _timer;

/// [top]：把 toast 放到**屏幕顶部**而不是默认的底部。
///
/// V1.1.4 Story 2.2 加的，只为一种场景：**底部抽屉正开着、而提示要谈的就是这个抽屉里的事**
/// （举报提交失败 → 抽屉保持打开让用户重试）。toast 挂 root Overlay 所以 z 序在抽屉之上不会被遮，
/// 但默认那个 `bottom + 90` 的位置正好压在抽屉的按钮区上，用户会以为提示是按钮的一部分。
/// **默认 false 时行为与改动前逐字节一致**，既有 30+ 处调用零影响。
void showAppToast(BuildContext context, String message,
    {Duration duration = const Duration(milliseconds: 2600), bool top = false}) {
  final overlay = Overlay.maybeOf(context, rootOverlay: true);
  if (overlay == null) return;
  showAppToastOnOverlay(overlay, message, duration: duration, top: top);
}

void showAppToastOnOverlay(OverlayState overlay, String message,
    {Duration duration = const Duration(milliseconds: 2600), bool top = false}) {
  _dismiss();
  final entry = OverlayEntry(
    builder: (_) => _ToastWidget(message: message, duration: duration, top: top),
  );
  _current = entry;
  overlay.insert(entry);
  _timer = Timer(duration, _dismiss);
}

void _dismiss() {
  _timer?.cancel();
  _timer = null;
  final entry = _current;
  _current = null;
  if (entry != null && entry.mounted) entry.remove();
}

class _ToastWidget extends StatefulWidget {
  const _ToastWidget({required this.message, required this.duration, this.top = false});

  final String message;
  final Duration duration;

  /// true = 贴屏幕顶部（避开正开着的底部抽屉）；false = 既有的底部位置。
  final bool top;

  @override
  State<_ToastWidget> createState() => _ToastWidgetState();
}

class _ToastWidgetState extends State<_ToastWidget> with SingleTickerProviderStateMixin {
  late final AnimationController _fade =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 200))..forward();
  Timer? _out;

  @override
  void initState() {
    super.initState();
    // 在自动移除前 220ms 开始淡出（移除由外部 timer 统一负责）。
    final outAt = widget.duration - const Duration(milliseconds: 220);
    _out = Timer(outAt.isNegative ? Duration.zero : outAt, () {
      if (mounted) _fade.reverse();
    });
  }

  @override
  void dispose() {
    _out?.cancel();
    _fade.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // 底距 = 安全区 + 键盘高度(viewInsets) + 90。键盘弹起时(如评论区)必须叠加 viewInsets.bottom，
    // 否则 toast 停在固定低位被键盘/输入框盖住看不见（bug 20260702-232）；键盘收起时 viewInsets=0，行为不变。
    final mq = MediaQuery.of(context);
    final bottom = mq.padding.bottom + mq.viewInsets.bottom + 90;
    return Positioned(
      left: 24,
      right: 24,
      // 顶部变体：安全区 + 16（避开刘海/状态栏）。底部那套算法一字未动。
      top: widget.top ? mq.padding.top + 16 : null,
      bottom: widget.top ? null : bottom,
      child: IgnorePointer(
        // 包一层透明 Material：否则 Overlay 里的 Text 会显示黄色双下划线（无 Material 上下文）。
        child: Material(
          type: MaterialType.transparency,
          child: Center(
            child: FadeTransition(
              opacity: _fade,
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 360),
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 11),
                  decoration: BoxDecoration(
                    color: AppColors.ink.withValues(alpha: 0.94),
                    borderRadius: BorderRadius.circular(12),
                    boxShadow: const [
                      BoxShadow(color: Color(0x33000000), blurRadius: 16, offset: Offset(0, 6)),
                    ],
                  ),
                  child: Text(
                    widget.message,
                    textAlign: TextAlign.center,
                    maxLines: 3,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        color: Colors.white,
                        fontSize: 14,
                        height: 1.3,
                        decoration: TextDecoration.none),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

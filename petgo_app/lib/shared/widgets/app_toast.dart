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

void showAppToast(BuildContext context, String message,
    {Duration duration = const Duration(milliseconds: 2600)}) {
  final overlay = Overlay.maybeOf(context, rootOverlay: true);
  if (overlay == null) return;
  showAppToastOnOverlay(overlay, message, duration: duration);
}

void showAppToastOnOverlay(OverlayState overlay, String message,
    {Duration duration = const Duration(milliseconds: 2600)}) {
  _dismiss();
  final entry = OverlayEntry(
    builder: (_) => _ToastWidget(message: message, duration: duration),
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
  const _ToastWidget({required this.message, required this.duration});

  final String message;
  final Duration duration;

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
    final bottom = MediaQuery.of(context).padding.bottom + 90;
    return Positioned(
      left: 24,
      right: 24,
      bottom: bottom,
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

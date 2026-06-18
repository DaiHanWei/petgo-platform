import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';

/// 在线脉冲点（原型咨询/问诊在线态绿点）：[pulsing] 为真时外环呼吸放大淡出，
/// 否则静态实心点。用 [AnimationController] 自绘，不引三方动画库。
class OnlinePulseDot extends StatefulWidget {
  const OnlinePulseDot({
    super.key,
    this.size = 7,
    this.color = AppColors.triageGreen,
    this.pulsing = true,
  });

  final double size;
  final Color color;
  final bool pulsing;

  @override
  State<OnlinePulseDot> createState() => _OnlinePulseDotState();
}

class _OnlinePulseDotState extends State<OnlinePulseDot>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;

  @override
  void initState() {
    super.initState();
    _controller =
        AnimationController(vsync: this, duration: const Duration(milliseconds: 1400));
    if (widget.pulsing) _controller.repeat();
  }

  @override
  void didUpdateWidget(OnlinePulseDot old) {
    super.didUpdateWidget(old);
    if (widget.pulsing && !_controller.isAnimating) {
      _controller.repeat();
    } else if (!widget.pulsing && _controller.isAnimating) {
      _controller.stop();
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final dot = Container(
      width: widget.size,
      height: widget.size,
      decoration: BoxDecoration(color: widget.color, shape: BoxShape.circle),
    );
    if (!widget.pulsing) return dot;
    return SizedBox(
      width: widget.size * 2.4,
      height: widget.size * 2.4,
      child: AnimatedBuilder(
        animation: _controller,
        builder: (context, child) {
          final t = _controller.value;
          return Stack(
            alignment: Alignment.center,
            children: [
              Container(
                width: widget.size + (widget.size * 1.4) * t,
                height: widget.size + (widget.size * 1.4) * t,
                decoration: BoxDecoration(
                  color: widget.color.withValues(alpha: (1 - t) * 0.4),
                  shape: BoxShape.circle,
                ),
              ),
              child!,
            ],
          );
        },
        child: dot,
      ),
    );
  }
}

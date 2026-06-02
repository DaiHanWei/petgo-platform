import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';

/// 动效分享 FAB（Story 2.7 · F1）。首访触发 scale pulse + ring ripple，之后静态；
/// pinned 不随滚动消失（置于 Scaffold.floatingActionButton）。
///
/// [animate] 由调用方据 prefs「首访标记」决定；动效播完回调 [onAnimationShown] 持久化标记。
class ShareFab extends StatefulWidget {
  const ShareFab({
    super.key,
    required this.onPressed,
    required this.semanticLabel,
    this.animate = false,
    this.onAnimationShown,
  });

  final VoidCallback onPressed;
  final String semanticLabel;
  final bool animate;
  final VoidCallback? onAnimationShown;

  @override
  State<ShareFab> createState() => _ShareFabState();
}

class _ShareFabState extends State<ShareFab> with SingleTickerProviderStateMixin {
  late final AnimationController _controller;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(vsync: this, duration: const Duration(milliseconds: 900));
    if (widget.animate) {
      _controller.forward().whenComplete(() => widget.onAnimationShown?.call());
    } else {
      _controller.value = 1.0; // 复访静态
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _controller,
      builder: (context, child) {
        // ring ripple：0→1 放大并淡出；scale pulse：1→1.15→1。
        final t = _controller.value;
        final ringScale = 1.0 + t * 0.8;
        final ringOpacity = widget.animate ? (1.0 - t) * 0.4 : 0.0;
        final pulse = 1.0 + (widget.animate ? (0.15 * (1 - (2 * t - 1).abs())) : 0.0);
        return Stack(
          alignment: Alignment.center,
          children: [
            if (ringOpacity > 0)
              Transform.scale(
                scale: ringScale,
                child: Container(
                  width: 56,
                  height: 56,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: AppColors.accentGrowth.withValues(alpha: ringOpacity),
                  ),
                ),
              ),
            Transform.scale(scale: pulse, child: child),
          ],
        );
      },
      child: Semantics(
        button: true,
        label: widget.semanticLabel,
        child: FloatingActionButton(
          key: const ValueKey('shareFab'),
          backgroundColor: AppColors.accentGrowth,
          foregroundColor: AppColors.onAccent,
          onPressed: widget.onPressed,
          child: const Icon(Icons.share),
        ),
      ),
    );
  }
}

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../../../../core/theme/colors.dart';

/// 动效分享按钮（Story 2.7 · F1）。首访触发 scale pulse + ring ripple，之后静态。
///
/// 两种形态，动效与分享锚点逻辑共用一份：
/// - **默认（56px 紫渐变圆）**：置于 `Scaffold.floatingActionButton`，pinned 不随滚动消失。
/// - **[compact]（38px 白底圆角）**：嵌在 Diary 页头标题行、编辑铅笔旁边
///   （2026-08-04 用户要求：悬浮 FAB 会盖住时间线与日历右下角的内容）。
///
/// [animate] 由调用方据 prefs「首访标记」决定；动效播完回调 [onAnimationShown] 持久化标记。
class ShareFab extends StatefulWidget {
  const ShareFab({
    super.key,
    required this.onPressed,
    required this.semanticLabel,
    this.animate = false,
    this.onAnimationShown,
    this.compact = false,
  });

  /// 点击回调，携按钮全局矩形作 iOS 分享面板锚点（[sharePositionOrigin]，bug 20260707）。
  final void Function(Rect origin) onPressed;
  final String semanticLabel;
  final bool animate;
  final VoidCallback? onAnimationShown;

  /// 紧凑形态：38×38 白底圆角小图标，与页头编辑按钮同款，不悬浮在内容之上。
  final bool compact;

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
    final size = widget.compact ? 38.0 : 56.0;
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
                  width: size,
                  height: size,
                  decoration: BoxDecoration(
                    shape: widget.compact ? BoxShape.rectangle : BoxShape.circle,
                    borderRadius: widget.compact ? BorderRadius.circular(11) : null,
                    color: AppColors.mint.withValues(alpha: ringOpacity),
                  ),
                ),
              ),
            Transform.scale(scale: pulse, child: child),
          ],
        );
      },
      // 默认：原型 145° 三段紫渐变 FAB（#9E83DA→#845EC9→#6C48AE），圆形 56px。
      // compact：与页头编辑按钮同款白底圆角 11 + 柔阴影（`.ibtn`），38px。
      child: Semantics(
        button: true,
        label: widget.semanticLabel,
        child: Container(
          key: const ValueKey('shareFab'),
          width: size,
          height: size,
          decoration: widget.compact
              ? BoxDecoration(
                  color: AppColors.card,
                  borderRadius: BorderRadius.circular(11),
                  boxShadow: const [
                    BoxShadow(color: Color(0x12162233), offset: Offset(0, 2), blurRadius: 8),
                  ],
                )
              : BoxDecoration(
                  shape: BoxShape.circle,
                  gradient: const LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [AppColors.mint500, AppColors.mint, AppColors.mint600],
                    stops: [0.0, 0.55, 1.0],
                  ),
                  boxShadow: [
                    BoxShadow(
                        color: Colors.black.withValues(alpha: 0.22),
                        blurRadius: 16,
                        offset: const Offset(0, 4)),
                    BoxShadow(
                        color: Colors.black.withValues(alpha: 0.12),
                        blurRadius: 6,
                        offset: const Offset(0, 2)),
                  ],
                ),
          child: Material(
            color: Colors.transparent,
            shape: widget.compact
                ? RoundedRectangleBorder(borderRadius: BorderRadius.circular(11))
                : const CircleBorder(),
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              onTap: () {
                // 按按钮的全局矩形作 iOS 分享面板锚点（缺失会导致 iOS 不弹/崩，bug 20260707）。
                final box = context.findRenderObject() as RenderBox?;
                final origin = (box != null && box.hasSize)
                    ? box.localToGlobal(Offset.zero) & box.size
                    : Rect.zero;
                widget.onPressed(origin);
              },
              child: Center(
                child: SvgPicture.asset(
                  'assets/brand/ic_link.svg',
                  width: widget.compact ? 18 : 24,
                  height: widget.compact ? 18 : 24,
                  colorFilter: ColorFilter.mode(
                      widget.compact ? AppColors.ink : AppColors.onAccent, BlendMode.srcIn),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/shadows.dart';

/// Duolingo 式立体按钮变体（对应 prototype `.btn-primary/.btn-soft/.btn-ghost`）。
enum Btn3dVariant { primary, soft, ghost }

/// 立体「下唇」按钮。
///
/// 静态时露出底部硬边色唇（offset 4px），按下时唇收到 1px、按钮面下沉 3px，
/// 底边保持不动 —— 复刻 prototype 的 `:active { transform: translateY(3px) }`。
class Btn3d extends StatefulWidget {
  const Btn3d({
    super.key,
    required this.child,
    this.onPressed,
    this.variant = Btn3dVariant.primary,
    this.expand = false,
    this.padding,
    this.fontSize,
    this.borderRadius,
  });

  final Widget child;
  final VoidCallback? onPressed;
  final Btn3dVariant variant;
  final bool expand;
  final EdgeInsets? padding;
  final double? fontSize;
  final double? borderRadius;

  bool get _enabled => onPressed != null;

  @override
  State<Btn3d> createState() => _Btn3dState();
}

class _Btn3dState extends State<Btn3d> {
  bool _down = false;

  @override
  Widget build(BuildContext context) {
    final v = widget.variant;
    final ghost = v == Btn3dVariant.ghost;
    final radius = widget.borderRadius ?? (ghost ? 14 : 16);
    final lipFull = ghost ? 0.0 : 4.0; // ghost 无下唇，仅平移
    final lipDown = ghost ? 0.0 : 1.0;
    final travel = ghost ? 2.0 : 3.0; // 按下平移量

    final Color bg;
    final Color fg;
    final Color lip;
    switch (v) {
      case Btn3dVariant.primary:
        bg = AppColors.mint;
        fg = Colors.white;
        lip = AppColors.mint600;
        break;
      case Btn3dVariant.soft:
        bg = AppColors.card;
        fg = AppColors.mint700;
        lip = AppColors.line;
        break;
      case Btn3dVariant.ghost:
        bg = AppColors.mintTint;
        fg = AppColors.mint700;
        lip = Colors.transparent;
        break;
    }

    final pressed = _down && widget._enabled;
    final lipH = pressed ? lipDown : lipFull;
    final dy = pressed ? travel : 0.0;

    final pad = widget.padding ??
        (ghost
            ? const EdgeInsets.symmetric(horizontal: 18, vertical: 12)
            : const EdgeInsets.symmetric(horizontal: 22, vertical: 15));

    final shadows = <BoxShadow>[
      if (lipH > 0) BoxShadow(color: lip, offset: Offset(0, lipH), blurRadius: 0),
      if (v == Btn3dVariant.primary) ...AppShadows.mint,
      if (v == Btn3dVariant.soft) ...AppShadows.sm,
    ];

    final face = AnimatedContainer(
      duration: const Duration(milliseconds: 60),
      curve: Curves.easeOut,
      transform: Matrix4.translationValues(0, dy, 0),
      padding: pad,
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(radius),
        boxShadow: shadows,
      ),
      child: DefaultTextStyle.merge(
        style: TextStyle(
          color: fg,
          fontWeight: ghost ? FontWeight.w700 : FontWeight.w800,
          fontSize: widget.fontSize ?? 17,
          letterSpacing: 0.2,
        ),
        child: IconTheme.merge(
          data: IconThemeData(color: fg),
          child: Center(widthFactor: widget.expand ? null : 1, child: widget.child),
        ),
      ),
    );

    return Opacity(
      opacity: widget._enabled ? 1 : 0.45,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTapDown: widget._enabled ? (_) => setState(() => _down = true) : null,
        onTapUp: widget._enabled ? (_) => setState(() => _down = false) : null,
        onTapCancel: widget._enabled ? () => setState(() => _down = false) : null,
        onTap: widget.onPressed,
        // 预留底部 4px 给下唇，避免裁切
        child: Padding(
          padding: const EdgeInsets.only(bottom: 4),
          child: widget.expand ? SizedBox(width: double.infinity, child: face) : face,
        ),
      ),
    );
  }
}

import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';

/// 答题文字选项（V1.3.2 Story 2.3 · AC3.4）：热区 ≥44、按压 scale(0.96)；
/// 选中态 = 主色 1.5px 描边 + 浅紫底 + 字重加粗。
class TsOptionTile extends StatefulWidget {
  const TsOptionTile({super.key, required this.label, required this.selected, required this.onTap});

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  State<TsOptionTile> createState() => _TsOptionTileState();
}

class _TsOptionTileState extends State<TsOptionTile> {
  bool _pressed = false;

  @override
  Widget build(BuildContext context) {
    final selected = widget.selected;
    return Semantics(
      button: true,
      selected: selected,
      child: Listener(
        onPointerDown: (_) => setState(() => _pressed = true),
        onPointerUp: (_) => setState(() => _pressed = false),
        onPointerCancel: (_) => setState(() => _pressed = false),
        child: AnimatedScale(
          scale: _pressed ? 0.96 : 1,
          duration: const Duration(milliseconds: 90),
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: widget.onTap,
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 120),
              constraints: const BoxConstraints(minHeight: 48),
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
              alignment: Alignment.centerLeft,
              decoration: BoxDecoration(
                color: selected ? AppColors.mintTint : AppColors.card,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: selected ? AppColors.mint : AppColors.line, width: selected ? 1.5 : 1),
              ),
              child: Text(
                widget.label,
                style: TextStyle(
                  fontSize: 14,
                  height: 1.35,
                  color: AppColors.ink,
                  fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

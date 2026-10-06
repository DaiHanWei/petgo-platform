import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';

/// 主人类型选择器的固定行序（UI 稿 A12）。**只有四字母**：无别名、无「跳过 / 我不知道」、不做逐轴四步选择（AD-20）。
const List<List<String>> kTsTypeGrid = [
  ['ENTJ', 'ENTP', 'ENFJ', 'ENFP'],
  ['ESTJ', 'ESTP', 'ESFJ', 'ESFP'],
  ['INTJ', 'INTP', 'INFJ', 'INFP'],
  ['ISTJ', 'ISTP', 'ISFJ', 'ISFP'],
];

/// 4×4 类型网格（V1.3.2 Story 2.5 · AC4）。同一时刻只一格选中；选中不跳，由页面的确认按钮提交。
class TsTypeSelector extends StatelessWidget {
  const TsTypeSelector({super.key, required this.selected, required this.onSelect});

  final String? selected;
  final ValueChanged<String> onSelect;

  @override
  Widget build(BuildContext context) {
    return Column(
      key: const ValueKey('tsTypeSelector'),
      children: [
        for (var r = 0; r < kTsTypeGrid.length; r++) ...[
          if (r > 0) const SizedBox(height: 8),
          Row(
            children: [
              for (var c = 0; c < 4; c++) ...[
                if (c > 0) const SizedBox(width: 8),
                Expanded(
                  child: _TypeCell(
                    code: kTsTypeGrid[r][c],
                    selected: selected == kTsTypeGrid[r][c],
                    onTap: () => onSelect(kTsTypeGrid[r][c]),
                  ),
                ),
              ],
            ],
          ),
        ],
      ],
    );
  }
}

class _TypeCell extends StatefulWidget {
  const _TypeCell({required this.code, required this.selected, required this.onTap});

  final String code;
  final bool selected;
  final VoidCallback onTap;

  @override
  State<_TypeCell> createState() => _TypeCellState();
}

class _TypeCellState extends State<_TypeCell> {
  bool _pressed = false;

  @override
  Widget build(BuildContext context) {
    final sel = widget.selected;
    return Semantics(
      button: true,
      selected: sel,
      label: widget.code,
      excludeSemantics: true,
      child: Listener(
        onPointerDown: (_) => setState(() => _pressed = true),
        onPointerUp: (_) => setState(() => _pressed = false),
        onPointerCancel: (_) => setState(() => _pressed = false),
        child: AnimatedScale(
          scale: _pressed ? 0.96 : 1,
          duration: const Duration(milliseconds: 90),
          child: GestureDetector(
            key: ValueKey('tsTypeCell_${widget.code}'),
            behavior: HitTestBehavior.opaque,
            onTap: widget.onTap,
            child: Container(
              height: 52,
              decoration: BoxDecoration(
                color: sel ? AppColors.mintTint : AppColors.card,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: sel ? AppColors.mint : AppColors.line, width: sel ? 1.5 : 1),
              ),
              // 字母分列排布：每个字母独立一格字距。
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  for (final ch in widget.code.split(''))
                    SizedBox(
                      width: 14,
                      child: Text(ch,
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            fontSize: 15,
                            fontWeight: sel ? FontWeight.w800 : FontWeight.w600,
                            color: sel ? AppColors.mint : AppColors.ink,
                          )),
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

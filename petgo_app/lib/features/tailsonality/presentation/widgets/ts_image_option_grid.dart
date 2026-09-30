import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';

/// 图片题 2×2 网格（V1.3.2 Story 2.3 · AC3.5）：**每格 = 图（4:3）+ 下方文字标签**，同一可点区域（PRD §3.2）。
///
/// 图路径 `assets/tailsonality/quiz_<group>_<i>.webp`。**素材未入库**（D-21）：图缺失时回落代码绘制的占位
/// （浅紫底 + 该组图标），文字标签照常显示，答题不受影响；素材到货同名放入即生效，不改代码。
class TsImageOptionGrid extends StatelessWidget {
  const TsImageOptionGrid({
    super.key,
    required this.group,
    required this.labels,
    required this.selected,
    required this.onSelect,
  });

  /// `p1` | `p2` | `p3`。
  final String group;

  /// 4 个文字标签，下标 = 原始选项序号。
  final List<String> labels;
  final int? selected;
  final ValueChanged<int> onSelect;

  static String assetFor(String group, int i) => 'assets/tailsonality/quiz_${group}_$i.webp';

  static IconData iconFor(String group) => switch (group) {
        'p1' => Icons.place_outlined,
        'p2' => Icons.bolt_outlined,
        _ => Icons.schedule_outlined,
      };

  @override
  Widget build(BuildContext context) {
    Widget cell(int i) {
      final isSel = selected == i;
      return Semantics(
        button: true,
        selected: isSel,
        label: labels[i],
        excludeSemantics: true,
        child: GestureDetector(
          key: ValueKey('tsImageOption_${group}_$i'),
          behavior: HitTestBehavior.opaque,
          onTap: () => onSelect(i),
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 120),
            padding: const EdgeInsets.all(6),
            decoration: BoxDecoration(
              color: isSel ? AppColors.mintTint : AppColors.card,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: isSel ? AppColors.mint : AppColors.line, width: isSel ? 1.5 : 1),
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                AspectRatio(
                  aspectRatio: 4 / 3,
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: Image.asset(
                      assetFor(group, i),
                      fit: BoxFit.cover,
                      errorBuilder: (_, _, _) => Container(
                        key: ValueKey('tsImagePlaceholder_${group}_$i'),
                        color: AppColors.violet100,
                        alignment: Alignment.center,
                        child: Icon(iconFor(group), size: 28, color: AppColors.mint),
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  labels[i],
                  maxLines: 2,
                  textAlign: TextAlign.center,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 12.5,
                    color: AppColors.ink,
                    fontWeight: isSel ? FontWeight.w700 : FontWeight.w500,
                  ),
                ),
              ],
            ),
          ),
        ),
      );
    }

    Widget row(int a, int b) => IntrinsicHeight(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [Expanded(child: cell(a)), const SizedBox(width: 10), Expanded(child: cell(b))],
          ),
        );

    return Column(children: [row(0, 1), const SizedBox(height: 10), row(2, 3)]);
  }
}

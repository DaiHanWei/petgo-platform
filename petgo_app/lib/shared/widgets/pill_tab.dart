import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/spacing.dart';

/// 筛选胶囊（从 Discover 分类 Tab `feed_tab_row.dart` 的私有 `_Tab` 抽出，V1.4.2 social-topic · Story 1.5 · AC1.2）。
///
/// 只抽**外观**：选中 = 品牌紫底白字；未选 = `card` 底 + 1.5 `line` 框、次级灰字；圆角 999、内边距 13 / 7、12 / w600。
/// [count] 非 null 时在文字后隔 4（`xs`）渲染数字（w700、`tabularFigures`，UI 稿 `.lA-chips span b`；与文字分开、不拼进 arb）。
/// [count] 为 null → 与抽取前的 Discover 胶囊逐像素一致。
class PillTab extends StatelessWidget {
  const PillTab({super.key, required this.label, required this.active, required this.onTap, this.count});

  final String label;
  final bool active;
  final VoidCallback onTap;
  final int? count;

  @override
  Widget build(BuildContext context) {
    final style = TextStyle(
      fontSize: 12,
      fontWeight: FontWeight.w600,
      color: active ? Colors.white : AppColors.textSecondary,
    );
    return Semantics(
      selected: active,
      button: true,
      child: GestureDetector(
        onTap: onTap,
        behavior: HitTestBehavior.opaque,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 7),
          decoration: BoxDecoration(
            color: active ? AppColors.mint : AppColors.card,
            borderRadius: BorderRadius.circular(999),
            border: Border.all(color: active ? AppColors.mint : AppColors.line, width: 1.5),
          ),
          child: count == null
              ? Text(label, style: style)
              : Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(label, style: style),
                    const SizedBox(width: AppSpacing.xs),
                    Text('$count',
                        style: style.copyWith(
                            fontWeight: FontWeight.w700, fontFeatures: const [FontFeature.tabularFigures()])),
                  ],
                ),
        ),
      ),
    );
  }
}

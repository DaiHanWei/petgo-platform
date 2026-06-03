import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../domain/feed_item.dart';

/// 首页分类 Tab Row（Story 3.2，UX-DR5）。
///
/// active 区域色 2px 下划线 + 文字色；内容区 cross-fade 由上层 [AnimatedSwitcher] 负责（不 slide）。
class FeedTabRow extends StatelessWidget {
  const FeedTabRow({
    super.key,
    required this.selected,
    required this.labels,
    required this.onSelected,
  });

  final FeedCategory selected;

  /// 各分类的本地化标签（来自 .arb）。
  final Map<FeedCategory, String> labels;
  final ValueChanged<FeedCategory> onSelected;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
      child: Row(
        children: [
          for (final category in FeedCategory.values)
            _Tab(
              label: labels[category] ?? category.wire,
              active: category == selected,
              onTap: () => onSelected(category),
            ),
        ],
      ),
    );
  }
}

class _Tab extends StatelessWidget {
  const _Tab({required this.label, required this.active, required this.onTap});

  final String label;
  final bool active;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      selected: active,
      button: true,
      // 用 GestureDetector 而非 InkWell：分类切换靠下划线+文字色表达，去掉点击灰色水波/高亮块。
      child: GestureDetector(
        onTap: onTap,
        behavior: HitTestBehavior.opaque,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md, vertical: AppSpacing.sm),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                label,
                style: active
                    ? AppTypography.body.copyWith(
                        color: AppColors.accentGrowth, fontWeight: FontWeight.w600)
                    : AppTypography.body.copyWith(color: AppColors.textSecondary),
              ),
              const SizedBox(height: AppSpacing.xs),
              // active 区域色 2px 下划线；非 active 透明占位保持高度稳定。
              Container(
                height: 2,
                width: 24,
                color: active ? AppColors.accentGrowth : Colors.transparent,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

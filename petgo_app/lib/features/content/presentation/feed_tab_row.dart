import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../domain/feed_item.dart';

/// 首页分类 Tab Row（Story 3.2 · 原型 feed.html chips 换肤）。
///
/// 原型 pill chips：选中=紫底白字；未选=白底 #E6E6E6 边框灰字。横向可滚。
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
          for (final category in FeedCategory.values) ...[
            _Tab(
              label: labels[category] ?? category.wire,
              active: category == selected,
              onTap: () => onSelected(category),
            ),
            const SizedBox(width: 7),
          ],
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
          child: Text(
            label,
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              color: active ? Colors.white : AppColors.textSecondary,
            ),
          ),
        ),
      ),
    );
  }
}

import 'package:flutter/material.dart';

import '../../../core/theme/spacing.dart';
import '../../../shared/widgets/pill_tab.dart';
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
          // bug 510（产品新决策）：首页加回 Diary(GROWTH_MOMENT) 筛选，按枚举顺序
          // Semua · Momen · Diary · Edukasi & Tips。
          for (final category in FeedCategory.values) ...[
            // V1.4.2 social-topic Story 1.5：外观抽成公共 [PillTab]（关注列表 Pengguna / Hewan 筛选复用），像素不变。
            PillTab(
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

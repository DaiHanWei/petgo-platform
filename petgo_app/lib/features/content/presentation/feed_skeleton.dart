import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/rounded.dart';
import '../../../core/theme/spacing.dart';

/// Feed 加载骨架屏（Story 3.2，UX-DR9）。灰底占位块，2 列瀑布布局，等高变体模拟不等高卡片。
class FeedSkeleton extends StatelessWidget {
  const FeedSkeleton({super.key});

  static const List<double> _heights = [160, 120, 200, 140, 180, 110];

  @override
  Widget build(BuildContext context) {
    final left = <Widget>[];
    final right = <Widget>[];
    for (var i = 0; i < _heights.length; i++) {
      final tile = _SkeletonTile(height: _heights[i]);
      (i.isEven ? left : right).add(tile);
    }
    return SingleChildScrollView(
      physics: const NeverScrollableScrollPhysics(),
      padding: const EdgeInsets.all(AppSpacing.screenEdge),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(child: Column(children: left)),
          const SizedBox(width: AppSpacing.sm),
          Expanded(child: Column(children: right)),
        ],
      ),
    );
  }
}

class _SkeletonTile extends StatelessWidget {
  const _SkeletonTile({required this.height});

  final double height;

  @override
  Widget build(BuildContext context) {
    return Container(
      height: height,
      margin: const EdgeInsets.only(bottom: AppSpacing.sm),
      decoration: BoxDecoration(
        color: AppColors.border,
        borderRadius: AppRounded.phoneRadius,
      ),
    );
  }
}

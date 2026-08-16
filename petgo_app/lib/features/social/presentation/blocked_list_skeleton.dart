import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';

/// 黑名单加载骨架屏（Story 1.5 AC4）。
///
/// 行结构已知（头像圆 + 昵称条 + 时间条 + 右侧按钮块），先把形状占住，数据到达时**不跳版**。
/// 照 `feed_skeleton.dart` 的灰条范式：**静态灰块，无 shimmer 动画、无第三方包**
/// （`FeedSkeleton` 本身是 feed 卡形状，复用不了，只能同款重写一份）。
class BlockedListSkeleton extends StatelessWidget {
  const BlockedListSkeleton({super.key});

  @override
  Widget build(BuildContext context) {
    return ListView(
      physics: const NeverScrollableScrollPhysics(),
      padding: const EdgeInsets.all(AppSpacing.screenEdge),
      children: const [
        _SkeletonRow(),
        _SkeletonRow(),
        _SkeletonRow(),
        _SkeletonRow(),
      ],
    );
  }
}

class _SkeletonRow extends StatelessWidget {
  const _SkeletonRow();

  static Widget _block(double w, double h, {double radius = 6}) => Container(
        width: w,
        height: h,
        decoration: BoxDecoration(
          color: AppColors.border,
          borderRadius: BorderRadius.circular(radius),
        ),
      );

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Row(
        children: [
          _block(40, 40, radius: 20),
          const SizedBox(width: 12),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _block(120, 14),
              const SizedBox(height: 8),
              _block(80, 11),
            ],
          ),
          const Spacer(),
          _block(84, 32, radius: 10),
        ],
      ),
    );
  }
}

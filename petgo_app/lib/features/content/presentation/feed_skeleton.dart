import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/rounded.dart';
import '../../../core/theme/spacing.dart';

/// Feed 加载骨架屏（Story 3.2，UX-DR9）。单列全宽卡占位（对齐 feed.html 单列卡流），
/// 每卡＝头像圆 + 两行文本条 + 全宽图块。
class FeedSkeleton extends StatelessWidget {
  const FeedSkeleton({super.key});

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      physics: const NeverScrollableScrollPhysics(),
      padding: const EdgeInsets.all(AppSpacing.screenEdge),
      child: Column(
        children: const [
          _SkeletonCard(imageHeight: 180),
          SizedBox(height: 12),
          _SkeletonCard(imageHeight: 140),
          SizedBox(height: 12),
          _SkeletonCard(imageHeight: 200),
        ],
      ),
    );
  }
}

/// 单列卡骨架：头部行（头像 + 名/时间条）+ 正文条 + 全宽图块。
class _SkeletonCard extends StatelessWidget {
  const _SkeletonCard({required this.imageHeight});

  final double imageHeight;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: AppRounded.phoneRadius,
        border: Border.all(color: AppColors.border),
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(AppSpacing.md, AppSpacing.md, AppSpacing.md, 0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    _block(34, 34, radius: 17),
                    const SizedBox(width: AppSpacing.sm),
                    Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        _block(90, 11),
                        const SizedBox(height: 6),
                        _block(54, 10),
                      ],
                    ),
                  ],
                ),
                const SizedBox(height: AppSpacing.sm),
                _block(double.infinity, 11),
                const SizedBox(height: 6),
                _block(180, 11),
                const SizedBox(height: AppSpacing.sm),
              ],
            ),
          ),
          _block(double.infinity, imageHeight, radius: 0),
        ],
      ),
    );
  }

  Widget _block(double w, double h, {double radius = 6}) => Container(
        width: w,
        height: h,
        decoration: BoxDecoration(
          color: AppColors.border,
          borderRadius: BorderRadius.circular(radius),
        ),
      );
}

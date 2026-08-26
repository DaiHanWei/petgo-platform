import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../domain/feed_image_layout.dart';

/// Feed 加载骨架屏（Story 3.2，UX-DR9）。单列全宽卡占位，块序与真实卡片一致：
/// 作者行 → 图片 → 操作行 → 正文条。
///
/// 🔴 骨架屏是卡片样式的**另一份拷贝** —— 不同步改，会在「加载中 → 加载完」之间跳一次布局。
///
/// V1.1.6 Story 3.3 起，图块高度不再写死，而是走与「尺寸未知的卡片」**完全相同**的口径：
/// 按 [kFeedPlaceholderRatio] 预留 + 同一条高度护栏。写死的 180/140/200 与新口径对不上，
/// 会让骨架屏白白多跳一次。
class FeedSkeleton extends StatelessWidget {
  const FeedSkeleton({super.key});

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final aspect = resolveFeedImageAspect(
          size: null, // 骨架屏永远"不知道尺寸"，与存量内容同路
          width: constraints.maxWidth,
          maxImageHeight: FeedCardMetrics.maxImageHeight(constraints.maxHeight),
        );
        return SingleChildScrollView(
          physics: const NeverScrollableScrollPhysics(),
          // 🔴 与卡片保持一致的通栏版式（V1.1.6 Story 3.2）：**去掉左右屏边距**。
          padding: const EdgeInsets.symmetric(vertical: AppSpacing.screenEdge),
          child: Column(
            children: [
              _SkeletonCard(imageAspect: aspect),
              const SizedBox(height: 24),
              _SkeletonCard(imageAspect: aspect),
            ],
          ),
        );
      },
    );
  }
}

/// 单列卡骨架：作者行 → 全宽图块 → 操作行 → 正文条（与 `MasonryCard` 同序）。
class _SkeletonCard extends StatelessWidget {
  const _SkeletonCard({required this.imageAspect});

  final double imageAspect;

  @override
  Widget build(BuildContext context) {
    return Container(
      // 通栏：无圆角、无描边（与 MasonryCard 同步，V1.1.6 Story 3.2）。
      color: AppColors.surface,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 作者行：32 头像 + 名字条（与卡片同尺寸）。
          Padding(
            padding: const EdgeInsets.fromLTRB(
                AppSpacing.screenEdge, 0, AppSpacing.screenEdge, 10),
            child: Row(
              children: [
                _block(32, 32, radius: 16),
                const SizedBox(width: 9),
                _block(90, 12),
              ],
            ),
          ),
          // 图片：通栏出血，比例与"尺寸未知的卡片"一致。
          AspectRatio(aspectRatio: imageAspect, child: _block(double.infinity, null, radius: 0)),
          // 操作行 + 正文条。
          Padding(
            padding: const EdgeInsets.fromLTRB(
                AppSpacing.screenEdge, 10, AppSpacing.screenEdge, 0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(children: [_block(40, 20), const SizedBox(width: 18), _block(40, 20)]),
                const SizedBox(height: 10),
                _block(double.infinity, 11),
                const SizedBox(height: 6),
                _block(180, 11),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _block(double w, double? h, {double radius = 6}) => Container(
        width: w,
        height: h,
        decoration: BoxDecoration(
          color: AppColors.border,
          borderRadius: BorderRadius.circular(radius),
        ),
      );
}

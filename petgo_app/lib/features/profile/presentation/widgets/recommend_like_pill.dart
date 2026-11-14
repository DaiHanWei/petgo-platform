import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../content/presentation/like_button.dart';

/// 大图右上角点赞胶囊（V1.5.0 Story 1.2 / 1.3 · AC7）。
///
/// 🔴 只用 [LikeButton] 的**公开参数**（Feed 双击点赞线会改造它的内部状态机，本线不 fork、不复制）。
/// 热区：LikeButton 关掉数字放大（`expandCountHitArea: false`）后爱心与数字都撑满 44 高；
/// 胶囊底色只画在中间 26 高，视觉尺寸不变。赞数等宽数字（`FontFeature.tabularFigures()`，经 DefaultTextStyle 合并）。
class RecommendLikePill extends StatelessWidget {
  const RecommendLikePill({
    super.key,
    required this.postId,
    required this.initialLiked,
    required this.initialCount,
    required this.source,
    this.onLiked,
    this.loginEntrySource,
  });

  final int postId;
  final bool initialLiked;
  final int initialCount;

  /// [LikeButton.source] / [LikeButton.likersSource] 的埋点来源。
  final String source;

  /// 服务端确认「点赞」（不是取消）后回调一次。
  final VoidCallback? onLiked;

  /// 游客点击时登录引导的 `entry_source`（null = LikeButton 现状）。
  final String? loginEntrySource;

  @override
  Widget build(BuildContext context) {
    return ConstrainedBox(
      key: ValueKey('recommendLikePill_$postId'),
      constraints: const BoxConstraints(minWidth: 44, minHeight: 44),
      child: Stack(
        alignment: Alignment.center,
        children: [
          Positioned(
            left: 4,
            right: 4,
            top: 9,
            bottom: 9,
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: AppColors.surface.withValues(alpha: 0.92),
                borderRadius: BorderRadius.circular(13),
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10),
            child: DefaultTextStyle.merge(
              style: const TextStyle(fontFeatures: [FontFeature.tabularFigures()]),
              child: LikeButton(
                postId: postId,
                initialLiked: initialLiked,
                initialCount: initialCount,
                source: source,
                likersSource: source,
                expandCountHitArea: false,
                loginEntrySource: loginEntrySource,
                onSettled: (liked, _) {
                  if (liked) onLiked?.call();
                },
              ),
            ),
          ),
        ],
      ),
    );
  }
}

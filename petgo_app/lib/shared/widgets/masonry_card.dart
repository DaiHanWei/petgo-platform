import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/rounded.dart';
import '../../core/theme/spacing.dart';
import '../../core/theme/typography.dart';
import '../../features/content/domain/feed_item.dart';

/// Feed 瀑布流卡片（Story 3.2，UX-DR4）。
///
/// 含作者头像+昵称、正文前 2 行、首图（无图 → 纯文字卡）；图片不裁切仅上圆角 14px。
/// 作者行右侧展示点赞数（PRD-642）；评论数仍不在卡片。注销作者 → 本地化「已注销用户」+ 默认头像，
/// 头像不可点（Story 3.8）。
class MasonryCard extends StatelessWidget {
  const MasonryCard({
    super.key,
    required this.item,
    required this.deletedUserLabel,
    this.onTap,
    this.onLongPress,
    this.onAuthorTap,
  });

  final FeedItem item;

  /// 注销用户占位文案（来自 .arb，双语）。
  final String deletedUserLabel;
  final VoidCallback? onTap;

  /// 长按 context menu（Story 3.7 举报入口，UX-DR12）。
  final VoidCallback? onLongPress;

  /// 点作者头像/昵称（Story 3.8 迷你主页卡）；注销作者不挂（不触发）。
  final VoidCallback? onAuthorTap;

  @override
  Widget build(BuildContext context) {
    final String name =
        item.authorDeleted ? deletedUserLabel : (item.authorNickname ?? deletedUserLabel);

    return Semantics(
      button: onTap != null,
      label: name,
      child: GestureDetector(
        onTap: onTap,
        onLongPress: onLongPress,
        child: Container(
          decoration: BoxDecoration(
            color: AppColors.surface,
            borderRadius: AppRounded.phoneRadius,
            border: Border.all(color: AppColors.border),
          ),
          clipBehavior: Clip.antiAlias,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (item.hasImage)
                // 不裁切仅上圆角：fitWidth 保留原比例，外层 Container 已 clip 上圆角。
                Image.network(
                  item.firstImageUrl!,
                  fit: BoxFit.fitWidth,
                  width: double.infinity,
                  errorBuilder: (context, error, stack) => const SizedBox.shrink(),
                ),
              Padding(
                padding: const EdgeInsets.all(AppSpacing.md),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (item.body != null && item.body!.isNotEmpty) ...[
                      Text(
                        item.body!,
                        style: AppTypography.body,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                      const SizedBox(height: AppSpacing.sm),
                    ],
                    GestureDetector(
                      // 作者头像/昵称点击 → 迷你主页卡（Story 3.8）；注销作者不挂手势（不触发）。
                      onTap: (item.authorDeleted || onAuthorTap == null) ? null : onAuthorTap,
                      child: Row(
                        children: [
                          _Avatar(url: item.authorDeleted ? null : item.authorAvatarUrl),
                          const SizedBox(width: AppSpacing.xs),
                          Expanded(
                            child: Text(
                              name,
                              style: AppTypography.caption,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                          // 点赞数（PRD-642）：暖红心 + 计数，右对齐于作者行。
                          const SizedBox(width: AppSpacing.xs),
                          const Icon(Icons.favorite_rounded, size: 13, color: AppColors.likeHeart),
                          const SizedBox(width: 2),
                          Text(
                            '${item.likeCount}',
                            style: AppTypography.caption.copyWith(color: AppColors.textTertiary),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _Avatar extends StatelessWidget {
  const _Avatar({this.url});

  final String? url;

  @override
  Widget build(BuildContext context) {
    const double size = 20;
    if (url == null || url!.isEmpty) {
      return const CircleAvatar(
        radius: size / 2,
        backgroundColor: AppColors.border,
        child: Icon(Icons.person_rounded, size: 14, color: AppColors.textTertiary),
      );
    }
    return CircleAvatar(radius: size / 2, backgroundImage: NetworkImage(url!));
  }
}

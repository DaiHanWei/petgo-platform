import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/rounded.dart';
import '../../core/theme/spacing.dart';
import '../../core/theme/typography.dart';
import '../../features/content/domain/feed_item.dart';

/// Feed 瀑布流卡片（Story 3.2，UX-DR4）。
///
/// 含作者头像+昵称、正文前 2 行、首图（无图 → 纯文字卡）；图片不裁切仅上圆角 14px。
/// **不展示点赞/评论数**（FR-17）。注销作者 → 本地化「已注销用户」+ 默认头像，头像不可点（Story 3.8）。
class MasonryCard extends StatelessWidget {
  const MasonryCard({
    super.key,
    required this.item,
    required this.deletedUserLabel,
    this.onTap,
  });

  final FeedItem item;

  /// 注销用户占位文案（来自 .arb，双语）。
  final String deletedUserLabel;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final String name =
        item.authorDeleted ? deletedUserLabel : (item.authorNickname ?? deletedUserLabel);

    return Semantics(
      button: onTap != null,
      label: name,
      child: GestureDetector(
        onTap: onTap,
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
                    Row(
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
                      ],
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

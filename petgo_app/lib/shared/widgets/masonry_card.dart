import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/rounded.dart';
import '../../core/theme/spacing.dart';
import '../../core/theme/typography.dart';
import '../../features/content/domain/feed_item.dart';
import '../../l10n/app_localizations.dart';
import 'app_image.dart';
import 'post_cover.dart';

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

  /// 类型 → (badge 文案, 文字色, 底色)：Momen 绿 / Tips 黄 / Cerita 紫（原型 b-happy/b-tips/b-story）。
  static (String, Color, Color) _badgeStyle(String type, AppLocalizations l10n) {
    switch (type) {
      case 'GROWTH_MOMENT':
        return (l10n.mePostTypeMomen, AppColors.momenBadgeText, AppColors.momenBadgeBg);
      case 'KNOWLEDGE':
        return (l10n.mePostTypeTips, AppColors.tipsBadgeText, AppColors.goldTint);
      default: // DAILY
        return (l10n.mePostTypeCerita, AppColors.mint, AppColors.skyTint);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final String name =
        item.authorDeleted ? deletedUserLabel : (item.authorNickname ?? deletedUserLabel);
    final (badgeLabel, badgeFg, badgeBg) = _badgeStyle(item.type, l10n);

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
                // 经 AppImage 统一解析 asset:/file:/network（mock 演示用 asset、发布回灌用 file）。
                AppImage.widget(
                  item.firstImageUrl!,
                  fit: BoxFit.fitWidth,
                  width: double.infinity,
                  errorBuilder: (context, error, stack) =>
                      PostCoverPlaceholder(type: item.type, height: _placeholderHeight(item.id)),
                )
              else
                // 无真实首图 → 类型彩块占位（对齐设计稿 S03，避免退化成纯文字白卡）。
                // 高度按 id 轻微错落，配合两列伪 masonry 形成 Pinterest 观感。
                PostCoverPlaceholder(type: item.type, height: _placeholderHeight(item.id)),
              Padding(
                padding: const EdgeInsets.all(AppSpacing.md),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // 类型彩徽章（原型 feed 卡 badge）。
                    Align(
                      alignment: Alignment.centerLeft,
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                        decoration: BoxDecoration(
                            color: badgeBg, borderRadius: BorderRadius.circular(6)),
                        child: Text(badgeLabel,
                            style: TextStyle(
                                fontSize: 10, fontWeight: FontWeight.w700, color: badgeFg)),
                      ),
                    ),
                    const SizedBox(height: AppSpacing.sm),
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

/// 占位封面高度：按 id 在 110/144/178 间轻微错落（配合两列伪 masonry 制造层次）。
/// 用 abs 防负 id（mock/占位场景）导致高度档位为负。
double _placeholderHeight(int id) => 110 + (id.abs() % 3) * 34;

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
    return CircleAvatar(radius: size / 2, backgroundImage: AppImage.provider(url));
  }
}

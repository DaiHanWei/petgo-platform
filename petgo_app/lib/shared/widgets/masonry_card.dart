import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/spacing.dart';
import '../../core/theme/typography.dart';
import '../../features/content/domain/feed_item.dart';
import '../../features/content/presentation/like_button.dart';
import '../../l10n/app_localizations.dart';
import 'app_image.dart';
import 'letter_avatar.dart';
import 'post_cover.dart';

/// Feed 单列卡片。**V1.1.6 Story 3.2 起为通栏版式**（FR-93）。
///
/// 自上而下：作者行 → 图片 → 操作行（点赞 / 评论）→ 正文 → 时间。
///
/// ## 🔴 只有图片出血
/// 「去掉屏边距」指的是**列表容器**那 16px，好让**图片**贴到屏幕左右边缘；
/// 作者行 / 操作行 / 正文 / 时间**各自仍有 16px 左右内边距**（视觉稿里这四块都写着）。
/// 做成整张贴边会让文字顶到屏幕边上 —— 这是本次改版最容易做错的一处。
///
/// ## 点击分区（FR-93）
/// 点赞就地切换、不跳转；评论跳详情页并定位到评论区；**其余区域**整块进详情页顶部。
/// 作者行另有自己的手势（迷你主页）。
///
/// 注销作者 → 本地化「已注销用户」+ 默认头像，头像不可点（Story 3.8）。
class MasonryCard extends StatelessWidget {
  const MasonryCard({
    super.key,
    required this.item,
    required this.deletedUserLabel,
    this.onTap,
    this.onLongPress,
    this.onAuthorTap,
    this.onComment,
    this.onMore,
  });

  final FeedItem item;

  /// 注销用户占位文案（来自 .arb，双语）。
  final String deletedUserLabel;
  final VoidCallback? onTap;

  /// 长按 context menu（Story 3.7 举报入口，UX-DR12）。
  final VoidCallback? onLongPress;

  /// 点作者头像/昵称（Story 3.8 迷你主页卡）；注销作者不挂（不触发）。
  final VoidCallback? onAuthorTap;

  /// 点评论按钮（V1.1.6 Story 3.2）：跳详情页并定位到评论区。
  final VoidCallback? onComment;

  /// 作者行右侧「···」（V1.1.6 Story 3.2）。Feed 此前只有长按举报，没有显式入口。
  final VoidCallback? onMore;

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

  static String _relativeTime(AppLocalizations l10n, DateTime t) {
    final d = DateTime.now().difference(t);
    if (d.inMinutes < 1) return l10n.timeJustNow;
    if (d.inHours < 1) return l10n.timeMinutesAgo(d.inMinutes);
    if (d.inDays < 1) return l10n.timeHoursAgo(d.inHours);
    return l10n.timeDaysAgo(d.inDays);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final String name =
        item.authorDeleted ? deletedUserLabel : (item.authorNickname ?? deletedUserLabel);
    final (badgeLabel, badgeFg, badgeBg) = _badgeStyle(item.type, l10n);
    final time = _relativeTime(l10n, item.createdAt);

    return Semantics(
      button: onTap != null,
      label: name,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // 作者行：有自己的手势（迷你主页），不参与整块点击。
          Padding(
            padding: const EdgeInsets.fromLTRB(
                AppSpacing.screenEdge, 0, AppSpacing.screenEdge, 10),
            child: Row(
              children: [
                Expanded(
                  child: GestureDetector(
                    behavior: HitTestBehavior.opaque,
                    onTap: (item.authorDeleted || onAuthorTap == null) ? null : onAuthorTap,
                    child: Row(
                      children: [
                        LetterAvatar(
                            url: item.authorDeleted ? null : item.authorAvatarUrl,
                            name: name,
                            deleted: item.authorDeleted,
                            size: 32),
                        const SizedBox(width: 9),
                        Flexible(
                          child: Text(name,
                              style: const TextStyle(
                                  fontSize: 13.5,
                                  fontWeight: FontWeight.w700,
                                  color: AppColors.ink),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis),
                        ),
                        const SizedBox(width: 6),
                        // 类型徽章保留：三类内容要在首页可辨识（FR-93 明确要求保留）。
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                          decoration: BoxDecoration(
                              color: badgeBg, borderRadius: BorderRadius.circular(5)),
                          child: Text(badgeLabel,
                              style: TextStyle(
                                  fontSize: 9.5, fontWeight: FontWeight.w700, color: badgeFg)),
                        ),
                      ],
                    ),
                  ),
                ),
                if (onMore != null)
                  GestureDetector(
                    key: ValueKey('feedCardMore_${item.id}'),
                    behavior: HitTestBehavior.opaque,
                    onTap: onMore,
                    child: const Padding(
                      padding: EdgeInsets.only(left: AppSpacing.sm),
                      child: Icon(Icons.more_horiz_rounded, size: 20, color: AppColors.ink2),
                    ),
                  ),
              ],
            ),
          ),
          // 图片：**全宽出血**，是这一版唯一贴边的东西。
          // 整块可点区从这里开始（图片 / 正文 / 时间都进详情页顶部）。
          GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: onTap,
            onLongPress: onLongPress,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (item.hasImage)
                  AspectRatio(
                    aspectRatio: 4 / 3,
                    child: AppImage.widget(
                      item.firstImageUrl!,
                      fit: BoxFit.cover,
                      width: double.infinity,
                      height: double.infinity,
                      thumbWidth: 800, // Feed 全宽封面：OSS 缩略图省流量、列表滚动更顺
                      errorBuilder: (context, error, stack) =>
                          PostCoverPlaceholder(type: item.type),
                    ),
                  ),
              ],
            ),
          ),
          // 操作行：点赞就地切换、评论跳详情定位评论区 —— 都**不**走整块点击。
          Padding(
            padding: const EdgeInsets.fromLTRB(
                AppSpacing.screenEdge, 10, AppSpacing.screenEdge, 0),
            child: Row(
              children: [
                LikeButton(
                  postId: item.id,
                  initialLiked: item.liked,
                  initialCount: item.likeCount,
                  // 🛡 两侧都要传，否则「首页点赞是净增还是把详情页的前移了」无从判断。
                  source: 'feed',
                ),
                const SizedBox(width: 18),
                GestureDetector(
                  key: ValueKey('feedCardComment_${item.id}'),
                  behavior: HitTestBehavior.opaque,
                  onTap: onComment,
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(Icons.mode_comment_outlined,
                          size: 20, color: AppColors.textSecondary),
                      const SizedBox(width: AppSpacing.xs),
                      Text('${item.commentCount}', style: AppTypography.caption),
                    ],
                  ),
                ),
              ],
            ),
          ),
          // 正文与时间：同样走整块点击。
          GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: onTap,
            onLongPress: onLongPress,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (item.body != null && item.body!.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.fromLTRB(
                        AppSpacing.screenEdge, 10, AppSpacing.screenEdge, 0),
                    // ⚠️ 不加作者名前缀（FR-93 明确要求）。
                    child: Text(item.body!,
                        style: AppTypography.body,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis),
                  ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(
                      AppSpacing.screenEdge, 5, AppSpacing.screenEdge, 0),
                  child: Text(time,
                      style: const TextStyle(fontSize: 11, color: AppColors.muted),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
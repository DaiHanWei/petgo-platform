import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/spacing.dart';
import '../../core/theme/typography.dart';
import '../../features/content/domain/feed_image_layout.dart';
import '../../features/content/domain/feed_item.dart';
import '../../features/content/presentation/like_button.dart';
import '../../l10n/app_localizations.dart';
import 'feed_image.dart';
import 'content_tag_chip.dart';
import 'user_tag_row.dart';
import 'letter_avatar.dart';

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
    this.maxImageHeight,
    this.pinnedBadge,
    this.decorTag,
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

  /// 图片区高度上限（V1.1.6 Story 3.3 的高度护栏）。
  ///
  /// 由**列表容器**量出滚动视口的真实高度后传下来 —— 卡片自己量不到视口。
  /// 为 null 时退化成按屏高（扣安全区）估算，够单卡场景用，但列表里请务必传实测值。
  final double? maxImageHeight;

  /// 🛡 图片区**右上角位**：顶置角标（Epic 4 挂）。
  final Widget? pinnedBadge;

  /// 🛡 图片区**左下角位**：装饰标签（Epic 5 挂）。
  final Widget? decorTag;

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
    final media = MediaQuery.of(context);
    // 护栏上限：优先用列表容器实测的视口高度；单卡场景退化成按屏高扣安全区估算。
    final maxH = maxImageHeight ??
        FeedCardMetrics.maxImageHeight(media.size.height - media.padding.vertical);

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
                          // V1.1.6 Story 5.1：昵称旁挂运营标签（四处展示位之一）。
                          // 🛡 空间不足时**昵称完整优先、标签丢弃**，不截断昵称、不做「+N」折叠。
                          child: UserTagRow(
                            position: 'feed',
                            name: name,
                            nameStyle: const TextStyle(
                                fontSize: 13.5,
                                fontWeight: FontWeight.w700,
                                color: AppColors.ink),
                            // 注销作者不挂标签（匿名化之后不该再有身份标识）。
                            tags: item.authorDeleted ? const [] : item.authorTags,
                          ),
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
                // 高度按**三段口径**定（实际比例 → 收敛闭区间 → 高度护栏）。
                // ⚠️ 这里此前是写死的 4:3 —— FR-71 起改为按实际比例，
                // 卡片高度**从齐整变为不齐整**是产品已知并接受的代价（保构图 > 保版面）。
                if (item.hasImage)
                  FeedImage(
                    urls: item.images,
                    type: item.type,
                    declaredSize: item.firstImageSize,
                    width: media.size.width,
                    maxImageHeight: maxH,
                    // 🛡 两个角位留给 Epic 4（顶置角标）与 Epic 5（装饰标签）——
                    // 它们只往这里挂内容，**不得再改图片区结构**（AD-8 Rule 6）。
                    topRight: pinnedBadge,
                    // V1.1.6 Story 5.2：装饰标签挂**左下角位**（3.4 交付的入参）。
                    // 只取第一枚 —— 角位空间有限，多枚会一路铺到底边圆点底下。
                    // ⚠️ 外部传入的 decorTag 优先（留给调用方覆盖用）。
                    bottomLeft: decorTag ??
                        (item.decorationTags.isEmpty
                            ? null
                            : ContentTagChip.overlay(tag: item.decorationTags.first, position: 'feed')),
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
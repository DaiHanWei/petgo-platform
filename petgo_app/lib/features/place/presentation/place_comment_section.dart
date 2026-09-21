import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/confirm_sheet.dart';
import '../../../shared/widgets/letter_avatar.dart';
import '../../user_profile/presentation/public_profile_page.dart';
import '../data/place_repository.dart';
import '../domain/place_comment.dart';
import 'place_comments_controller.dart';

/// 场所详情页的评论区（V1.3.0 batch-b1 Story 1.7 · UI 稿 A4）。
///
/// <h2>🔴 一级 only —— 这里**没有**「回复」按钮</h2>
/// 场所评论是攻略提示，没有对话需求（PRD ③）。三层都没有回复的位置：
/// 表里没有 `parent_id`、DTO 里没有 `replies`、端点里没有 `/replies`。
/// 所以这里也**不要**挂一个"回复"文字按钮 —— 挂了就得有人去实现它。
///
/// <h2>每条评论下面的是**这一条的态度**，不是计数</h2>
/// UI 稿 A4 把每条评论下画成「👍 12 · 👎 0」，那是稿子画松了：按 B1-D3，
/// 「详情页评论里那两个数字是**评论态度的累计**」—— 累计在**场所维度**的计数行（Story 1.8），
/// 每条评论自己带的只是它的立场（推荐 / 不推荐 / 没表态）。
/// 照着稿子做会变成"给评论点赞"，那是一个谁也没设计过的功能。
class PlaceCommentSection extends ConsumerWidget {
  const PlaceCommentSection({super.key, required this.token});

  final String token;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(placeCommentsProvider(token));
    final page = async.value;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          // 数字拿不到（还在加载 / 失败）就先只显示标题，不要显示「评论 (0)」——
          // 那会和"真的一条都没有"混淆。
          page == null ? l10n.placeCommentsTitle : l10n.placeCommentsTitleCount(page.total),
          style: AppTypography.caption.copyWith(fontWeight: FontWeight.w700),
        ),
        const SizedBox(height: AppSpacing.sm),
        if (async.isLoading && page == null)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: AppSpacing.lg),
            child: Center(child: CircularProgressIndicator()),
          )
        else if (async.hasError && page == null)
          // F13：首次加载失败 → 文案 + 重试，不是一块空白。
          _Retry(onRetry: () => ref.invalidate(placeCommentsProvider(token)))
        else if (page == null || page.items.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
            child: Text(l10n.placeCommentsEmpty, style: AppTypography.caption),
          )
        else
          for (final c in page.items)
            _CommentRow(
              comment: c,
              onAuthorTap: c.authorTappable
                  ? () => openUserProfile(context, ref, c.authorId,
                      // 主页里拉黑 / 举报成功后回到这里：服务端已隐藏此人的评论，
                      // 不重拉的话他的评论还挂着、点进去落在「你已屏蔽此人」页（同帖子评论区）。
                      onBlocked: () => _onAuthorHidden(ref),
                      onReported: () => _onAuthorHidden(ref))
                  : null,
              onDelete: c.mine ? () => _confirmDelete(context, ref, c) : null,
            ),
        // 「加载更多」：场所评论量级小，不做无限滚动（详情页本身就是一个长列表，
        // 再嵌一层滚动监听只会互相抢手势）。
        if (page != null && page.hasMore)
          _LoadMore(onTap: () => _loadMore(context, ref)),
      ],
    );
  }

  /// 拉黑 / 举报评论作者之后：评论区与详情一起重拉（详情里的评论数也要跟着变）。
  void _onAuthorHidden(WidgetRef ref) {
    ref.read(placeCommentsProvider(token).notifier).reload();
    invalidatePlaceDetail(ref, token);
  }

  /// 追加下一页。失败只提示一声，**不动已加载的列表**（F13）。
  Future<void> _loadMore(BuildContext context, WidgetRef ref) async {
    try {
      await ref.read(placeCommentsProvider(token).notifier).loadMore();
    } catch (_) {
      if (context.mounted) {
        showAppToast(context, AppLocalizations.of(context).placeErrorTitle);
      }
    }
  }

  /// AC7 删除自己的评论。**二次确认**：软删之后用户自己也找不回来。
  Future<void> _confirmDelete(
      BuildContext context, WidgetRef ref, PlaceComment c) async {
    final l10n = AppLocalizations.of(context);
    final ok = await showConfirmSheet(
      context,
      title: l10n.placeCommentDeleteTitle,
      message: l10n.placeCommentDeleteBody,
      confirmLabel: l10n.placeCommentDeleteConfirm,
      cancelLabel: l10n.commonCancel,
      icon: Icons.delete_outline_rounded,
      danger: true,
      confirmKey: const ValueKey('placeCommentDeleteConfirm'),
    );
    if (!ok) return;
    try {
      await ref.read(placeRepositoryProvider).deleteComment(c.id);
      await ref.read(placeCommentsProvider(token).notifier).reload();
      // 计数行同刷（见 `invalidatePlaceDetail` 的说明）。
      invalidatePlaceDetail(ref, token);
      if (context.mounted) showAppToast(context, l10n.placeCommentDeleted);
    } catch (_) {
      if (context.mounted) showAppToast(context, l10n.placeCommentDeleteFailed);
    }
  }
}

class _CommentRow extends StatelessWidget {
  const _CommentRow({required this.comment, required this.onAuthorTap, required this.onDelete});

  final PlaceComment comment;
  final VoidCallback? onAuthorTap;
  final VoidCallback? onDelete;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final name = comment.authorDeleted
        ? l10n.feedDeletedUser
        : (comment.authorNickname ?? '');
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.md),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 注销用户的头像**不可点**（NFR-8）。
          GestureDetector(
            onTap: onAuthorTap,
            child: LetterAvatar(
                name: name,
                url: comment.authorAvatarUrl,
                deleted: comment.authorDeleted,
                size: 30),
          ),
          const SizedBox(width: AppSpacing.sm),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(name,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: AppTypography.caption
                              .copyWith(fontWeight: FontWeight.w600)),
                    ),
                    // 「仅你可见」：挂起 / 被拒 / 被下架的评论只会下发给作者本人，
                    // 不给标签的话他会以为自己发的东西凭空消失了。
                    if (comment.moderation.onlyVisibleToMe)
                      Padding(
                        padding: const EdgeInsets.only(left: AppSpacing.xs),
                        child: Text(l10n.placeCommentOnlyVisibleToYou,
                            style: AppTypography.micro
                                .copyWith(color: AppColors.textTertiary)),
                      ),
                    if (onDelete != null)
                      GestureDetector(
                        key: ValueKey('placeCommentDelete-${comment.id}'),
                        onTap: onDelete,
                        // 44×44 热区（UX-DR16）：图标本身只有 16。
                        child: const Padding(
                          padding: EdgeInsets.all(14),
                          child: Icon(Icons.delete_outline_rounded,
                              size: 16, color: AppColors.textTertiary),
                        ),
                      ),
                  ],
                ),
                Text(comment.body, style: AppTypography.body),
                if (comment.attitude != null) ...[
                  const SizedBox(height: AppSpacing.xxs),
                  _AttitudeBadge(attitude: comment.attitude!),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// 一条评论自己的态度（不是计数 —— 见本文件头部）。
class _AttitudeBadge extends StatelessWidget {
  const _AttitudeBadge({required this.attitude});

  final PlaceCommentAttitude attitude;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final recommend = attitude == PlaceCommentAttitude.recommend;
    return Text(
      recommend ? '👍 ${l10n.placeAttitudeRecommend}' : '👎 ${l10n.placeAttitudeNotRecommend}',
      style: AppTypography.micro.copyWith(color: AppColors.textTertiary),
    );
  }
}

class _Retry extends StatelessWidget {
  const _Retry({required this.onRetry});

  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: AppSpacing.sm),
      child: Row(
        children: [
          Expanded(child: Text(l10n.placeErrorTitle, style: AppTypography.caption)),
          TextButton(
            key: const ValueKey('placeCommentsRetry'),
            onPressed: onRetry,
            style: TextButton.styleFrom(
                minimumSize: const Size(44, 44), foregroundColor: AppColors.mint),
            child: Text(l10n.placeRetry),
          ),
        ],
      ),
    );
  }
}

class _LoadMore extends StatelessWidget {
  const _LoadMore({required this.onTap});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Center(
      child: TextButton(
        key: const ValueKey('placeCommentsLoadMore'),
        onPressed: onTap,
        style: TextButton.styleFrom(
            minimumSize: const Size(44, 44), foregroundColor: AppColors.mint),
        child: Text(l10n.placeCommentsLoadMore),
      ),
    );
  }
}

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../social/data/following_strip_repository.dart';
import '../../social/domain/account_action_entry.dart';
import '../../user_profile/presentation/public_profile_page.dart';
import '../domain/feed_item.dart';
import 'author_moderation_callbacks.dart';
import 'feed_controller.dart';
import 'feed_skeleton.dart';
import '../domain/home_refresh_provider.dart';
import 'feed_view.dart';
import 'following_feed_controller.dart';
import 'report_sheet.dart';
import 'widgets/following_empty_view.dart';
import 'widgets/following_strip.dart';
import '../video/video_nav.dart';

/// Social → Following 页（V1.3.4 following 流 · spec §2.2 ~ §2.4）。
///
/// - 有帖 → 双列瀑布流（直接复用 Discover 的 [FeedMasonryView] 与卡片），头部 = 关注头像排。
/// - 首页无帖 → **只显示**空状态（推荐关注 + Explore Discover），**不显示头像排**（2026-10-14 拍板）。
/// - 卡片交互（点赞 / 评论 / 进详情 / 「…」举报 / 点头像）与 Discover 一致，只是落在 [followingFeedProvider] 上；
///   举报 / 拉黑的收尾经 [removeFeedPost] / [onAuthorHidden] 两条流一起清。
/// - 卡片上的关注按钮由 [FollowButton] 按 `authorFollowState.iFollow=true` 自行隐藏，这里不另做。
///
/// ⚠️ 只给登录用户挂（接口限 USER）；游客门控在 [HomePage]。
class FollowingTabView extends ConsumerWidget {
  const FollowingTabView({super.key});

  Future<void> _refresh(WidgetRef ref) async {
    ref.invalidate(followingStripProvider);
    await ref.read(followingFeedProvider.notifier).refresh();
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final feedAsync = ref.watch(followingFeedProvider);

    Widget scrollable(Widget child) => _TopScrollable(onRefresh: () => _refresh(ref), child: child);

    return feedAsync.when(
      loading: () => const SingleChildScrollView(child: FeedSkeleton()),
      error: (error, stack) => scrollable(
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 48),
          child: EmptyState(
            title: l10n.feedErrorTitle,
            message: l10n.feedErrorBody,
            hideIcon: true,
            actionLabel: l10n.feedRetry,
            onAction: () => _refresh(ref),
          ),
        ),
      ),
      data: (state) {
        if (state.isEmpty) return scrollable(const FollowingEmptyView());
        void onReported(FeedItem item) {
          removeFeedPost(ref, item.id);
          if (context.mounted) {
            ScaffoldMessenger.of(context)
              ..hideCurrentSnackBar()
              ..showSnackBar(SnackBar(content: Text(l10n.reportHiddenToast)));
          }
        }

        return FeedMasonryView(
          header: const FollowingStrip(),
          feedTab: 'following',
          videoSource: VideoSource.following,
          rankMode: state.rankMode,
          followSurfacePrefix: 'following',
          autoLoadMore: true,
          items: state.items,
          hasMore: state.hasMore,
          loadingMore: state.loadingMore,
          loadMoreFailed: state.loadMoreFailed,
          loadMoreErrorLabel: l10n.feedLoadMoreError,
          deletedUserLabel: l10n.feedDeletedUser,
          onLoadMore: () => ref.read(followingFeedProvider.notifier).loadMore(),
          onRefresh: () => _refresh(ref),
          onTapItem: (item) =>
              pushContentDetail(context, item.id,
                  isVideo: item.isVideo, source: VideoSource.following, coverUrl: item.firstImageUrl, video: item.video),
          onLongPressItem: (item) => openReport(context, ref, item.id, onReported: () => onReported(item)),
          onAuthorTap: (item) => openUserProfile(
            context,
            ref,
            item.authorId,
            onBlocked: _hideAuthor(ref, item.authorId),
            onReported: _hideAuthor(ref, item.authorId),
          ),
          onTapMention: (userId) => openUserProfile(
            context,
            ref,
            userId,
            entry: AccountActionEntry.mention,
            onBlocked: _hideAuthor(ref, userId),
            onReported: _hideAuthor(ref, userId),
          ),
          onCommentItem: (item) => pushContentDetail(context, item.id,
              isVideo: item.isVideo,
              source: VideoSource.following,
              focusComments: true,
              coverUrl: item.firstImageUrl,
              video: item.video),
          onMoreItem: (item) => openReport(context, ref, item.id, onReported: () => onReported(item)),
        );
      },
    );
  }

  /// 拉黑 / 举报某作者：两条流清掉他的卡（[onAuthorHidden]）+ 头像排重取（拉黑会解除关注）。
  VoidCallback _hideAuthor(WidgetRef ref, int authorId) {
    final hide = onAuthorHidden(ref, authorId);
    return () {
      hide();
      ref.invalidate(followingStripProvider);
    };
  }
}

/// 空状态 / 错误态的可下拉滚动容器；监听 [homeScrollTopProvider]（重点击 Social 底栏 / Following 页签）回到顶部。
class _TopScrollable extends ConsumerStatefulWidget {
  const _TopScrollable({required this.onRefresh, required this.child});

  final Future<void> Function() onRefresh;
  final Widget child;

  @override
  ConsumerState<_TopScrollable> createState() => _TopScrollableState();
}

class _TopScrollableState extends ConsumerState<_TopScrollable> {
  final ScrollController _controller = ScrollController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    ref.listen<int>(homeScrollTopProvider, (_, _) {
      if (!_controller.hasClients) return;
      _controller.animateTo(0, duration: const Duration(milliseconds: 300), curve: Curves.easeOut);
    });
    return RefreshIndicator(
      color: AppColors.mint,
      onRefresh: widget.onRefresh,
      child: SingleChildScrollView(
        controller: _controller,
        physics: const AlwaysScrollableScrollPhysics(),
        child: widget.child,
      ),
    );
  }
}

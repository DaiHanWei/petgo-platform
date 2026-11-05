import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../content/domain/feed_item.dart';
import '../../content/domain/pinned_slot.dart';
import '../../content/presentation/author_moderation_callbacks.dart';
import '../../content/presentation/feed_controller.dart';
import '../../content/presentation/feed_skeleton.dart';
import '../../content/presentation/feed_view.dart';
import '../../content/presentation/promo_target.dart';
import '../../content/presentation/report_sheet.dart';
import '../../social/domain/account_action_entry.dart';
import '../../user_profile/presentation/public_profile_page.dart';
import '../domain/topic.dart';
import 'topic_providers.dart';
import 'widgets/topic_empty_view.dart';
import 'widgets/topic_official_badge.dart';

/// 话题详情页（V1.4.2 social-topic · Story 4.3 · AC2 / AC3 / AC7 · UI C6–C11 · D-20）。
///
/// 结构：`AppBar`（主题返回键 + 「#displayName」+ 官方 Resmi）→ `NestedScrollView`：
/// 说明段（仅官方，随列表滚走）→ **吸顶** Terbaru / Populer 页签 → 当前页签的列表
/// （`FeedMasonryView(usePrimaryScrollController: true)`，顶置走它的 `pinned:` 参数）。
/// 🔴 不用 `TabBarView`：两份列表同挂一个 PrimaryScrollController 会多 position；页签切换只换 body 数据源。
///
/// 无封面、无帖子数、无分享、无 ⋯（PRD FR-69 AC10）；游客可浏览，互动沿用卡片自带的登录拉起。
class TopicPage extends ConsumerStatefulWidget {
  const TopicPage({super.key, required this.name, this.source});

  /// shell 外顶层路由（push 时隐藏底部 Tab，同 `/places/:token`）；**不进** `_controlledLocations`（游客可访问）。
  static const String routePattern = '/topics/:name';

  /// 🔴 [name] 传**标准名**（`TopicSummary.name`）；[from] 只喂埋点 `topic_page_viewed.source`。
  static String routeFor(String name, {TopicEntrySource? from}) =>
      '/topics/${Uri.encodeComponent(name)}${from == null ? '' : '?from=${from.wire}'}';

  /// 路由里的话题名（后端按标准化规则查，大小写不敏感）。
  final String name;

  /// 进页来源；缺省 / 不认识 → null（埋点不带 `source`）。
  final TopicEntrySource? source;

  @override
  ConsumerState<TopicPage> createState() => _TopicPageState();
}

class _TopicPageState extends ConsumerState<TopicPage> with SingleTickerProviderStateMixin {
  late final TabController _tabs = TabController(length: TopicSort.values.length, vsync: this);

  TopicSort _sort = TopicSort.latest;

  /// 访问过的页签：各自的列表一直 watch 着（autoDispose 不回收）→ 来回切换不重拉（AC3.2）。
  final Set<TopicSort> _visited = {TopicSort.latest};

  /// `topic_page_viewed` 同一页实例只报一次。
  bool _viewReported = false;

  /// 页面主体（详情 + 首屏）出现过。之前首屏失败 = 整页 C11；之后切页签首屏失败 = 列表区失败态（AC2.6）。
  bool _shown = false;

  TopicPostsKey get _key => (widget.name, _sort);

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  /// 用户主动切页签（进页默认选中不报，AC3.4）。
  void _onTabTap(int index) {
    final next = TopicSort.values[index];
    if (next == _sort) return;
    setState(() {
      _sort = next;
      _visited.add(next);
    });
    Analytics.capture('topic_sort_switched', {'sort': next.wire});
  }

  void _reportView(TopicDetail detail) {
    if (_viewReported) return;
    _viewReported = true;
    // R-2：topic_id = 标准名（取服务端返回的 name，不用路由里的原写法）；不传 displayName。
    Analytics.capture('topic_page_viewed', {
      'topic_id': detail.name,
      if (widget.source != null) 'source': widget.source!.wire,
    });
  }

  /// C11「Coba lagi」：详情 + 当前页签首屏 + 顶置（+ 推荐）一起重发（AC3.1）。
  Future<void> _retryAll() async {
    ref.invalidate(topicDetailProvider(widget.name));
    ref.invalidate(topicPostsProvider(_key));
    ref.invalidate(topicPinnedProvider(widget.name));
    ref.invalidate(topicRelatedProvider(widget.name));
    try {
      await ref.read(topicDetailProvider(widget.name).future);
    } catch (_) {
      // 失败由页面状态机呈现
    }
  }

  /// 下拉刷新：当前页签首屏 + 顶置 + 详情（+ 空态推荐）。另一个页签的缓存不动。
  Future<void> _refresh() async {
    ref.invalidate(topicDetailProvider(widget.name));
    ref.invalidate(topicPinnedProvider(widget.name));
    ref.invalidate(topicRelatedProvider(widget.name));
    try {
      await ref.read(topicPostsProvider(_key).notifier).refresh();
    } catch (_) {
      // 失败由列表状态呈现
    }
  }

  void _removePostLocally(int postId) {
    for (final s in _visited) {
      final p = topicPostsProvider((widget.name, s));
      if (ref.exists(p)) ref.read(p.notifier).removeItem(postId);
    }
  }

  /// 举报 / 拉黑作者成功：首页 / 关注流 / 推荐位的既有收尾 + 本页两个页签的列表（AC3.3）。
  VoidCallback _authorHidden(int authorId) {
    final shared = onAuthorHidden(ref, authorId);
    return () {
      shared();
      if (!mounted) return;
      for (final s in _visited) {
        final p = topicPostsProvider((widget.name, s));
        if (ref.exists(p)) ref.read(p.notifier).removeByAuthor(authorId);
      }
    };
  }

  void _report(AppLocalizations l10n, FeedItem item) {
    openReport(context, ref, item.id, onReported: () {
      // cm-6 §6.1：举报成功 → 乐观移除 +「不再向你展示」提示。首页的列表与本页的列表都移除。
      removeFeedPost(ref, item.id);
      if (!mounted) return;
      _removePostLocally(item.id);
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text(l10n.reportHiddenToast)));
    });
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final detailAsync = ref.watch(topicDetailProvider(widget.name));
    // 访问过的页签都 watch 住（缓存）；当前页签的那份拿来渲染。
    for (final s in _visited) {
      if (s != _sort) ref.watch(topicPostsProvider((widget.name, s)));
    }
    final postsAsync = ref.watch(topicPostsProvider(_key));
    final pinned = ref.watch(topicPinnedProvider(widget.name)).value;

    final detail = detailAsync.value;
    if (detail != null && postsAsync.hasValue) _shown = true;

    final detailError = (detailAsync.hasError && !detailAsync.hasValue) ? detailAsync.error : null;
    final postsFirstError = (!_shown && postsAsync.hasError && !postsAsync.hasValue) ? postsAsync.error : null;

    // C10：详情 404（含 DISABLED）。🔴 不看有没有旧值：下拉刷新时话题刚被禁用（旧值还在）也要进 C10；
    // 当前页签列表 404 同理（只有话题不可见才会 404）。网络错误则保留旧值、不整页报错（见下方 C11 判据）。
    if (detailAsync.error is TopicNotFoundException || postsAsync.error is TopicNotFoundException) {
      return _shell(EmptyState(
        key: const ValueKey('topicNotFound'),
        icon: Icons.search_off_rounded,
        title: l10n.topicNotFoundTitle,
        message: l10n.topicNotFoundBody,
        actionLabel: l10n.detailBackToFeed,
        onAction: () => Navigator.of(context).maybePop(),
      ));
    }
    // C11：详情或首屏任一网络失败（顶置失败不连带，provider 内已吞成 null）。
    if (detailError != null || postsFirstError != null) {
      return _shell(RefreshIndicator(
        color: AppColors.mint,
        onRefresh: _retryAll,
        child: CustomScrollView(
          physics: const AlwaysScrollableScrollPhysics(),
          slivers: [
            SliverFillRemaining(
              hasScrollBody: false,
              child: EmptyState(
                key: const ValueKey('topicLoadFailed'),
                icon: Icons.cloud_off_rounded,
                title: l10n.detailNetworkError,
                actionLabel: l10n.commonRetry,
                onAction: _retryAll,
              ),
            ),
          ],
        ),
      ));
    }

    // AC7.1：只在页面主体真正出来（详情 + 首屏都到手，与 [_shown] 同一判据）后报；C10 / C11 不报。
    if (detail != null && postsAsync.hasValue) _reportView(detail);

    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(
        backgroundColor: AppColors.cream,
        scrolledUnderElevation: 0,
        // 返回键由主题 backButtonIconBuilder 给（chevron_left_rounded 28），titleSpacing 2 为主题默认。
        title: detail == null ? _titleSkeleton() : _title(detail),
      ),
      body: NestedScrollView(
        headerSliverBuilder: (context, _) => [
          if (detail == null)
            const SliverToBoxAdapter(child: _HeaderSkeleton())
          else if (detail.hasDescription)
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(
                    AppSpacing.screenEdge, AppSpacing.xs, AppSpacing.screenEdge, AppSpacing.md),
                child: Text(
                  detail.description!.trim(),
                  key: const ValueKey('topicDescription'),
                  style: AppTypography.caption.copyWith(color: AppColors.ink2),
                ),
              ),
            ),
          SliverPersistentHeader(
            pinned: true,
            delegate: _TabBarDelegate(_tabBar(l10n)),
          ),
        ],
        body: KeyedSubtree(
          key: ValueKey(_sort),
          child: _body(l10n, detail, postsAsync, pinned),
        ),
      ),
    );
  }

  /// C10 / C11 外壳：顶栏只有返回，不显示页头 / 页签（照 `content_detail_page.dart` `_shell`）。
  Widget _shell(Widget body) => Scaffold(
        backgroundColor: AppColors.base,
        appBar: AppBar(backgroundColor: AppColors.base, scrolledUnderElevation: 0),
        body: body,
      );

  Widget _title(TopicDetail detail) => Row(
        key: const ValueKey('topicPageTitle'),
        mainAxisSize: MainAxisSize.min,
        children: [
          Flexible(
            child: Text(
              '#${detail.displayName}',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: AppTypography.title.copyWith(color: AppColors.ink),
            ),
          ),
          if (detail.official) ...[
            const SizedBox(width: AppSpacing.sm),
            const TopicOfficialBadge(),
          ],
        ],
      );

  /// C6：话题名骨架条（高 16）。
  Widget _titleSkeleton() => Align(
        alignment: Alignment.centerLeft,
        child: Container(
          key: const ValueKey('topicTitleSkeleton'),
          width: 140,
          height: 16,
          decoration: BoxDecoration(
            color: AppColors.border,
            borderRadius: BorderRadius.circular(6),
          ),
        ),
      );

  /// 页签样式照 `follow_list_page.dart` `_tabBar`：14 w600、未选 muted、选中 ink + 3px mint 整 Tab 宽下划线、底部 1px line。
  Widget _tabBar(AppLocalizations l10n) => Container(
        decoration: const BoxDecoration(
          color: AppColors.cream,
          border: Border(bottom: BorderSide(color: AppColors.line)),
        ),
        child: TabBar(
          controller: _tabs,
          onTap: _onTabTap,
          labelColor: AppColors.ink,
          unselectedLabelColor: AppColors.muted,
          labelStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
          unselectedLabelStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
          indicator: const UnderlineTabIndicator(
            borderSide: BorderSide(color: AppColors.mint, width: 3),
          ),
          indicatorSize: TabBarIndicatorSize.tab,
          dividerHeight: 0,
          tabs: [
            Tab(key: const ValueKey('topicTabLatest'), text: l10n.topicSortLatest),
            Tab(key: const ValueKey('topicTabPopular'), text: l10n.topicSortPopular),
          ],
        ),
      );

  Widget _body(
    AppLocalizations l10n,
    TopicDetail? detail,
    AsyncValue<TopicPostsState> postsAsync,
    PinnedSlot? pinned,
  ) {
    final state = postsAsync.value;
    if (state == null) {
      if (postsAsync.hasError) {
        // 已出详情后切页签首屏失败 → 列表区失败态（不整页 C11）。
        return _scrollable(Padding(
          padding: const EdgeInsets.only(top: AppSpacing.xxl),
          child: EmptyState(
            key: const ValueKey('topicListFailed'),
            hideIcon: true,
            title: l10n.detailNetworkError,
            actionLabel: l10n.commonRetry,
            onAction: () => ref.invalidate(topicPostsProvider(_key)),
          ),
        ));
      }
      // C6：列表区骨架（正好两张卡）。不接 PrimaryScrollController（骨架本身不可滚）。
      return const PrimaryScrollController.none(child: FeedSkeleton());
    }

    final related = state.isEmpty
        ? (ref.watch(topicRelatedProvider(widget.name)).value ?? const <TopicSummary>[])
        : const <TopicSummary>[];

    return FeedMasonryView(
      key: ValueKey('topicFeed_${_sort.wire}'),
      usePrimaryScrollController: true,
      followSurfacePrefix: 'topic',
      items: state.items,
      hasMore: state.hasMore,
      loadingMore: state.loadingMore,
      loadMoreFailed: state.loadMoreFailed,
      loadMoreErrorLabel: l10n.feedLoadMoreError,
      deletedUserLabel: l10n.feedDeletedUser,
      onLoadMore: () => ref.read(topicPostsProvider(_key).notifier).loadMore(),
      onRefresh: _refresh,
      // 顶置位：两个页签显示同一张；无生效排期 → null → 整块不渲染（AC2.2）。
      pinned: pinned,
      onTapPinned: (item) => context.push('/content/${item.id}'),
      onTapPromo: (promo) => openPromoTarget(context, promo),
      // C9 空态：顶置照常在上，列表区换成空态块（FeedMasonryView 自带 16 的上边距，这里再补 16 = 页签下 32）。
      footer: state.isEmpty
          ? TopicEmptyView(
              displayName: detail?.displayName ?? widget.name,
              related: related,
              topGap: AppSpacing.xxl - AppSpacing.screenEdge,
            )
          : null,
      onTapItem: (item) => context.push('/content/${item.id}'),
      onLongPressItem: (item) => _report(l10n, item),
      onMoreItem: (item) => _report(l10n, item),
      onAuthorTap: (item) => openUserProfile(
        context,
        ref,
        item.authorId,
        onBlocked: _authorHidden(item.authorId),
        onReported: _authorHidden(item.authorId),
      ),
      // ⚠️ 收尾回调按**被 @ 的那个人**清列表，不是按卡片作者。
      onTapMention: (userId) => openUserProfile(
        context,
        ref,
        userId,
        entry: AccountActionEntry.mention,
        onBlocked: _authorHidden(userId),
        onReported: _authorHidden(userId),
      ),
      onCommentItem: (item) => context.push('/content/${item.id}?focus=comments'),
    );
  }

  /// 失败态 body：可下拉刷新、挂 PrimaryScrollController（与吸顶联动一致）。
  Widget _scrollable(Widget child) => RefreshIndicator(
        color: AppColors.mint,
        onRefresh: _refresh,
        child: CustomScrollView(
          primary: true,
          physics: const AlwaysScrollableScrollPhysics(),
          slivers: [SliverToBoxAdapter(child: child)],
        ),
      );
}

/// C6 页头骨架：两行说明条（进页时不知是否官方，按「可能有说明」占位）。
class _HeaderSkeleton extends StatelessWidget {
  const _HeaderSkeleton();

  @override
  Widget build(BuildContext context) {
    Widget bar(double? w) => Container(
          width: w,
          height: 11,
          decoration: BoxDecoration(
            color: AppColors.border,
            borderRadius: BorderRadius.circular(6),
          ),
        );
    return Padding(
      key: const ValueKey('topicHeaderSkeleton'),
      padding: const EdgeInsets.fromLTRB(
          AppSpacing.screenEdge, AppSpacing.xs, AppSpacing.screenEdge, AppSpacing.md),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          bar(double.infinity),
          const SizedBox(height: 6),
          bar(200),
        ],
      ),
    );
  }
}

/// 吸顶页签（UI C7「滚动后顶栏」结论：说明段与顶置卡随列表滚走，页签吸在顶栏下方）。
class _TabBarDelegate extends SliverPersistentHeaderDelegate {
  _TabBarDelegate(this.child);

  final Widget child;

  /// TabBar 文字页签 48（46 + 默认 indicatorWeight 2，与 `follow_list_page` 同一画法）+ 底部 1px 线。
  static const double extent = kTextTabBarHeight + 1;

  @override
  double get minExtent => extent;

  @override
  double get maxExtent => extent;

  @override
  Widget build(BuildContext context, double shrinkOffset, bool overlapsContent) =>
      SizedBox.expand(child: child);

  @override
  bool shouldRebuild(covariant _TabBarDelegate oldDelegate) => oldDelegate.child != child;
}

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/network/problem_detail.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/letter_avatar.dart';
import '../../user_profile/data/public_profile_repository.dart';
import '../../user_profile/presentation/public_profile_page.dart';
import '../data/follow_suggestion_repository.dart';
import '../domain/follow_list_item.dart';
import 'blocked_list_skeleton.dart';
import 'follow_list_controller.dart';
import 'widgets/follow_button.dart';

/// 关注 / 粉丝列表页（V1.3.4 follow · Story 2.2 · UI 稿 B1 / B3 / B7 ~ B10）。
///
/// 路由 `/users/:userId/follows?tab=following|followers`（游客可看）。页面只拿路由里的 `userId`，
/// 主页用户的 `userToken` / 昵称 / `self` 一律取 `publicProfileProvider(userId)`：
/// 深链 / 进程恢复不丢；标题与主页同源；被拉黑 / 注销 / 封号的判定直接复用主页接口的结论。
///
/// 行样式复用拉黑列表（LetterAvatar 40 + w600 昵称 + 右侧按钮、行间 1px 分隔），
/// **没有第二行小字、没有「⋮」**（FR-140.9）。
class FollowListPage extends ConsumerStatefulWidget {
  const FollowListPage({super.key, required this.userId, this.initialTab = FollowListTab.following});

  /// 路由模板（shell 外顶层，紧挨主页路由）。
  static const String routePattern = '${PublicProfilePage.routeBase}/:userId/follows';

  /// 拼某人列表页的路径。
  static String location(int userId, FollowListTab tab) =>
      '${PublicProfilePage.routeBase}/$userId/follows?tab=${tab.wire}';

  final int userId;
  final FollowListTab initialTab;

  @override
  ConsumerState<FollowListPage> createState() => _FollowListPageState();
}

class _FollowListPageState extends ConsumerState<FollowListPage> with SingleTickerProviderStateMixin {
  late final TabController _tabs = TabController(
    length: 2,
    vsync: this,
    initialIndex: widget.initialTab == FollowListTab.followers ? 1 : 0,
  );

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(publicProfileProvider(widget.userId));
    final profile = async.value;
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(
        backgroundColor: AppColors.base,
        scrolledUnderElevation: 0,
        title: Text(
          profile == null || profile.isDeactivated ? '' : (profile.nickname ?? ''),
          key: const ValueKey('followListTitle'),
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: AppTypography.title,
        ),
      ),
      body: async.when(
        loading: () => const BlockedListSkeleton(),
        error: (e, _) => _profileError(l10n, e),
        data: (p) {
          final token = p.userToken;
          if (p.isDeactivated || token == null) {
            return _pageEmpty(const ValueKey('followListNotFound'), Icons.person_off_outlined, l10n.profileNotFound);
          }
          return Column(
            children: [
              _tabBar(l10n),
              Expanded(
                child: TabBarView(
                  controller: _tabs,
                  children: [
                    for (final tab in FollowListTab.values)
                      _FollowListTabView(
                        key: PageStorageKey('followList_${tab.wire}'),
                        ownerId: widget.userId,
                        token: token,
                        tab: tab,
                        ownerIsSelf: p.self,
                      ),
                  ],
                ),
              ),
            ],
          );
        },
      ),
    );
  }

  /// UI 稿 `.tabs`：14 号、未选 w500 muted、选中 w600 ink + 3px 品牌紫下划线、底部 1px 分隔线；不带数量。
  Widget _tabBar(AppLocalizations l10n) => Container(
        decoration: const BoxDecoration(
          border: Border(bottom: BorderSide(color: AppColors.line)),
        ),
        child: TabBar(
          controller: _tabs,
          labelColor: AppColors.ink,
          unselectedLabelColor: AppColors.muted,
          labelStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
          unselectedLabelStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w500),
          indicator: const UnderlineTabIndicator(
            borderSide: BorderSide(color: AppColors.mint, width: 3),
          ),
          indicatorSize: TabBarIndicatorSize.label,
          dividerHeight: 0,
          tabs: [
            Tab(key: const ValueKey('followListTabFollowing'), text: l10n.followStatFollowing),
            Tab(key: const ValueKey('followListTabFollowers'), text: l10n.followStatFollowers),
          ],
        ),
      );

  /// 主页接口的错误分支照抄主页：403 blocked-user → 已拉黑（无重试）；404 → 不存在；其余 → 失败可重试。
  Widget _profileError(AppLocalizations l10n, Object e) {
    final problem = e is DioException ? ProblemDetail.fromDioException(e) : null;
    if (problem?.typeSlug == 'blocked-user') {
      return _pageEmpty(const ValueKey('followListBlocked'), Icons.block_rounded, l10n.profileBlockedEmpty);
    }
    if (problem?.status == 404) {
      return _pageEmpty(const ValueKey('followListNotFound'), Icons.person_off_outlined, l10n.profileNotFound);
    }
    return _ListError(onRetry: () => ref.invalidate(publicProfileProvider(widget.userId)));
  }

  Widget _pageEmpty(Key key, IconData icon, String message) => Center(
        key: key,
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.xl),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, size: 56, color: AppColors.textTertiary),
              const SizedBox(height: AppSpacing.md),
              Text(message,
                  textAlign: TextAlign.center,
                  style: AppTypography.body.copyWith(fontWeight: FontWeight.w700, color: AppColors.ink)),
            ],
          ),
        ),
      );
}

/// 一个 Tab 的列表（各自独立分页状态；切 Tab 不丢已加载数据）。
class _FollowListTabView extends ConsumerStatefulWidget {
  const _FollowListTabView({
    super.key,
    required this.ownerId,
    required this.token,
    required this.tab,
    required this.ownerIsSelf,
  });

  final int ownerId;
  final String token;
  final FollowListTab tab;
  final bool ownerIsSelf;

  @override
  ConsumerState<_FollowListTabView> createState() => _FollowListTabViewState();
}

class _FollowListTabViewState extends ConsumerState<_FollowListTabView>
    with AutomaticKeepAliveClientMixin {
  /// AC7.1：同一页面实例内同一 Tab 只报一次 `follow_list_viewed`（首页数据到达后）。
  bool _reported = false;

  /// Story 2.3 AC4.1（T-23）：同一页面实例内同一 Tab 只报一次 `suggested_follow_shown`（推荐区至少 1 行时）。
  bool _suggestedReported = false;

  FollowListKey get _key => (token: widget.token, tab: widget.tab);

  /// AD-7：我自己的 Mengikuti 列表 = 常驻位置（三态常显、取关后该行保留）。
  bool get _persistent => widget.ownerIsSelf && widget.tab == FollowListTab.following;

  @override
  bool get wantKeepAlive => true;

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(followListControllerProvider(_key));
    final loaded = async.value;
    if (!_reported && loaded != null && !async.isLoading) {
      // 首页数据到达后报一次（切到这个 Tab 时才构建 → 「进页与切 Tab 各一次」）；刷新 / 加载更多不报。
      _reported = true;
      Analytics.capture('follow_list_viewed', {
        'tab': widget.tab.wire,
        'is_self': widget.ownerIsSelf,
        'is_empty': loaded.items.isEmpty,
      });
    }
    return RefreshIndicator(
      onRefresh: () {
        // Story 2.3 AC3.3：我自己的列表下拉刷新连推荐一起重拉（已关注的人由服务端排除）。
        // 推荐快照发请求前重记版本号 → 本页之前写的覆盖对新推荐自动失效（Story 2.1 AC3.5）。
        if (widget.ownerIsSelf) ref.invalidate(followSuggestionsProvider);
        return ref.read(followListControllerProvider(_key).notifier).refresh();
      },
      child: async.when(
        skipLoadingOnRefresh: true,
        loading: () => const BlockedListSkeleton(),
        // 🔴 绝不把请求错误画成空态（bug 20260625-088）。
        error: (_, _) => _ListError(onRetry: () => ref.invalidate(followListControllerProvider(_key))),
        // Story 2.3 AC2：我自己的空列表换成「提示条 + 推荐」；别人的空列表保持普通空态（UI 稿 B8）。
        data: (s) => s.items.isEmpty
            ? (widget.ownerIsSelf ? _selfEmpty(l10n) : _empty(l10n))
            : _list(l10n, s),
      ),
    );
  }

  Widget _list(AppLocalizations l10n, FollowListState s) =>
      NotificationListener<ScrollNotification>(
        onNotification: (n) {
          if (n.metrics.extentAfter < 300 && s.hasMore && !s.loadingMore && !s.loadMoreFailed) {
            ref.read(followListControllerProvider(_key).notifier).loadMore();
          }
          return false;
        },
        child: ListView.separated(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
          itemCount: s.items.length + (s.hasMore || s.loadMoreFailed ? 1 : 0),
          separatorBuilder: (_, _) => const Divider(height: 1, thickness: 1, color: AppColors.line2),
          itemBuilder: (context, i) {
            if (i < s.items.length) return _row(l10n, s.items[i]);
            return _footer(l10n, s);
          },
        ),
      );

  Widget _footer(AppLocalizations l10n, FollowListState s) => Padding(
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
        child: Center(
          child: s.loadMoreFailed
              ? TextButton(
                  key: const ValueKey('followListLoadMoreRetry'),
                  onPressed: () => ref.read(followListControllerProvider(_key).notifier).loadMore(),
                  child: Text(l10n.feedRetry),
                )
              : const SizedBox(
                  width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)),
        ),
      );

  /// 一行（列表与推荐共用，Story 2.3 AC2.1）。[surfaceKey] / [persistent] / [source] 缺省为列表行的取值。
  Widget _row(
    AppLocalizations l10n,
    FollowListItem item, {
    String? surfaceKey,
    bool? persistent,
    String source = 'follow_list',
  }) {
    final name = item.deleted ? l10n.feedDeletedUser : (item.nickname ?? l10n.feedDeletedUser);
    final token = item.userToken;
    final identity = Row(
      children: [
        LetterAvatar(url: item.deleted ? null : item.avatarUrl, name: name, deleted: item.deleted, size: 40),
        const SizedBox(width: 12),
        Expanded(
          child: Text(name,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: AppTypography.body.copyWith(fontWeight: FontWeight.w600)),
        ),
      ],
    );
    // 注销行：不可点、无按钮；自己那一行 / 我拉黑的人：无按钮（AC4 / AC5）。
    final showButton = !item.deleted && !item.self && !item.blockedByMe && token != null;
    return Padding(
      key: ValueKey(surfaceKey == null ? 'followListRow_${item.userId}' : 'followSuggestRow_${item.userId}'),
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Row(
        children: [
          Expanded(
            child: item.deleted
                ? identity
                : GestureDetector(
                    key: ValueKey('followListOpen_${item.userId}'),
                    behavior: HitTestBehavior.opaque,
                    // T-21：from 记 avatar（PRD 定死三值，不新增）。
                    onTap: () => openUserProfile(context, ref, item.userId),
                    child: identity,
                  ),
          ),
          if (showButton) ...[
            const SizedBox(width: 8),
            FollowButton(
              userId: item.userId,
              userToken: token,
              initial: item.followState,
              snapshotEpoch: item.followEpoch,
              surfaceKey: surfaceKey ?? 'list:${widget.tab.wire}:${widget.ownerId}:${item.userId}',
              style: FollowButtonStyle.listCompact,
              persistent: persistent ?? _persistent,
              source: source,
            ),
          ],
        ],
      ),
    );
  }

  /// Story 2.3 · UI 稿 B4 / B5：我自己的空 Tab = 浅紫提示条 +「Disarankan untukmu」+ 推荐行。
  ///
  /// 推荐加载中 → 提示条 + 骨架；失败或空数组 → 只剩提示条（T-24）。外层可滚（下拉刷新可用）。
  /// 推荐行属非常驻位置（AD-7）：点关注后本按钮保留「Mengikuti」可再点取消，行不移除。
  Widget _selfEmpty(AppLocalizations l10n) {
    final following = widget.tab == FollowListTab.following;
    final suggestions = ref.watch(followSuggestionsProvider);
    final items = suggestions.value ?? const <FollowListItem>[];
    if (!_suggestedReported && items.isNotEmpty) {
      _suggestedReported = true;
      Analytics.capture('suggested_follow_shown');
    }
    return ListView(
      key: ValueKey('followListSelfEmpty_${widget.tab.wire}'),
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
      children: [
        Container(
          key: const ValueKey('followListSelfEmptyHint'),
          margin: const EdgeInsets.only(top: 14, bottom: 4),
          padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 16),
          decoration: BoxDecoration(
            color: AppColors.cream2,
            borderRadius: BorderRadius.circular(14),
          ),
          child: Row(
            children: [
              Icon(following ? Icons.group : Icons.person_add, size: 28, color: AppColors.mint),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  following ? l10n.followListEmptySelfFollowing : l10n.followListEmptyFollowers,
                  style: const TextStyle(fontSize: 13, color: AppColors.ink2),
                ),
              ),
            ],
          ),
        ),
        if (suggestions.isLoading && !suggestions.hasValue)
          const SizedBox(
            key: ValueKey('followSuggestLoading'),
            height: 320,
            child: BlockedListSkeleton(),
          )
        else if (items.isNotEmpty) ...[
          Padding(
            padding: const EdgeInsets.only(top: 16, bottom: 2),
            child: Text(
              l10n.followSuggestedTitle.toUpperCase(),
              key: const ValueKey('followSuggestTitle'),
              style: const TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w700,
                letterSpacing: 0.6,
                color: AppColors.muted,
              ),
            ),
          ),
          for (var i = 0; i < items.length; i++) ...[
            if (i > 0) const Divider(height: 1, thickness: 1, color: AppColors.line2),
            _row(
              l10n,
              items[i],
              surfaceKey: 'suggest:${widget.tab.wire}:${items[i].userId}',
              persistent: false,
              source: 'suggested',
            ),
          ],
        ],
      ],
    );
  }

  /// UI 稿 B8：居中图标 48 + 标题；外层可滚（下拉刷新可用）。别人的空列表（我自己的空列表见 [_selfEmpty]）。
  Widget _empty(AppLocalizations l10n) {
    final following = widget.tab == FollowListTab.following;
    return ListView(
      key: ValueKey('followListEmpty_${widget.tab.wire}'),
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.xl),
      children: [
        const SizedBox(height: 120),
        Icon(following ? Icons.group_outlined : Icons.person_add_outlined, size: 48, color: AppColors.muted),
        const SizedBox(height: AppSpacing.md),
        Text(following ? l10n.followListEmptyFollowing : l10n.followListEmptyFollowers,
            textAlign: TextAlign.center, style: AppTypography.title),
      ],
    );
  }
}

/// 失败态（拉黑列表同款）：标题 + 正文 + 描边「Coba lagi」，只重拉当前 Tab。
class _ListError extends StatelessWidget {
  const _ListError({required this.onRetry});

  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.xl),
      children: [
        const SizedBox(height: 120),
        Text(l10n.blockedListLoadFailed, textAlign: TextAlign.center, style: AppTypography.title),
        const SizedBox(height: AppSpacing.sm),
        Text(l10n.blockedListLoadFailedBody,
            textAlign: TextAlign.center,
            style: AppTypography.body.copyWith(color: AppColors.textSecondary)),
        const SizedBox(height: AppSpacing.lg),
        Center(
          child: OutlinedButton(
            key: const ValueKey('followListRetry'),
            onPressed: onRetry,
            child: Text(l10n.feedRetry),
          ),
        ),
      ],
    );
  }
}

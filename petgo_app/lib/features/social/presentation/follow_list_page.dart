import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/network/problem_detail.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../auth/domain/auth_state.dart';
import '../../user_profile/data/public_profile_repository.dart';
import '../../user_profile/presentation/public_profile_page.dart';
import '../data/follow_suggestion_repository.dart';
import '../domain/follow_list_item.dart';
import 'blocked_list_skeleton.dart';
import 'follow_list_controller.dart';
import 'widgets/follow_user_row.dart';
import 'widgets/suggested_follow_list.dart';

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
  // 在 initState 里建，不用懒初始化：错误态页面从不读 _tabs，懒建会拖到 dispose 才创建 → 断言崩。
  late final TabController _tabs;

  @override
  void initState() {
    super.initState();
    _tabs = TabController(
      length: 2,
      vsync: this,
      initialIndex: widget.initialTab == FollowListTab.followers ? 1 : 0,
    );
  }

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // 2.2 code-review F1：游客在本页登录（或换了账号）→ 主页信息（`self`）与两个 Tab 按新身份重取，
    // 否则自己那一行会冒出关注按钮、已关注的人仍显示 Ikuti（同主页 Story 1.3 AC5.6）。
    ref.listen<int?>(
      authControllerProvider.select((s) => s.isLoggedIn ? s.profile?.id : null),
      (prev, next) {
        if (prev == next) return;
        ref.invalidate(publicProfileProvider(widget.userId));
        ref.invalidate(followListControllerProvider);
        ref.invalidate(followSuggestionsProvider);
      },
    );
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
        // UI 稿 B9 / B10：资料加载中 / 失败也先画 Tab 栏外壳，骨架 / 错误态放在 Tab 内容区。
        loading: () => _withTabBar(l10n, const BlockedListSkeleton(rows: 7, divided: true)),
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

  /// Tab 栏 + 内容区（资料尚未就绪时的外壳）。
  Widget _withTabBar(AppLocalizations l10n, Widget content) => Column(
        children: [
          _tabBar(l10n),
          Expanded(child: content),
        ],
      );

  /// UI 稿 `.tabs`：14 号 w600、未选 muted、选中 ink + 3px 品牌紫下划线（整 Tab 宽）、底部 1px 分隔线；不带数量。
  Widget _tabBar(AppLocalizations l10n) => Container(
        decoration: const BoxDecoration(
          border: Border(bottom: BorderSide(color: AppColors.line)),
        ),
        child: TabBar(
          controller: _tabs,
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
    return _withTabBar(l10n, _ListError(onRetry: () => ref.invalidate(publicProfileProvider(widget.userId))));
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
      onRefresh: () async {
        // Story 2.3 AC3.3：我自己的列表下拉刷新连推荐一起重拉（已关注的人由服务端排除）。
        // 推荐快照发请求前重记版本号 → 本页之前写的覆盖对新推荐自动失效（Story 2.1 AC3.5）。
        if (widget.ownerIsSelf) ref.invalidate(followSuggestionsProvider);
        await ref.read(followListControllerProvider(_key).notifier).refresh();
        // 2.3 code-review F4：仍是空态（推荐区还在 watch）→ 转圈等推荐也回来再收，不让旧推荐在转圈结束后才换掉。
        // 推荐失败吞掉：失败只隐藏推荐区（T-24），不算刷新失败。
        final stillEmpty = mounted && (ref.read(followListControllerProvider(_key)).value?.items.isEmpty ?? false);
        if (widget.ownerIsSelf && stillEmpty) {
          try {
            await ref.read(followSuggestionsProvider.future);
          } catch (_) {}
        }
      },
      child: async.when(
        skipLoadingOnRefresh: true,
        loading: () => const BlockedListSkeleton(rows: 7, divided: true),
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
          if (n.metrics.extentAfter >= 300) return false;
          // ⚠️ 读**当前**状态，不用闭包里构建时的 `s`：失败落地到下一帧重建之间，惯性滚动的通知仍拿着旧 `s`
          // （loadMoreFailed=false），会绕过「失败后不自动重试、等底部 Coba lagi」直接再发一次。
          final cur = ref.read(followListControllerProvider(_key)).value;
          if (cur != null && cur.hasMore && !cur.loadingMore && !cur.loadMoreFailed) {
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

  /// 一行（关注 / 粉丝列表）；推荐行见 [SuggestedFollowList]。
  Widget _row(AppLocalizations l10n, FollowListItem item) => FollowUserRow(
        item: item,
        rowKey: ValueKey('followListRow_${item.userId}'),
        surfaceKey: 'list:${widget.tab.wire}:${widget.ownerId}:${item.userId}',
        persistent: _persistent,
        source: 'follow_list',
      );

  /// Story 2.3 · UI 稿 B4 / B5：我自己的空 Tab = 浅紫提示条 +「Disarankan untukmu」+ 推荐行。
  ///
  /// 推荐加载中 → 提示条 + 骨架；失败或空数组 → 只剩提示条（T-24）。外层可滚（下拉刷新可用）。
  /// 推荐行属非常驻位置（AD-7）：点关注后本按钮保留「Mengikuti」可再点取消，行不移除。
  Widget _selfEmpty(AppLocalizations l10n) {
    final following = widget.tab == FollowListTab.following;
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
              Icon(following ? Icons.group_outlined : Icons.person_add_outlined, size: 28, color: AppColors.mint),
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
        SuggestedFollowList(source: 'suggested', surfaceScope: 'suggest:${widget.tab.wire}'),
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

/// 失败态（UI 稿 B10 `.center.top`）：顶距 120、间距 12；标题 18 w600 + 正文 14 ink2 + `.obtn` 描边「Coba lagi」。
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
        const SizedBox(height: AppSpacing.md),
        Text(l10n.blockedListLoadFailedBody,
            textAlign: TextAlign.center,
            style: AppTypography.body.copyWith(fontSize: 14, color: AppColors.textSecondary)),
        const SizedBox(height: AppSpacing.lg),
        Center(
          child: OutlinedButton(
            key: const ValueKey('followListRetry'),
            onPressed: onRetry,
            style: OutlinedButton.styleFrom(
              foregroundColor: AppColors.mint,
              side: const BorderSide(color: AppColors.mint, width: 1.5),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
              padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 24),
              // 稿 .obtn 继承 body：Poppins 15 w700。按钮 textStyle 不合并主题字体，必须显式写 family，否则退成 Roboto
              textStyle: const TextStyle(fontFamily: 'Poppins', fontSize: 15, fontWeight: FontWeight.w700),
            ),
            child: Text(l10n.feedRetry),
          ),
        ),
      ],
    );
  }
}

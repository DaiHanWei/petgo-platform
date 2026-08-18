import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:go_router/go_router.dart';

import '../../../core/router/route_intent.dart';
import '../../../core/theme/colors.dart';
import '../../../features/auth/domain/auth_guard.dart';
import '../../../features/auth/domain/auth_state.dart';
import '../../../features/auth/domain/login_guide_controller.dart';
import '../../../features/notify/presentation/notification_bell.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/empty_state.dart';
import '../domain/feed_item.dart';
import 'feed_controller.dart';
import 'feed_skeleton.dart';
import 'feed_tab_row.dart';
import 'feed_view.dart';
import 'publish_compose_page.dart';
import 'report_sheet.dart';
import '../../../shared/widgets/mini_profile_sheet.dart';

/// 首页 Beranda（TailTopia Prototype 全面换肤）。
///
/// 固定问候头（Momo + 时段问候 + 通知铃）；随 Feed 同滚的 Beranda 头部
/// （快捷入口卡 + 每日记录提示卡 + 「Untukmu」区头 + 分类 Tab）；下方瀑布流 Feed。
/// 数据接线：feedProvider 三态、分类过滤、游客第 3 页软登录（FR-0B）、门控发布（Story 1.5）。
///
/// ⚠️ **V1.1.2 Story 2.3 起本页不再有建档提示条**：FR-0H 整条废止（AD-15 Rule 3）——
/// 首页改名 Social（V1.1.2 起；曾短暂叫 Discovery）并挪到第 4 位后，状态 A 未建档用户的落地页是 Diary，这条提示曝光趋近于零；
/// 建档引导渠道**收敛为唯一一条** = Diary 的未建档分支。勿在此重新加回任何建档提示。
class HomePage extends ConsumerWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final auth = ref.watch(authControllerProvider);
    final selectedCategory = ref.watch(feedCategoryProvider);
    final feedAsync = ref.watch(feedProvider);

    // FR-0B：游客浏览至第 3 页 → 软性登录浮层（控制器内部 session 去重）。
    ref.listen<AsyncValue<FeedState>>(feedProvider, (prev, next) {
      final state = next.value;
      if (state != null && state.pagesLoaded >= 3 && auth.status == AuthStatus.guest) {
        ref.read(loginGuideControllerProvider).showSoftSheet(context);
      }
    });

    // 推倒重做为原型 feed.html：AppBar「TailTopia 🐾」+ 通知铃；下方分类 Chips + 瀑布流。
    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(
        backgroundColor: AppColors.cream,
        scrolledUnderElevation: 0,
        titleSpacing: 20,
        // 左上角品牌标：Tailtopia wordmark。源 logo.svg(Adobe 多图层导出)经 svgo 清洗——
        // 去 display:none 隐藏层 + 内联样式转属性 + viewBox 裁到文字区，得 flutter_svg 可渲的干净 SVG。
        title: SvgPicture.asset(
          'assets/brand/logo.svg',
          height: 28,
          semanticsLabel: l10n.appTitle,
        ),
        actions: [
          if (auth.isLoggedIn)
            const Padding(
              padding: EdgeInsets.only(right: 12),
              child: NotificationBell(),
            )
          else
            // 访客态（feed-guest.html）：AppBar 右「Masuk」描边按钮 → 登录。
            Padding(
              padding: const EdgeInsets.only(right: 12),
              child: OutlinedButton(
                key: const ValueKey('feedGuestLoginButton'),
                onPressed: () => context.push('/login'),
                style: OutlinedButton.styleFrom(
                  foregroundColor: AppColors.mint,
                  side: const BorderSide(color: AppColors.dashedViolet, width: 1.5),
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
                  minimumSize: Size.zero,
                  tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                ),
                child: Text(l10n.loginTitle,
                    style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
              ),
            ),
        ],
      ),
      body: SafeArea(
        top: false,
        bottom: false,
        child: _content(context, ref, l10n, feedAsync, selectedCategory),
      ),
    );
  }

  Widget _content(
    BuildContext context,
    WidgetRef ref,
    AppLocalizations l10n,
    AsyncValue<FeedState> feedAsync,
    FeedCategory category,
  ) {
    final header = _BerandaTop(
      selectedCategory: category,
      labels: _tabLabels(l10n),
      onSelectCategory: (c) => ref.read(feedCategoryProvider.notifier).select(c),
    );

    // 头部（分类 Tab）在四态恒渲染：data 非空时随瀑布同滚；
    // 其余态包进可下拉滚动容器，保证分类 Tab 始终可见可达。
    Widget wrapped(Widget body, {Future<void> Function()? onRefresh}) {
      final scroll = SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        child: Column(children: [header, body]),
      );
      if (onRefresh == null) return scroll;
      return RefreshIndicator(color: AppColors.mint, onRefresh: onRefresh, child: scroll);
    }

    return feedAsync.when(
      loading: () => wrapped(const FeedSkeleton()),
      // AC5：首屏加载失败（无任何已加载内容）→ 失败态 + 重试入口（下拉刷新 / 重试按钮），不白屏。
      error: (error, stack) => wrapped(
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 48),
          child: EmptyState(
            // feed-error.html：标题 + 副文 + 紫「Coba Lagi」+ 灰「Laporkan Masalah」次链接，无大 icon。
            title: l10n.feedErrorTitle,
            message: l10n.feedErrorBody,
            hideIcon: true,
            actionLabel: l10n.feedRetry,
            onAction: () => ref.read(feedProvider.notifier).refresh(),
            secondaryLabel: l10n.feedReportProblem,
            onSecondary: () => ref.read(feedProvider.notifier).refresh(),
          ),
        ),
        onRefresh: () => ref.read(feedProvider.notifier).refresh(),
      ),
      data: (state) {
        if (state.isEmpty) {
          return wrapped(
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 24),
              child: EmptyState(
                // feed-empty.html：标题 + 副文 + 紫「✨ Bagikan Momen Pertama」+「Temukan Teman →」次链接，无大 icon。
                title: category == FeedCategory.growthMoment
                    ? l10n.feedGrowthEmptyTitle
                    : l10n.feedEmptyTitle,
                message: l10n.feedEmptyBody,
                hideIcon: true,
                actionLabel: l10n.feedEmptyCta,
                onAction: () => requireLogin(
                  ref,
                  context,
                  pendingAction: const RouteIntent(location: '/home'),
                  onAllowed: () => PublishComposePage.open(context),
                ),
                secondaryLabel: '${l10n.feedFindFriends} →',
                onSecondary: () => context.go('/home'),
              ),
            ),
            onRefresh: () => ref.read(feedProvider.notifier).refresh(),
          );
        }
        // 访客登录引导卡已下线（不再显示登录/注册按钮）；游客与登录态一致自动翻页浏览。
        return FeedMasonryView(
          header: header,
          autoLoadMore: true,
          footer: null,
          items: state.items,
          hasMore: state.hasMore,
          loadingMore: state.loadingMore,
          loadMoreFailed: state.loadMoreFailed,
          loadMoreErrorLabel: l10n.feedLoadMoreError,
          deletedUserLabel: l10n.feedDeletedUser,
          onLoadMore: () => ref.read(feedProvider.notifier).loadMore(),
          onRefresh: () => ref.read(feedProvider.notifier).refresh(),
          onTapItem: (item) => context.push('/content/${item.id}'),
          onLongPressItem: (item) => openReport(context, ref, item.id, onReported: () {
            // cm-6 §6.1：举报成功 → 乐观移除卡片 +「不再向你展示」提示（后端 §5.4 已过滤，刷新亦不复现）。
            ref.read(feedProvider.notifier).removeItem(item.id);
            if (context.mounted) {
              ScaffoldMessenger.of(context)
                ..hideCurrentSnackBar()
                ..showSnackBar(SnackBar(content: Text(l10n.reportHiddenToast)));
            }
          }),
          onAuthorTap: (item) => showMiniProfile(context, ref, item.authorId),
          // V1.1.6 Story 3.2：评论跳详情页并**定位到评论区**。
          // ⚠️ `?focus=comments` 是既有参数名（通知深链一直在产出它），两侧必须同名。
          onCommentItem: (item) => context.push('/content/${item.id}?focus=comments'),
          // 「···」：Feed 此前只有长按举报，没有显式入口；两者走同一个动作。
          onMoreItem: (item) => openReport(context, ref, item.id, onReported: () {
            ref.read(feedProvider.notifier).removeItem(item.id);
            if (context.mounted) {
              ScaffoldMessenger.of(context)
                ..hideCurrentSnackBar()
                ..showSnackBar(SnackBar(content: Text(l10n.reportHiddenToast)));
            }
          }),
        );
      },
    );
  }

  Map<FeedCategory, String> _tabLabels(AppLocalizations l10n) => {
        FeedCategory.all: l10n.feedTabAll,
        FeedCategory.daily: l10n.feedTabDaily,
        FeedCategory.growthMoment: l10n.feedTabGrowth,
        FeedCategory.knowledge: l10n.feedTabKnowledge,
      };
}

/// Social 滚动头部（原型 feed.html）：分类 Chips。
/// 已移除 Momo 问候头 / 快捷入口卡 / 每日提示卡 / 「Untukmu」区头（推倒重做决策 #6），
/// 以及建档提示条（V1.1.2 Story 2.3：FR-0H 整条废止，**顶部不再预留该区域**）。
class _BerandaTop extends StatelessWidget {
  const _BerandaTop({
    required this.selectedCategory,
    required this.labels,
    required this.onSelectCategory,
  });

  final FeedCategory selectedCategory;
  final Map<FeedCategory, String> labels;
  final ValueChanged<FeedCategory> onSelectCategory;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: 8),
        FeedTabRow(selected: selectedCategory, labels: labels, onSelected: onSelectCategory),
        const SizedBox(height: 8),
      ],
    );
  }
}

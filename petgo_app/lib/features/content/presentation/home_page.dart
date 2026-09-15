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
import '../../../features/place/presentation/place_entry_row.dart';
import '../../profile/data/pet_recommendation_repository.dart';
import '../../profile/presentation/widgets/pet_recommendation_strip.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../social/domain/account_action_entry.dart';
import '../../user_profile/presentation/public_profile_page.dart';
import '../domain/feed_item.dart';
import 'author_moderation_callbacks.dart';
import 'feed_controller.dart';
import 'feed_skeleton.dart';
import 'feed_tab_row.dart';
import 'feed_view.dart';
import 'promo_target.dart';
import 'publish_compose_page.dart';
import 'report_sheet.dart';

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
          // V1.1.6 Story 16.5：FR-95 的效果归因靠这两个属性。
          // 🔴 rank_mode 由**服务端下发**（state.rankMode）而不是按 category 自己推 ——
          // 降级链级别 4 会让 ALL Tab 也走时间倒序，那对客户端完全无感。
          feedTab: category.analyticsTab,
          rankMode: state.rankMode,
          // 顶置坑位（V1.1.6 Story 4.2）：取数失败或无生效配置 → null → 什么都不渲染。
          pinned: ref.watch(pinnedSlotProvider).value,
          onTapPinned: (item) => context.push('/content/${item.id}'),
          onTapPromo: (promo) => openPromoTarget(context, promo),
          autoLoadMore: true,
          footer: null,
          items: state.items,
          hasMore: state.hasMore,
          loadingMore: state.loadingMore,
          loadMoreFailed: state.loadMoreFailed,
          loadMoreErrorLabel: l10n.feedLoadMoreError,
          deletedUserLabel: l10n.feedDeletedUser,
          onLoadMore: () => ref.read(feedProvider.notifier).loadMore(),
          onRefresh: () async {
            // 顶置与首页各自取数，下拉刷新要**两边一起**刷 —— 只刷一边会让坑位停在旧配置上。
            ref.invalidate(pinnedSlotProvider);
            // 宠物横滑行（Story 4.4）也在这一屏上，同理一起刷。
            // 🔴 漏了它的表现很难受：那条 provider 关掉了自动重试、又被常驻的首页钉着不回收，
            //    于是**断网启动**那一次失败会让整行永久消失，下拉刷新也救不回来，只能杀进程
            //    （code-review 2026-09-15 实测）。
            ref.invalidate(petRecommendationsProvider);
            await ref.read(feedProvider.notifier).refresh();
          },
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
          // V1.3.0 batch-b1 Story 2.1：点头像**直接进完整主页**，不再弹迷你卡（FR-118.1）。
          onAuthorTap: (item) => openUserProfile(
            context,
            ref,
            item.authorId,
            // 拉黑 / 举报成功 → 该作者在当前列表的**全部**卡片立刻消失。
            // 拉黑的成功 Toast 由主页统一给；**举报一律静默**（提示会泄露「举报会隐藏内容」）。
            onBlocked: onAuthorHidden(ref, item.authorId),
            onReported: onAuthorHidden(ref, item.authorId),
          ),
          // V1.3.0 batch-b1 Story 3.3：点正文里的 @ → 那个人的公开主页（AC2）。
          // ⚠️ 收尾回调按**被 @ 的那个人**清列表，不是按卡片作者 —— 两者通常不是同一个人。
          onTapMention: (userId) => openUserProfile(
            context,
            ref,
            userId,
            entry: AccountActionEntry.mention,
            onBlocked: onAuthorHidden(ref, userId),
            onReported: onAuthorHidden(ref, userId),
          ),
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

/// Social 滚动头部（原型 feed.html）：场所入口行 + 分类 Chips。
/// 已移除 Momo 问候头 / 快捷入口卡 / 每日提示卡 / 「Untukmu」区头（推倒重做决策 #6），
/// 以及建档提示条（V1.1.2 Story 2.3：FR-0H 整条废止，**顶部不再预留该区域**）。
///
/// V1.3.0 batch-b1 Story 1.1（AC7 · FR-112.4）：分类 chips **之上**新增场所入口行。
/// ⚠️ AppBar（品牌标 + 通知铃）、分类 chips、瀑布流、底部 Tab **一处不改**。
/// Epic 4 的宠物横滑行将加在场所入口行**之下**，本 story **不为它预留占位**。
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
        // 场所入口行（Story 1.1 AC7）：在分类 chips 之上。
        const PlaceEntryRow(),
        // 宠物横滑行（Story 4.4 AC1/AC2）：在场所入口行**之下**、分类 chips **之上**。
        // 🛡 游客 / 池子为空 / 取不到 → 整行不渲染（AC3）。判断全在组件内部，
        //    这里**不留占位** —— 留一个空 SizedBox 会让下一个人以为它没接上。
        const PetRecommendationStrip(),
        FeedTabRow(selected: selectedCategory, labels: labels, onSelected: onSelectCategory),
        const SizedBox(height: 8),
      ],
    );
  }
}

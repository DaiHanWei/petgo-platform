import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/router/route_intent.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/motion.dart';
import '../../../core/theme/spacing.dart';
import '../../../features/auth/domain/auth_guard.dart';
import '../../../features/auth/domain/auth_state.dart';
import '../../../features/auth/domain/login_guide_controller.dart';
import '../../../features/profile/domain/profile_prompt_controller.dart';
import '../../../features/profile/domain/profile_prompt_state.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../../shared/widgets/profile_prompt_bar.dart';
import '../domain/feed_item.dart';
import 'feed_controller.dart';
import 'feed_skeleton.dart';
import 'feed_tab_row.dart';
import 'feed_view.dart';
import 'publish_compose_page.dart';
import 'report_sheet.dart';

/// 首页 Tab（Story 3.2 Feed 内容流）。
///
/// 顶部档案提示条（FR-0H，仅状态 A 未完成档案）+ 分类 Tab Row + 瀑布流 Feed（cross-fade 切分类）。
/// 三态：loading 骨架 / data 瀑布流或空态 / error 可下拉重试。
/// FR-0B 接线：游客浏览至第 3 页 → 触发软性登录浮层（每 session 一次）。
class HomePage extends ConsumerWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final auth = ref.watch(authControllerProvider);
    final promptState = ref.watch(profilePromptProvider);
    final bool showPrompt = shouldShowProfilePrompt(
      petStatus: auth.profile?.petStatus,
      hasPetProfile: auth.profile?.hasPetProfile ?? false,
      state: promptState,
    );

    final selectedCategory = ref.watch(feedCategoryProvider);
    final feedAsync = ref.watch(feedProvider);

    // FR-0B：游客浏览至第 3 页 → 软性登录浮层（控制器内部 session 去重）。
    ref.listen<AsyncValue<FeedState>>(feedProvider, (prev, next) {
      final state = next.value;
      if (state != null && state.pagesLoaded >= 3 && auth.status == AuthStatus.guest) {
        ref.read(loginGuideControllerProvider).showSoftSheet(context);
      }
    });

    return Scaffold(
      backgroundColor: AppColors.base,
      body: SafeArea(
        child: Column(
          children: [
            if (showPrompt)
              Padding(
                padding: const EdgeInsets.fromLTRB(
                    AppSpacing.screenEdge, AppSpacing.sm, AppSpacing.screenEdge, 0),
                child: ProfilePromptBar(
                  onCreate: () => context.go('/onboarding/profile'),
                  onDismiss: () => ref.read(profilePromptProvider.notifier).dismiss(),
                ),
              ),
            Padding(
              padding: const EdgeInsets.only(top: AppSpacing.sm),
              child: FeedTabRow(
                selected: selectedCategory,
                labels: _tabLabels(l10n),
                onSelected: (c) => ref.read(feedCategoryProvider.notifier).select(c),
              ),
            ),
            Expanded(
              // 切分类 cross-fade（不 slide，UX-DR5）。
              child: AnimatedSwitcher(
                duration: AppMotion.tabFade,
                child: KeyedSubtree(
                  key: ValueKey(selectedCategory),
                  child: _content(context, ref, l10n, feedAsync, selectedCategory),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _content(BuildContext context, WidgetRef ref, AppLocalizations l10n,
      AsyncValue<FeedState> feedAsync, FeedCategory category) {
    return feedAsync.when(
      loading: () => const FeedSkeleton(),
      error: (error, stack) => _RefreshableMessage(
        message: l10n.feedLoadError,
        onRefresh: () => ref.read(feedProvider.notifier).refresh(),
      ),
      data: (state) {
        if (state.isEmpty) {
          return _RefreshableMessage.empty(
            onRefresh: () => ref.read(feedProvider.notifier).refresh(),
            child: EmptyState(
              title: category == FeedCategory.growthMoment
                  ? l10n.feedGrowthEmptyTitle
                  : l10n.feedEmptyTitle,
              message: l10n.feedEmptyBody,
              actionLabel: l10n.feedEmptyCta,
              // CTA 受 Story 1.5 门控：游客触发 FR-0C，已登录开发布。
              onAction: () => requireLogin(
                ref,
                context,
                pendingAction: const RouteIntent(location: '/home'),
                onAllowed: () => PublishComposePage.open(context),
              ),
            ),
          );
        }
        return FeedMasonryView(
          items: state.items,
          hasMore: state.hasMore,
          loadingMore: state.loadingMore,
          deletedUserLabel: l10n.feedDeletedUser,
          onLoadMore: () => ref.read(feedProvider.notifier).loadMore(),
          onRefresh: () => ref.read(feedProvider.notifier).refresh(),
          // 点卡进详情（Story 3.3）：push 非 replace，返回保持 Feed 滚动位置。
          onTapItem: (item) => context.push('/content/${item.id}'),
          // 长按举报（Story 3.7，UX-DR12）：未登录触发 FR-0C。
          onLongPressItem: (item) => openReport(context, ref, item.id),
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

/// 空态 / 错误态包一层可下拉刷新的滚动容器（保证下拉手势可用）。
class _RefreshableMessage extends StatelessWidget {
  const _RefreshableMessage({required this.message, required this.onRefresh}) : child = null;

  const _RefreshableMessage.empty({required this.onRefresh, required this.child}) : message = null;

  final String? message;
  final Widget? child;
  final Future<void> Function() onRefresh;

  @override
  Widget build(BuildContext context) {
    return RefreshIndicator(
      color: AppColors.accentGrowth,
      onRefresh: onRefresh,
      child: LayoutBuilder(
        builder: (context, constraints) => SingleChildScrollView(
          physics: const AlwaysScrollableScrollPhysics(),
          child: ConstrainedBox(
            constraints: BoxConstraints(minHeight: constraints.maxHeight),
            child: child ?? EmptyState(title: message!, icon: Icons.cloud_off_rounded),
          ),
        ),
      ),
    );
  }
}

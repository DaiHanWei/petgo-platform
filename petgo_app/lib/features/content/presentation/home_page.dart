import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/mock/mock_config.dart';
import '../../../core/router/route_intent.dart';
import '../../../core/theme/colors.dart';
import '../../notify/presentation/push_permission_sheet.dart';
import '../../../features/auth/domain/auth_guard.dart';
import '../../../features/auth/domain/auth_state.dart';
import '../../../features/auth/domain/login_guide_controller.dart';
import '../../../features/profile/domain/profile_prompt_controller.dart';
import '../../../features/profile/domain/profile_prompt_state.dart';
import '../../../features/notify/presentation/notification_bell.dart';
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
import '../../../shared/widgets/mini_profile_sheet.dart';

/// 首页 Beranda（TailTopia Prototype 全面换肤）。
///
/// 固定问候头（Momo + 时段问候 + 通知铃）；随 Feed 同滚的 Beranda 头部
/// （快捷入口卡 + 每日记录提示卡 + 「Untukmu」区头 + 分类 Tab）；下方瀑布流 Feed。
/// 保留全部数据接线：feedProvider 三态、分类过滤、档案提示条（FR-0H）、
/// 游客第 3 页软登录（FR-0B）、门控发布（Story 1.5）。
/// Debug 截图钩子一次性 guard（DEV_STATE=notif-gate 自动弹推送权限 sheet，截 notif-gate 用）。
bool _devNotifGateShown = false;

class HomePage extends ConsumerWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    if (kDevState == 'notif-gate' && !_devNotifGateShown) {
      _devNotifGateShown = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (context.mounted) showPushPermissionSheet(context);
      });
    }
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

    // 推倒重做为原型 feed.html：AppBar「TailTopia 🐾」+ 通知铃；下方分类 Chips + 瀑布流。
    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(
        backgroundColor: AppColors.cream,
        scrolledUnderElevation: 0,
        titleSpacing: 20,
        title: Text('${l10n.appTitle} 🐾',
            style: const TextStyle(
                fontSize: 19, fontWeight: FontWeight.w700, color: AppColors.ink)),
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
                child: const Text('Masuk',
                    style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
              ),
            ),
        ],
      ),
      body: SafeArea(
        top: false,
        bottom: false,
        child: _content(context, ref, l10n, feedAsync, selectedCategory, showPrompt),
      ),
    );
  }

  Widget _content(
    BuildContext context,
    WidgetRef ref,
    AppLocalizations l10n,
    AsyncValue<FeedState> feedAsync,
    FeedCategory category,
    bool showPrompt,
  ) {
    final header = _BerandaTop(
      showPrompt: showPrompt,
      selectedCategory: category,
      labels: _tabLabels(l10n),
      onSelectCategory: (c) => ref.read(feedCategoryProvider.notifier).select(c),
      onPromptCreate: () => context.go('/onboarding/profile'),
      onPromptDismiss: () => ref.read(profilePromptProvider.notifier).dismiss(),
    );

    // 头部（提示条+快捷入口+每日卡+Untukmu+分类Tab）在四态恒渲染：
    // data 非空时随瀑布同滚；其余态包进可下拉滚动容器，保证 Tab/提示条始终可见可达。
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
            title: 'Gagal memuat feed',
            message: 'Periksa koneksi internet kamu dan coba lagi.',
            hideIcon: true,
            actionLabel: l10n.feedRetry,
            onAction: () => ref.read(feedProvider.notifier).refresh(),
            secondaryLabel: 'Laporkan Masalah',
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
                secondaryLabel: 'Temukan Teman →',
                onSecondary: () => context.go('/home'),
              ),
            ),
            onRefresh: () => ref.read(feedProvider.notifier).refresh(),
          );
        }
        final isGuest = ref.read(authControllerProvider).status == AuthStatus.guest;
        return FeedMasonryView(
          header: header,
          footer: isGuest ? _GuestJoinBanner(onLogin: () => context.push('/login')) : null,
          items: state.items,
          hasMore: state.hasMore,
          loadingMore: state.loadingMore,
          loadMoreFailed: state.loadMoreFailed,
          loadMoreErrorLabel: l10n.feedLoadMoreError,
          deletedUserLabel: l10n.feedDeletedUser,
          onLoadMore: () => ref.read(feedProvider.notifier).loadMore(),
          onRefresh: () => ref.read(feedProvider.notifier).refresh(),
          onTapItem: (item) => context.push('/content/${item.id}'),
          onLongPressItem: (item) => openReport(context, ref, item.id),
          onAuthorTap: (item) => showMiniProfile(context, ref, item.authorId),
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

/// Beranda 滚动头部（原型 feed.html）：档案提示条（FR-0H）+ 分类 Chips。
/// 已移除 Momo 问候头 / 快捷入口卡 / 每日提示卡 / 「Untukmu」区头（推倒重做决策 #6）。
class _BerandaTop extends StatelessWidget {
  const _BerandaTop({
    required this.showPrompt,
    required this.selectedCategory,
    required this.labels,
    required this.onSelectCategory,
    required this.onPromptCreate,
    required this.onPromptDismiss,
  });

  final bool showPrompt;
  final FeedCategory selectedCategory;
  final Map<FeedCategory, String> labels;
  final ValueChanged<FeedCategory> onSelectCategory;
  final VoidCallback onPromptCreate;
  final VoidCallback onPromptDismiss;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (showPrompt)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
            child: ProfilePromptBar(onCreate: onPromptCreate, onDismiss: onPromptDismiss),
          ),
        const SizedBox(height: 8),
        FeedTabRow(selected: selectedCategory, labels: labels, onSelected: onSelectCategory),
        const SizedBox(height: 8),
      ],
    );
  }
}

/// 访客登录引导横幅（feed-guest.html 底部）：紫渐变卡 + 标题/副文 + Daftar Gratis / Masuk 双钮 + 「Lanjut lihat dulu →」。
class _GuestJoinBanner extends StatelessWidget {
  const _GuestJoinBanner({required this.onLogin});

  final VoidCallback onLogin;

  @override
  Widget build(BuildContext context) {
    return Container(
      key: const ValueKey('feedGuestJoinBanner'),
      margin: const EdgeInsets.only(top: 4, bottom: 8),
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(20),
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [AppColors.mint, AppColors.mint500],
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Bergabunglah dengan komunitas!',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: Colors.white)),
          const SizedBox(height: 6),
          Text('Rekam tumbuh kembang, konsultasi dokter hewan, dan bagikan momen berharga bersama mereka.',
              style: TextStyle(fontSize: 12, height: 1.5, color: Colors.white.withValues(alpha: 0.85))),
          const SizedBox(height: 14),
          Row(
            children: [
              Expanded(
                flex: 2,
                child: FilledButton(
                  onPressed: onLogin,
                  style: FilledButton.styleFrom(
                    backgroundColor: Colors.white,
                    foregroundColor: AppColors.mint,
                    padding: const EdgeInsets.symmetric(vertical: 12),
                  ),
                  child: const Text('Daftar Gratis',
                      style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700)),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: OutlinedButton(
                  onPressed: onLogin,
                  style: OutlinedButton.styleFrom(
                    foregroundColor: Colors.white,
                    backgroundColor: Colors.white.withValues(alpha: 0.15),
                    side: BorderSide(color: Colors.white.withValues(alpha: 0.4), width: 1.5),
                    padding: const EdgeInsets.symmetric(vertical: 12),
                  ),
                  child: const Text('Masuk',
                      style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Center(
            child: TextButton(
              onPressed: () {},
              child: Text('Lanjut lihat dulu →',
                  style: TextStyle(fontSize: 12, color: Colors.white.withValues(alpha: 0.8))),
            ),
          ),
        ],
      ),
    );
  }
}


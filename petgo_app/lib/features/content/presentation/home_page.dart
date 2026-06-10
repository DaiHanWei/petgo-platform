import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/router/route_intent.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/shadows.dart';
import '../../../features/auth/domain/auth_guard.dart';
import '../../../features/auth/domain/auth_state.dart';
import '../../../features/auth/domain/login_guide_controller.dart';
import '../../../features/profile/domain/profile_prompt_controller.dart';
import '../../../features/profile/domain/profile_prompt_state.dart';
import '../../../features/notify/presentation/notification_bell.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/design/btn3d.dart';
import '../../../shared/widgets/design/momo.dart';
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

    final name = (auth.profile?.nickname?.trim().isNotEmpty ?? false)
        ? auth.profile!.nickname!.trim()
        : (auth.profile?.displayName?.trim().isNotEmpty ?? false)
            ? auth.profile!.displayName!.trim()
            : 'teman';

    return Scaffold(
      backgroundColor: AppColors.cream,
      body: SafeArea(
        bottom: false,
        child: Column(
          children: [
            _GreetingHeader(name: name, showBell: auth.isLoggedIn),
            Expanded(
              child: _content(context, ref, l10n, feedAsync, selectedCategory, showPrompt),
            ),
          ],
        ),
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
      l10n: l10n,
      showPrompt: showPrompt,
      selectedCategory: category,
      labels: _tabLabels(l10n),
      onSelectCategory: (c) => ref.read(feedCategoryProvider.notifier).select(c),
      onPromptCreate: () => context.go('/onboarding/profile'),
      onPromptDismiss: () => ref.read(profilePromptProvider.notifier).dismiss(),
      onKonsultasi: () => context.go('/triage'),
      onGath: () => context.push('/gath'),
      onPaspor: () => context.go('/profile'),
      onCatat: () => requireLogin(
        ref,
        context,
        pendingAction: const RouteIntent(location: '/home'),
        onAllowed: () => PublishComposePage.open(context),
      ),
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
            title: l10n.feedLoadError,
            icon: Icons.cloud_off_rounded,
            actionLabel: l10n.feedRetry,
            onAction: () => ref.read(feedProvider.notifier).refresh(),
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
                title: category == FeedCategory.growthMoment
                    ? l10n.feedGrowthEmptyTitle
                    : l10n.feedEmptyTitle,
                message: l10n.feedEmptyBody,
                actionLabel: l10n.feedEmptyCta,
                onAction: () => requireLogin(
                  ref,
                  context,
                  pendingAction: const RouteIntent(location: '/home'),
                  onAllowed: () => PublishComposePage.open(context),
                ),
              ),
            ),
            onRefresh: () => ref.read(feedProvider.notifier).refresh(),
          );
        }
        return FeedMasonryView(
          header: header,
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

/// 固定问候头：Momo + 时段问候 + 通知铃（薄荷渐变底）。
class _GreetingHeader extends StatelessWidget {
  const _GreetingHeader({required this.name, required this.showBell});

  final String name;
  final bool showBell;

  String _greeting() {
    final h = DateTime.now().hour;
    if (h < 11) return 'Selamat pagi ☀️';
    if (h < 15) return 'Selamat siang 🌤️';
    if (h < 18) return 'Selamat sore 🌇';
    return 'Selamat malam 🌙';
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(18, 8, 18, 14),
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [AppColors.mintTint, AppColors.cream],
        ),
      ),
      child: Row(
        children: [
          // happy:false → 不跑常驻眨眼动画（避免阻塞测试 pumpAndSettle；首页静态足矣）。
          const Momo(size: 52, happy: false),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  _greeting(),
                  style: const TextStyle(
                      fontSize: 13, color: AppColors.mint700, fontWeight: FontWeight.w700),
                ),
                Text(
                  'Apa kabar, $name?',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                      fontSize: 19,
                      fontWeight: FontWeight.w900,
                      letterSpacing: -0.3,
                      color: AppColors.ink),
                ),
              ],
            ),
          ),
          if (showBell) const NotificationBell(),
        ],
      ),
    );
  }
}

/// Beranda 滚动头部：档案提示条 + 快捷入口 + 每日提示卡 + 区头 + 分类 Tab。
class _BerandaTop extends StatelessWidget {
  const _BerandaTop({
    required this.l10n,
    required this.showPrompt,
    required this.selectedCategory,
    required this.labels,
    required this.onSelectCategory,
    required this.onPromptCreate,
    required this.onPromptDismiss,
    required this.onKonsultasi,
    required this.onGath,
    required this.onPaspor,
    required this.onCatat,
  });

  final AppLocalizations l10n;
  final bool showPrompt;
  final FeedCategory selectedCategory;
  final Map<FeedCategory, String> labels;
  final ValueChanged<FeedCategory> onSelectCategory;
  final VoidCallback onPromptCreate;
  final VoidCallback onPromptDismiss;
  final VoidCallback onKonsultasi;
  final VoidCallback onGath;
  final VoidCallback onPaspor;
  final VoidCallback onCatat;

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
        // 快捷入口：左大 Konsultasi + 右列 Gath / Paspor。
        Padding(
          padding: const EdgeInsets.fromLTRB(18, 8, 18, 6),
          child: IntrinsicHeight(
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Expanded(
                  child: _ActionCard(
                    tone: AppColors.mintTint,
                    iconColor: AppColors.mint700,
                    icon: Icons.medical_services_outlined,
                    title: 'Konsultasi Kilat',
                    sub: 'Tanya dokter / AI',
                    big: true,
                    onTap: onKonsultasi,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      _ActionCard(
                        tone: AppColors.goldTint,
                        iconColor: const Color(0xFFA9821E),
                        icon: Icons.calendar_today_outlined,
                        title: 'Gabung Gath',
                        sub: 'Kumpul bareng',
                        onTap: onGath,
                      ),
                      const SizedBox(height: 12),
                      _ActionCard(
                        tone: AppColors.coralTint,
                        iconColor: const Color(0xFFC26A4E),
                        icon: Icons.pets,
                        title: 'Paspor',
                        sub: 'Tumbuh kembang',
                        onTap: onPaspor,
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
        // 每日记录提示卡。
        Padding(
          padding: const EdgeInsets.fromLTRB(18, 12, 18, 4),
          child: _DailyPromptCard(onCatat: onCatat),
        ),
        // 区头 Untukmu。
        Padding(
          padding: const EdgeInsets.fromLTRB(18, 16, 18, 8),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: const [
              Text('Untukmu',
                  style: TextStyle(
                      fontSize: 17, fontWeight: FontWeight.w900, letterSpacing: -0.2)),
            ],
          ),
        ),
        FeedTabRow(selected: selectedCategory, labels: labels, onSelected: onSelectCategory),
        const SizedBox(height: 4),
      ],
    );
  }
}

/// 快捷入口卡（白卡 + 柔阴影 + 色块图标）。
class _ActionCard extends StatelessWidget {
  const _ActionCard({
    required this.tone,
    required this.iconColor,
    required this.icon,
    required this.title,
    required this.sub,
    required this.onTap,
    this.big = false,
  });

  final Color tone;
  final Color iconColor;
  final IconData icon;
  final String title;
  final String sub;
  final VoidCallback onTap;
  final bool big;

  @override
  Widget build(BuildContext context) {
    final box = big ? 52.0 : 40.0;
    return GestureDetector(
      onTap: onTap,
      child: Container(
        constraints: BoxConstraints(minHeight: big ? 0 : 78),
        padding: const EdgeInsets.all(15),
        decoration: BoxDecoration(
          color: AppColors.card,
          borderRadius: BorderRadius.circular(20),
          boxShadow: AppShadows.md,
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Container(
              width: box,
              height: box,
              decoration:
                  BoxDecoration(color: tone, borderRadius: BorderRadius.circular(big ? 16 : 12)),
              child: Icon(icon, size: big ? 28 : 22, color: iconColor),
            ),
            SizedBox(height: big ? 12 : 6),
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title,
                    style: TextStyle(
                        fontSize: big ? 16.5 : 14,
                        fontWeight: FontWeight.w800,
                        letterSpacing: -0.2,
                        color: AppColors.ink)),
                const SizedBox(height: 2),
                Text(sub, style: TextStyle(fontSize: big ? 13 : 12, color: AppColors.muted)),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// 每日记录提示卡（📸 + 文案 + 「+ Catat」立体小按钮）。
class _DailyPromptCard extends StatelessWidget {
  const _DailyPromptCard({required this.onCatat});

  final VoidCallback onCatat;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          begin: Alignment.centerLeft,
          end: Alignment.centerRight,
          colors: [AppColors.card, AppColors.mintTint2],
        ),
        borderRadius: BorderRadius.circular(24),
        boxShadow: AppShadows.md,
      ),
      child: Row(
        children: [
          Container(
            width: 50,
            height: 50,
            alignment: Alignment.center,
            decoration:
                BoxDecoration(color: AppColors.goldTint, borderRadius: BorderRadius.circular(16)),
            child: const Text('📸', style: TextStyle(fontSize: 26)),
          ),
          const SizedBox(width: 14),
          const Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Catat momen hari ini',
                    style: TextStyle(fontSize: 15, fontWeight: FontWeight.w800)),
                SizedBox(height: 1),
                Text('Simpan kenangan kecil hari ini ~',
                    style: TextStyle(fontSize: 13, color: AppColors.muted)),
              ],
            ),
          ),
          const SizedBox(width: 8),
          Btn3d(
            onPressed: onCatat,
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
            fontSize: 14,
            borderRadius: 13,
            child: const Text('+ Catat'),
          ),
        ],
      ),
    );
  }
}


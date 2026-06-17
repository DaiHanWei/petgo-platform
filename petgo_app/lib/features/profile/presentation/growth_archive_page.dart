import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../../shared/widgets/pet_status_selector.dart';
import '../../auth/data/me_repository.dart';
import '../../auth/domain/auth_state.dart';
import '../../content/domain/home_refresh_provider.dart';
import '../data/milestone_repository.dart';
import '../data/profile_repository.dart';
import '../data/timeline_repository.dart';
import '../domain/card_link.dart';
import '../domain/pet_profile.dart';
import '../domain/share_service.dart';
import '../domain/timeline_item.dart';
import 'widgets/archive_calendar.dart';
import 'widgets/pet_info_card.dart';
import 'widgets/share_fab.dart';
import 'widgets/timeline_tiles.dart';

/// 成长档案 Tab 主屏（Story 2.4）。三态：
/// - 状态 A + 有档案 → 信息卡 + FAB 占位 + 倒序时间线；
/// - 状态 A + 无档案 → 空状态「立即创建」；
/// - 状态 B/C → 「有宠专属」+ 修改状态入口。
class GrowthArchivePage extends ConsumerWidget {
  const GrowthArchivePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authControllerProvider);
    final petStatus = auth.profile?.petStatus;

    // PLANNING/ENTHUSIAST：非有宠态。
    if (petStatus != null && petStatus != 'HAS_PET') {
      return _NonOwnerView(onChangeStatus: () => _openStatusEditor(context, ref));
    }

    // HAS_PET（或未知）：据是否有档案分支。
    final profileAsync = ref.watch(petProfileProvider);
    return Scaffold(
      backgroundColor: AppColors.cream,
      // 分享名片 FAB（Story 2.7）：仅 A + 有档案 + 有 cardToken 渲染；动效首访一次。
      floatingActionButton: _shareFab(context, ref, profileAsync.asData?.value),
      body: profileAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (err, stack) => _EmptyProfileView(onCreate: () => context.go('/profile/create')),
        data: (profile) {
          if (profile == null) {
            return _EmptyProfileView(onCreate: () => context.go('/profile/create'));
          }
          return _ArchiveBody(
            child: PetInfoCard(
              profile: profile,
              onEditStatus: () => _openStatusEditor(context, ref),
              onEditProfile: () => context.go('/profile/edit'),
            ),
          );
        },
      ),
    );
  }

  /// 仅 (状态 A + 有档案 + 有 cardToken) 渲染分享 FAB；B/C 或无档案不渲染（AC3）。
  Widget? _shareFab(BuildContext context, WidgetRef ref, PetProfile? profile) {
    if (profile == null || profile.cardToken.isEmpty) return null;
    final l10n = AppLocalizations.of(context);
    final alreadyShown = ref.watch(shareFabAnimatedShownProvider).asData?.value ?? true;
    return ShareFab(
      semanticLabel: l10n.shareFabLabel,
      animate: !alreadyShown,
      onAnimationShown: () {
        markShareFabAnimated();
        ref.invalidate(shareFabAnimatedShownProvider);
      },
      onPressed: () {
        ref.read(shareServiceProvider)(petCardShareUrl(profile.cardToken));
        // 名片分享信号 → 里程碑 C-S3 自动完成（Story 8.3，fire-and-forget，失败静默）。
        ref.read(milestoneRepositoryProvider).signalCardShared().catchError((_) {});
        ref.invalidate(milestoneListProvider);
      },
    );
  }

  Future<void> _openStatusEditor(BuildContext context, WidgetRef ref) async {
    final l10n = AppLocalizations.of(context);
    String? selected = ref.read(authControllerProvider).profile?.petStatus;
    await showModalBottomSheet<void>(
      context: context,
      backgroundColor: AppColors.base,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setSheetState) => Padding(
          padding: const EdgeInsets.all(AppSpacing.lg),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(l10n.growthEditStatusTitle, style: Theme.of(ctx).textTheme.titleMedium),
              const SizedBox(height: AppSpacing.md),
              PetStatusSelector(
                selected: selected,
                onChanged: (v) => setSheetState(() => selected = v),
              ),
              const SizedBox(height: AppSpacing.md),
              FilledButton(
                key: const ValueKey('saveStatusButton'),
                onPressed: selected == null
                    ? null
                    : () async {
                        await _saveStatus(context, ref, selected!);
                        if (ctx.mounted) Navigator.of(ctx).pop();
                      },
                child: Text(l10n.commonSave),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _saveStatus(BuildContext context, WidgetRef ref, String status) async {
    final l10n = AppLocalizations.of(context);
    try {
      final updated = await ref.read(meRepositoryProvider).updatePetStatus(status);
      // FR-21 即时同步：回填全局 profile → 「我的」一致；bump 首页 → Feed 即时刷新。
      ref.read(authControllerProvider.notifier).applyProfile(updated);
      ref.read(homeRefreshProvider.notifier).bump();
      ref.invalidate(petProfileProvider);
      ref.invalidate(timelineFirstPageProvider);
      ref.invalidate(archiveStatsProvider);
    } catch (_) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
          ..clearSnackBars()
          ..showSnackBar(SnackBar(content: Text(l10n.growthStatusSaveFailed)));
      }
    }
  }
}

/// 档案主屏内容（信息卡 + 统计栏 + 里程碑 + 双视图）。view mode 为本地状态：
/// 留在页面切换时保持（session 内），离开后重建恢复默认时间线（StatefulShellRoute 分支保活下为近似）。
enum _ArchiveView { timeline, calendar }

class _ArchiveBody extends ConsumerStatefulWidget {
  const _ArchiveBody({required this.child});

  final Widget child;

  @override
  ConsumerState<_ArchiveBody> createState() => _ArchiveBodyState();
}

class _ArchiveBodyState extends ConsumerState<_ArchiveBody> {
  _ArchiveView _view = _ArchiveView.timeline;

  @override
  Widget build(BuildContext context) {
    return RefreshIndicator(
      color: AppColors.mint,
      onRefresh: () async {
        ref.invalidate(timelineFirstPageProvider);
        ref.invalidate(archiveStatsProvider);
      },
      child: ListView(
        padding: const EdgeInsets.fromLTRB(AppSpacing.lg, 54, AppSpacing.lg, AppSpacing.section),
        children: [
          widget.child, // 信息卡（缓存数据，内容区失败不覆盖，AC7）
          const SizedBox(height: 12),
          const _StatsBar(),
          const SizedBox(height: 10),
          const _MilestoneBar(),
          const SizedBox(height: 10),
          Center(
            child: TextButton.icon(
              key: const ValueKey('previewCardButton'),
              onPressed: () => context.push('/card/preview'),
              icon: const Icon(Icons.public, size: 16, color: AppColors.mint700),
              label: const Text('Pratinjau kartu publik',
                  style: TextStyle(color: AppColors.mint700, fontWeight: FontWeight.w700, fontSize: 13)),
            ),
          ),
          const SizedBox(height: 8),
          _viewToggleRow(),
          const SizedBox(height: 14),
          if (_view == _ArchiveView.timeline) const _TimelineView() else _calendarView(),
        ],
      ),
    );
  }

  /// 视图切换行（右上角图标）：时间线 ↔ 日历。
  Widget _viewToggleRow() {
    final l10n = AppLocalizations.of(context);
    return Row(
      children: [
        const Icon(Icons.calendar_today_outlined, size: 19, color: AppColors.mint700),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
              _view == _ArchiveView.timeline ? 'Linimasa Tumbuh Kembang' : l10n.growthViewCalendar,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w900)),
        ),
        IconButton(
          key: const ValueKey('archiveViewTimeline'),
          tooltip: l10n.growthViewTimeline,
          isSelected: _view == _ArchiveView.timeline,
          onPressed: () => setState(() => _view = _ArchiveView.timeline),
          icon: const Icon(Icons.view_agenda_outlined),
        ),
        IconButton(
          key: const ValueKey('archiveViewCalendar'),
          tooltip: l10n.growthViewCalendar,
          isSelected: _view == _ArchiveView.calendar,
          onPressed: () => setState(() => _view = _ArchiveView.calendar),
          icon: const Icon(Icons.calendar_month_outlined),
        ),
      ],
    );
  }

  Widget _calendarView() {
    return ArchiveCalendar(
      onOpenDay: (date) => context.push(
          '/profile/day?date=${date.year}-${_two(date.month)}-${_two(date.day)}'),
      onAddOnDate: (date) => context.go(
          '/publish?preset=growth-calendar&date=${date.year}-${_two(date.month)}-${_two(date.day)}'),
    );
  }

  static String _two(int n) => n.toString().padLeft(2, '0');
}

/// 统计栏「快乐时刻 X · 问诊 X」（AC5）。统计失败不阻断页面——退化为隐藏。
class _StatsBar extends ConsumerWidget {
  const _StatsBar();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final stats = ref.watch(archiveStatsProvider);
    return stats.maybeWhen(
      data: (s) => Container(
        key: const ValueKey('archiveStatsBar'),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        decoration: BoxDecoration(color: AppColors.card, borderRadius: BorderRadius.circular(14)),
        child: Row(
          children: [
            const Text('🌈', style: TextStyle(fontSize: 16)),
            const SizedBox(width: 6),
            Flexible(
              child: Text(l10n.growthStatsHappy(s.happyMomentCount),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13)),
            ),
            const SizedBox(width: 14),
            const Text('🏥', style: TextStyle(fontSize: 15)),
            const SizedBox(width: 6),
            Flexible(
              child: Text(l10n.growthStatsConsult(s.consultCount),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13)),
            ),
          ],
        ),
      ),
      orElse: () => const SizedBox.shrink(),
    );
  }
}

/// 里程碑入口进度条「已完成 X / N」（AC5；mini-epic 未就绪走零态）。
class _MilestoneBar extends ConsumerWidget {
  const _MilestoneBar();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final stats = ref.watch(archiveStatsProvider);
    return stats.maybeWhen(
      data: (s) {
        final ratio = s.milestoneTotal == 0 ? 0.0 : s.milestoneCompleted / s.milestoneTotal;
        return GestureDetector(
          key: const ValueKey('archiveMilestoneBar'),
          onTap: () => context.push('/profile/milestones'),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
            decoration: BoxDecoration(color: AppColors.card, borderRadius: BorderRadius.circular(14)),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Text('🏆', style: TextStyle(fontSize: 15)),
                    const SizedBox(width: 6),
                    Text(l10n.growthMilestoneProgress(s.milestoneCompleted, s.milestoneTotal),
                        style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13)),
                  ],
                ),
                const SizedBox(height: 8),
                ClipRRect(
                  borderRadius: BorderRadius.circular(999),
                  child: LinearProgressIndicator(
                    value: ratio,
                    minHeight: 6,
                    backgroundColor: AppColors.line,
                    color: AppColors.mint,
                  ),
                ),
              ],
            ),
          ),
        );
      },
      orElse: () => const SizedBox.shrink(),
    );
  }
}

/// 时间线视图（倒序快乐时刻 + 健康事件；首条各显 🌟；加载失败可重试，AC7）。
class _TimelineView extends ConsumerWidget {
  const _TimelineView();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final timelineAsync = ref.watch(timelineFirstPageProvider);
    return timelineAsync.when(
      loading: () => const Padding(
        padding: EdgeInsets.all(AppSpacing.xl),
        child: Center(child: CircularProgressIndicator()),
      ),
      // AC7：内容区加载失败 → 「加载失败，下拉重试」+ 重试入口（信息卡/统计栏不受影响）。
      error: (err, stack) => Container(
        key: const ValueKey('timelineError'),
        padding: const EdgeInsets.all(AppSpacing.lg),
        decoration: BoxDecoration(color: AppColors.card, borderRadius: BorderRadius.circular(12)),
        child: Column(
          children: [
            Text(l10n.growthLoadFailed, style: const TextStyle(color: AppColors.muted)),
            const SizedBox(height: 8),
            TextButton(
              key: const ValueKey('timelineRetry'),
              onPressed: () => ref.invalidate(timelineFirstPageProvider),
              child: Text(l10n.growthLoadRetry),
            ),
          ],
        ),
      ),
      data: (page) {
        if (page.items.isEmpty) {
          return Padding(
            padding: const EdgeInsets.all(AppSpacing.xl),
            child: Center(
              child: Text(l10n.growthArchiveTimelineEmpty,
                  style: const TextStyle(color: AppColors.textTertiary)),
            ),
          );
        }
        // 首条快乐时刻 / 首条健康事件各加 🌟 永久标签（AC5）。
        bool firstHappy = true;
        bool firstHealth = true;
        final tiles = <Widget>[];
        for (final item in page.items) {
          if (item.kind == TimelineKind.healthEvent) {
            tiles.add(HealthEventTile(
                item: item, firstLabel: firstHealth ? l10n.growthFirstHealthEvent : null));
            firstHealth = false;
          } else {
            tiles.add(HappyMomentTile(
                item: item, firstLabel: firstHappy ? l10n.growthFirstHappyMoment : null));
            firstHappy = false;
          }
        }
        return Column(children: tiles);
      },
    );
  }
}

class _EmptyProfileView extends StatelessWidget {
  const _EmptyProfileView({required this.onCreate});

  final VoidCallback onCreate;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          EmptyState(title: l10n.growthArchiveEmptyTitle),
          FilledButton(
            key: const ValueKey('growthCreateButton'),
            onPressed: onCreate,
            child: Text(l10n.growthArchiveEmptyCreate),
          ),
        ],
      ),
    );
  }
}

class _NonOwnerView extends StatelessWidget {
  const _NonOwnerView({required this.onChangeStatus});

  final VoidCallback onChangeStatus;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(title: Text(l10n.tabProfile), backgroundColor: AppColors.base),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.xl),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text(l10n.growthArchiveNonOwnerTitle, textAlign: TextAlign.center),
              const SizedBox(height: AppSpacing.lg),
              FilledButton(
                key: const ValueKey('changeStatusButton'),
                onPressed: onChangeStatus,
                child: Text(l10n.growthArchiveChangeStatus),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
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
            profile: profile,
            onEditProfile: () => context.go('/profile/edit'),
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
  const _ArchiveBody({required this.profile, required this.onEditProfile});

  final PetProfile profile;
  final VoidCallback onEditProfile;

  @override
  ConsumerState<_ArchiveBody> createState() => _ArchiveBodyState();
}

class _ArchiveBodyState extends ConsumerState<_ArchiveBody> {
  // Debug 截图钩子（仅 debug + flag）：默认进 Kalender 视图，截 catatan-calendar 用。
  _ArchiveView _view =
      (kDebugMode && const String.fromEnvironment('DEV_ARCHIVE_VIEW') == 'calendar')
          ? _ArchiveView.calendar
          : _ArchiveView.timeline;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final stats = ref.watch(archiveStatsProvider).asData?.value;
    final name = widget.profile.name;
    return RefreshIndicator(
      color: AppColors.mint,
      onRefresh: () async {
        ref.invalidate(timelineFirstPageProvider);
        ref.invalidate(archiveStatsProvider);
      },
      child: ListView(
        padding: const EdgeInsets.fromLTRB(AppSpacing.lg, 50, AppSpacing.lg, AppSpacing.section),
        children: [
          // AppBar：Paspor {name} + 编辑铅笔（paspor.html appbar）
          Row(
            children: [
              Expanded(
                child: Text(l10n.growthArchivePassportTitle(name),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontSize: 19, fontWeight: FontWeight.w700, color: AppColors.ink)),
              ),
              _appbarBtn(),
            ],
          ),
          const SizedBox(height: 14),
          PetInfoCard(
            profile: widget.profile,
            happyCount: stats?.happyMomentCount,
            consultCount: stats?.consultCount,
            milestoneCount: stats?.milestoneCompleted,
          ),
          const SizedBox(height: 11),
          _MilestoneBar(petName: name),
          const SizedBox(height: 12),
          _viewToggleRow(l10n),
          const SizedBox(height: 14),
          if (_view == _ArchiveView.timeline) const _TimelineView() else _calendarView(),
        ],
      ),
    );
  }

  /// appbar 编辑按钮（ibtn：白底 rounded-11 + 柔阴影 + 铅笔）。
  Widget _appbarBtn() => Material(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(11),
        elevation: 0,
        child: InkWell(
          key: const ValueKey('editProfileButton'),
          borderRadius: BorderRadius.circular(11),
          onTap: widget.onEditProfile,
          child: Container(
            width: 38,
            height: 38,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(11),
              boxShadow: const [
                BoxShadow(color: Color(0x122B2A27), offset: Offset(0, 2), blurRadius: 8),
              ],
            ),
            child: const Icon(Icons.edit_outlined, size: 18, color: AppColors.ink),
          ),
        ),
      );

  /// Timeline / Kalender 药丸切换（paspor.html 双按钮）。
  Widget _viewToggleRow(AppLocalizations l10n) {
    return Row(
      children: [
        Expanded(child: _toggleBtn('⏱ ${l10n.growthArchiveViewTimeline}', _view == _ArchiveView.timeline,
            () => setState(() => _view = _ArchiveView.timeline), const ValueKey('archiveViewTimeline'))),
        const SizedBox(width: 7),
        Expanded(child: _toggleBtn('📅 ${l10n.growthArchiveViewCalendar}', _view == _ArchiveView.calendar,
            () => setState(() => _view = _ArchiveView.calendar), const ValueKey('archiveViewCalendar'))),
      ],
    );
  }

  Widget _toggleBtn(String label, bool on, VoidCallback onTap, Key key) => GestureDetector(
        key: key,
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 9),
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: on ? AppColors.mint : AppColors.card,
            borderRadius: BorderRadius.circular(10),
            border: on ? null : Border.all(color: AppColors.line, width: 1.5),
          ),
          child: Text(label,
              style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  color: on ? AppColors.onAccent : AppColors.ink2)),
        ),
      );

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

/// 里程碑进度卡（msbar）：「🏆 Pencapaian {name}」+ "X / N" 紫色 + 进度槽（AC5）。
class _MilestoneBar extends ConsumerWidget {
  const _MilestoneBar({required this.petName});

  final String petName;

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
            padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 11),
            decoration: BoxDecoration(
              color: AppColors.card,
              borderRadius: BorderRadius.circular(12),
              boxShadow: const [
                BoxShadow(color: Color(0x0D2B2A27), offset: Offset(0, 2), blurRadius: 8),
              ],
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Expanded(
                      child: Text('🏆 ${l10n.growthArchiveAchievements(petName)}',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                              fontWeight: FontWeight.w600, fontSize: 12, color: AppColors.ink)),
                    ),
                    Text('${s.milestoneCompleted} / ${s.milestoneTotal}',
                        style: const TextStyle(
                            fontWeight: FontWeight.w600, fontSize: 12, color: AppColors.mint)),
                  ],
                ),
                const SizedBox(height: 7),
                ClipRRect(
                  borderRadius: BorderRadius.circular(999),
                  child: LinearProgressIndicator(
                    value: ratio,
                    minHeight: 5,
                    backgroundColor: AppColors.cream2,
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
          // timeline-empty.html：居中空态引导（标题 + 副文 + 紫 CTA「+ Catat Momen Pertama」）。
          return Padding(
            padding: const EdgeInsets.fromLTRB(AppSpacing.lg, 32, AppSpacing.lg, AppSpacing.xl),
            child: Column(
              children: [
                Text(l10n.growthArchiveTimelineEmpty,
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                        fontSize: 18, fontWeight: FontWeight.w700, color: AppColors.ink)),
                const SizedBox(height: 10),
                Text(
                  l10n.growthArchiveTimelineEmptyBody,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 13, height: 1.5, color: AppColors.ink2),
                ),
                const SizedBox(height: 22),
                FilledButton(
                  key: const ValueKey('timelineEmptyCta'),
                  onPressed: () => context.push('/publish?preset=growth-calendar'),
                  style: FilledButton.styleFrom(
                    backgroundColor: AppColors.mint,
                    foregroundColor: AppColors.onAccent,
                    padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 14),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                  ),
                  child: Text('+ ${l10n.growthArchiveRecordFirstMoment}',
                      style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
                ),
              ],
            ),
          );
        }
        final items = page.items;
        // 「Pertama」🌟 标在最旧的那条（debut，列表为倒序故取最后出现的索引）；
        // 仅在已无更多分页时可断定为真正第一条（AC5）。
        int debutHappy = -1, debutHealth = -1;
        if (!page.hasMore) {
          for (var i = 0; i < items.length; i++) {
            if (items[i].kind == TimelineKind.healthEvent) {
              debutHealth = i;
            } else if (items[i].kind != TimelineKind.unknown) {
              debutHappy = i;
            }
          }
        }
        // 按月分组：月份变化插入 "Juni 2026" 区标题。
        final tiles = <Widget>[];
        String? lastMonthKey;
        var happyIdx = 0;
        for (var i = 0; i < items.length; i++) {
          final item = items[i];
          final d = item.kind == TimelineKind.healthEvent ? item.date : item.displayDate;
          final monthKey = '${d.year}-${d.month}';
          if (monthKey != lastMonthKey) {
            tiles.add(Padding(
              padding: EdgeInsets.only(top: lastMonthKey == null ? 0 : 6, bottom: 10),
              child: Text(formatMonthYear(context, d),
                  style: const TextStyle(
                      fontSize: 15, fontWeight: FontWeight.w600, color: AppColors.ink)),
            ));
            lastMonthKey = monthKey;
          }
          if (item.kind == TimelineKind.healthEvent) {
            tiles.add(HealthEventTile(
                item: item, firstLabel: i == debutHealth ? l10n.growthFirstHealthEvent : null));
          } else {
            tiles.add(HappyMomentTile(
                item: item,
                index: happyIdx++,
                firstLabel: i == debutHappy ? l10n.growthFirstHappyMoment : null));
          }
        }
        return Column(crossAxisAlignment: CrossAxisAlignment.start, children: tiles);
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

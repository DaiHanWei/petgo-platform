import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/theme/colors.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../l10n/app_localizations.dart';
import '../../data/newbie_task_repository.dart';
import '../../domain/newbie_task_go.dart';
import '../../domain/newbie_task_labels.dart';
import '../../domain/newbie_tasks.dart';

/// 新手任务卡出现在哪一页（埋点 `page`）。
const String kNewbieCardPagePetMilestones = 'pet_milestones';
const String kNewbieCardPageAccountMilestones = 'account_milestones';

/// 新手任务卡（V1.5.0 onboarding-recall Story 3.1 · FR-176 · UI 稿 C1 ~ C5）—— 宠物里程碑页与账号级里程碑页共用。
///
/// - 收起（C1）：标题 + `x/7 selesai` + 圆形计数；浅紫底块「Habis ini, coba 👇」+ 第一项未完成任务；「+N tugas lainnya」。
/// - 展开（C2 / C3）：7 项按序，未完成项右侧「去完成」按钮 / 提示 / 剩余天数（规则见 [NewbieTaskGo]）。
/// - 完成（C4）：`lulusPemulaUnlocked`（含按旧 6 项解锁的老用户）或全部完成 → 只显示 🎓 横幅。
/// - 失败（C5）：沿用现有错误文案 + 重试。
/// - 未知 key 的行不渲染（不再露原始大写 key）。
///
/// 「Yuk!」可点区域扩到 44×44（视觉尺寸不变）；数字等宽（`FontFeature.tabularFigures()`）。
/// 从「去完成」返回后刷新进度（[newbieTasksProvider] 失效重取）。
class NewbieTaskCard extends ConsumerStatefulWidget {
  const NewbieTaskCard({super.key, required this.page});

  /// 埋点 `page`：[kNewbieCardPagePetMilestones] / [kNewbieCardPageAccountMilestones]。
  final String page;

  @override
  ConsumerState<NewbieTaskCard> createState() => _NewbieTaskCardState();
}

class _NewbieTaskCardState extends ConsumerState<NewbieTaskCard> {
  bool _expanded = false;
  bool _viewedReported = false;

  static const List<FontFeature> _tabular = [FontFeature.tabularFigures()];

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(newbieTasksProvider);
    return async.when(
      loading: () => Container(
        key: const ValueKey('newbieCardSkeleton'),
        height: 96,
        decoration: BoxDecoration(color: AppColors.card, borderRadius: BorderRadius.circular(16)),
        child: const Center(
          child: SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)),
        ),
      ),
      error: (_, _) => Container(
        key: const ValueKey('newbieCardError'),
        padding: const EdgeInsets.all(AppSpacing.lg),
        decoration: BoxDecoration(color: AppColors.card, borderRadius: BorderRadius.circular(16)),
        child: Row(
          children: [
            Expanded(
              child: Text(l10n.newbieCardError, style: const TextStyle(fontSize: 13, color: AppColors.muted)),
            ),
            TextButton(
              onPressed: () => ref.invalidate(newbieTasksProvider),
              child: Text(l10n.newbieCardRetry,
                  style: const TextStyle(color: AppColors.mint, fontWeight: FontWeight.w600)),
            ),
          ],
        ),
      ),
      data: (tasks) {
        _reportViewed(tasks);
        final done = tasks.lulusPemulaUnlocked || tasks.allDone;
        return done ? _doneBanner(l10n) : _checklist(context, l10n, tasks);
      },
    );
  }

  void _reportViewed(NewbieTasks tasks) {
    if (_viewedReported) return;
    _viewedReported = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      Analytics.capture('newbie_task_card_viewed', {
        'page': widget.page,
        'done_count': tasks.completedCount,
      });
    });
  }

  Future<void> _go(NewbieTaskItem item, String location) async {
    final ordinal = newbieTaskOrdinal(item.key);
    Analytics.capture('newbie_task_go_tapped', {'task': ?ordinal});
    // 社区首页是 Tab 根：不能 push（shell 分支根被 push 会二次构建 → GlobalKey 撞车），用 go。
    if (location == NewbieTaskGo.home) {
      context.go(location);
      return;
    }
    await context.push(location);
    // AC8：从「去完成」回来后刷新进度。
    if (mounted) ref.invalidate(newbieTasksProvider);
  }

  /// C4：紧凑达成横幅（沿用现状样式）。
  Widget _doneBanner(AppLocalizations l10n) => Container(
        key: const ValueKey('newbieCardDone'),
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg, vertical: 14),
        decoration: BoxDecoration(
          color: AppColors.mintTint,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: AppColors.lineViolet),
        ),
        child: Row(
          children: [
            const Text('🎓', style: TextStyle(fontSize: 20)),
            const SizedBox(width: AppSpacing.md),
            Expanded(
              child: Text(l10n.newbieCardAllDone,
                  style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: AppColors.mint700)),
            ),
          ],
        ),
      );

  Widget _checklist(BuildContext context, AppLocalizations l10n, NewbieTasks tasks) {
    // 未知 key 不渲染（旧版 key / 未来新 key 都不露原文）。
    final items = [for (final i in tasks.items) if (newbieTaskLabel(i.key, l10n) != null) i];
    final next = items.where((i) => !i.done).firstOrNull;
    final hiddenCount = items.length - 1;
    return Container(
      key: const ValueKey('newbieCard'),
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(16),
        boxShadow: const [BoxShadow(color: Color(0x0D2B2A27), offset: Offset(0, 2), blurRadius: 8)],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _header(l10n, tasks),
          const Padding(
            padding: EdgeInsets.symmetric(vertical: AppSpacing.md),
            child: Divider(height: 1, color: AppColors.line),
          ),
          if (_expanded)
            for (final item in items) _row(l10n, item, tasks)
          else if (next != null)
            Container(
              key: const ValueKey('newbieCardNextUp'),
              width: double.infinity,
              padding: const EdgeInsets.fromLTRB(AppSpacing.md, AppSpacing.sm, AppSpacing.sm, AppSpacing.xs),
              decoration: BoxDecoration(color: AppColors.violet100, borderRadius: BorderRadius.circular(12)),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(l10n.newbieCardNextUp,
                      style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.mint)),
                  _row(l10n, next, tasks),
                ],
              ),
            ),
          if (hiddenCount > 0) ...[
            const Padding(
              padding: EdgeInsets.only(top: AppSpacing.sm),
              child: Divider(height: 1, color: AppColors.line),
            ),
            InkWell(
              key: const ValueKey('newbieCardToggle'),
              onTap: () => setState(() => _expanded = !_expanded),
              child: Padding(
                padding: const EdgeInsets.only(top: AppSpacing.md),
                child: Row(
                  children: [
                    Text(
                      _expanded ? l10n.newbieCardShowLess : '+${l10n.newbieCardShowMore(hiddenCount)}',
                      style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.mint),
                    ),
                    const SizedBox(width: 4),
                    Icon(_expanded ? Icons.keyboard_arrow_up_rounded : Icons.keyboard_arrow_down_rounded,
                        size: 18, color: AppColors.mint),
                  ],
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _header(AppLocalizations l10n, NewbieTasks tasks) => Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(l10n.newbieCardTitle,
                    style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.ink)),
                const SizedBox(height: 4),
                Text(
                  l10n.newbieCardProgress(tasks.completedCount, tasks.total),
                  key: const ValueKey('newbieCardProgress'),
                  style: const TextStyle(fontSize: 12, height: 1.4, color: AppColors.muted, fontFeatures: _tabular),
                ),
              ],
            ),
          ),
          const SizedBox(width: AppSpacing.md),
          Container(
            width: 54,
            height: 54,
            alignment: Alignment.center,
            decoration: const BoxDecoration(color: AppColors.mintTint, shape: BoxShape.circle),
            child: Text('${tasks.completedCount}/${tasks.total}',
                style: const TextStyle(
                    fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.mint, fontFeatures: _tabular)),
          ),
        ],
      );

  Widget _row(AppLocalizations l10n, NewbieTaskItem item, NewbieTasks tasks) {
    final action = NewbieTaskGo.actionFor(item, tasks);
    final hint = action is NewbieTaskHint
        ? (action.kind == NewbieTaskHintKind.likeReceived ? l10n.newbieTaskHintLikeReceived : l10n.newbieTaskHintReply)
        : null;
    final Widget? trailing = switch (action) {
      NewbieTaskGoButton(:final location, :final createProfile) => _GoButton(
          key: ValueKey('newbieTaskGo_${item.key}'),
          label: createProfile ? l10n.newbieTaskCreateProfile : l10n.newbieTaskGo,
          onTap: () => _go(item, location),
        ),
      NewbieTaskDaysLeft(:final days) => Text(
          l10n.newbieTaskDaysLeft(days),
          key: ValueKey('newbieTaskDays_${item.key}'),
          style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.mint, fontFeatures: _tabular),
        ),
      _ => null,
    };
    return Padding(
      key: ValueKey('newbieTaskRow_${item.key}'),
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Icon(item.done ? Icons.check_circle_rounded : Icons.radio_button_unchecked,
              size: 22, color: item.done ? AppColors.mint : AppColors.muted),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  newbieTaskLabel(item.key, l10n)!,
                  style: TextStyle(
                    fontSize: 14,
                    color: item.done ? AppColors.ink2 : AppColors.ink,
                    decoration: item.done ? TextDecoration.lineThrough : null,
                    decorationColor: AppColors.muted,
                  ),
                ),
                if (hint != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 2),
                    child: Text(hint,
                        key: ValueKey('newbieTaskHint_${item.key}'),
                        style: const TextStyle(fontSize: 12, height: 1.4, color: AppColors.muted)),
                  ),
              ],
            ),
          ),
          if (trailing != null) ...[const SizedBox(width: AppSpacing.sm), trailing],
        ],
      ),
    );
  }
}

/// 「Yuk!」/「Buat profil」小按钮：视觉高 28，可点区域扩到 44×44。
class _GoButton extends StatelessWidget {
  const _GoButton({super.key, required this.label, required this.onTap});

  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: ConstrainedBox(
        constraints: const BoxConstraints(minWidth: 44, minHeight: 44),
        child: Center(
          widthFactor: 1,
          child: Container(
            height: 28,
            padding: const EdgeInsets.symmetric(horizontal: 12),
            alignment: Alignment.center,
            decoration: BoxDecoration(color: AppColors.mint, borderRadius: BorderRadius.circular(999)),
            child: Text(label,
                style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: AppColors.onAccent)),
          ),
        ),
      ),
    );
  }
}

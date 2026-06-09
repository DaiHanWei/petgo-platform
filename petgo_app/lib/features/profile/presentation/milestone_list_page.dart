import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_image.dart';
import '../data/milestone_repository.dart';
import '../domain/milestone.dart';

/// 里程碑列表页（Story 8.2 · FR-42）。壳→真页：顶部宠物信息 + 总进度 + L/M/S 三级分区徽章
/// （彩色/灰锁）+ 点击弹层分流（系统类→说明文案 / 打卡类→「已打卡 / 去发布」两入口）。
///
/// 承接 `MILESTONE_NODE` 推送深链（`/profile/milestones`）。三级庆祝动效在 8.5；
/// 「已打卡」picker + 打卡 API、「去发布」预选成长日历的完成回填在 8.4。
class MilestoneListPage extends ConsumerWidget {
  const MilestoneListPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(milestoneListProvider);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.milestoneListTitle)),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (err, _) => _MilestoneError(
          onRetry: () => ref.invalidate(milestoneListProvider),
        ),
        data: (data) => RefreshIndicator(
          onRefresh: () async => ref.invalidate(milestoneListProvider),
          child: ListView(
            padding: const EdgeInsets.fromLTRB(
                AppSpacing.lg, AppSpacing.lg, AppSpacing.lg, AppSpacing.xl),
            children: [
              _Header(data: data),
              const SizedBox(height: AppSpacing.lg),
              for (final group in data.groups) ...[
                _GroupSection(group: group),
                const SizedBox(height: AppSpacing.lg),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// 顶部宠物信息 + 总进度。
class _Header extends StatelessWidget {
  const _Header({required this.data});

  final MilestoneList data;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final ratio = data.totalCount == 0 ? 0.0 : data.completedCount / data.totalCount;
    return Container(
      key: const ValueKey('milestoneHeader'),
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Row(
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(28),
            child: SizedBox(
              width: 56,
              height: 56,
              child: data.petAvatarUrl == null
                  ? Container(
                      color: AppColors.mintTint,
                      child: const Icon(Icons.pets_rounded, color: AppColors.mint),
                    )
                  : AppImage.widget(data.petAvatarUrl!, fit: BoxFit.cover),
            ),
          ),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(data.petName,
                    style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 17)),
                const SizedBox(height: 6),
                Text(l10n.growthMilestoneProgress(data.completedCount, data.totalCount),
                    style: const TextStyle(color: AppColors.ink2, fontSize: 13)),
                const SizedBox(height: 8),
                ClipRRect(
                  borderRadius: BorderRadius.circular(999),
                  child: LinearProgressIndicator(
                    value: ratio,
                    minHeight: 7,
                    backgroundColor: AppColors.line,
                    color: AppColors.mint,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// 一个级别分区：标题（含该级进度）+ 徽章网格。
class _GroupSection extends StatelessWidget {
  const _GroupSection({required this.group});

  final MilestoneGroup group;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final label = switch (group.level) {
      MilestoneLevel.l => l10n.milestoneLevelL,
      MilestoneLevel.m => l10n.milestoneLevelM,
      MilestoneLevel.s => l10n.milestoneLevelS,
    };
    return Column(
      key: ValueKey('milestoneSection_${group.level.name}'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(label, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 15)),
            Text('${group.completedCount}/${group.totalCount}',
                style: const TextStyle(color: AppColors.ink2, fontWeight: FontWeight.w700)),
          ],
        ),
        const SizedBox(height: AppSpacing.md),
        Wrap(
          spacing: AppSpacing.md,
          runSpacing: AppSpacing.md,
          children: [
            for (final item in group.items) _Badge(item: item),
          ],
        ),
      ],
    );
  }
}

/// 单枚徽章：已完成彩色（mint 描边 + 奖杯）/ 未完成灰色锁定轮廓。
class _Badge extends StatelessWidget {
  const _Badge({required this.item});

  final MilestoneItem item;

  static const double _size = 76;

  @override
  Widget build(BuildContext context) {
    final completed = item.completed;
    return GestureDetector(
      key: ValueKey('milestoneBadge_${item.code}'),
      onTap: () => _showBadgeSheet(context, item),
      child: SizedBox(
        width: _size,
        child: Column(
          children: [
            Container(
              width: _size,
              height: _size,
              decoration: BoxDecoration(
                color: completed ? AppColors.mintTint : AppColors.line2,
                shape: BoxShape.circle,
                border: Border.all(
                  color: completed ? AppColors.mint : AppColors.line,
                  width: 2,
                ),
              ),
              child: Icon(
                completed ? Icons.emoji_events_rounded : Icons.lock_outline_rounded,
                color: completed ? AppColors.mint700 : AppColors.muted,
                size: 30,
              ),
            ),
            const SizedBox(height: 6),
            Text(
              item.title,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 11,
                height: 1.15,
                color: completed ? AppColors.ink : AppColors.muted,
                fontWeight: completed ? FontWeight.w700 : FontWeight.w500,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// 徽章点击弹层（FR-42）：系统类→说明文案、打卡类未完成→「已打卡 / 去发布」两入口。
void _showBadgeSheet(BuildContext context, MilestoneItem item) {
  showModalBottomSheet<void>(
    context: context,
    backgroundColor: AppColors.card,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
    ),
    builder: (sheetContext) {
      final l10n = AppLocalizations.of(sheetContext);
      final hint = switch (item.trigger) {
        MilestoneTrigger.systemAuto => l10n.milestoneHintSystemAuto,
        MilestoneTrigger.pushPublish => l10n.milestoneHintPushPublish,
        MilestoneTrigger.userCheckin => l10n.milestoneHintCheckin,
      };
      // 仅「用户打卡」且未完成才出两入口；系统/推送类、或已完成 → 只读说明。
      final showCheckinActions = item.trigger.isCheckin && !item.completed;
      return SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(
              AppSpacing.lg, AppSpacing.lg, AppSpacing.lg, AppSpacing.lg),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Icon(
                    item.completed ? Icons.emoji_events_rounded : Icons.flag_outlined,
                    color: item.completed ? AppColors.mint700 : AppColors.muted,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(item.title,
                        style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
                  ),
                ],
              ),
              if (item.completed && item.completedAt != null) ...[
                const SizedBox(height: 8),
                Text(
                  l10n.milestoneCompletedOn(_formatDate(item.completedAt!)),
                  style: const TextStyle(color: AppColors.mint700, fontWeight: FontWeight.w700),
                ),
              ],
              const SizedBox(height: 10),
              Text(hint, style: const TextStyle(color: AppColors.ink2, height: 1.35)),
              if (showCheckinActions) ...[
                const SizedBox(height: AppSpacing.lg),
                Row(
                  children: [
                    Expanded(
                      child: OutlinedButton(
                        key: const ValueKey('milestoneCheckedIn'),
                        onPressed: () => _onCheckedIn(sheetContext, item),
                        child: Text(l10n.milestoneActionCheckedIn),
                      ),
                    ),
                    const SizedBox(width: AppSpacing.md),
                    Expanded(
                      child: FilledButton(
                        key: const ValueKey('milestoneGoPublish'),
                        onPressed: () => _onGoPublish(sheetContext),
                        child: Text(l10n.milestoneActionGoPublish),
                      ),
                    ),
                  ],
                ),
              ],
            ],
          ),
        ),
      );
    },
  );
}

/// 「已打卡」→ 内容关联选择器（picker + 打卡 API 实操在 8.4）。8.2 先落入口占位。
void _onCheckedIn(BuildContext context, MilestoneItem item) {
  Navigator.of(context).pop();
  final l10n = AppLocalizations.of(context);
  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(content: Text(l10n.milestoneListComingSoon)),
  );
}

/// 「去发布」→ 统一发布入口（预选成长日历 + 发布成功回填完成在 8.4）。
void _onGoPublish(BuildContext context) {
  Navigator.of(context).pop();
  context.push('/publish');
}

String _formatDate(DateTime d) {
  final local = d.toLocal();
  return '${local.year}-${local.month.toString().padLeft(2, '0')}'
      '-${local.day.toString().padLeft(2, '0')}';
}

/// F13 统一失败态（加载失败 + 重试入口）。
class _MilestoneError extends StatelessWidget {
  const _MilestoneError({required this.onRetry});

  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Icon(Icons.cloud_off_rounded, size: 44, color: AppColors.muted),
          const SizedBox(height: AppSpacing.md),
          Text(l10n.milestoneLoadFailed, style: const TextStyle(color: AppColors.muted)),
          const SizedBox(height: AppSpacing.sm),
          TextButton(
            key: const ValueKey('milestoneRetry'),
            onPressed: onRetry,
            child: Text(l10n.growthLoadRetry),
          ),
        ],
      ),
    );
  }
}

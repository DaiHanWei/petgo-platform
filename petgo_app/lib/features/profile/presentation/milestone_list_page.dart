import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_image.dart';
import '../data/milestone_repository.dart';
import '../domain/milestone.dart';
import '../domain/milestone_titles.dart';
import '../domain/share_service.dart';
import 'widgets/milestone_celebration.dart';

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
      backgroundColor: AppColors.base,
      appBar: AppBar(
        backgroundColor: AppColors.base,
        centerTitle: true,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back, color: AppColors.ink),
          onPressed: () => context.canPop() ? context.pop() : context.go('/profile'),
        ),
        title: Text(l10n.milestoneListTitle,
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: AppColors.ink)),
      ),
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
    final ratio = data.totalCount == 0 ? 0.0 : data.completedCount / data.totalCount;
    return Container(
      key: const ValueKey('milestoneHeader'),
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        children: [
          Row(
            children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(28),
                child: SizedBox(
                  width: 52,
                  height: 52,
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
                child: Text(data.petName,
                    style: const TextStyle(
                        fontWeight: FontWeight.w700, fontSize: 17, color: AppColors.ink)),
              ),
              // 右侧总进度大字（原型 5 / 30 milestone）。
              Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Text('${data.completedCount} / ${data.totalCount}',
                      style: const TextStyle(
                          fontWeight: FontWeight.w700, fontSize: 19, color: AppColors.mint)),
                  const Text('milestone',
                      style: TextStyle(fontSize: 11, color: AppColors.muted)),
                ],
              ),
            ],
          ),
          const SizedBox(height: 12),
          ClipRRect(
            borderRadius: BorderRadius.circular(999),
            child: LinearProgressIndicator(
              value: ratio,
              minHeight: 6,
              backgroundColor: AppColors.cream2,
              color: AppColors.mint,
            ),
          ),
        ],
      ),
    );
  }
}

/// 级别主题色（原型：L 金 / M 紫 / S 绿）。
Color _levelColor(MilestoneLevel level) => switch (level) {
      MilestoneLevel.l => AppColors.gold,
      MilestoneLevel.m => AppColors.mint,
      MilestoneLevel.s => AppColors.triageGreen,
    };

/// 级别分区标题（原型印尼语：L 级 — LEGENDA / M 级 — MAJOR / S 级 — SMALL）。
String _levelTitle(MilestoneLevel level) => switch (level) {
      MilestoneLevel.l => 'L 级 — LEGENDA',
      MilestoneLevel.m => 'M 级 — MAJOR',
      MilestoneLevel.s => 'S 级 — SMALL',
    };

/// 一个级别分区：彩色标题（含该级进度）+ 徽章网格。
class _GroupSection extends StatelessWidget {
  const _GroupSection({required this.group});

  final MilestoneGroup group;

  @override
  Widget build(BuildContext context) {
    final color = _levelColor(group.level);
    return Column(
      key: ValueKey('milestoneSection_${group.level.name}'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(_levelTitle(group.level),
                style: TextStyle(fontWeight: FontWeight.w700, fontSize: 13, color: color, letterSpacing: 0.3)),
            Text('${group.completedCount}/${group.totalCount}',
                style: const TextStyle(color: AppColors.muted, fontWeight: FontWeight.w600, fontSize: 12)),
          ],
        ),
        const SizedBox(height: AppSpacing.md),
        Wrap(
          spacing: AppSpacing.md,
          runSpacing: AppSpacing.md,
          children: [
            for (final item in group.items) _Badge(item: item, levelColor: color),
          ],
        ),
      ],
    );
  }
}

/// 单枚徽章：已完成按级别配色（彩色实心圆 + 奖杯）/ 未完成灰色锁定轮廓。
class _Badge extends ConsumerWidget {
  const _Badge({required this.item, required this.levelColor});

  final MilestoneItem item;
  final Color levelColor;

  static const double _size = 64;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final completed = item.completed;
    return GestureDetector(
      key: ValueKey('milestoneBadge_${item.code}'),
      onTap: () => _showBadgeSheet(context, ref, item),
      child: SizedBox(
        width: _size,
        child: Column(
          children: [
            Container(
              width: _size,
              height: _size,
              decoration: BoxDecoration(
                // 已解锁：级别彩色实心圆 + 同色辉光；未解锁：浅灰圆。
                color: completed ? levelColor : AppColors.line2,
                shape: BoxShape.circle,
                boxShadow: completed
                    ? [BoxShadow(color: levelColor.withValues(alpha: 0.35), blurRadius: 10, offset: const Offset(0, 3))]
                    : null,
              ),
              child: Icon(
                completed ? Icons.emoji_events_rounded : Icons.lock_outline_rounded,
                color: completed ? AppColors.onAccent : AppColors.muted,
                size: 26,
              ),
            ),
            const SizedBox(height: 6),
            Text(
              localizedMilestoneTitle(item.code, Localizations.localeOf(context)),
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
void _showBadgeSheet(BuildContext context, WidgetRef ref, MilestoneItem item) {
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
                    child: Text(localizedMilestoneTitle(item.code, Localizations.localeOf(sheetContext)),
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
                        onPressed: () => _onCheckedIn(sheetContext, ref, item),
                        child: Text(l10n.milestoneActionCheckedIn),
                      ),
                    ),
                    const SizedBox(width: AppSpacing.md),
                    Expanded(
                      child: FilledButton(
                        key: const ValueKey('milestoneGoPublish'),
                        onPressed: () => _onGoPublish(sheetContext, item),
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

/// 「已打卡」→ 内容关联选择器（Story 8.4）：选一条本人成长日历内容关联并完成。
void _onCheckedIn(BuildContext context, WidgetRef ref, MilestoneItem item) {
  Navigator.of(context).pop(); // 关徽章弹层
  showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    backgroundColor: AppColors.card,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
    ),
    builder: (_) => _CheckinPickerSheet(milestoneCode: item.code),
  );
}

/// 「去发布」→ 统一发布入口预选成长日历，携里程碑 code；发布成功后自动打卡回填（Story 8.4）。
void _onGoPublish(BuildContext context, MilestoneItem item) {
  Navigator.of(context).pop();
  context.push('/publish?preset=growth-calendar&milestoneCode=${item.code}');
}

String _formatDate(DateTime d) {
  final local = d.toLocal();
  return '${local.year}-${local.month.toString().padLeft(2, '0')}'
      '-${local.day.toString().padLeft(2, '0')}';
}

/// 「已打卡」内容关联选择器（Story 8.4）：仅本人成长日历内容，已关联其它里程碑的置灰不可选。
class _CheckinPickerSheet extends ConsumerWidget {
  const _CheckinPickerSheet({required this.milestoneCode});

  final String milestoneCode;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(milestoneCheckinCandidatesProvider);
    return SafeArea(
      child: ConstrainedBox(
        constraints: BoxConstraints(
            maxHeight: MediaQuery.of(context).size.height * 0.7),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.all(AppSpacing.lg),
              child: Text(l10n.milestoneCheckinPickerTitle,
                  style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
            ),
            Flexible(
              child: async.when(
                loading: () => const Padding(
                  padding: EdgeInsets.all(AppSpacing.xl),
                  child: Center(child: CircularProgressIndicator()),
                ),
                error: (_, _) => Padding(
                  padding: const EdgeInsets.all(AppSpacing.lg),
                  child: TextButton(
                    key: const ValueKey('milestoneCheckinRetry'),
                    onPressed: () => ref.invalidate(milestoneCheckinCandidatesProvider),
                    child: Text(l10n.growthLoadRetry),
                  ),
                ),
                data: (items) {
                  if (items.isEmpty) {
                    return Padding(
                      padding: const EdgeInsets.all(AppSpacing.xl),
                      child: Text(l10n.milestoneCheckinEmpty,
                          textAlign: TextAlign.center,
                          style: const TextStyle(color: AppColors.muted)),
                    );
                  }
                  return ListView.separated(
                    shrinkWrap: true,
                    padding: const EdgeInsets.fromLTRB(
                        AppSpacing.lg, 0, AppSpacing.lg, AppSpacing.lg),
                    itemCount: items.length,
                    separatorBuilder: (_, _) => const Divider(height: 1),
                    itemBuilder: (_, i) => _CandidateTile(
                      candidate: items[i],
                      milestoneCode: milestoneCode,
                    ),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _CandidateTile extends ConsumerWidget {
  const _CandidateTile({required this.candidate, required this.milestoneCode});

  final MilestoneCheckinCandidate candidate;
  final String milestoneCode;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final disabled = candidate.linked;
    return Opacity(
      opacity: disabled ? 0.4 : 1,
      child: ListTile(
        key: ValueKey('milestoneCandidate_${candidate.contentId}'),
        contentPadding: EdgeInsets.zero,
        leading: ClipRRect(
          borderRadius: BorderRadius.circular(8),
          child: SizedBox(
            width: 48,
            height: 48,
            child: candidate.firstImageUrl == null
                ? Container(color: AppColors.line2,
                    child: const Icon(Icons.photo_outlined, color: AppColors.muted, size: 20))
                : AppImage.widget(candidate.firstImageUrl!, fit: BoxFit.cover),
          ),
        ),
        title: Text(
          candidate.text?.isNotEmpty == true ? candidate.text! : l10n.milestoneCheckinUntitled,
          maxLines: 1, overflow: TextOverflow.ellipsis,
        ),
        subtitle: candidate.eventDate == null
            ? null
            : Text(_formatDate(candidate.eventDate!),
                style: const TextStyle(fontSize: 12, color: AppColors.muted)),
        trailing: disabled
            ? Text(l10n.milestoneCheckinLinked,
                style: const TextStyle(fontSize: 11, color: AppColors.muted))
            : const Icon(Icons.chevron_right_rounded),
        onTap: disabled ? null : () => _confirm(context, ref),
      ),
    );
  }

  Future<void> _confirm(BuildContext context, WidgetRef ref) async {
    final l10n = AppLocalizations.of(context);
    final locale = Localizations.localeOf(context);
    final messenger = ScaffoldMessenger.of(context);
    final navigator = Navigator.of(context);
    try {
      final completed =
          await ref.read(milestoneRepositoryProvider).checkIn(milestoneCode, candidate.contentId);
      ref.invalidate(milestoneListProvider);
      // L 级分享卡文案（捕获 l10n / locale，避免 pop 后失效）。标题按 locale 本地化（杜绝后端中文）。
      final shareText = l10n.milestoneShareText(localizedMilestoneTitle(completed.code, locale));
      navigator.pop(); // 关 picker
      if (!context.mounted) return;
      // 完成后按级触发三级庆祝动效（Story 8.5）；L 级 Duolingo 开宝箱后自动弹分享卡（8.6，复用 2-6 分享通道）。
      await showMilestoneCelebration(
        context,
        completed,
        onShare: completed.level == MilestoneLevel.l
            ? () => ref.read(shareServiceProvider)(shareText)
            : null,
      );
    } catch (_) {
      messenger.showSnackBar(SnackBar(content: Text(l10n.milestoneCheckinFailed)));
    }
  }
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

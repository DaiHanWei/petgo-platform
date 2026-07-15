import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import '../../../shared/widgets/app_toast.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/router/deep_link_routes.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_image.dart';
import '../../../shared/widgets/dashed_rect.dart';
import '../../../shared/widgets/design/baru_badge.dart';
import '../data/milestone_repository.dart';
import '../data/newbie_task_repository.dart';
import '../domain/milestone.dart';
import '../domain/milestone_checkin_prompt_copy.dart';
import '../domain/milestone_share.dart';
import '../domain/milestone_titles.dart';
import '../domain/newbie_task_labels.dart';
import '../domain/newbie_tasks.dart';
import 'widgets/milestone_celebration.dart';

/// 里程碑列表页（Story 8.2 · FR-42）。壳→真页：顶部宠物信息 + 总进度 + L/M/S 三级分区徽章
/// （彩色/灰锁）+ 点击弹层分流（系统类→说明文案 / 打卡类→「已打卡 / 去发布」两入口）。
///
/// 承接 `MILESTONE_NODE` 推送深链（`/profile/milestones`）。三级庆祝动效在 8.5；
/// 「已打卡」picker + 打卡 API、「去发布」预选成长日历的完成回填在 8.4。
class MilestoneListPage extends ConsumerStatefulWidget {
  const MilestoneListPage({super.key});

  @override
  ConsumerState<MilestoneListPage> createState() => _MilestoneListPageState();
}

class _MilestoneListPageState extends ConsumerState<MilestoneListPage> {
  bool _devShown = false;

  /// 筛选（0711）：false=「Belum Semua Selesai」显示未全完成级别；true=「Semua Sudah Selesai」显示已全完成级别。
  bool _showCompleted = false;

  /// Debug 截图钩子（仅 debug + flag）：数据就绪后自动弹 milestone-sheet / milestone-unlock。
  void _maybeDevShow(MilestoneList data) {
    if (_devShown || !kDebugMode) return;
    const sheet = String.fromEnvironment('DEV_SHEET'); // 'milestone' → 弹徽章详情 sheet
    const celebrate = String.fromEnvironment('DEV_CELEBRATE'); // 's|m|l' → 弹解锁庆祝
    if (sheet != 'milestone' && celebrate.isEmpty) return;
    _devShown = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (sheet == 'milestone') {
        // 选一个「打卡类未完成」item 以展现两入口；无则取首个。
        final items = [for (final g in data.groups) ...g.items];
        final item = items.firstWhere((i) => i.trigger.isCheckin && !i.completed,
            orElse: () => items.isNotEmpty ? items.first : _devItem(MilestoneLevel.s));
        _showBadgeSheet(context, ref, item);
      } else {
        final level = switch (celebrate) {
          'l' => MilestoneLevel.l,
          's' => MilestoneLevel.s,
          _ => MilestoneLevel.m,
        };
        showMilestoneCelebration(context, _devItem(level),
            petName: data.petName, collection: [for (final g in data.groups) ...g.items]);
      }
    });
  }

  MilestoneItem _devItem(MilestoneLevel level) => MilestoneItem(
        code: 'FIRST_PHOTO',
        title: 'Foto Pertama',
        level: level,
        trigger: MilestoneTrigger.userCheckin,
        completed: true,
        completedAt: DateTime(2026, 6, 18),
      );

  /// 筛选 chips（0711）：未全完成级别 / 已全完成级别 双段切换。
  Widget _filterChips(AppLocalizations l10n) {
    return Row(
      children: [
        Expanded(
          child: _filterChip(l10n.milestoneFilterIncomplete, !_showCompleted,
              () => setState(() => _showCompleted = false), const ValueKey('milestoneFilterIncomplete')),
        ),
        const SizedBox(width: AppSpacing.sm),
        Expanded(
          child: _filterChip(l10n.milestoneFilterComplete, _showCompleted,
              () => setState(() => _showCompleted = true), const ValueKey('milestoneFilterComplete')),
        ),
      ],
    );
  }

  Widget _filterChip(String label, bool selected, VoidCallback onTap, Key key) {
    return InkWell(
      key: key,
      borderRadius: BorderRadius.circular(12),
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 13),
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: selected ? AppColors.mint : AppColors.card,
          borderRadius: BorderRadius.circular(12),
          border: selected ? null : Border.all(color: AppColors.line, width: 1.5),
        ),
        child: Text(label,
            style: TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w700,
                color: selected ? Colors.white : AppColors.mint)),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(milestoneListProvider);
    async.whenData(_maybeDevShow);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(
        backgroundColor: AppColors.base,
        centerTitle: true,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back, color: AppColors.ink),
          onPressed: () => context.canPop() ? context.pop() : context.go('/profile'),
        ),
        // 标题带宠名（0711「Milestone Mochi」）；数据未就绪时回退通用词。
        title: Text(
            async.asData?.value.petName != null
                ? l10n.milestoneListTitleNamed(async.asData!.value.petName)
                : l10n.milestoneListTitle,
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: AppColors.ink)),
      ),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (err, _) => _MilestoneError(
          onRetry: () => ref.invalidate(milestoneListProvider),
        ),
        data: (data) => RefreshIndicator(
          onRefresh: () async {
            ref.invalidate(milestoneListProvider);
            ref.invalidate(newbieTasksProvider);
          },
          child: ListView(
            padding: const EdgeInsets.fromLTRB(
                AppSpacing.lg, AppSpacing.lg, AppSpacing.lg, AppSpacing.xl),
            children: [
              // 0711 milestone-with-starter 顺序：宠物卡 → 筛选chips → 新手任务卡 → 分级徽章。
              _Header(data: data),
              const SizedBox(height: AppSpacing.lg),
              _filterChips(l10n),
              const SizedBox(height: AppSpacing.lg),
              const _NewbieCard(),
              const SizedBox(height: AppSpacing.lg),
              for (final group in data.groups.where((g) => _showCompleted
                  ? g.completedCount == g.totalCount
                  : g.completedCount < g.totalCount)) ...[
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
                      : AppImage.widget(data.petAvatarUrl!, fit: BoxFit.cover, thumbWidth: 240),
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
                  Text(l10n.milestoneCountLabel,
                      style: const TextStyle(fontSize: 11, color: AppColors.muted)),
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

/// 新手任务卡（Story 7.3 · FR-47）：里程碑页顶部 6 任务勾选进度；全完成显 Lulus Pemula 达成态。
/// 独立 AsyncValue（不阻塞里程碑列表）；loading 骨架 / error 可重试——离线不留白。
class _NewbieCard extends ConsumerStatefulWidget {
  const _NewbieCard();

  @override
  ConsumerState<_NewbieCard> createState() => _NewbieCardState();
}

class _NewbieCardState extends ConsumerState<_NewbieCard> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(newbieTasksProvider);
    return async.when(
      loading: () => Container(
        key: const ValueKey('newbieCardSkeleton'),
        height: 96,
        decoration: BoxDecoration(
          color: AppColors.card,
          borderRadius: BorderRadius.circular(16),
        ),
        child: const Center(
          child: SizedBox(
              width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)),
        ),
      ),
      error: (_, _) => Container(
        key: const ValueKey('newbieCardError'),
        padding: const EdgeInsets.all(AppSpacing.lg),
        decoration: BoxDecoration(
          color: AppColors.card,
          borderRadius: BorderRadius.circular(16),
        ),
        child: Row(
          children: [
            Expanded(
              child: Text(l10n.newbieCardError,
                  style: const TextStyle(fontSize: 13, color: AppColors.muted)),
            ),
            TextButton(
              onPressed: () => ref.invalidate(newbieTasksProvider),
              child: Text(l10n.newbieCardRetry,
                  style: const TextStyle(color: AppColors.mint, fontWeight: FontWeight.w600)),
            ),
          ],
        ),
      ),
      data: (tasks) => tasks.allDone ? _newbieDoneBanner(l10n) : _newbieChecklist(context, l10n, tasks),
    );
  }

  /// 全完成：紧凑达成横幅（不再铺开清单，减少长期噪音）。
  Widget _newbieDoneBanner(AppLocalizations l10n) => Container(
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
                  style: const TextStyle(
                      fontSize: 14, fontWeight: FontWeight.w700, color: AppColors.mint700)),
            ),
          ],
        ),
      );

  /// 进行中（0711 Tugas Pemula）：紫虚线卡 + BARU 角标 + 右上圆形计数 + 副文案 + 折叠（默认露 4 项）。
  Widget _newbieChecklist(BuildContext context, AppLocalizations l10n, NewbieTasks tasks) {
    final locale = Localizations.localeOf(context);
    final items = tasks.items;
    final visible = _expanded ? items : items.take(4).toList();
    final hidden = items.length - visible.length;
    final card = Container(
      key: const ValueKey('newbieCard'),
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 标题 + 副文案 | 右上圆形计数徽章。
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(l10n.newbieCardTitle,
                        style: const TextStyle(
                            fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.ink)),
                    const SizedBox(height: 4),
                    Text(
                      '${l10n.newbieCardProgress(tasks.completedCount, tasks.total)} · ${l10n.newbieCardSubtitle}',
                      style: const TextStyle(fontSize: 12, height: 1.4, color: AppColors.muted),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: AppSpacing.md),
              Container(
                width: 54,
                height: 54,
                alignment: Alignment.center,
                decoration: const BoxDecoration(
                  color: AppColors.mintTint,
                  shape: BoxShape.circle,
                ),
                child: Text('${tasks.completedCount}/${tasks.total}',
                    style: const TextStyle(
                        fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.mint)),
              ),
            ],
          ),
          const Padding(
            padding: EdgeInsets.symmetric(vertical: AppSpacing.md),
            child: Divider(height: 1, color: AppColors.line),
          ),
          for (final item in visible)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 6),
              child: Row(
                children: [
                  Icon(
                    item.done ? Icons.check_circle_rounded : Icons.radio_button_unchecked,
                    size: 22,
                    color: item.done ? AppColors.mint : AppColors.muted,
                  ),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: Text(
                      localizedNewbieTaskLabel(item.key, locale),
                      style: TextStyle(
                        fontSize: 14,
                        color: item.done ? AppColors.ink2 : AppColors.ink,
                        decoration: item.done ? TextDecoration.lineThrough : null,
                        decorationColor: AppColors.muted,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          // 折叠链接（>4 项时）：收起「+N tugas lainnya ↓」/ 展开「Lihat lebih sedikit ↑」。
          if (hidden > 0 || _expanded) ...[
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
                      _expanded ? l10n.newbieCardShowLess : '+${l10n.newbieCardShowMore(hidden)}',
                      style: const TextStyle(
                          fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.mint),
                    ),
                    const SizedBox(width: 4),
                    Icon(
                        _expanded
                            ? Icons.keyboard_arrow_up_rounded
                            : Icons.keyboard_arrow_down_rounded,
                        size: 18,
                        color: AppColors.mint),
                  ],
                ),
              ),
            ),
          ],
        ],
      ),
    );
    // 紫虚线描边 + 右上 BARU 徽章（0711 Tugas Pemula 专区，独立于 L/M/S 里程碑计数）。
    return Stack(
      clipBehavior: Clip.none,
      children: [
        CustomPaint(
          foregroundPainter: DashedRRectPainter(
            color: AppColors.mint,
            radius: 16,
            dash: 6,
            gap: 4,
            strokeWidth: 2,
          ),
          child: card,
        ),
        const Positioned(top: -7, right: 14, child: BaruBadge()),
      ],
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
String _levelTitle(AppLocalizations l10n, MilestoneLevel level) => switch (level) {
      MilestoneLevel.l => l10n.milestoneLevelLegenda,
      MilestoneLevel.m => l10n.milestoneLevelMajor,
      MilestoneLevel.s => l10n.milestoneLevelSmall,
    };

/// 一个级别分区：彩色标题（含该级进度）+ 徽章网格。
class _GroupSection extends StatelessWidget {
  const _GroupSection({required this.group});

  final MilestoneGroup group;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final color = _levelColor(group.level);
    return Column(
      key: ValueKey('milestoneSection_${group.level.name}'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(_levelTitle(l10n, group.level),
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
      // 已完成 → 重温 P-35 解锁庆祝；未完成 → P-33b 详情底抽屉。
      onTap: () => completed
          ? _showCelebration(context, ref, item)
          : _showBadgeSheet(context, ref, item),
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
/// 点击已完成徽章 → 重温 P-35 解锁庆祝动效（L 级附分享卡）。
void _showCelebration(BuildContext context, WidgetRef ref, MilestoneItem item) {
  final l10n = AppLocalizations.of(context);
  final locale = Localizations.localeOf(context);
  final data = ref.read(milestoneListProvider).asData?.value;
  final petName = data?.petName ?? '';
  final collection = data == null ? const <MilestoneItem>[] : [for (final g in data.groups) ...g.items];
  final shareText = l10n.milestoneShareText(localizedMilestoneTitle(item.code, locale));
  final router = GoRouter.maybeOf(context);
  showMilestoneCelebration(
    context,
    item,
    petName: petName,
    collection: collection,
    onShare: () => shareMilestoneWithLink(ref,
        item: item, locale: locale, petName: petName, shareText: shareText, collection: collection),
    onSeeAll: router == null ? null : () => router.go(DeepLinkRoutes.milestoneList),
  );
}

/// P-33b 里程碑详情底抽屉（原型 milestone-sheet）：把手 + 居中大徽章 + 标题 + 级别 chip
/// + DESKRIPSI 说明卡（FR-43 文案）+ 打卡类两入口/系统类只读。
void _showBadgeSheet(BuildContext context, WidgetRef ref, MilestoneItem item) {
  // 打卡引导/说明文案（P-33b · FR-43）需替换 {name} → 宠物名（列表已加载，取当前值即可）。
  final petName = ref.read(milestoneListProvider).asData?.value.petName ?? '';
  showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    backgroundColor: AppColors.surface,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
    ),
    builder: (sheetContext) {
      final l10n = AppLocalizations.of(sheetContext);
      final locale = Localizations.localeOf(sheetContext);
      final levelColor = _levelColor(item.level);
      final completed = item.completed;
      // FR-43 文案：打卡类→提问 Header + 描述 Body；系统/推送类→仅说明（header 空）。
      // 未配文案的 code（如生日 *-L1）→ body 空 → 回退到通用 hint。
      final copy = localizedMilestoneCheckinPrompt(item.code, locale, petName);
      final fallbackHint = switch (item.trigger) {
        MilestoneTrigger.systemAuto => l10n.milestoneHintSystemAuto,
        MilestoneTrigger.pushPublish => l10n.milestoneHintPushPublish,
        MilestoneTrigger.userCheckin => l10n.milestoneHintCheckin,
      };
      final body = copy.body.isNotEmpty ? copy.body : fallbackHint;
      // 仅「用户打卡」且未完成才出两入口；系统/推送类、或已完成 → 只读说明。
      final showCheckinActions = item.trigger.isCheckin && !completed;
      // CTA 文案按级别取（FR-43）；S/M 之外（不会出现在打卡类）兜底用通用文案。
      final (checkedInLabel, goPublishLabel) = switch (item.level) {
        MilestoneLevel.m => (l10n.milestoneActionCheckedInM, l10n.milestoneActionGoPublishM),
        MilestoneLevel.s => (l10n.milestoneActionCheckedInS, l10n.milestoneActionGoPublishS),
        MilestoneLevel.l => (l10n.milestoneActionCheckedIn, l10n.milestoneActionGoPublish),
      };
      final levelChip = switch (item.level) {
        MilestoneLevel.l => l10n.milestoneLevelL,
        MilestoneLevel.m => l10n.milestoneLevelM,
        MilestoneLevel.s => l10n.milestoneLevelS,
      };
      return SafeArea(
        child: SingleChildScrollView(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(22, 8, 22, 24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // 拖拽把手。
              Center(
                child: Container(
                  width: 36,
                  height: 4,
                  margin: const EdgeInsets.only(bottom: 18),
                  decoration: BoxDecoration(
                    color: AppColors.line, borderRadius: BorderRadius.circular(9999)),
                ),
              ),
              // 居中大徽章 + 标题 + 级别 chip。
              Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Container(
                      width: 76,
                      height: 76,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        gradient: completed
                            ? LinearGradient(
                                begin: Alignment.topLeft,
                                end: Alignment.bottomRight,
                                colors: [levelColor, Color.lerp(levelColor, Colors.white, 0.25)!],
                              )
                            : null,
                        color: completed ? null : AppColors.line2,
                        boxShadow: completed
                            ? [BoxShadow(color: levelColor.withValues(alpha: 0.3), blurRadius: 24, offset: const Offset(0, 8))]
                            : null,
                      ),
                      child: Icon(
                        completed ? Icons.emoji_events_rounded : Icons.lock_outline_rounded,
                        size: 36,
                        color: completed ? AppColors.onAccent : AppColors.muted,
                      ),
                    ),
                    const SizedBox(height: 12),
                    Text(
                      localizedMilestoneTitle(item.code, locale),
                      textAlign: TextAlign.center,
                      style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: AppColors.ink),
                    ),
                    const SizedBox(height: 6),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
                      decoration: BoxDecoration(
                        color: AppColors.mintTint, borderRadius: BorderRadius.circular(9999)),
                      child: Text(levelChip,
                          style: const TextStyle(
                              fontSize: 11, fontWeight: FontWeight.w700, color: AppColors.mint700)),
                    ),
                    if (completed && item.completedAt != null) ...[
                      const SizedBox(height: 8),
                      Text(
                        l10n.milestoneCompletedOn(_formatDate(item.completedAt!)),
                        style: const TextStyle(color: AppColors.mint700, fontWeight: FontWeight.w700, fontSize: 12),
                      ),
                    ],
                  ],
                ),
              ),
              const SizedBox(height: 18),
              // 说明卡（DESKRIPSI）：打卡类→提问 Header + 描述；系统类→自动点亮说明。
              Container(
                width: double.infinity,
                padding: const EdgeInsets.fromLTRB(15, 13, 15, 15),
                decoration: BoxDecoration(
                  color: AppColors.mintTint, borderRadius: BorderRadius.circular(14)),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (copy.header.isNotEmpty) ...[
                      Text(copy.header,
                          style: const TextStyle(
                              fontSize: 14.5, fontWeight: FontWeight.w800, height: 1.35, color: AppColors.ink)),
                      const SizedBox(height: 8),
                    ],
                    Text(body, style: const TextStyle(fontSize: 13, height: 1.55, color: AppColors.ink2)),
                  ],
                ),
              ),
              if (showCheckinActions) ...[
                const SizedBox(height: 18),
                // 主按钮「已经历过」在上、次按钮「去拍」在下、最后「稍后」软关闭（原型 P-33b CTA 结构）。
                FilledButton(
                  key: const ValueKey('milestoneCheckedIn'),
                  onPressed: () => _onCheckedIn(sheetContext, ref, item),
                  child: Text(checkedInLabel, textAlign: TextAlign.center),
                ),
                const SizedBox(height: 10),
                OutlinedButton(
                  key: const ValueKey('milestoneGoPublish'),
                  onPressed: () => _onGoPublish(sheetContext, item),
                  child: Text(goPublishLabel, textAlign: TextAlign.center),
                ),
                const SizedBox(height: 4),
                TextButton(
                  onPressed: () => Navigator.of(sheetContext).pop(),
                  child: Text(l10n.pushSoftGuideLater,
                      style: const TextStyle(color: AppColors.muted, fontSize: 12)),
                ),
              ],
            ],
          ),
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
                : AppImage.widget(candidate.firstImageUrl!, fit: BoxFit.cover, thumbWidth: 240),
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
    final overlay = Overlay.of(context, rootOverlay: true);
    final navigator = Navigator.of(context);
    final router = GoRouter.maybeOf(context);
    try {
      // 庆祝卡片文案需替换 {name} → 宠物名（列表已加载，取当前值即可）。
      final listData = ref.read(milestoneListProvider).asData?.value;
      final petName = listData?.petName ?? '';
      final completed =
          await ref.read(milestoneRepositoryProvider).checkIn(milestoneCode, candidate.contentId);
      ref.invalidate(milestoneListProvider);
      // 合集预览：用 checkIn 前的快照，并把刚打卡的那条替换为已完成态（refetch 是异步的，拿不到即时新值）。
      final collection = listData == null
          ? const <MilestoneItem>[]
          : [
              for (final g in listData.groups)
                for (final it in g.items) it.code == completed.code ? completed : it,
            ];
      // L 级分享卡文案（捕获 l10n / locale，避免 pop 后失效）。标题按 locale 本地化（杜绝后端中文）。
      final shareText = l10n.milestoneShareText(localizedMilestoneTitle(completed.code, locale));
      navigator.pop(); // 关 picker
      if (!context.mounted) return;
      // 完成后触发统一 P-35 庆祝动效（Story 8.5）；L 级分享卡复用 2-6 分享通道（8.6）。
      await showMilestoneCelebration(
        context,
        completed,
        petName: petName,
        collection: collection,
        onShare: () => shareMilestoneWithLink(ref,
            item: completed,
            locale: locale,
            petName: petName,
            shareText: shareText,
            collection: collection),
        onSeeAll: router == null ? null : () => router.go(DeepLinkRoutes.milestoneList),
      );
    } catch (_) {
      showAppToastOnOverlay(overlay, l10n.milestoneCheckinFailed);
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

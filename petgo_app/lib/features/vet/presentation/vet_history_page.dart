import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../data/vet_repository.dart';
import '../domain/vet_workbench_lists.dart';
import 'vet_empty_state.dart';

/// 历史记录 Tab（原型 vet-history.html 1:1）：深色顶栏 + 今日总数 + 筛选 Chip + 记录卡（只读）。
class VetHistoryPage extends ConsumerStatefulWidget {
  const VetHistoryPage({super.key});

  @override
  ConsumerState<VetHistoryPage> createState() => _VetHistoryPageState();
}

/// 筛选维度（客户端过滤已拉列表）。
enum _HistoryFilter { all, topRated, yellow, red }

class _VetHistoryPageState extends ConsumerState<VetHistoryPage> {
  late Future<List<VetHistoryEntry>> _items;
  _HistoryFilter _filter = _HistoryFilter.all;

  @override
  void initState() {
    super.initState();
    _items = ref.read(vetRepositoryProvider).history();
  }

  List<VetHistoryEntry> _apply(List<VetHistoryEntry> all) {
    switch (_filter) {
      case _HistoryFilter.all:
        return all;
      case _HistoryFilter.topRated:
        return all.where((e) => e.stars != null && e.stars! >= 4).toList();
      case _HistoryFilter.yellow:
        return all.where((e) => e.dangerLevel == 'YELLOW').toList();
      case _HistoryFilter.red:
        return all.where((e) => e.dangerLevel == 'RED').toList();
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.vetSurface2,
      body: FutureBuilder<List<VetHistoryEntry>>(
        future: _items,
        builder: (context, snapshot) {
          final loading = snapshot.connectionState == ConnectionState.waiting;
          final all = snapshot.data ?? const <VetHistoryEntry>[];
          final filtered = _apply(all);
          return Column(
            children: [
              _topBar(l10n, all.length),
              _filterRow(l10n),
              Expanded(
                child: loading
                    ? const Center(child: CircularProgressIndicator())
                    : all.isEmpty
                        ? VetEmptyState(icon: Icons.history, message: l10n.vetHistoryEmpty)
                        : filtered.isEmpty
                            ? VetEmptyState(icon: Icons.filter_alt_off_outlined, message: l10n.vetHistoryFilterEmpty)
                            : ListView.separated(
                                padding: const EdgeInsets.fromLTRB(
                                    AppSpacing.md, AppSpacing.sm, AppSpacing.md, AppSpacing.xl),
                                itemCount: filtered.length,
                                separatorBuilder: (_, _) => const SizedBox(height: AppSpacing.sm),
                                itemBuilder: (ctx, i) => _HistoryCard(entry: filtered[i]),
                              ),
              ),
            ],
          );
        },
      ),
    );
  }

  /// 深色顶栏 #2B2540：「Riwayat Konsultasi」+ 今日总数。
  Widget _topBar(AppLocalizations l10n, int total) {
    return Container(
      width: double.infinity,
      color: AppColors.vetTopBar,
      child: SafeArea(
        bottom: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(AppSpacing.lg, AppSpacing.md, AppSpacing.lg, AppSpacing.md),
          child: Row(
            children: [
              Expanded(
                child: Text(l10n.vetHistoryTitle, style: AppTypography.title.copyWith(color: Colors.white)),
              ),
              Text(l10n.vetHistoryTotalToday(total),
                  style: AppTypography.caption.copyWith(color: Colors.white.withValues(alpha: 0.5))),
            ],
          ),
        ),
      ),
    );
  }

  /// 筛选 Chip 行：Semua / ⭐4-5 / 🟡 / 🔴。选中薄荷实底白字。
  Widget _filterRow(AppLocalizations l10n) {
    return SizedBox(
      height: 48,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md, vertical: AppSpacing.sm),
        children: [
          _chip(l10n.vetHistoryFilterAll, _HistoryFilter.all),
          _chip(l10n.vetHistoryFilterTopRated, _HistoryFilter.topRated),
          _chip(l10n.vetHistoryFilterYellow, _HistoryFilter.yellow),
          _chip(l10n.vetHistoryFilterRed, _HistoryFilter.red),
        ],
      ),
    );
  }

  Widget _chip(String label, _HistoryFilter value) {
    final selected = _filter == value;
    return Padding(
      padding: const EdgeInsets.only(right: AppSpacing.sm),
      child: InkWell(
        onTap: () => setState(() => _filter = value),
        borderRadius: BorderRadius.circular(999),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 5),
          decoration: BoxDecoration(
            color: selected ? AppColors.vetPrimary : AppColors.surface,
            borderRadius: BorderRadius.circular(999),
            border: Border.all(color: selected ? AppColors.vetPrimary : AppColors.border),
          ),
          child: Center(
            child: Text(
              label,
              style: AppTypography.caption.copyWith(
                color: selected ? AppColors.vetOnAccent : AppColors.textSecondary,
                fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _HistoryCard extends StatelessWidget {
  const _HistoryCard({required this.entry});

  final VetHistoryEntry entry;

  bool get _interrupted => entry.terminalState == 'INTERRUPTED';

  String _speciesEmoji() {
    switch (entry.petSpecies) {
      case 'CAT':
        return '🐱';
      case 'DOG':
        return '🐶';
      default:
        return '🐾';
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final nameLine = entry.ownerHandle != null ? '${entry.petName} · @${entry.ownerHandle}' : entry.petName;
    return Container(
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(14),
        boxShadow: [
          BoxShadow(color: AppColors.ink.withValues(alpha: 0.06), blurRadius: 8, offset: const Offset(0, 2)),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 36,
                height: 36,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: (_levelColor() ?? AppColors.muted).withValues(alpha: 0.12),
                  shape: BoxShape.circle,
                ),
                child: Text(_speciesEmoji(), style: const TextStyle(fontSize: 16)),
              ),
              const SizedBox(width: AppSpacing.sm),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(nameLine,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: AppTypography.body.copyWith(color: AppColors.ink, fontWeight: FontWeight.w700)),
                    const SizedBox(height: 2),
                    Text(entry.summary,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: AppTypography.caption.copyWith(color: AppColors.textTertiary)),
                  ],
                ),
              ),
              const SizedBox(width: AppSpacing.sm),
              Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  if (!_interrupted && entry.stars != null)
                    Text('⭐ ${entry.stars!.toStringAsFixed(1)}',
                        style: AppTypography.caption.copyWith(color: AppColors.gold, fontWeight: FontWeight.w700)),
                  const SizedBox(height: 2),
                  Text(entry.dateLabel, style: AppTypography.micro.copyWith(color: AppColors.textTertiary)),
                ],
              ),
            ],
          ),
          if (entry.reviewText != null && entry.reviewText!.isNotEmpty) ...[
            const SizedBox(height: AppSpacing.sm),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
              decoration: BoxDecoration(color: AppColors.vetSurface2, borderRadius: BorderRadius.circular(10)),
              child: Text('"${entry.reviewText!}"',
                  style: AppTypography.caption.copyWith(color: AppColors.ink2, height: 1.5)),
            ),
          ],
          const SizedBox(height: AppSpacing.sm),
          Row(
            children: [
              if (entry.dangerLevel != null) ...[
                _badge('${_levelEmoji()} ${_levelLabel(l10n)!}', _levelBg(), _levelColor()!),
                const SizedBox(width: 6),
              ],
              _badge(
                _interrupted ? l10n.terminalInterrupted : l10n.terminalClosed,
                _interrupted ? AppColors.muted.withValues(alpha: 0.15) : AppColors.vetSurface,
                _interrupted ? AppColors.textSecondary : AppColors.vetPrimaryDeep,
              ),
              const Spacer(),
              Text(l10n.vetHistoryView,
                  style: AppTypography.caption.copyWith(color: AppColors.vetPrimary, fontWeight: FontWeight.w600)),
            ],
          ),
        ],
      ),
    );
  }

  String _levelEmoji() {
    switch (entry.dangerLevel) {
      case 'RED':
        return '🔴';
      case 'YELLOW':
        return '🟡';
      default:
        return '🟢';
    }
  }

  Color? _levelColor() {
    switch (entry.dangerLevel) {
      case 'RED':
        return AppColors.triageRed;
      case 'YELLOW':
        return AppColors.gold;
      case 'GREEN':
        return AppColors.vetPrimaryDeep;
      default:
        return null;
    }
  }

  Color _levelBg() {
    switch (entry.dangerLevel) {
      case 'RED':
        return AppColors.coralTint;
      case 'YELLOW':
        return AppColors.goldTint;
      default:
        return AppColors.vetSurface;
    }
  }

  String? _levelLabel(AppLocalizations l10n) {
    switch (entry.dangerLevel) {
      case 'RED':
        return l10n.vetQueueLevelRed;
      case 'YELLOW':
        return l10n.vetQueueLevelYellow;
      case 'GREEN':
        return l10n.vetQueueLevelGreen;
      default:
        return null;
    }
  }

  Widget _badge(String text, Color bg, Color fg) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(5)),
      child: Text(text, style: AppTypography.micro.copyWith(color: fg, fontWeight: FontWeight.w600)),
    );
  }
}

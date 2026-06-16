import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../data/vet_repository.dart';
import '../domain/vet_workbench_lists.dart';
import 'vet_empty_state.dart';

/// 历史记录 Tab：兽医已结束/中断的问诊概览（只读）。
class VetHistoryPage extends ConsumerStatefulWidget {
  const VetHistoryPage({super.key});

  @override
  ConsumerState<VetHistoryPage> createState() => _VetHistoryPageState();
}

class _VetHistoryPageState extends ConsumerState<VetHistoryPage> {
  late Future<List<VetHistoryEntry>> _items;

  @override
  void initState() {
    super.initState();
    _items = ref.read(vetRepositoryProvider).history();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.vetTabHistory)),
      body: FutureBuilder<List<VetHistoryEntry>>(
        future: _items,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }
          final items = snapshot.data ?? const [];
          if (items.isEmpty) {
            return VetEmptyState(icon: Icons.history, message: l10n.vetHistoryEmpty);
          }
          return ListView.separated(
            padding: const EdgeInsets.all(AppSpacing.md),
            itemCount: items.length,
            separatorBuilder: (_, _) => const SizedBox(height: AppSpacing.sm),
            itemBuilder: (ctx, i) => _HistoryCard(entry: items[i]),
          );
        },
      ),
    );
  }
}

class _HistoryCard extends StatelessWidget {
  const _HistoryCard({required this.entry});

  final VetHistoryEntry entry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final interrupted = entry.terminalState == 'INTERRUPTED';
    return Container(
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(child: Text(entry.petName, style: AppTypography.body)),
              Text(entry.dateLabel, style: AppTypography.caption),
            ],
          ),
          const SizedBox(height: 4),
          Text(entry.summary, style: AppTypography.caption),
          const SizedBox(height: AppSpacing.sm),
          Row(
            children: [
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                decoration: BoxDecoration(
                  color: AppColors.divider,
                  borderRadius: BorderRadius.circular(999),
                ),
                child: Text(interrupted ? l10n.terminalInterrupted : l10n.terminalClosed,
                    style: AppTypography.disclaimer),
              ),
              const Spacer(),
              if (!interrupted && entry.stars != null) _Stars(filled: entry.stars!),
            ],
          ),
        ],
      ),
    );
  }
}

/// 用户评分星（filled=星数）。
class _Stars extends StatelessWidget {
  const _Stars({required this.filled});

  final int filled;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (int i = 0; i < 5; i++)
          Icon(
            i < filled ? Icons.star_rounded : Icons.star_outline_rounded,
            size: 16,
            color: Colors.amber,
          ),
      ],
    );
  }
}

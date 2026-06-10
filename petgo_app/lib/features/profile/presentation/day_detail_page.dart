import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../data/timeline_repository.dart';
import '../domain/timeline_item.dart';
import 'widgets/timeline_tiles.dart';

/// 当天详情页（Story 2.4 AC6 · F9）。某事件日期当天快乐时刻 + 健康事件，created_at 正序。
///
/// **不设「+」、不设删除入口**（AC6）。快乐时刻条目点击进 FR-28 内容详情；健康事件不可点。
class DayDetailPage extends ConsumerWidget {
  const DayDetailPage({super.key, required this.date});

  final DateTime date;

  String get _title =>
      '${date.year}-${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')}';

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final dayKey = DateTime(date.year, date.month, date.day);
    final async = ref.watch(dayDetailProvider(dayKey));
    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(title: Text(_title), backgroundColor: AppColors.cream),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => _DayError(onRetry: () => ref.invalidate(dayDetailProvider(dayKey))),
        data: (detail) {
          if (detail.items.isEmpty) {
            return Center(
                child: Text(l10n.growthDayDetailEmpty,
                    style: const TextStyle(color: AppColors.textTertiary)));
          }
          return ListView(
            padding: const EdgeInsets.fromLTRB(AppSpacing.lg, AppSpacing.lg, AppSpacing.lg, AppSpacing.section),
            children: [for (final item in detail.items) _tile(context, item)],
          );
        },
      ),
    );
  }

  Widget _tile(BuildContext context, TimelineItem item) {
    if (item.kind == TimelineKind.healthEvent) {
      return HealthEventTile(item: item); // 健康事件当天不可点
    }
    // 快乐时刻 → FR-28 内容详情。
    return GestureDetector(
      key: ValueKey('dayItem_${item.postId}'),
      onTap: item.postId == null ? null : () => context.push('/content/${item.postId}'),
      child: HappyMomentTile(item: item),
    );
  }
}

class _DayError extends StatelessWidget {
  const _DayError({required this.onRetry});

  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Center(
      key: const ValueKey('dayDetailError'),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Text(l10n.growthLoadFailed, style: const TextStyle(color: AppColors.muted)),
          const SizedBox(height: 8),
          TextButton(onPressed: onRetry, child: Text(l10n.growthLoadRetry)),
        ],
      ),
    );
  }
}

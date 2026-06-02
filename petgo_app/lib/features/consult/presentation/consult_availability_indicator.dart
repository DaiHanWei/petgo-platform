import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../data/consult_repository.dart';

/// 兽医咨询入口可用性提示（Story 5.2 F3）。
///
/// 读 `consultAvailabilityProvider`：无兽医在线 → 「当前暂无兽医在线」基础态。
/// **不展示在线人数**（架构 FR-4B：概率性展示）。完整离线软引导（恢复时段 + AI 分诊跳转）在 5.3 扩展。
class ConsultAvailabilityIndicator extends ConsumerWidget {
  const ConsultAvailabilityIndicator({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final availability = ref.watch(consultAvailabilityProvider);
    return availability.maybeWhen(
      data: (online) => online
          ? const SizedBox.shrink()
          : Container(
              key: const ValueKey('consultNoVetOnline'),
              padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md, vertical: AppSpacing.sm),
              decoration: BoxDecoration(
                color: AppColors.surface,
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: AppColors.border),
              ),
              child: Row(
                children: [
                  const Icon(Icons.schedule, size: 18, color: AppColors.textTertiary),
                  const SizedBox(width: AppSpacing.sm),
                  Expanded(child: Text(l10n.consultNoVetOnline, style: AppTypography.caption)),
                ],
              ),
            ),
      orElse: () => const SizedBox.shrink(),
    );
  }
}

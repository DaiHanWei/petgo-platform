import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/rounded.dart';
import '../../core/theme/spacing.dart';
import '../../core/theme/typography.dart';
import '../../l10n/app_localizations.dart';

/// 宠物状态三选一（A/B/C）可复用组件（FR-0F / FR-21 / FR-20）。
///
/// 引导流用一次；成长档案 Tab（Epic 2）/「我的」（Epic 7）后续复用同一界面（注入不同提交回调）。
/// **必选、不可跳过**由调用方据 [selected] 是否为空控制「完成」按钮可用性。
class PetStatusSelector extends StatelessWidget {
  const PetStatusSelector({super.key, required this.selected, required this.onChanged});

  /// 当前选中（'A'|'B'|'C'，null=未选）。
  final String? selected;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        _option('A', l10n.petStatusA),
        const SizedBox(height: AppSpacing.md),
        _option('B', l10n.petStatusB),
        const SizedBox(height: AppSpacing.md),
        _option('C', l10n.petStatusC),
      ],
    );
  }

  Widget _option(String value, String label) {
    final bool active = selected == value;
    return InkWell(
      key: ValueKey('petStatus_$value'),
      borderRadius: AppRounded.mdRadius,
      onTap: () => onChanged(value),
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.all(AppSpacing.lg),
        decoration: BoxDecoration(
          color: active ? AppColors.accentGrowth.withValues(alpha: 0.10) : AppColors.surface,
          borderRadius: AppRounded.mdRadius,
          border: Border.all(
            color: active ? AppColors.accentGrowth : AppColors.border,
            width: active ? 2 : 1,
          ),
        ),
        child: Row(
          children: [
            Icon(
              active ? Icons.radio_button_checked : Icons.radio_button_unchecked,
              color: active ? AppColors.accentGrowth : AppColors.textTertiary,
            ),
            const SizedBox(width: AppSpacing.md),
            Expanded(child: Text(label, style: AppTypography.body)),
          ],
        ),
      ),
    );
  }
}

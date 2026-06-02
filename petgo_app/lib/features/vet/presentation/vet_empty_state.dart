import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';

/// 兽医工作台 Tab 空态占位（Story 5.2）。待接单/进行中/历史在本故事均为空态，后续 Story 填内容。
class VetEmptyState extends StatelessWidget {
  const VetEmptyState({super.key, required this.icon, required this.message});

  final IconData icon;
  final String message;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.xl),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 48, color: AppColors.textTertiary),
            const SizedBox(height: AppSpacing.md),
            Text(message, style: AppTypography.body, textAlign: TextAlign.center),
          ],
        ),
      ),
    );
  }
}

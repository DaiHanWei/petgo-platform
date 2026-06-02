import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/rounded.dart';
import '../../core/theme/spacing.dart';
import '../../core/theme/typography.dart';
import '../../features/triage/data/triage_repository.dart';

/// 分诊结果三态卡视觉（Story 4.4 · F1，UX-DR6）。白底 `rounded.md` + **左 3px 区域色边框** +
/// triage badge（icon + 文本 + 色三重表达，不依赖颜色单一，NFR-13）。仅用于绿/黄；红走 4.5 半屏。
class TriageResultCard extends StatelessWidget {
  const TriageResultCard({
    super.key,
    required this.level,
    required this.title,
    required this.child,
  });

  /// 仅 [DangerLevel.green] / [DangerLevel.yellow]（红色不在本卡渲染）。
  final DangerLevel level;
  final String title;
  final Widget child;

  Color get _color =>
      level == DangerLevel.yellow ? AppColors.triageYellow : AppColors.triageGreen;

  IconData get _icon => level == DangerLevel.yellow
      ? Icons.warning_amber_rounded
      : Icons.check_circle_outline;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(AppRounded.md),
        border: Border(left: BorderSide(color: _color, width: 3)),
      ),
      padding: const EdgeInsets.all(AppSpacing.lg),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Row(
            children: <Widget>[
              Icon(_icon, color: _color), // icon 表达
              const SizedBox(width: AppSpacing.sm),
              Expanded(
                child: Text(title, style: AppTypography.title.copyWith(color: _color)), // 文本+色
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.md),
          child,
        ],
      ),
    );
  }
}

import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/spacing.dart';
import '../../core/theme/typography.dart';

/// 通用空状态占位（UX-DR8）。文案由调用方传入（须来自 .arb）。
///
/// Story 1.5 用于游客首页 Feed 未填充时的占位；Story 3.2 填充真实 Feed 空态 + 发布 CTA。
/// 可选 [actionLabel]/[onAction] 渲染一个引导按钮（如「发布第一条内容」）。
class EmptyState extends StatelessWidget {
  const EmptyState({
    super.key,
    required this.title,
    this.message,
    this.icon,
    this.actionLabel,
    this.onAction,
    this.secondaryLabel,
    this.onSecondary,
    this.hideIcon = false,
    this.iconBackground,
  });

  final String title;
  final String? message;
  final IconData? icon;

  /// 可选图标圆形底盘（原型 notif/通知空态：80×80 浅紫圆底包裹图标）。null=裸图标。
  final Color? iconBackground;
  final String? actionLabel;
  final VoidCallback? onAction;

  /// 可选次级链接（原型 feed 空/错态的「Temukan Teman →」/「Laporkan Masalah」）。
  final String? secondaryLabel;
  final VoidCallback? onSecondary;

  /// 隐藏大图标（原型 feed 空/错态无大 icon，仅标题+副文+按钮）。
  final bool hideIcon;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.xl),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            if (!hideIcon) ...[
              if (iconBackground != null)
                Container(
                  width: 80,
                  height: 80,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(color: iconBackground, shape: BoxShape.circle),
                  child: Icon(icon ?? Icons.pets_rounded, size: 36, color: AppColors.textTertiary),
                )
              else
                Icon(icon ?? Icons.pets_rounded, size: 48, color: AppColors.textTertiary),
              const SizedBox(height: AppSpacing.md),
            ],
            Text(title, style: AppTypography.title, textAlign: TextAlign.center),
            if (message != null) ...[
              const SizedBox(height: AppSpacing.sm),
              Text(message!, style: AppTypography.caption, textAlign: TextAlign.center),
            ],
            if (actionLabel != null && onAction != null) ...[
              const SizedBox(height: AppSpacing.lg),
              FilledButton(
                key: const ValueKey('emptyStateAction'),
                onPressed: onAction,
                child: Text(actionLabel!),
              ),
            ],
            if (secondaryLabel != null && onSecondary != null) ...[
              const SizedBox(height: AppSpacing.xs),
              TextButton(
                key: const ValueKey('emptyStateSecondary'),
                onPressed: onSecondary,
                child: Text(secondaryLabel!,
                    style: const TextStyle(
                        color: AppColors.mint, fontWeight: FontWeight.w600)),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

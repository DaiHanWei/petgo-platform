import 'package:flutter/material.dart';

import 'colors.dart';
import 'typography.dart';

/// 应用主题装配（V1 仅浅色，dark 延 V2）。
///
/// 所有视觉常量来自 `core/theme` 设计 token（colors/typography/...）。
/// `scaffoldBackgroundColor = AppColors.base` —— 底色恒 #FAF8F5，不随 Tab 变（UX-DR1）。
class AppTheme {
  AppTheme._();

  static ThemeData get light {
    final ColorScheme scheme = ColorScheme.fromSeed(
      seedColor: AppColors.accentGrowth,
      brightness: Brightness.light,
    ).copyWith(
      surface: AppColors.surface,
      onSurface: AppColors.textPrimary,
    );

    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.light,
      colorScheme: scheme,
      scaffoldBackgroundColor: AppColors.base,
      textTheme: const TextTheme(
        displaySmall: AppTypography.display,
        headlineSmall: AppTypography.headline,
        titleMedium: AppTypography.title,
        bodyMedium: AppTypography.body,
        bodySmall: AppTypography.caption,
        labelSmall: AppTypography.micro,
      ),
    );
  }
}

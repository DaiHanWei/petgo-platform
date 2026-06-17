import 'package:flutter/material.dart';

import 'colors.dart';
import 'typography.dart';

/// 应用主题装配（V1 仅浅色，dark 延 V2）。
///
/// 所有视觉常量来自 `core/theme` 设计 token（colors/typography/...）。
/// `scaffoldBackgroundColor = AppColors.base` —— 底色恒 #FAF8F5，不随 Tab 变（UX-DR1）。
class AppTheme {
  AppTheme._();

  static ThemeData get light => _build(AppColors.accentGrowth);

  /// 兽医端独立主题：以薄荷绿 `vetPrimary` 种子化（原型 H5）。
  /// 由 router 在 `/vet/*` 子树包 `Theme(data: AppTheme.vet)` 应用，与用户侧紫物理隔离。
  static ThemeData get vet => _build(AppColors.vetPrimary);

  static ThemeData _build(Color seed) {
    final ColorScheme scheme = ColorScheme.fromSeed(
      seedColor: seed,
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

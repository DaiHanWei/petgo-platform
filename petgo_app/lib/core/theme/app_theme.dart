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
      // primary 精确取种子本色（原型主色 #845EC9 / 兽医薄荷）；fromSeed 会把 primary
      // 派生成偏暗的哑色，导致 FilledButton 等比设计稿主色暗一档，故显式回填 seed。
      primary: seed,
      surface: AppColors.surface,
      onSurface: AppColors.textPrimary,
    );

    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.light,
      colorScheme: scheme,
      scaffoldBackgroundColor: AppColors.base,
      // 全局字体 = Poppins（原型 UI 字体，已打包 assets/fonts，权重 400/500/600/700）。
      fontFamily: 'Poppins',
      textTheme: const TextTheme(
        displaySmall: AppTypography.display,
        headlineSmall: AppTypography.headline,
        titleMedium: AppTypography.title,
        bodyMedium: AppTypography.body,
        bodySmall: AppTypography.caption,
        labelSmall: AppTypography.micro,
      ),
      // 子页标题栏全 App 统一跟 UI 稿（决策 UI-2，2026-09-21 选 batch-a `tt-appbar` 样式）：
      // 44 高 · 纯图标「‹」返回 · 标题 16/w700 紧跟返回键左对齐 · 底色同页面 · 滚动不被 M3 染灰。
      // 个别页面显式 centerTitle / leadingWidth（表单页「Batal | 标题 | Simpan」等）照旧由页面覆盖。
      appBarTheme: const AppBarTheme(
        toolbarHeight: 44,
        backgroundColor: AppColors.base,
        foregroundColor: AppColors.ink,
        elevation: 0,
        scrolledUnderElevation: 0,
        surfaceTintColor: Colors.transparent,
        centerTitle: false,
        // 返回键区 44 宽 + 标题间距 2 ≈ 稿里「14 内边距 + 22 图标 + 10 间距」后的标题起点。
        leadingWidth: 44,
        titleSpacing: 2,
        iconTheme: IconThemeData(color: AppColors.ink, size: 22),
        titleTextStyle: TextStyle(
          fontFamily: 'Poppins',
          fontSize: 16,
          fontWeight: FontWeight.w700,
          color: AppColors.ink,
        ),
      ),
      actionIconTheme: ActionIconThemeData(
        backButtonIconBuilder: (_) => const Icon(Icons.chevron_left_rounded, size: 28),
      ),
      // 主按钮全 App 统一跟 UI 稿（决策 UI-2，batch-a `tt-btn-primary`）：46 高 · 圆角 12 · 14.5/w700。
      // 页面 styleFrom 显式写了的属性仍优先（主题只提供默认值）。
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size(64, 46),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          textStyle: const TextStyle(
            fontFamily: 'Poppins',
            fontSize: 14.5,
            fontWeight: FontWeight.w700,
          ),
        ),
      ),
    );
  }
}

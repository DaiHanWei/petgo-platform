import 'package:flutter/material.dart';

/// 应用主题（V1 仅浅色）。
/// 注：完整设计 token 体系（配色/间距/圆角/Tab Bar 外壳）属 Story 1.2，本 Story 仅给可运行的浅色基线。
class AppTheme {
  AppTheme._();

  static ThemeData get light => ThemeData(
        useMaterial3: true,
        brightness: Brightness.light,
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF4CAF50),
          brightness: Brightness.light,
        ),
      );
}

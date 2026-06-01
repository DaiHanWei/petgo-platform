import 'package:flutter/material.dart';

import 'colors.dart';

/// 设计 token —— 字体阶（UX-DR1）。
///
/// scale：display → micro，外加 badge / button / disclaimer 专用样式。
/// 支持动态字体（NFR-13，由系统 textScaler 驱动，缩放 ≤3 级）。
class AppTypography {
  AppTypography._();

  // --- 正文 scale（display → micro）---
  static const TextStyle display = TextStyle(
      fontSize: 30, fontWeight: FontWeight.w700, height: 1.2, color: AppColors.textPrimary);
  static const TextStyle headline = TextStyle(
      fontSize: 24, fontWeight: FontWeight.w700, height: 1.25, color: AppColors.textPrimary);
  static const TextStyle title = TextStyle(
      fontSize: 18, fontWeight: FontWeight.w600, height: 1.3, color: AppColors.textPrimary);
  static const TextStyle body = TextStyle(
      fontSize: 15, fontWeight: FontWeight.w400, height: 1.45, color: AppColors.textPrimary);
  static const TextStyle caption = TextStyle(
      fontSize: 13, fontWeight: FontWeight.w400, height: 1.4, color: AppColors.textSecondary);
  static const TextStyle micro = TextStyle(
      fontSize: 11, fontWeight: FontWeight.w400, height: 1.3, color: AppColors.textTertiary);

  // --- 专用样式 ---
  static const TextStyle button = TextStyle(
      fontSize: 16, fontWeight: FontWeight.w600, height: 1.2, color: AppColors.onAccent);
  static const TextStyle badge = TextStyle(
      fontSize: 10, fontWeight: FontWeight.w600, height: 1.0, color: AppColors.onAccent);
  static const TextStyle disclaimer = TextStyle(
      fontSize: 12, fontWeight: FontWeight.w400, height: 1.4, color: AppColors.textDisclaimer);

  /// 底部 Tab inactive 标签（9px）。
  static const TextStyle tabLabel =
      TextStyle(fontSize: 9, fontWeight: FontWeight.w500, height: 1.1);
}

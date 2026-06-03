import 'package:flutter/material.dart';

/// 设计 token —— 颜色（UX-DR1）。
///
/// 全局底色恒为 #FAF8F5，不因 Tab 切换整屏变色。
/// 强制护栏：业务/组件文件**禁止**硬编码 `Color(0x...)` / hex，一律引用此处常量。
class AppColors {
  AppColors._();

  /// 全局底色（scaffoldBackgroundColor，恒定）。
  static const Color base = Color(0xFFFAF8F5);

  /// 卡片/表面白。
  static const Color surface = Color(0xFFFFFFFF);

  // --- 文字三级（满足 NFR-13 AA ≥4.5:1）---
  static const Color textPrimary = Color(0xFF2E2A26);
  static const Color textSecondary = Color(0xFF6B6259);
  static const Color textTertiary = Color(0xFF9B928A);

  /// disclaimer 文案（对比度 ≥3:1）。
  static const Color textDisclaimer = Color(0xFF8A817A);

  // --- 区域强调色（zone accent）---
  /// 成长区焦糖色。
  static const Color accentGrowth = Color(0xFFC8874A);

  /// 问诊区莫兰迪蓝。
  static const Color accentConsult = Color(0xFF7BA7BC);

  // --- 分诊语义三色（备 Epic 4）---
  static const Color triageGreen = Color(0xFF7FB069);
  static const Color triageYellow = Color(0xFFE0A458);
  static const Color triageRed = Color(0xFFC97A7A);

  /// 黄色协议浅底（Epic 4 倒计时协议背景）。
  static const Color triageYellowSurface = Color(0xFFEEF4F7);

  // --- 中性 ---
  /// 分割线 / 描边。
  static const Color border = Color(0xFFEDE7E0);
  static const Color divider = Color(0xFFE5DED6);

  /// 强调色之上的前景（白图标 / 凸起「＋」描边白）。
  static const Color onAccent = Color(0xFFFFFFFF);

  /// 危险 / 错误（复用 triageRed 语义）。
  static const Color danger = triageRed;

  /// 点赞红心（暖珊瑚红，PRD-642 卡片点赞数）。刻意区别于 triageRed，不稀释红色态危险语义。
  static const Color likeHeart = Color(0xFFE5705F);
}

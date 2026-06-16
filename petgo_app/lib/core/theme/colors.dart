import 'package:flutter/material.dart';

/// 设计 token —— 颜色（TailTopia Prototype / 薄荷绿 × 米白 · Duolingo×Notion）。
///
/// 2026-06-04 全面换肤：主色由焦糖色切换为**薄荷绿 #7FD1AE**，作为新设计源真相。
/// 强制护栏：业务/组件文件**禁止**硬编码 `Color(0x...)` / hex，一律引用此处常量。
///
/// 分两层：
/// 1) 新设计 token（mint / cream / ink / 强调色 / 吉祥物）—— 新组件优先用这一层。
/// 2) 兼容别名（base/surface/textPrimary/accentGrowth…）—— 老页面引用照旧编译，值已迁到薄荷绿世界。
class AppColors {
  AppColors._();

  // ============================================================
  // 1) 新设计 token
  // ============================================================

  // —— Brand: mint ——
  static const Color mint = Color(0xFF7FD1AE); // primary
  static const Color mint500 = Color(0xFF63C49B);
  static const Color mint600 = Color(0xFF48B083); // 按钮下唇 / 按下态
  static const Color mint700 = Color(0xFF2F9669); // tint 上深色文字
  static const Color mintTint = Color(0xFFEAF7F1); // 柔填充
  static const Color mintTint2 = Color(0xFFF2FBF7);

  // —— Canvas: 米白 ——
  static const Color cream = Color(0xFFFBF8F1); // app 画布
  static const Color cream2 = Color(0xFFF5F0E5); // 分组背景
  static const Color card = Color(0xFFFFFFFF);

  // —— Ink ——
  static const Color ink = Color(0xFF2B2A27);
  static const Color ink2 = Color(0xFF5C594F);
  static const Color muted = Color(0xFF98948A);
  static const Color line = Color(0xFFECE7DB);
  static const Color line2 = Color(0xFFF3EFE6);

  // —— Playful accents（oklch 等明度/彩度、异相 → 已换算为 sRGB）——
  static const Color coral = Color(0xFFF5997C); // 日常分享 / warm
  static const Color gold = Color(0xFFE4C064); // 成长日历 / 快乐
  static const Color sky = Color(0xFF5BC1F2); // 专业科普 / info
  static const Color grape = Color(0xFFBD8EDA); // 群聊认领
  static const Color coralTint = Color(0xFFFFE3D8);
  static const Color goldTint = Color(0xFFFBEDCD);
  static const Color skyTint = Color(0xFFD5F4FF);
  static const Color grapeTint = Color(0xFFF5E9FE);

  // —— 吉祥物 Momo（占位 IP，仅插画内部用，业务勿引）——
  static const Color momoBody = Color(0xFF7FD1AE);
  static const Color momoBodyLight = Color(0xFF93DABE);
  static const Color momoEarInner = Color(0xFFF4B8C0);
  static const Color momoEye = Color(0xFF2B4A3C);
  static const Color momoBlush = Color(0x8CF496A4); // rgba(244,150,164,.55)
  static const Color momoNose = Color(0xFFE78CA0);

  // —— Splash 启动屏品牌暗底（一次性品牌过场，深薄荷墨；本流唯一深色屏）——
  static const Color splashInk = Color(0xFF12211B); // 深底
  static const Color splashGlow = mint; // 中心辉光（薄荷，非原型紫）

  // —— Status ——
  static const Color danger = Color(0xFFE5604D);

  // ============================================================
  // 2) 兼容别名（老页面照旧引用；值已迁薄荷绿）
  // ============================================================

  /// 全局底色（scaffoldBackgroundColor，恒定）= 米白画布。
  static const Color base = cream;

  /// 卡片/表面白。
  static const Color surface = card;

  // 文字三级（满足 NFR-13 AA ≥4.5:1）
  static const Color textPrimary = ink;
  static const Color textSecondary = ink2;
  static const Color textTertiary = muted;

  /// disclaimer 文案。
  static const Color textDisclaimer = Color(0xFF8A817A);

  /// 区域强调色 —— 全面换肤后统一为薄荷绿主色。
  static const Color accentGrowth = mint;

  /// 问诊区强调 —— 迁为 sky（原莫兰迪蓝）。
  static const Color accentConsult = sky;

  // 分诊语义三色（Epic 4；保留语义不动）
  static const Color triageGreen = Color(0xFF7FB069);
  static const Color triageYellow = Color(0xFFE0A458);
  static const Color triageRed = Color(0xFFC97A7A);

  /// 黄色协议浅底（Epic 4 倒计时协议背景）。
  static const Color triageYellowSurface = Color(0xFFEEF4F7);

  // Feed/发布卡封面占位柔彩（按内容类型取色）—— 迁到新强调 tint。
  static const Color coverDaily = coralTint; // 日常分享
  static const Color coverGrowth = goldTint; // 成长瞬间
  static const Color coverKnowledge = skyTint; // 专业科普

  // 问诊入口卡柔彩底（AI 薄荷 / 兽医天蓝）。
  static const Color consultEntryAi = mintTint;
  static const Color consultEntryVet = skyTint;

  // 中性
  static const Color border = line;
  static const Color divider = line2;

  /// 强调色之上的前景（白图标 / 凸起「＋」描边白）。
  static const Color onAccent = Color(0xFFFFFFFF);

  /// 点赞红心 —— 迁为 coral。
  static const Color likeHeart = coral;
}

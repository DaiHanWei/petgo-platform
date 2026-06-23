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

  // —— Brand: violet（原型 #845EC9 体系。常量名沿用 mint* 不改，业务代码照旧编译）——
  static const Color mint = Color(0xFF845EC9); // violet-500 primary
  static const Color mint500 = Color(0xFF9E83DA); // violet-400 secondary
  static const Color mint600 = Color(0xFF6C48AE); // violet-700 按下态/下唇
  static const Color mint700 = Color(0xFF6C48AE); // tint 上深色文字
  static const Color mintTint = Color(0xFFF8F2FF); // violet-50 柔填充
  static const Color mintTint2 = Color(0xFFF8F6FF);

  // —— Canvas: 纯白（原型 QA：画布纯白无紫调）——
  static const Color cream = Color(0xFFFFFFFF); // app 画布
  static const Color cream2 = Color(0xFFF8F2FF); // 分组背景（violet-50 点缀）
  static const Color card = Color(0xFFFFFFFF);

  // —— Ink（原型文字色）——
  static const Color ink = Color(0xFF2E2A45); // 标题/正文主
  static const Color ink2 = Color(0xFF544864); // ink-700 次级
  static const Color muted = Color(0xFF9690A6); // 弱化/占位
  static const Color line = Color(0xFFE6E6E6);
  static const Color line2 = Color(0xFFF0F0F0);
  static const Color lineViolet = Color(0xFFDCD2F7); // 紫浅底卡描边（原型 daterow/紫浅底）
  static const Color dashedViolet = Color(0xFFC2B0EC); // 虚线占位描边（原型照片格 pcell-add）

  // —— Pop Art 红 + accents（原型体系）——
  static const Color popRed = Color(0xFFF0425A); // Pop Art 红（错位影/危险/点赞）
  static const Color coral = Color(0xFFF0425A); // 日常/点赞红心 → Pop Art 红
  static const Color gold = Color(0xFFF6A609); // 成长/快乐/分诊黄
  static const Color sky = Color(0xFF845EC9); // 问诊强调 → 统一紫
  static const Color grape = Color(0xFF9E83DA); // 群聊 → violet-400
  static const Color coralTint = Color(0xFFFDE7EB); // 红浅底
  static const Color healthEventText = Color(0xFFC4263C); // 健康事件深红文字（原型 hentry #C4263C）
  static const Color goldTint = Color(0xFFFEF3DE); // 黄浅底（badge tips）
  static const Color momenBadgeBg = Color(0xFFE7F8F0); // Momen 绿浅底（原型 b-happy）
  static const Color momenBadgeText = Color(0xFF0E7A4D); // Momen 绿文字
  static const Color tipsBadgeText = Color(0xFF8A5A00); // Tips 黄文字（原型 b-tips）
  static const Color skyTint = Color(0xFFF8F6FF); // → violet-50 浅底
  static const Color grapeTint = Color(0xFFF8F2FF);

  // —— 吉祥物 Momo（占位 IP，仅插画内部用，业务勿引）——
  static const Color momoBody = Color(0xFF7FD1AE);
  static const Color momoBodyLight = Color(0xFF93DABE);
  static const Color momoEarInner = Color(0xFFF4B8C0);
  static const Color momoEye = Color(0xFF2B4A3C);
  static const Color momoBlush = Color(0x8CF496A4); // rgba(244,150,164,.55)
  static const Color momoNose = Color(0xFFE78CA0);

  // —— Splash 启动屏品牌暗底（原型 #141019 深墨 + 紫辉光 + Pop Art 红错位）——
  static const Color splashInk = Color(0xFF141019); // 原型深墨底
  static const Color splashGlow = mint; // 中心辉光 = violet

  /// 品牌重塑紫（新 logo 实底；splash / 登录页头同色，原型 preview-new-splash-auth-0622）。
  static const Color brandViolet = Color(0xFF7D45F6);

  // —— Status ——
  static const Color danger = Color(0xFFE5604D);

  /// Google 品牌蓝（第三方品牌色，仅 Google 登录按钮 G 字标用）。
  static const Color brandGoogleBlue = Color(0xFF4285F4);

  // —— 兽医端独立主题 token（原型 H5 pages/vet-*.html 权威色值）——
  // 用户侧紫体系不动；vet 子树用这套薄荷绿。深顶栏/工具栏本步仅落 token，
  // 结构性铺设留 P1 逐屏（见 spec-vet-mint-theme.md）。
  static const Color vetPrimary = Color(0xFF5BCBBB); // vet 主薄荷（按钮/在线态/气泡）
  static const Color vetPrimaryDeep = Color(0xFF203D39); // 薄荷之上深绿字
  static const Color vetSurface = Color(0xFFEFF7F4); // 薄荷浅底（badge/卡）
  static const Color vetSurface2 = Color(0xFFF8FBFA); // 更浅薄荷底
  static const Color vetTopBar = Color(0xFF2B2540); // 深色顶栏（P1 备用）
  static const Color vetToolbar = Color(0xFF1A2B28); // 对话工具栏深底（P1 备用）
  static const Color vetOnAccent = Color(0xFFFFFFFF); // 薄荷之上前景白

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
  static const Color textDisclaimer = Color(0xFF9690A6);

  /// 区域强调色 = 紫主色。
  static const Color accentGrowth = mint;

  /// 问诊区强调 = 紫主色。
  static const Color accentConsult = mint;

  /// 在线态深绿文字（原型 #136B41，配 vetSurface 浅底）。
  static const Color onlineDeepGreen = Color(0xFF136B41);

  /// 流程第 3 步浅紫圆（原型 #B4A0E3）。
  static const Color violetSoft = Color(0xFFB4A0E3);

  // 分诊语义三色（原型值：绿/黄/红）
  static const Color triageGreen = Color(0xFF1F9E6A);
  static const Color triageYellow = Color(0xFFF6A609);
  static const Color triageRed = Color(0xFFF0425A);

  /// 黄色协议浅底（原型倒计时协议背景 #EEF4F7）。
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

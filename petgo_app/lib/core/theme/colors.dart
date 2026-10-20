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
  static const Color violet100 = Color(0xFFEFE6FF); // violet-100 场所入口条
  // V1.3.2 Story 3.3：Tailsonality 专属浅紫底（Diary 条目 / 角色小标），与里程碑 banner 配色区分。
  static const Color tailsonalityBannerBg = Color(0xFFF1EAFB);

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

  /// 生理期（月经）记录专用红（V1.1.2 · FR-84 · OQ-11B 定稿 2026-08-04）。
  ///
  /// 刻意选**深紫红**而非 [coral]：coral 已被「危险 / 点赞 / 疫苗 / 问诊」占满，
  /// 生理期图标若同色，用户在日历格子里分不出「今天是生理期记录」还是「今天有健康告警」。
  static const Color menstruationRed = Color(0xFFB03060);
  static const Color healthEventText = Color(0xFFC4263C); // 健康事件深红文字（原型 hentry #C4263C）
  static const Color goldTint = Color(0xFFFEF3DE); // 黄浅底（badge tips）
  // V1.4.0 gift Story 2.2：礼物页粉丝榜前三名排名圆（金用 [gold]；不用 🥇🥈🥉 emoji，UI 稿 E1）。
  static const Color rankSilver = Color(0xFFA7A7A7);
  static const Color rankBronze = Color(0xFFC98A5A);
  // V1.3.2 Story 1.6 · Diary 打卡条目专属底色（青蓝，与里程碑 S/M/L 金紫、身份证紫区分）
  static const Color checkinTint = Color(0xFFE6F5F7);
  static const Color checkinBorder = Color(0xFFB7DFE5);
  static const Color checkinIcon = Color(0xFF1B7F8C);

  /// 装饰标签胶囊的渐变终点（UI 稿 `.deco-badge`：135° 从 [gold] 到此色）。
  ///
  /// ⚠️ 与 [coral]（0xFFF0425A）**不是同一个红**，只差一点但不要混用：
  /// coral 是「危险 / 点赞 / 疫苗」那套语义色，这个只是装饰标签的渐变终点。
  /// 投影色用的才是 coral（规格写的是 rgba(240,66,90,.22)）。
  static const Color decoBadgeEnd = Color(0xFFF0596E);
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

  // —— 宠物年龄卡（V1.3.0 Story 5.2 设计稿实测值）——
  //
  // ⚠️ 这三个值**只服务年龄卡卡面**（一张会被发到 Stories 的图），不是 App 主题的一部分：
  // 卡面是设计单独出的一套配色（品牌紫之外还有洋红主数字 + 深紫墨底），
  // 与 App 内的 violet 体系刻意不同。业务页面请勿引用。
  /// 卡面底色（设计稿 `BACKGROUD.png` 实测 #F7F5FF）。
  static const Color ageCardBg = Color(0xFFF7F5FF);

  /// 主数字与「TAHUN MANUSIA」徽标底的洋红。
  static const Color ageCardMagenta = Color(0xFFD601A6);

  /// 底部渐变与趣味文案文字用的深紫墨（设计稿 `Card Info.png` 实测 #3D2E51）。
  static const Color ageCardInk = Color(0xFF3D2E51);

  // —— Status ——
  static const Color danger = Color(0xFFE5604D);

  /// Google 品牌四色（第三方品牌色，仅 Google 登录按钮 G 字标用；多色渐变让 G 五彩）。
  static const Color brandGoogleBlue = Color(0xFF4285F4);
  static const Color brandGoogleRed = Color(0xFFEA4335);
  static const Color brandGoogleYellow = Color(0xFFFBBC05);
  static const Color brandGoogleGreen = Color(0xFF34A853);
  static const Color infoBlue = Color(0xFF5B9BD5); // 次要信息蓝（健康分类·发情周期等）

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

  // ============================================================
  // V1.4.0 ① Tailrent Level 段位色（架构 §5.6，UI 稿 A1「白天到星夜」，OQ-12）
  // ============================================================
  // 🔴 段位色只在这里定义：角标 / 段位名 / 等级胶囊一律用段位色；逐级色只用于等级页进度条。
  // 刻意避开里程碑 S·M·L 三色（绿 #1F9E6A / 紫 #845EC9 / 金 #F6A609，OQ-6）。

  static const Color tierPemula = Color(0xFF66748C);
  static const Color tierSahabat = Color(0xFF0A7FA3);
  static const Color tierPenjaga = Color(0xFF2B4FBF);

  /// Bintang 为渐变（Start → End）；纯色场景取 Start。
  static const Color tierBintangStart = Color(0xFF4A2E9E);
  static const Color tierBintangEnd = Color(0xFFB5307F);

  // 段位浅色底（等级卡 / 胶囊外底）。
  static const Color tierPemulaTint = Color(0xFFEEF1F5);
  static const Color tierSahabatTint = Color(0xFFE1F2F8);
  static const Color tierPenjagaTint = Color(0xFFE7ECFB);
  static const Color tierBintangTint = Color(0xFFEFE9FB);

  // 头像框三档（V1.4.0 ① Story 3.2，UI 稿 `.avw.fr-b|fr-u|fr-p`）：框色随档位、不随佩戴者段位。
  // 深端复用段位色（Sahabat / Penjaga / Bintang）。
  static const Color frameBasicStart = Color(0xFF3FB3D6);
  static const Color frameUpgradedStart = Color(0xFF7D93EA);
  static const Color frameUpgradedEnd = Color(0xFF1A2F80);
  static const Color frameUpgradedOuter = Color(0xFFAEBCF0);
  static const Color frameAnimatedHighlight = Color(0xFFF09BC4);
  static const Color frameAnimatedGlow = Color(0x596E2E9E); // rgba(110,46,158,.35)

  // 选框（V1.4.0 ① Story 3.3）：切换中转圈底圈 `.spin`、庆祝页「已戴上」按钮底 `.pbtn.done`（字色复用 mint700）。
  static const Color growthFrameSpinTrack = Color(0xFFE4DCF5);
  static const Color growthFrameAppliedFill = Color(0xFFF1EEF7);

  /// 升级庆祝卡进度环底轨 `#ECEEF2`、EXP 胶囊底 `#F6F6F9`、等级页 / 庆祝权益卡行分隔 `#F3F3F6`（V1.4.0 L2 还原度）。
  static const Color growthRingTrack = Color(0xFFECEEF2);
  static const Color growthChipBg = Color(0xFFF6F6F9);
  static const Color growthDivider = Color(0xFFF3F3F6);

  /// 存量汇总弹窗小账单分隔线 `#E6E2EE`（行间虚线 / 合计上实线）。
  static const Color growthReceiptLine = Color(0xFFE6E2EE);

  /// 等级页（UI 稿 B1 / B2 / B6）：规则卡行分隔 `#F4F2F8`、今日进度小条底轨 `#EEEAF5`、未解锁段位卡底 `#FAFAFB`、
  /// 「Terbuka di LvN」灰胶囊底 `#F0F0F0`（同 line2）、加分绿字 `#0E7A4D`（同 Momen 绿）。
  static const Color growthRowDivider = Color(0xFFF4F2F8);
  static const Color growthQuotaTrack = Color(0xFFEEEAF5);
  static const Color growthLockedCard = Color(0xFFFAFAFB);
  static const Color growthGain = Color(0xFF0E7A4D);
  static const Color growthGainTint = Color(0xFFE7F8F0);

  // V1.4.0 多宠物名额页（UI 稿 D1 / D2）：名额卡淡紫渐变起点 `#F3EDFF`、可租格底 `#F4F3F8` + 锁 `#B8B3C6`、
  // 月租将到期字色 `#C4870B`；「Aktif」绿胶囊复用 [growthGainTint] / [growthGain]。
  static const Color petSlotCardTop = Color(0xFFF3EDFF);
  static const Color petSlotCellOff = Color(0xFFF4F3F8);
  static const Color petSlotCellLock = Color(0xFFB8B3C6);
  static const Color petSlotWarn = Color(0xFFC4870B);

  /// Diary 资料卡（UI 稿 `.pic`）：135° `#F3EDFF` → `#FBF8FF` 渐变；「Diary saja」小开关关闭态底轨 `#E3E0EC`。
  static const Color diaryCardTop = Color(0xFFF3EDFF);
  static const Color diaryCardEnd = Color(0xFFFBF8FF);
  static const Color diaryToggleOff = Color(0xFFE3E0EC);

  /// 角标阴影 `0 1px 3px rgba(20,16,40,.18)`。
  static const Color growthBadgeShadow = Color(0x2E141028);

  /// 逐级色（UI 稿 A1 色条）：段内浅 → 深、跨段换色相。Lv1–20 依次；Lv21+ 由 `growthLevelColor` 兜底。
  static const List<Color> growthLevelColors = [
    // Pemula Lv1–4
    Color(0xFFC5CCD8), Color(0xFF9CA4B3), Color(0xFF737D8D), Color(0xFF4A5568),
    // Sahabat Lv5–9
    Color(0xFF8ED3EA), Color(0xFF6CB5CD), Color(0xFF4A96B0), Color(0xFF287892), Color(0xFF065A75),
    // Penjaga Lv10–14
    Color(0xFFA8B8F2), Color(0xFF8597DA), Color(0xFF6276C2), Color(0xFF3F56A9), Color(0xFF1C3591),
    // Bintang Lv15–20
    Color(0xFFB9A6EE), Color(0xFFB38CD3), Color(0xFFAD72B8), Color(0xFFA6589E), Color(0xFFA03E83),
    Color(0xFF9A2468),
  ];
}

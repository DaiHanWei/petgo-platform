import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/utils/date_format.dart';
import '../../../../shared/widgets/app_image.dart';
import '../../domain/health_record_icons.dart';
import '../../domain/milestone_celebration_copy.dart';
import '../../domain/milestone_titles.dart';
import '../../domain/timeline_item.dart';
import '../../../../shared/widgets/content_tag_chip.dart';

/// Diary 时间线**五类条目的唯一渲染组件**（V1.1.2 Story 2.2 · FR-80/82 · NFR-7 · AD-13 Rule 4）。
///
/// 视觉基准：UI 稿 **A6 屏**（① 照片卡 / ② 照片卡+金徽章 / ③ 通栏 banner / ④a 粉底问诊条 +
/// ④b 蓝调矮胶囊 / ⑤ 紫色证件卡）。样式差异本身即传达条目类型，不靠文字说明。
///
/// ⚠️ **游客示例时间线与真实时间线必须复用本组件**，差异仅在数据来源（内置常量 vs 后端下发）。
/// 禁止为任一侧另写一套渲染 —— 两套样式会随时间漂移，游客被种草的「成长本」就跟他注册后
/// 真正拿到的不是一个东西，FR-80 的承诺落空。
///
/// ⚠️ **本组件不持有任何跳转行为**：点击语义由调用方注入（[onTap] / [onBadgeTap]）。
/// 游客态点 banner 是弹建档引导，真实态点 banner 是跳里程碑列表 —— 语义不同，写死在组件里
/// 就会迫使游客态另写一套 tile。
///
/// 布局：左侧 34px 日期槽（日 + 月缩写）+ 右侧类型化卡片（A6 的 `tl-row` 结构）。
class TimelineItemTile extends StatelessWidget {
  const TimelineItemTile({
    super.key,
    required this.item,
    this.petName,
    this.onTap,
    this.onBadgeTap,
    this.firstLabel,
    this.thumbIndex = 0,
  });

  final TimelineItem item;

  /// 宠物名（类 ③ 庆祝文案与类 ⑤ 证件卡标题需要）。为空时退化为不含名字的文案。
  final String? petName;

  /// 整条点击回调。为空 → 该条不可点（不吞手势）。
  final VoidCallback? onTap;

  /// 类 ② 金徽章角标的独立点击回调；为空则徽章不可点（整条 [onTap] 仍生效）。
  final VoidCallback? onBadgeTap;

  /// 非空则为该类首条（debut）：标题加 🌟 且转主色（沿用 V1.0.0 FR-37 表现）。
  final String? firstLabel;

  /// 无图时缩略底色 / emoji 轮换序号（沿用现状表现）。
  final int thumbIndex;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final tile = switch (item.resolvedType) {
      TimelineItemType.happyMoment => _photoCard(context, l10n, badge: null),
      TimelineItemType.happyMomentMilestone =>
        _photoCard(context, l10n, badge: _milestoneStamp(context)),
      TimelineItemType.milestoneBanner => _milestoneBanner(context, l10n),
      TimelineItemType.healthRecord =>
        item.isConsultRecord ? _consultRow(l10n) : _healthCapsule(context, l10n),
      TimelineItemType.idCardIssued => _idCard(l10n),
    };
    final tappable = onTap == null
        ? tile
        : GestureDetector(
            key: ValueKey('timelineTileTap_${item.resolvedType.wire}'),
            behavior: HitTestBehavior.opaque,
            onTap: onTap,
            child: tile,
          );
    return Padding(
      key: ValueKey('timelineTile_${item.resolvedType.wire}'),
      padding: const EdgeInsets.only(bottom: 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _dateGutter(context),
          const SizedBox(width: 9),
          Expanded(child: tappable),
        ],
      ),
    );
  }

  /// 日期槽（A6 `tl-date`）：34px 宽，日 16px 粗 + 月缩写 9px 弱化大写。
  Widget _dateGutter(BuildContext context) {
    final d = item.displayDate;
    return SizedBox(
      width: 34,
      child: Padding(
        padding: const EdgeInsets.only(top: 3),
        child: Column(
          children: [
            Text('${d.day}'.padLeft(2, '0'),
                style: const TextStyle(
                    fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.ink, height: 1)),
            const SizedBox(height: 2),
            Text(formatMonthAbbr(context, d).toUpperCase(),
                style: const TextStyle(
                    fontSize: 9, fontWeight: FontWeight.w600, color: AppColors.muted)),
          ],
        ),
      ),
    );
  }

  // ===== 类 ① / ② 照片卡（A6 `tl-happy`；② 多一枚金徽章角标） =====

  Widget _photoCard(BuildContext context, AppLocalizations l10n, {Widget? badge}) {
    final isFirst = firstLabel != null;
    final photoN = item.imageUrls.length;
    // 标题恒为内容配文（A1/A6：条目主文案就是用户写的那句话）；debut 只在前面加 🌟 并转主色，
    // **不用「首条」标签顶替配文** —— 顶替会让 A1 的 9 条示例第 8 条丢掉自己的内容。
    final caption =
        item.text != null && item.text!.isNotEmpty ? item.text! : l10n.timelineHappyMoment;
    final title = isFirst ? '🌟 $caption' : caption;
    final sub = isFirst
        ? '$firstLabel · ${formatDayMonthYear(context, item.displayDate)}'
        : (photoN > 0 ? l10n.timelineHappyMomentPhotos(photoN) : l10n.timelineHappyMoment);

    return Container(
      key: const ValueKey('timelineHappyCard'),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 9),
      decoration: BoxDecoration(
        color: AppColors.card,
        border: Border.all(color: AppColors.line2),
        borderRadius: BorderRadius.circular(14),
        boxShadow: const [
          BoxShadow(color: Color(0x0D2E2A45), offset: Offset(0, 2), blurRadius: 8),
        ],
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          _thumb(),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                        fontSize: 12.5,
                        fontWeight: isFirst ? FontWeight.w700 : FontWeight.w600,
                        color: isFirst ? AppColors.mint : AppColors.ink)),
                const SizedBox(height: 3),
                Text(sub, style: const TextStyle(fontSize: 10.5, color: AppColors.muted)),
                // V1.1.6 Story 5.2：装饰标签（三处展示位之一 —— 宠物日记页内）。
                // 这里的卡是横向小卡、没有大图可叠，所以走正文下方那种小胶囊形态。
                if (item.decorationTags.isNotEmpty) ...[
                  const SizedBox(height: 6),
                  Wrap(
                    spacing: 4,
                    runSpacing: 4,
                    children: [
                      for (final t in item.decorationTags) ContentTagChip.inline(tag: t, position: 'diary'),
                    ],
                  ),
                ],
                if (badge != null) ...[const SizedBox(height: 7), badge],
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// 缩略图 50×50（A6 `tl-happy-thumb`）。`asset:` 前缀走内置资源（游客示例零网络）。
  Widget _thumb() {
    if (item.imageUrls.isNotEmpty) {
      return ClipRRect(
        borderRadius: BorderRadius.circular(11),
        child: AppImage.widget(item.imageUrls.first,
            width: 50,
            height: 50,
            fit: BoxFit.cover,
            thumbWidth: 150,
            errorBuilder: (c, e, s) => _emojiThumb()),
      );
    }
    return _emojiThumb();
  }

  /// 无图时的 emoji 缩略占位（底色/emoji 按序轮换）。
  ///
  /// debut 条目**不再用 🌟 当缩略图**：🌟 已经前缀在标题里，缩略图再放一个就是同一张卡上两个
  /// 相同 emoji（与 banner 那处「图标 + 标题尾 emoji」同类问题）。debut 只保留浅绿底作区分。
  Widget _emojiThumb() {
    const bgs = [AppColors.skyTint, AppColors.goldTint, AppColors.momenBadgeBg];
    const emojis = ['🐾', '🧶', '☀️'];
    final isFirst = firstLabel != null;
    return Container(
      width: 50,
      height: 50,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: isFirst ? AppColors.momenBadgeBg : bgs[thumbIndex % bgs.length],
        borderRadius: BorderRadius.circular(11),
      ),
      child: Text(emojis[thumbIndex % emojis.length], style: const TextStyle(fontSize: 22)),
    );
  }

  /// 类 ② 金徽章角标（A6 `tl-stamp`）：🏆 + 里程碑名。**独立可点区域**，回调由调用方注入。
  Widget _milestoneStamp(BuildContext context) {
    final label = _milestoneName(context);
    final chip = Container(
      key: const ValueKey('timelineMilestoneStamp'),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: const Color(0xFFFFF7E8),
        border: Border.all(color: AppColors.gold, width: 1.3),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text('🏆 $label',
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(
              fontSize: 9, fontWeight: FontWeight.w800, color: Color(0xFF9A6800))),
    );
    if (onBadgeTap == null) return chip;
    return GestureDetector(
      key: const ValueKey('timelineMilestoneStampTap'),
      behavior: HitTestBehavior.opaque,
      onTap: onBadgeTap,
      child: chip,
    );
  }

  /// 里程碑短名：客户端按 locale 出（后端只给稳定 code），无 code 时退化为通用「里程碑」。
  String _milestoneName(BuildContext context) {
    final code = item.milestoneCode;
    if (code == null || code.isEmpty) return AppLocalizations.of(context).timelineMilestoneGeneric;
    return localizedMilestoneTitle(code, Localizations.localeOf(context));
  }

  // ===== 类 ③ 系统自动型里程碑通栏 banner（A6 `tl-ms`，按 S/M/L 配色） =====

  Widget _milestoneBanner(BuildContext context, AppLocalizations l10n) {
    final level = (item.milestoneLevel ?? 'S').toUpperCase();
    final (Color from, Color to, Color border, Color chip) = switch (level) {
      'L' => (const Color(0xFFEBE0FF), const Color(0xFFF6F0FF), const Color(0xFFD2BDF7),
          AppColors.mint),
      'M' => (const Color(0xFFEFEAFB), const Color(0xFFF7F3FF), const Color(0xFFDACBF3),
          AppColors.mint500),
      _ => (AppColors.goldTint, const Color(0xFFFFF7E8), const Color(0xFFF3DBA0), AppColors.gold),
    };
    // 标题/副文取里程碑庆祝文案（唯一文案源，含 {name} 插值）；无 code 时退化为等级说明。
    final code = item.milestoneCode;
    final copy = code == null || code.isEmpty
        ? (title: l10n.timelineMilestoneGeneric, body: '')
        : localizedMilestoneCelebration(code, Localizations.localeOf(context), petName ?? '');
    // 图标取标题尾部的 emoji（庆祝文案本就带一个，如「100 hari bersama Mochi! 🌟」），
    // 并把它从标题里剥掉 —— 否则同一条会出现两个 emoji（图标一个、标题尾一个），
    // 与 UX-DR14「一条文案最多一个 emoji」的观感相冲。标题不带 emoji 时按等级取默认图标。
    final match = _trailingEmoji.firstMatch(copy.title);
    final emoji = match != null
        ? match.group(0)!.trim()
        : switch (level) {
            'L' => '🎉',
            'M' => '🗓️',
            _ => '🐾',
          };
    final bannerTitle =
        match != null ? copy.title.substring(0, match.start).trimRight() : copy.title;
    return Container(
      key: const ValueKey('timelineMilestoneBanner'),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
      decoration: BoxDecoration(
        gradient: LinearGradient(
            begin: Alignment.topLeft, end: Alignment.bottomRight, colors: [from, to]),
        border: Border.all(color: border),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 40,
            height: 40,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: AppColors.card,
              borderRadius: BorderRadius.circular(11),
              boxShadow: const [
                BoxShadow(color: Color(0x14000000), offset: Offset(0, 2), blurRadius: 6),
              ],
            ),
            child: Text(emoji, style: const TextStyle(fontSize: 21)),
          ),
          const SizedBox(width: 11),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(bannerTitle,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.ink)),
                if (copy.body.isNotEmpty) ...[
                  const SizedBox(height: 2),
                  Text(copy.body,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 11, height: 1.4, color: AppColors.ink2)),
                ],
              ],
            ),
          ),
          const SizedBox(width: 8),
          Container(
            width: 20,
            height: 20,
            alignment: Alignment.center,
            decoration: BoxDecoration(color: chip, borderRadius: BorderRadius.circular(7)),
            child: Text(level,
                style: const TextStyle(
                    fontSize: 9, fontWeight: FontWeight.w800, color: AppColors.onAccent)),
          ),
        ],
      ),
    );
  }

  /// 文案尾部的 emoji（含变体选择符 / ZWJ 组合），用于把庆祝标题的尾 emoji 提为 banner 图标。
  static final RegExp _trailingEmoji = RegExp(
      r'[\u{1F000}-\u{1FAFF}\u{2190}-\u{21FF}\u{2300}-\u{23FF}\u{25A0}-\u{27BF}\u{2B00}-\u{2BFF}\u{FE0F}\u{200D}]+\s*$',
      unicode: true);

  // ===== 类 ④a 问诊 / AI 健康事件（A6 `tl-health`，粉底·沿用现状） =====

  Widget _consultRow(AppLocalizations l10n) {
    final isVet = item.isVetConsult;
    final sub = item.symptomSummary != null && item.symptomSummary!.isNotEmpty
        ? item.symptomSummary!
        : l10n.timelineConsultTapToView;
    return Container(
      key: const ValueKey('timelineConsultRow'),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: AppColors.coralTint,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(isVet ? '🩺' : '🏥', style: const TextStyle(fontSize: 17)),
          const SizedBox(width: 9),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(isVet ? l10n.timelineVetConsult : l10n.timelineAiConsult,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontSize: 12.5,
                        fontWeight: FontWeight.w700,
                        color: AppColors.healthEventText)),
                const SizedBox(height: 2),
                Text(sub,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                        fontSize: 10.5,
                        height: 1.4,
                        color: AppColors.healthEventText.withValues(alpha: 0.8))),
              ],
            ),
          ),
          if (item.aiLevel != null) ...[
            const SizedBox(width: 9),
            _levelBadge(l10n, item.aiLevel!),
          ],
        ],
      ),
    );
  }

  /// AI 风险等级徽章（沿用现状实心样式，与问诊结果页一致）。
  Widget _levelBadge(AppLocalizations l10n, String level) {
    final (String text, Color bg) = switch (level) {
      'RED' => ('🔴 ${l10n.triageBadgeRed}', AppColors.popRed),
      'YELLOW' => ('🟡 ${l10n.triageBadgeYellow}', AppColors.popRed),
      'GREEN' => ('🟢 ${l10n.triageBadgeGreen}', AppColors.triageGreen),
      _ => (level, AppColors.muted),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(7)),
      child: Text(text,
          style: const TextStyle(
              fontSize: 10, fontWeight: FontWeight.w700, color: AppColors.onAccent)),
    );
  }

  // ===== 类 ④b 结构化健康记录矮胶囊（A6 `tl-hcap`，本版本新增） =====

  Widget _healthCapsule(BuildContext context, AppLocalizations l10n) {
    final ic = healthRecordIconFor(item.healthRecordType);
    final title = item.text != null && item.text!.isNotEmpty
        ? item.text!
        : healthRecordLabel(l10n, item.healthRecordType);
    return Container(
      key: const ValueKey('timelineHealthCapsule'),
      padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 7),
      decoration: BoxDecoration(
        color: const Color(0xFFF1F6FF),
        border: Border.all(color: const Color(0xFFDBE7FA)),
        borderRadius: BorderRadius.circular(11),
      ),
      child: Row(
        children: [
          Icon(ic.icon, size: 14, color: ic.color),
          const SizedBox(width: 8),
          Expanded(
            child: Text(title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                    fontSize: 11.5, fontWeight: FontWeight.w600, color: Color(0xFF3A5580))),
          ),
          const SizedBox(width: 8),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
            decoration: BoxDecoration(
                color: const Color(0xFFE2ECFB), borderRadius: BorderRadius.circular(5)),
            child: Text(l10n.timelineHealthTag,
                style: const TextStyle(
                    fontSize: 8.5, fontWeight: FontWeight.w700, color: Color(0xFF5B7BA8))),
          ),
        ],
      ),
    );
  }

  // ===== 类 ⑤ 身份证解锁证件卡（A6 `tl-idcard`） =====

  Widget _idCard(AppLocalizations l10n) {
    final name = petName;
    final serial = item.idCardSerial;
    return ClipRRect(
      key: const ValueKey('timelineIdCard'),
      borderRadius: BorderRadius.circular(14),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 11),
        decoration: const BoxDecoration(
          gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [AppColors.mint, AppColors.mint600]),
        ),
        child: Stack(
          clipBehavior: Clip.none,
          children: [
            // 装饰圆（A6 `::after`）：右上角白色半透明圆，营造证件质感。
            Positioned(
              top: -39,
              right: -25,
              child: Container(
                width: 66,
                height: 66,
                decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.1), shape: BoxShape.circle),
              ),
            ),
            Row(
              children: [
                Container(
                  width: 38,
                  height: 38,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.2),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: const Icon(Icons.badge_outlined, size: 19, color: AppColors.onAccent),
                ),
                const SizedBox(width: 11),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                          name == null || name.isEmpty
                              ? l10n.timelineIdCardIssuedGeneric
                              : l10n.timelineIdCardIssued(name),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                              fontSize: 12.5,
                              fontWeight: FontWeight.w700,
                              color: AppColors.onAccent)),
                      const SizedBox(height: 2),
                      Text(l10n.timelineIdCardTapToView,
                          style: TextStyle(
                              fontSize: 10, color: Colors.white.withValues(alpha: 0.85))),
                    ],
                  ),
                ),
                // 编号可空（老档案未申请前后端无编号）→ 无编号时不渲染该位，不显示占位符。
                if (serial != null && serial.isNotEmpty) ...[
                  const SizedBox(width: 8),
                  Text(serial,
                      style: const TextStyle(
                          fontSize: 11,
                          fontWeight: FontWeight.w700,
                          letterSpacing: 0.6,
                          fontFamily: 'monospace',
                          color: AppColors.onAccent)),
                ],
              ],
            ),
          ],
        ),
      ),
    );
  }
}

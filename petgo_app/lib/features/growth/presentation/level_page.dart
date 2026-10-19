import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../../shared/widgets/initial_avatar.dart';
import '../../auth/domain/auth_state.dart';
import '../../notify/domain/notification_grouping.dart';
import '../data/growth_repository.dart';
import '../domain/growth_models.dart';
import 'exp_ledger_page.dart';
import 'frame_picker_row.dart';
import 'growth_labels.dart';
import 'growth_ledger_tile.dart';
import 'growth_progress_bar.dart';
import 'growth_tier_style.dart';

/// 等级页（V1.4.0 ① Story 1.5 · AC2 ~ AC4，UI 稿 B1 ~ B5）。路由 `/me/growth`（`/me` 前缀受控，游客重定向）。
///
/// 首屏：等级卡（头像 / Lv / 段位区间 / 进度条）→ 段位权益 → 获取规则（今日进度）→ 最近 3 条流水。
/// 头像框一排（UI 稿 C 组）本 story 不渲染，Story 3.3 接在等级卡与权益之间。
class LevelPage extends ConsumerWidget {
  const LevelPage({super.key});

  static const location = '/me/growth';

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(growthPageProvider);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(title: Text(l10n.growthLevelPageTitle)),
      body: async.when(
        // B4：个人页类页面沿用转圈，不做骨架。
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => EmptyState(
          key: const ValueKey('growthLevelError'),
          icon: Icons.cloud_off_outlined,
          title: l10n.growthLevelLoadFailed,
          message: l10n.feedErrorBody,
          actionLabel: l10n.notificationLoadRetry,
          onAction: () => ref.invalidate(growthPageProvider),
        ),
        data: (s) => s.participating
            ? _LevelContent(summary: s)
            // 防御性：不参与者正常路径进不来；同一失败态、不给重试。
            : EmptyState(
                key: const ValueKey('growthLevelNotParticipating'),
                icon: Icons.cloud_off_outlined,
                title: l10n.growthLevelLoadFailed,
              ),
      ),
    );
  }
}

class _LevelContent extends ConsumerStatefulWidget {
  const _LevelContent({required this.summary});

  final GrowthSummary summary;

  @override
  ConsumerState<_LevelContent> createState() => _LevelContentState();
}

class _LevelContentState extends ConsumerState<_LevelContent> {
  /// Story 3.3：切框成功后用 `PUT` 响应覆盖首屏的头像框四档（不必再拉整页）；首屏数据换了就以新数据为准。
  GrowthFrames? _frames;

  @override
  void didUpdateWidget(_LevelContent oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.summary, widget.summary)) _frames = null;
  }

  @override
  Widget build(BuildContext context) {
    final summary = widget.summary;
    final profile = ref.watch(authControllerProvider).profile;
    final frames = _frames ?? summary.frames;
    final nickname = profile?.nickname ?? profile?.displayName ?? '';
    final l10n = AppLocalizations.of(context);
    return ListView(
      padding: const EdgeInsets.fromLTRB(AppSpacing.screenEdge, 8, AppSpacing.screenEdge, 24),
      children: [
        _LevelCard(
          summary: summary,
          avatarUrl: profile?.avatarUrl,
          nickname: nickname,
          effectiveFrame: frames?.effective,
        ),
        // B1 顺序：等级卡之下、「Keuntungan level」之上。
        if (frames != null && frames.options.isNotEmpty) ...[
          _SectionTitle(l10n.growthFrameSectionTitle),
          FramePickerRow(
            frames: frames,
            avatarUrl: profile?.avatarUrl,
            nickname: nickname,
            onChanged: (f) => setState(() => _frames = f),
          ),
        ],
        _BenefitsSection(summary: summary),
        _RulesSection(summary: summary),
        _RecentLedgerSection(entries: summary.recentLedger),
      ],
    );
  }
}

/// 小节标题：12 / w700 / 大写 / `muted`（稿 `.sec2`：上 16、下 6）；右侧附注不大写。
class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.text, {this.trailing});

  final String text;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(2, 16, 2, 6),
        child: Row(
          children: [
            Expanded(
              child: Text(
                text.toUpperCase(),
                style: const TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 0.6,
                  color: AppColors.muted,
                ),
              ),
            ),
            ?trailing,
          ],
        ),
      );
}

String _tierRange(AppLocalizations l10n, GrowthTier? tier, int from, int? to) {
  final name = GrowthTierStyle.name(l10n, tier);
  return to == null
      ? l10n.growthLevelTierRangeOpen(name, '$from')
      : l10n.growthLevelTierRange(name, '$from', '$to');
}

/// 等级卡（B1 / B3）：段位浅色底。
class _LevelCard extends StatelessWidget {
  const _LevelCard({required this.summary, this.avatarUrl, required this.nickname, this.effectiveFrame});

  final GrowthSummary summary;
  final String? avatarUrl;
  final String nickname;

  /// 生效框代码（Story 3.3：切框后即时重绘）。
  final String? effectiveFrame;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final s = summary;
    final range = s.tierBenefits.where((t) => t.tier == s.tier).firstOrNull;
    final tierColor = GrowthTierStyle.color(s.tier);
    const metaStyle = TextStyle(fontSize: 12, fontFeatures: [FontFeature.tabularFigures()], color: AppColors.ink2);
    const metaBold = TextStyle(fontWeight: FontWeight.w700, color: AppColors.ink);
    final Widget progress;
    if (s.isMaxLevel || s.nextLevel == null) {
      progress = Text(
        l10n.growthLevelTotalExp(growthNumber(context, s.lifetimeTotal)),
        key: const ValueKey('growthLevelProgressText'),
        textAlign: TextAlign.center,
        style: metaStyle,
      );
    } else {
      // 稿 `.pmeta`：左「1.420 / 1.800 EXP」、右「Kurang 380 EXP ke Lv8」，关键数字加粗。
      final remain = (s.nextThreshold! - s.lifetimeTotal).clamp(0, s.nextThreshold!);
      final total = growthNumber(context, s.lifetimeTotal);
      final remainText = growthNumber(context, remain);
      progress = Row(
        key: const ValueKey('growthLevelProgressText'),
        children: [
          Expanded(
            child: Text.rich(
              _boldFirst(l10n.growthLevelProgress(total, growthNumber(context, s.nextThreshold!)), total, metaBold),
              style: metaStyle,
            ),
          ),
          const SizedBox(width: 8),
          Text.rich(
            _boldFirst(l10n.growthLevelToNext(remainText, '${s.nextLevel}'), remainText, metaBold),
            style: metaStyle,
          ),
        ],
      );
    }
    return Container(
      key: const ValueKey('growthLevelCard'),
      padding: const EdgeInsets.fromLTRB(16, 20, 16, 16),
      // 稿 `.lvc`：段位浅色 → 白（70%）纵向渐变，圆角 24，双层轻投影。
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [GrowthTierStyle.tint(s.tier), AppColors.surface],
          stops: const [0, 0.7],
        ),
        borderRadius: BorderRadius.circular(24),
        boxShadow: const [
          BoxShadow(color: Color(0x0D2B2A27), offset: Offset(0, 1), blurRadius: 2),
          BoxShadow(color: Color(0x0A2B2A27), offset: Offset(0, 2), blurRadius: 8),
        ],
      ),
      child: Column(
        children: [
          // Story 3.3（B1）：84 + 生效框 + 数字角标 26（3.2 共享装饰层）。
          InitialAvatar(
            key: const ValueKey('growthLevelAvatar'),
            avatarUrl: avatarUrl,
            nickname: nickname,
            radius: 42,
            growth: growthBadgeFor(s, effectiveFrame),
          ),
          const SizedBox(height: 12),
          Text(
            l10n.growthLevelShort('${s.level}'),
            key: const ValueKey('growthLevelBig'),
            style: TextStyle(
              fontSize: 34,
              height: 1.1,
              fontWeight: FontWeight.w800,
              letterSpacing: -0.7,
              fontFeatures: const [FontFeature.tabularFigures()],
              color: tierColor,
            ),
          ),
          const SizedBox(height: 6),
          // 稿 `.tpill`：白底胶囊 + 段位色点 + 段位色字。
          Container(
            key: const ValueKey('growthLevelTierPill'),
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
            decoration: const BoxDecoration(
              color: AppColors.surface,
              borderRadius: BorderRadius.all(Radius.circular(999)),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(
                  width: 8,
                  height: 8,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    gradient: LinearGradient(colors: GrowthTierStyle.gradient(s.tier)),
                  ),
                ),
                const SizedBox(width: 6),
                Text(
                  range == null
                      ? GrowthTierStyle.name(l10n, s.tier)
                      : _tierRange(l10n, s.tier, range.fromLevel, range.toLevel),
                  style: TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600, color: tierColor),
                ),
              ],
            ),
          ),
          const SizedBox(height: 18),
          GrowthProgressBar(summary: s),
          const SizedBox(height: 8),
          progress,
        ],
      ),
    );
  }

  /// 把 [full] 里第一次出现的 [part] 加粗（数字来自 arb 占位符，语序随语言变也能命中）。
  static TextSpan _boldFirst(String full, String part, TextStyle bold) {
    final i = full.indexOf(part);
    if (i < 0) return TextSpan(text: full);
    return TextSpan(children: [
      TextSpan(text: full.substring(0, i)),
      TextSpan(text: part, style: bold),
      TextSpan(text: full.substring(i + part.length)),
    ]);
  }
}

/// 段位权益（B1「Keuntungan level」）：不出现任何优惠券；未知权益代码忽略。
class _BenefitsSection extends StatelessWidget {
  const _BenefitsSection({required this.summary});

  final GrowthSummary summary;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final groups = <Widget>[];
    for (final t in summary.tierBenefits) {
      final items = t.benefits.map((b) => GrowthLabels.benefit(l10n, b)).whereType<String>().toList();
      if (items.isEmpty) continue;
      final unlocked = summary.level >= t.fromLevel;
      final range = t.toLevel == null
          ? l10n.growthLevelShort('${t.fromLevel}+')
          : l10n.growthLevelShort('${t.fromLevel}–${t.toLevel}');
      // 稿 `.trow2`：每段位一张描边卡 —— 段位色点 + 「段位名 LvA–B」+ 权益行；右上状态胶囊。未解锁浅灰底、文字置灰。
      groups.add(Container(
        key: ValueKey('growthBenefitGroup_${t.tier?.name ?? t.fromLevel}'),
        margin: const EdgeInsets.only(bottom: 8),
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: unlocked ? AppColors.surface : AppColors.growthLockedCard,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: AppColors.line2),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              width: 10,
              height: 10,
              margin: const EdgeInsets.only(top: 5),
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: LinearGradient(colors: GrowthTierStyle.gradient(t.tier)),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text.rich(
                    TextSpan(children: [
                      TextSpan(text: GrowthTierStyle.name(l10n, t.tier)),
                      TextSpan(
                        text: '  $range',
                        style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w500, color: AppColors.muted),
                      ),
                    ]),
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w600,
                      color: unlocked ? AppColors.ink : AppColors.muted,
                    ),
                  ),
                  for (final text in items)
                    Padding(
                      padding: const EdgeInsets.only(top: 3),
                      child: Row(
                        children: [
                          Icon(
                            unlocked ? Icons.check_circle : Icons.lock,
                            size: 14,
                            color: unlocked ? AppColors.triageGreen : AppColors.muted,
                          ),
                          const SizedBox(width: 6),
                          Expanded(
                            child: Text(
                              text,
                              style: TextStyle(fontSize: 12.5, color: unlocked ? AppColors.ink2 : AppColors.muted),
                            ),
                          ),
                        ],
                      ),
                    ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
              decoration: BoxDecoration(
                color: unlocked ? AppColors.growthGainTint : AppColors.line2,
                borderRadius: BorderRadius.circular(999),
              ),
              child: Text(
                unlocked ? l10n.growthBenefitUnlocked : l10n.growthLevelUnlocksAt('${t.fromLevel}'),
                style: TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w700,
                  color: unlocked ? AppColors.growthGain : AppColors.muted,
                ),
              ),
            ),
          ],
        ),
      ));
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [_SectionTitle(l10n.growthBenefitSectionTitle), ...groups],
    );
  }
}

/// 一行规则的展示数据（B2）。
class _RuleLine {
  const _RuleLine({
    required this.key,
    required this.icon,
    required this.name,
    required this.progress,
    required this.points,
    this.ratio,
    this.maxed = false,
  });

  final String key;
  final IconData icon;
  final String name;
  final String progress;
  final String points;

  /// 今日进度小条比例；null = 无上限规则，不画小条。
  final double? ratio;
  final bool maxed;
}

/// 「Cara dapat EXP」（B2）：主动规则各一行；被动三项合成一行（进度取 passiveGroup）；里程碑三项合成一行；
/// 送礼 / 消费各一行写「Tanpa batas harian」。后端未下发（停用）的规则不显示。
class _RulesSection extends StatelessWidget {
  const _RulesSection({required this.summary});

  final GrowthSummary summary;

  static const _active = ['DAILY_LOGIN', 'POST_PUBLISHED', 'COMMENT_POSTED', 'LIKE_GIVEN', 'PLACE_CHECKIN'];
  static const _passive = ['LIKE_RECEIVED', 'COMMENT_RECEIVED', 'COLLECT_RECEIVED'];
  static const _milestones = ['MILESTONE_S', 'MILESTONE_M', 'MILESTONE_L'];
  static const _perAmount = ['GIFT_SENT', 'GIFT_RECEIVED', 'SPEND_DIGITAL', 'SPEND_SHOP'];

  static List<_RuleLine> linesOf(BuildContext context, GrowthSummary s) {
    final l10n = AppLocalizations.of(context);
    final byCode = {for (final r in s.rules) r.code: r};
    final lines = <_RuleLine>[];
    for (final code in _active) {
      final r = byCode[code];
      if (r == null) continue;
      final cap = r.dailyCap;
      final count = r.todayCount ?? 0;
      lines.add(_RuleLine(
        key: code,
        icon: GrowthLabels.ruleIcon(code),
        name: GrowthLabels.ruleName(l10n, code),
        progress: r.maxedToday
            ? l10n.growthRuleMaxedToday
            : cap == null
                ? l10n.growthRuleNoDailyLimit
                : l10n.growthRuleTodayProgress('$count', '$cap'),
        points: l10n.growthRulePoints('${r.points}'),
        ratio: cap == null || cap <= 0 ? null : (count / cap).clamp(0.0, 1.0),
        maxed: r.maxedToday,
      ));
    }
    final passive = _passive.map((c) => byCode[c]).whereType<GrowthRuleProgress>().toList();
    if (passive.isNotEmpty) {
      final g = s.passiveGroup;
      final cap = g?.dailyCap;
      final earned = g?.todayEarned ?? 0;
      final maxed = g?.maxedToday ?? false;
      lines.add(_RuleLine(
        key: 'PASSIVE',
        icon: GrowthLabels.ruleIcon('LIKE_RECEIVED'),
        name: l10n.growthRulePassive,
        progress: maxed
            ? l10n.growthRuleMaxedToday
            : cap == null
                ? l10n.growthRuleNoDailyLimit
                : l10n.growthRuleTodayProgress('$earned', '$cap'),
        points: l10n.growthRulePoints(passive.map((r) => '${r.points}').join(' · +')),
        ratio: cap == null || cap <= 0 ? null : (earned / cap).clamp(0.0, 1.0),
        maxed: maxed,
      ));
    }
    final milestones = _milestones.map((c) => byCode[c]).whereType<GrowthRuleProgress>().toList();
    if (milestones.isNotEmpty) {
      lines.add(_RuleLine(
        key: 'MILESTONE',
        icon: GrowthLabels.ruleIcon('MILESTONE_S'),
        name: l10n.growthRuleMilestone,
        progress: l10n.growthRuleNoDailyLimit,
        points: l10n.growthRulePoints(milestones.map((r) => '${r.points}').join(' · +')),
      ));
    }
    for (final code in _perAmount) {
      final r = byCode[code];
      if (r == null) continue;
      final unit = r.unitAmount;
      lines.add(_RuleLine(
        key: code,
        icon: GrowthLabels.ruleIcon(code),
        name: GrowthLabels.ruleName(l10n, code),
        progress: l10n.growthRuleNoDailyLimit,
        points: unit == null
            ? l10n.growthRulePoints('${r.points}')
            : l10n.growthRulePointsPerAmount('${r.points}', growthNumber(context, unit)),
      ));
    }
    return lines;
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final lines = linesOf(context, summary);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _SectionTitle(
          l10n.growthRuleSectionTitle,
          trailing: Text(l10n.growthRuleResetHint,
              style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.muted)),
        ),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
          decoration: _cardDecoration,
          child: Column(children: [
            for (var i = 0; i < lines.length; i++) _RuleRow(line: lines[i], divider: i > 0),
          ]),
        ),
      ],
    );
  }
}

/// 稿 `.qcard`：白底 + 1px 浅描边 + 圆角 16。
const BoxDecoration _cardDecoration = BoxDecoration(
  color: AppColors.surface,
  borderRadius: BorderRadius.all(Radius.circular(16)),
  border: Border.fromBorderSide(BorderSide(color: AppColors.line2)),
);

/// 稿 `.qrow`：34 圆角方块图标 + 名称 + 今日进度（72×5 紫色小条 + 「Hari ini 1/3」/ 满额绿字）；右侧紫色分值 + 小号「EXP」。
class _RuleRow extends StatelessWidget {
  const _RuleRow({required this.line, this.divider = false});

  final _RuleLine line;
  final bool divider;

  @override
  Widget build(BuildContext context) {
    return Container(
      key: ValueKey('growthRule_${line.key}'),
      padding: const EdgeInsets.symmetric(vertical: 10),
      decoration: divider
          ? const BoxDecoration(border: Border(top: BorderSide(color: AppColors.growthRowDivider)))
          : null,
      child: Row(
        children: [
          Container(
            width: 34,
            height: 34,
            decoration: const BoxDecoration(
              color: AppColors.cream2,
              borderRadius: BorderRadius.all(Radius.circular(10)),
            ),
            child: Icon(line.icon, size: 18, color: AppColors.mint),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(line.name,
                    style: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w600, color: AppColors.ink)),
                const SizedBox(height: 5),
                Row(
                  children: [
                    if (line.ratio != null) ...[
                      SizedBox(
                        key: ValueKey('growthRuleBar_${line.key}'),
                        width: 72,
                        height: 5,
                        child: ClipRRect(
                          borderRadius: BorderRadius.circular(999),
                          child: LinearProgressIndicator(
                            value: line.ratio,
                            minHeight: 5,
                            backgroundColor: AppColors.growthQuotaTrack,
                            color: AppColors.mint,
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                    ],
                    Flexible(
                      child: Text(
                        line.progress,
                        style: TextStyle(
                          fontSize: 11,
                          fontWeight: line.maxed ? FontWeight.w700 : FontWeight.w400,
                          fontFeatures: const [FontFeature.tabularFigures()],
                          color: line.maxed ? AppColors.growthGain : AppColors.muted,
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
          const SizedBox(width: 10),
          _points(line.points),
        ],
      ),
    );
  }

  /// 「+5 EXP」→ 紫色 15 粗体数字 + 10.5 灰「EXP」；按金额计的「+5 EXP / Rp1.000」同理，「EXP / Rp1.000」走小号灰字。
  static Widget _points(String text) {
    const unit = ' EXP';
    const numStyle = TextStyle(
      fontSize: 15,
      fontWeight: FontWeight.w700,
      fontFeatures: [FontFeature.tabularFigures()],
      color: AppColors.mint,
    );
    final cut = text.indexOf(unit);
    if (cut < 0) {
      return Text(text, textAlign: TextAlign.right, style: numStyle);
    }
    return Text.rich(
      TextSpan(children: [
        TextSpan(text: text.substring(0, cut)),
        TextSpan(
          text: text.substring(cut),
          style: const TextStyle(fontSize: 10.5, fontWeight: FontWeight.w600, color: AppColors.ink2),
        ),
      ]),
      textAlign: TextAlign.right,
      style: numStyle,
    );
  }
}

/// 「Riwayat EXP」最近 3 条（B2 底部）：条目与明细页同一组件；无流水显示一行空态、不显示「Lihat semua」。
class _RecentLedgerSection extends StatelessWidget {
  const _RecentLedgerSection({required this.entries});

  final List<GrowthLedgerEntry> entries;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _SectionTitle(
          l10n.growthExpSectionTitle,
          trailing: entries.isEmpty
              ? null
              : GestureDetector(
                  key: const ValueKey('growthSeeAllLedger'),
                  onTap: () => context.push(ExpLedgerPage.location),
                  child: Text(
                    '${l10n.growthExpSeeAll} ›',
                    style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: AppColors.mint),
                  ),
                ),
        ),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 12),
          decoration: _cardDecoration,
          child: entries.isEmpty
              ? Padding(
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  child: Text(l10n.growthExpEmptyTitle,
                      key: const ValueKey('growthRecentEmpty'),
                      style: const TextStyle(fontSize: 13, color: AppColors.muted)),
                )
              : Column(
                  children: [
                    for (var i = 0; i < entries.length; i++)
                      GrowthLedgerTile(
                        entry: entries[i],
                        showClock: _isRecentDay(entries[i].createdAt),
                        divider: i < entries.length - 1,
                      ),
                  ],
                ),
        ),
      ],
    );
  }
}

/// 今天 / 昨天（WIB 自然日）显示 `HH.mm`，更早显示 `d MMM`（与明细页同一判定，复用 `localDayDiff`）。
bool _isRecentDay(DateTime? createdAt) =>
    createdAt == null || localDayDiff(createdAt, DateTime.now(), toLocal: toWib) <= 1;

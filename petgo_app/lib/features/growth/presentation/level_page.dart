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
      padding: const EdgeInsets.all(AppSpacing.screenEdge),
      children: [
        _LevelCard(
          summary: summary,
          avatarUrl: profile?.avatarUrl,
          nickname: nickname,
          effectiveFrame: frames?.effective,
        ),
        // B1 顺序：等级卡之下、「Keuntungan level」之上。
        if (frames != null && frames.options.isNotEmpty) ...[
          const SizedBox(height: AppSpacing.lg),
          _SectionTitle(l10n.growthFrameSectionTitle),
          FramePickerRow(
            frames: frames,
            avatarUrl: profile?.avatarUrl,
            nickname: nickname,
            onChanged: (f) => setState(() => _frames = f),
          ),
        ],
        const SizedBox(height: AppSpacing.lg),
        _BenefitsSection(summary: summary),
        const SizedBox(height: AppSpacing.lg),
        _RulesSection(summary: summary),
        const SizedBox(height: AppSpacing.lg),
        _RecentLedgerSection(entries: summary.recentLedger),
      ],
    );
  }
}

/// 小节标题：12 / w700 / 大写 / `muted`（与「我的」页同款）。
class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.text, {this.trailing});

  final String text;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: AppSpacing.sm),
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
    final String progress;
    if (s.isMaxLevel || s.nextLevel == null) {
      progress = l10n.growthLevelTotalExp(growthNumber(context, s.lifetimeTotal));
    } else {
      final remain = (s.nextThreshold! - s.lifetimeTotal).clamp(0, s.nextThreshold!);
      progress = '${l10n.growthLevelProgress(growthNumber(context, s.lifetimeTotal), growthNumber(context, s.nextThreshold!))}'
          ' · ${l10n.growthLevelToNext(growthNumber(context, remain), '${s.nextLevel}')}';
    }
    return Container(
      key: const ValueKey('growthLevelCard'),
      padding: const EdgeInsets.fromLTRB(20, 24, 20, 20),
      decoration: BoxDecoration(color: GrowthTierStyle.tint(s.tier), borderRadius: BorderRadius.circular(20)),
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
              fontSize: 32,
              fontWeight: FontWeight.w800,
              fontFeatures: const [FontFeature.tabularFigures()],
              color: tierColor,
            ),
          ),
          const SizedBox(height: 4),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
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
                style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.ink2),
              ),
            ],
          ),
          const SizedBox(height: 16),
          GrowthProgressBar(summary: s),
          const SizedBox(height: 8),
          Text(
            progress,
            key: const ValueKey('growthLevelProgressText'),
            textAlign: TextAlign.center,
            style: const TextStyle(
              fontSize: 12,
              fontFeatures: [FontFeature.tabularFigures()],
              color: AppColors.ink2,
            ),
          ),
        ],
      ),
    );
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
      groups.add(Container(
        key: ValueKey('growthBenefitGroup_${t.tier?.name ?? t.fromLevel}'),
        margin: const EdgeInsets.only(bottom: AppSpacing.sm),
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(color: AppColors.surface, borderRadius: BorderRadius.circular(14)),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              _tierRange(l10n, t.tier, t.fromLevel, t.toLevel),
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w700,
                color: unlocked ? GrowthTierStyle.color(t.tier) : AppColors.muted,
              ),
            ),
            const SizedBox(height: 8),
            for (final text in items)
              Padding(
                padding: const EdgeInsets.only(bottom: 6),
                child: Row(
                  children: [
                    Icon(
                      unlocked ? Icons.check_circle : Icons.lock_outline,
                      size: 16,
                      color: unlocked ? AppColors.triageGreen : AppColors.muted,
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        text,
                        style: TextStyle(fontSize: 13, color: unlocked ? AppColors.ink : AppColors.muted),
                      ),
                    ),
                  ],
                ),
              ),
            Text(
              unlocked ? l10n.growthBenefitUnlocked : l10n.growthLevelUnlocksAt('${t.fromLevel}'),
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: unlocked ? AppColors.triageGreen : AppColors.muted,
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
          trailing: Text(l10n.growthRuleResetHint, style: const TextStyle(fontSize: 11, color: AppColors.muted)),
        ),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 4),
          decoration: BoxDecoration(color: AppColors.surface, borderRadius: BorderRadius.circular(14)),
          child: Column(children: [for (final line in lines) _RuleRow(line: line)]),
        ),
      ],
    );
  }
}

class _RuleRow extends StatelessWidget {
  const _RuleRow({required this.line});

  final _RuleLine line;

  @override
  Widget build(BuildContext context) {
    return Padding(
      key: ValueKey('growthRule_${line.key}'),
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Row(
        children: [
          Container(
            width: 36,
            height: 36,
            decoration: const BoxDecoration(color: AppColors.mintTint, shape: BoxShape.circle),
            child: Icon(line.icon, size: 18, color: AppColors.mint),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(line.name,
                    style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.ink)),
                const SizedBox(height: 2),
                Text(
                  line.progress,
                  style: TextStyle(fontSize: 12, color: line.maxed ? AppColors.triageGreen : AppColors.muted),
                ),
                if (line.ratio != null) ...[
                  const SizedBox(height: 4),
                  ClipRRect(
                    borderRadius: BorderRadius.circular(2),
                    child: LinearProgressIndicator(
                      value: line.ratio,
                      minHeight: 4,
                      backgroundColor: AppColors.line2,
                      color: line.maxed ? AppColors.triageGreen : AppColors.mint,
                    ),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(width: 12),
          Text(
            line.points,
            style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.ink),
          ),
        ],
      ),
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
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 4),
          decoration: BoxDecoration(color: AppColors.surface, borderRadius: BorderRadius.circular(14)),
          child: entries.isEmpty
              ? Padding(
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  child: Text(l10n.growthExpEmptyTitle,
                      key: const ValueKey('growthRecentEmpty'),
                      style: const TextStyle(fontSize: 13, color: AppColors.muted)),
                )
              : Column(
                  children: [
                    for (final e in entries)
                      GrowthLedgerTile(entry: e, showClock: _isRecentDay(e.createdAt)),
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

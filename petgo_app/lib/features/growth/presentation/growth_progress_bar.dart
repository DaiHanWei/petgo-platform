import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/dashed_rect.dart';
import '../domain/growth_level_color.dart';
import '../domain/growth_models.dart';
import 'growth_tier_style.dart';

/// 等级页进度条（Story 1.5 · AC2.3 / AC2.4，UI 稿 B1 / B3）：轨道 12 高圆角、淡染下一级色；填充从当前级色渐变到下一级色，
/// 只露出走过的一段；两端「Lv{n}」实心胶囊 /「Lv{n+1}」虚线胶囊。满级满格、右端「Level maksimal」。
class GrowthProgressBar extends StatelessWidget {
  const GrowthProgressBar({super.key, required this.summary});

  final GrowthSummary summary;

  /// 进度比例 = (累计 − 当前门槛) / (下一级门槛 − 当前门槛)，截断到 [0, 1]；满级 1。
  static double ratioOf(GrowthSummary s) {
    final next = s.nextThreshold;
    if (next == null) return 1;
    final span = next - s.currentThreshold;
    if (span <= 0) return 1;
    return ((s.lifetimeTotal - s.currentThreshold) / span).clamp(0.0, 1.0);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final s = summary;
    final maxed = s.isMaxLevel || s.nextLevel == null;
    final from = growthLevelColor(s.level, s.levels);
    final to = maxed ? from : growthLevelColor(s.nextLevel!, s.levels);
    final ratio = maxed ? 1.0 : ratioOf(s);
    // 两端胶囊用段位色（逐级色只用于进度条本身，§5.6；浅色逐级色上的白字对比度不够）。
    final nextTier = maxed ? s.tier : _tierOfLevel(s.nextLevel!, s);
    return Row(
      children: [
        _Pill(text: l10n.growthLevelShort('${s.level}'), color: GrowthTierStyle.color(s.tier), filled: true),
        const SizedBox(width: 8),
        Expanded(
          child: LayoutBuilder(
            builder: (context, c) {
              final width = c.maxWidth;
              return ClipRRect(
                key: const ValueKey('growthProgressTrack'),
                borderRadius: BorderRadius.circular(6),
                child: Container(
                  height: 12,
                  color: to.withValues(alpha: 0.16),
                  alignment: Alignment.centerLeft,
                  child: SizedBox(
                    key: const ValueKey('growthProgressFill'),
                    width: width * ratio,
                    height: 12,
                    child: ClipRect(
                      child: OverflowBox(
                        alignment: Alignment.centerLeft,
                        minWidth: width,
                        maxWidth: width,
                        // 渐变按整条轨道铺开，填充只露出走过的一段。
                        child: DecoratedBox(
                          decoration: BoxDecoration(gradient: LinearGradient(colors: [from, to])),
                          child: const SizedBox.expand(),
                        ),
                      ),
                    ),
                  ),
                ),
              );
            },
          ),
        ),
        const SizedBox(width: 8),
        if (maxed)
          Text(
            l10n.growthLevelMax,
            key: const ValueKey('growthLevelMax'),
            style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: AppColors.ink2),
          )
        else
          _Pill(text: l10n.growthLevelShort('${s.nextLevel}'), color: GrowthTierStyle.color(nextTier), filled: false),
      ],
    );
  }

  static GrowthTier? _tierOfLevel(int level, GrowthSummary s) {
    for (final d in s.levels) {
      if (d.level == level) return d.tier ?? s.tier;
    }
    return s.tier;
  }
}

class _Pill extends StatelessWidget {
  const _Pill({required this.text, required this.color, required this.filled});

  final String text;
  final Color color;
  final bool filled;

  @override
  Widget build(BuildContext context) {
    final label = Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w700,
          fontFeatures: const [FontFeature.tabularFigures()],
          color: filled ? AppColors.onAccent : color,
        ),
      ),
    );
    if (filled) {
      return DecoratedBox(
        decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(999)),
        child: label,
      );
    }
    return CustomPaint(
      painter: DashedRRectPainter(color: color, radius: 999, dash: 3, gap: 2, strokeWidth: 1.2),
      child: label,
    );
  }
}

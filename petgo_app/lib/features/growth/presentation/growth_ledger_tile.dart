import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../domain/growth_models.dart';
import 'growth_labels.dart';
import 'growth_tier_style.dart';

/// WIB = UTC+7（印尼西部时间，无夏令时）。明细按 WIB 自然日分组 / 显示时间，不用手机本地时区。
DateTime toWib(DateTime d) => d.toUtc().add(const Duration(hours: 7));

/// EXP 明细条目（Story 1.5 · AC5.3）：等级页「Riwayat EXP」预览与明细页**同一个组件**。
/// 左 34 圆角方块图标（入账浅紫底、扣回浅红底）、名称 + 时间、右侧增减值（加绿减红）；行底 1px 分隔（稿 `.hrow`）。
class GrowthLedgerTile extends StatelessWidget {
  const GrowthLedgerTile({super.key, required this.entry, this.showClock = true, this.divider = true});

  /// 行底分隔线（卡片里最后一行不画）。
  final bool divider;

  final GrowthLedgerEntry entry;

  /// true：时间显示 WIB `HH.mm`（今天 / 昨天组）；false：显示 `d MMM`（更早）。
  final bool showClock;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final negative = entry.delta < 0;
    final createdAt = entry.createdAt;
    final locale = Localizations.localeOf(context).toLanguageTag();
    final time = createdAt == null
        ? ''
        : (showClock ? DateFormat('HH.mm', locale) : DateFormat('d MMM', locale)).format(toWib(createdAt));
    final amount = growthNumber(context, entry.delta.abs());
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 11),
      decoration: divider
          ? const BoxDecoration(border: Border(bottom: BorderSide(color: AppColors.line2)))
          : null,
      child: Row(
        children: [
          Container(
            width: 34,
            height: 34,
            decoration: BoxDecoration(
              color: negative ? AppColors.coralTint : AppColors.cream2,
              borderRadius: BorderRadius.circular(10),
            ),
            child: Icon(GrowthLabels.ledgerIcon(entry), size: 18, color: negative ? AppColors.danger : AppColors.mint),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  GrowthLabels.ledgerTitle(l10n, entry),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w600, color: AppColors.ink),
                ),
                if (time.isNotEmpty)
                  Text(time, style: const TextStyle(fontSize: 11.5, color: AppColors.muted)),
              ],
            ),
          ),
          Text(
            negative ? l10n.growthExpDeltaMinus(amount) : l10n.growthRulePoints(amount),
            style: TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w700,
              fontFeatures: const [FontFeature.tabularFigures()],
              color: negative ? AppColors.danger : AppColors.growthGain,
            ),
          ),
        ],
      ),
    );
  }
}

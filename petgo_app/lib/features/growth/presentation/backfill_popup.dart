import 'package:flutter/material.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/initial_avatar.dart';
import '../../ops/presentation/ops_popup_dialog.dart';
import '../domain/growth_popups.dart';
import 'growth_tier_style.dart';
import 'level_up_celebration.dart' show GrowthPopupOutcome;

/// 存量汇总弹窗（V1.4.0 ① Story 2.3 · AC5，UI 稿 E1）：复用运营弹窗卡片外壳 [OpsPopupCardShell]。
/// 头部段位浅色底 + 头像 + 「Lv{n}」 + 段位名；文案区标题 / 正文 / 三行小账单（如实显示，含 0 天，P-6）。
class BackfillPopup extends StatefulWidget {
  const BackfillPopup({super.key, required this.backfill, this.avatarUrl, this.nickname = ''});

  final GrowthBackfill backfill;
  final String? avatarUrl;
  final String nickname;

  @override
  State<BackfillPopup> createState() => _BackfillPopupState();
}

class _BackfillPopupState extends State<BackfillPopup> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      Analytics.capture('growth_backfill_popup_shown', {'level': widget.backfill.level});
    });
  }

  void _pop(GrowthPopupOutcome outcome) => Navigator.of(context).pop(outcome);

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final b = widget.backfill;
    final tier = b.tier;
    return OpsPopupCardShell(
      key: const ValueKey('growthBackfillPopup'),
      header: Stack(
        children: [
          Container(
            width: double.infinity,
            color: GrowthTierStyle.tint(tier),
            padding: const EdgeInsets.fromLTRB(16, 24, 16, 18),
            child: Column(
              children: [
                InitialAvatar(avatarUrl: widget.avatarUrl, nickname: widget.nickname, radius: 34),
                const SizedBox(height: 10),
                Text(
                  l10n.growthLevelShort('${b.level}'),
                  key: const ValueKey('growthBackfillLevel'),
                  style: TextStyle(
                    fontSize: 28,
                    fontWeight: FontWeight.w800,
                    fontFeatures: const [FontFeature.tabularFigures()],
                    color: GrowthTierStyle.color(tier),
                  ),
                ),
                const SizedBox(height: 6),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
                  decoration: BoxDecoration(
                    gradient: LinearGradient(colors: GrowthTierStyle.gradient(tier)),
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Text(GrowthTierStyle.name(l10n, tier),
                      style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: AppColors.onAccent)),
                ),
              ],
            ),
          ),
          Positioned(
            top: 4,
            right: 4,
            child: OpsPopupCloseButton(
              tapKey: const ValueKey('growthBackfillClose'),
              onLight: true,
              onTap: () => _pop(GrowthPopupOutcome.close),
            ),
          ),
        ],
      ),
      text: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(l10n.growthBackfillTitle,
                style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.ink)),
            const SizedBox(height: 6),
            Text(l10n.growthBackfillBody, style: const TextStyle(fontSize: 13, color: AppColors.ink2, height: 1.5)),
            const SizedBox(height: 10),
            _BillRow(
              key: const ValueKey('growthBackfillDays'),
              label: l10n.growthBackfillActiveDays(b.activeDays),
              value: l10n.growthExpAmount(growthNumber(context, b.activeDays * 3)),
            ),
            _BillRow(
              key: const ValueKey('growthBackfillMilestones'),
              label: l10n.growthBackfillMilestones(b.milestoneCount),
              value: l10n.growthExpAmount(growthNumber(context, b.milestoneExp)),
            ),
            const Divider(height: 16, color: AppColors.line2),
            _BillRow(
              key: const ValueKey('growthBackfillTotal'),
              label: l10n.growthBackfillTotal,
              value: l10n.growthExpAmount(growthNumber(context, b.total)),
              strong: true,
            ),
          ],
        ),
      ),
      button: SizedBox(
        width: double.infinity,
        height: OpsPopupDialog.buttonHeight,
        child: Material(
          color: AppColors.mint,
          borderRadius: const BorderRadius.all(Radius.circular(12)),
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            key: const ValueKey('growthBackfillButton'),
            onTap: () => _pop(GrowthPopupOutcome.viewLevel),
            child: Center(
              child: Text(l10n.growthViewMyLevel,
                  style: const TextStyle(fontSize: 14.5, fontWeight: FontWeight.w700, color: AppColors.onAccent)),
            ),
          ),
        ),
      ),
    );
  }
}

class _BillRow extends StatelessWidget {
  const _BillRow({super.key, required this.label, required this.value, this.strong = false});

  final String label;
  final String value;
  final bool strong;

  @override
  Widget build(BuildContext context) {
    final style = TextStyle(
      fontSize: 13,
      fontWeight: strong ? FontWeight.w700 : FontWeight.w500,
      color: strong ? AppColors.ink : AppColors.ink2,
      fontFeatures: const [FontFeature.tabularFigures()],
    );
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        children: [
          Expanded(child: Text(label, style: style)),
          Text(value, textAlign: TextAlign.right, style: style),
        ],
      ),
    );
  }
}

/// 弹出（同 `showOpsPopupDialog`：根 Navigator、点遮罩不关、返回键 → null）。
Future<GrowthPopupOutcome?> showBackfillPopup(BuildContext rootContext, BackfillPopup popup) {
  return showDialog<GrowthPopupOutcome>(
    context: rootContext,
    useRootNavigator: true,
    barrierDismissible: false,
    barrierColor: OpsPopupDialog.barrierColor,
    builder: (_) => popup,
  );
}

import 'package:flutter/foundation.dart';
import 'dart:async';

import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../l10n/app_localizations.dart';
import '../../domain/milestone.dart';
import '../../domain/milestone_titles.dart';

/// 里程碑三级庆祝动效（Story 8.5 · FR-42）。完成后按级触发，mint 风格、无第三方动画包（手绘 implicit 动画）：
/// - **S（小）**：半屏庆祝弹层，1-2 秒自动消失，含徽章展示。
/// - **M（中）**：全屏动效约 3 秒 + 徽章解锁（锁→奖杯 burst）。
/// - **L（大）**：Duolingo 开宝箱式交互（宝箱→爆发→奖杯），结束自动衔接分享卡（8.6 通过 [onShare] 注入）。
///
/// 云端 headless 验不了视觉/计时观感（L2 待本地）；本组件保证构建/计时/自动消失逻辑 L0 可测。
Future<void> showMilestoneCelebration(
  BuildContext context,
  MilestoneItem item, {
  FutureOr<void> Function()? onShare,
}) {
  return showGeneralDialog<void>(
    context: context,
    barrierDismissible: item.level == MilestoneLevel.s,
    barrierLabel: 'milestone-celebration',
    barrierColor: Colors.black.withValues(alpha: item.level == MilestoneLevel.s ? 0.25 : 0.55),
    transitionDuration: const Duration(milliseconds: 240),
    pageBuilder: (_, _, _) => _MilestoneCelebrationView(item: item, onShare: onShare),
  );
}

class _MilestoneCelebrationView extends StatefulWidget {
  const _MilestoneCelebrationView({required this.item, this.onShare});

  final MilestoneItem item;
  final FutureOr<void> Function()? onShare;

  @override
  State<_MilestoneCelebrationView> createState() => _MilestoneCelebrationViewState();
}

class _MilestoneCelebrationViewState extends State<_MilestoneCelebrationView>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;
  Timer? _autoClose;
  bool _opened = false; // L 级宝箱是否已开

  /// 各级停留时长（FR-42：S/M ~3s / L 交互后停留）。
  /// S 改为底部抽屉式三段内容（名字/级别+日期/贺词），停留延长到 3.5s 供阅读。
  Duration get _holdDuration => switch (widget.item.level) {
        MilestoneLevel.s => const Duration(milliseconds: 3500),
        MilestoneLevel.m => const Duration(milliseconds: 3000),
        MilestoneLevel.l => const Duration(milliseconds: 4000),
      };

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 700),
    )..forward();
    // S/M 自动消失；L 需用户开宝箱后再自动收尾（交互式）。
    // Debug 截图钩子（仅 debug + flag）：跳过自动消失，让 S/M 庆祝层停住可截 milestone-unlock。
    final devHold = kDebugMode && const bool.fromEnvironment('DEV_HOLD_CELEBRATION');
    if (widget.item.level != MilestoneLevel.l && !devHold) {
      _autoClose = Timer(_holdDuration, _close);
    }
  }

  @override
  void dispose() {
    _autoClose?.cancel();
    _controller.dispose();
    super.dispose();
  }

  void _close() {
    if (mounted && Navigator.of(context).canPop()) {
      Navigator.of(context).pop();
    }
  }

  Future<void> _openChest() async {
    if (_opened) return;
    setState(() => _opened = true);
    _controller
      ..reset()
      ..forward();
    // 开箱后停留，衔接分享卡（8.6），再自动收尾。
    _autoClose = Timer(_holdDuration, () async {
      if (widget.onShare != null) {
        await widget.onShare!.call();
      }
      _close();
    });
  }

  @override
  Widget build(BuildContext context) {
    return switch (widget.item.level) {
      MilestoneLevel.s => _half(context),
      MilestoneLevel.m => _full(context, big: false),
      MilestoneLevel.l => _chest(context),
    };
  }

  // ---- S：底部抽屉式庆祝弹层（复用 more-sheet/confirm-sheet 样式：把手 + 顶圆角 + surface 底）----
  Widget _half(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final locale = Localizations.localeOf(context);
    final title = localizedMilestoneTitle(widget.item.code, locale);
    // 「具体内容」：里程碑级别 +（若有）达成日期。无逐条描述字段，用真实元信息。
    final levelLabel = switch (widget.item.level) {
      MilestoneLevel.s => l10n.milestoneLevelS,
      MilestoneLevel.m => l10n.milestoneLevelM,
      MilestoneLevel.l => l10n.milestoneLevelL,
    };
    final completedLine =
        widget.item.completedAt == null ? null : l10n.milestoneCompletedOn(_fmtDate(widget.item.completedAt!));
    return Align(
      alignment: Alignment.bottomCenter,
      child: SlideTransition(
        position: Tween<Offset>(begin: const Offset(0, 1), end: Offset.zero)
            .animate(CurvedAnimation(parent: _controller, curve: Curves.easeOutCubic)),
        child: Container(
          key: const ValueKey('milestoneCelebrationS'),
          width: double.infinity,
          decoration: const BoxDecoration(
            color: AppColors.surface,
            borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
            boxShadow: [BoxShadow(color: Colors.black26, blurRadius: 24, offset: Offset(0, -4))],
          ),
          child: SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(AppSpacing.lg, 12, AppSpacing.lg, AppSpacing.lg),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  // 顶部把手（与 detail 更多抽屉一致）。
                  Container(
                    width: 36,
                    height: 4,
                    margin: const EdgeInsets.only(bottom: AppSpacing.lg),
                    decoration:
                        BoxDecoration(color: AppColors.line, borderRadius: BorderRadius.circular(9999)),
                  ),
                  _badge(64),
                  const SizedBox(height: AppSpacing.md),
                  // 解锁标头。
                  Text(l10n.milestoneCelebrateUnlocked,
                      style: const TextStyle(
                          fontWeight: FontWeight.w800, fontSize: 13, color: AppColors.mint700)),
                  const SizedBox(height: AppSpacing.xs),
                  // ① 里程碑名字。
                  Text(title,
                      textAlign: TextAlign.center,
                      style: const TextStyle(
                          fontWeight: FontWeight.w800, fontSize: 19, color: AppColors.ink)),
                  const SizedBox(height: 6),
                  // ② 具体内容：级别（+ 达成日期）。
                  Text(
                    completedLine == null ? levelLabel : '$levelLabel · $completedLine',
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 12.5, color: AppColors.muted),
                  ),
                  const SizedBox(height: AppSpacing.md),
                  // ③ 贺词。
                  Text(l10n.milestoneCelebrateCongrats,
                      textAlign: TextAlign.center,
                      style: const TextStyle(fontSize: 14, height: 1.4, color: AppColors.ink2)),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  /// 达成日期格式（与里程碑列表页一致：yyyy-MM-dd，本地时区）。
  static String _fmtDate(DateTime d) {
    final local = d.toLocal();
    return '${local.year}-${local.month.toString().padLeft(2, '0')}'
        '-${local.day.toString().padLeft(2, '0')}';
  }

  // ---- M：全屏动效 + 徽章解锁 ----
  Widget _full(BuildContext context, {required bool big}) {
    final l10n = AppLocalizations.of(context);
    return Center(
      key: const ValueKey('milestoneCelebrationM'),
      child: FadeTransition(
        opacity: _controller,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ScaleTransition(
              scale: CurvedAnimation(parent: _controller, curve: Curves.elasticOut),
              child: _badge(120),
            ),
            const SizedBox(height: AppSpacing.lg),
            Text(l10n.milestoneCelebrateUnlocked,
                style: const TextStyle(
                    color: Colors.white, fontWeight: FontWeight.w800, fontSize: 18)),
            const SizedBox(height: 6),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: AppSpacing.xl),
              child: Text(localizedMilestoneTitle(widget.item.code, Localizations.localeOf(context)),
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w700)),
            ),
          ],
        ),
      ),
    );
  }

  // ---- L：Duolingo 开宝箱 ----
  Widget _chest(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Center(
      key: const ValueKey('milestoneCelebrationL'),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (!_opened)
            GestureDetector(
              key: const ValueKey('milestoneChestTap'),
              onTap: _openChest,
              child: ScaleTransition(
                scale: Tween<double>(begin: 0.96, end: 1.04).animate(
                    CurvedAnimation(parent: _controller, curve: Curves.easeInOut)),
                child: const Icon(Icons.card_giftcard_rounded, size: 140, color: AppColors.gold),
              ),
            )
          else ...[
            ScaleTransition(
              scale: CurvedAnimation(parent: _controller, curve: Curves.elasticOut),
              child: _badge(140),
            ),
            const SizedBox(height: AppSpacing.lg),
            Text(localizedMilestoneTitle(widget.item.code, Localizations.localeOf(context)),
                textAlign: TextAlign.center,
                style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w800, fontSize: 18)),
          ],
          const SizedBox(height: AppSpacing.lg),
          Text(_opened ? l10n.milestoneCelebrateUnlocked : l10n.milestoneCelebrateOpenChest,
              style: const TextStyle(color: Colors.white70, fontWeight: FontWeight.w600)),
        ],
      ),
    );
  }

  Widget _badge(double size) => Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          color: AppColors.mintTint,
          shape: BoxShape.circle,
          border: Border.all(color: AppColors.mint, width: 3),
        ),
        child: Icon(Icons.emoji_events_rounded, color: AppColors.mint700, size: size * 0.5),
      );
}

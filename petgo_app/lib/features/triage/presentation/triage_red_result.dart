import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/red_alert_overlay.dart';
import '../../profile/data/profile_repository.dart';
import '../data/triage_repository.dart';
import '../domain/triage_wording_guard.dart';

/// 红色结果（Story 4.5）。进入即自底滑起 [RedAlertOverlay] 半屏强提醒；关闭后保留红色摘要——
/// 🔒 **零兽医 CTA / 零存档 / 零变现引流 / 零地图导航 / 零医院推荐**（F3 · 2026-06-08 去导航化，
/// 区别于绿/黄页），红色态唯一出口是单一「我已知晓」按钮关闭遮罩、返回纯结果摘要。
class TriageRedResult extends ConsumerStatefulWidget {
  const TriageRedResult({super.key, required this.result});

  final TriageResult result;

  @override
  ConsumerState<TriageRedResult> createState() => _TriageRedResultState();
}

class _TriageRedResultState extends ConsumerState<TriageRedResult> {
  bool _shown = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _showOverlayOnce());
  }

  Future<void> _showOverlayOnce() async {
    if (_shown || !mounted) return;
    _shown = true;
    final pet = await ref.read(petProfileProvider.future);
    if (!mounted) return;
    final l10n = AppLocalizations.of(context);
    final title =
        pet?.name != null ? l10n.triageRedTitle(pet!.name) : l10n.triageRedTitleNoPet;
    await showModalBottomSheet<void>(
      context: context,
      isDismissible: false, // 🔒 背景点击不可关闭
      enableDrag: false, // 🔒 拖拽不可关闭
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (sheetCtx) => RedAlertOverlay(
        title: title,
        onAcknowledge: () => Navigator.of(sheetCtx).pop(),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final advice =
        TriageWordingGuard.sanitize(widget.result.advice, fallback: l10n.triageNeutralAdvice);
    // 关闭 overlay 后保留的红色摘要：⚠️ + 等级 + 建议 + 前置免责。
    // 🔒 无兽医 CTA / 无存档 / 无变现 / 无地图导航 / 无医院推荐（F3 去导航化）。
    return ListView(
      key: const ValueKey('triageRedSummary'),
      padding: const EdgeInsets.all(AppSpacing.screenEdge),
      children: <Widget>[
        Row(
          children: <Widget>[
            const Icon(Icons.warning_amber_rounded, color: AppColors.triageRed),
            const SizedBox(width: AppSpacing.sm),
            Text(l10n.triageRedLevelLabel,
                style: AppTypography.title.copyWith(color: AppColors.triageRed)),
          ],
        ),
        const SizedBox(height: AppSpacing.md),
        Text(advice, style: AppTypography.body),
        const SizedBox(height: AppSpacing.lg),
        Text(widget.result.disclaimer ?? l10n.triageDisclaimer, style: AppTypography.disclaimer),
      ],
    );
  }
}

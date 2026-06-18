import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/red_alert_overlay.dart';
import '../../profile/data/profile_repository.dart';
import '../data/triage_repository.dart';
import '../domain/triage_archive.dart';
import '../domain/triage_upload_controller.dart';
import '../domain/triage_wording_guard.dart';

/// 红色结果（Story 4.5）。进入即自底滑起 [RedAlertOverlay] 半屏强提醒；关闭后保留红色摘要。
/// 🔒 **零兽医 CTA / 零变现引流 / 零地图导航 / 零医院推荐**（F3 · 去导航化），红色态唯一关闭出口
/// 是单一「我已知晓」按钮。
/// 🆕 **R2（FR-3 · F15）：结果页底部加「存入档案」入口**——存档为免费工具，**非变现/引流**，
/// 零变现护栏不变；A 已建档直接存、未建档/B-C 引导建档（见 [triageRedArchiveHandlerProvider]）。
class TriageRedResult extends ConsumerStatefulWidget {
  const TriageRedResult({super.key, required this.result, this.triageId});

  final TriageResult result;
  final int? triageId;

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
    final symptom = ref.read(triageUploadProvider).symptomText;
    // 决策 #5：全屏沉浸（opaque 红屏，barrier 不可点关，PopScope 锁返回键）。
    await showGeneralDialog<void>(
      context: context,
      barrierDismissible: false, // 🔒 背景点击不可关闭
      barrierColor: AppColors.triageRed,
      barrierLabel: l10n.triageRedLevelLabel,
      pageBuilder: (dialogCtx, _, _) => RedAlertOverlay(
        title: title,
        symptom: symptom,
        onAcknowledge: () => Navigator.of(dialogCtx).pop(),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final advice =
        TriageWordingGuard.sanitize(widget.result.advice, fallback: l10n.triageNeutralAdvice);
    // 关闭 overlay 后保留的红色摘要：⚠️ + 等级 + 建议 +「存入档案」(R2 · FR-3) + 前置免责。
    // 🔒 仍无兽医 CTA / 无变现引流 / 无地图导航 / 无医院推荐（F3 去导航化）；
    //    「存入档案」是唯一新增工具入口——免费存档、非变现（守 NFR-9 零变现护栏）。
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
        // R2（FR-3 · F15）：红色态「存入档案」入口（仅存档、免费；A 已建档直存 / 未建档·B-C 引导建档）。
        FilledButton.tonal(
          key: const ValueKey('triageRedSaveToArchive'),
          onPressed: () => ref.read(triageRedArchiveHandlerProvider)(
            context,
            ref,
            triageId: widget.triageId,
            level: DangerLevel.red,
            advice: widget.result.advice,
            symptom: ref.read(triageUploadProvider).symptomText,
          ),
          child: Text(l10n.triageSaveToArchive),
        ),
        const SizedBox(height: AppSpacing.lg),
        Text(widget.result.disclaimer ?? l10n.triageDisclaimer, style: AppTypography.disclaimer),
      ],
    );
  }
}

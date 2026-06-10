import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/rounded.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import 'package:go_router/go_router.dart';

import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/triage_result_card.dart';
import '../../consult/data/consult_repository.dart';
import '../data/triage_repository.dart';
import '../domain/triage_archive.dart';
import '../domain/triage_upload_controller.dart';
import '../domain/triage_wording_guard.dart';
import 'triage_red_result.dart';

/// 分诊绿/黄结果展示（Story 4.4）。三项同屏（等级 + 观察建议 + 用药参考）+ 黄色条件倒计时协议块 +
/// 前置免责 + 非红「存入档案」触发 FR-16。红色一律交棒 4.5 半屏（本视图只占位，不软化渲染）。
class TriageResultView extends ConsumerWidget {
  const TriageResultView({super.key, required this.result, this.triageId});

  final TriageResult result;
  final int? triageId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final level = result.dangerLevel;

    // 🔒 红色走 4.5 半屏强提醒（自底滑起 overlay）+ 关闭后保留零兽医/零变现红色摘要。
    if (level == DangerLevel.red || level == null) {
      return TriageRedResult(result: result, triageId: triageId);
    }

    final isYellow = level == DangerLevel.yellow;
    // 终结性表述守卫：模型若吐出「不严重/可以放心」等，拦截降级为中性提示。
    final advice = TriageWordingGuard.sanitize(result.advice, fallback: l10n.triageNeutralAdvice);

    return ListView(
      key: ValueKey(isYellow ? 'triageYellowPage' : 'triageGreenPage'),
      padding: const EdgeInsets.all(AppSpacing.screenEdge),
      children: <Widget>[
        TriageResultCard(
          level: level,
          title: isYellow ? l10n.triageYellowTitle : l10n.triageGreenTitle,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text(advice, style: AppTypography.body),
              if (isYellow && result.medicationRef != null) ...<Widget>[
                const SizedBox(height: AppSpacing.md),
                Text(l10n.triageMedicationRefLabel, style: AppTypography.caption),
                Text(result.medicationRef!, style: AppTypography.body),
              ],
              if (isYellow && (result.observation?.hasContent ?? false)) ...<Widget>[
                const SizedBox(height: AppSpacing.lg),
                _ProtocolBlock(observation: result.observation!),
              ],
              if (!isYellow) ...<Widget>[
                const SizedBox(height: AppSpacing.md),
                Text(l10n.triageSoftVetGuide, style: AppTypography.caption),
              ],
            ],
          ),
        ),
        const SizedBox(height: AppSpacing.lg),
        FilledButton.tonal(
          key: const ValueKey('triageSaveToArchive'),
          onPressed: () => ref.read(triageArchiveHandlerProvider)(
            context,
            ref,
            triageId: triageId,
            level: level,
            advice: result.advice,
            symptom: ref.read(triageUploadProvider).symptomText,
          ),
          child: Text(l10n.triageSaveToArchive),
        ),
        // 「咨询兽医」升级入口（Story 5.4 F1）：仅绿/黄态出现（红色走 TriageRedResult 早返回，此处永不可达）。
        // 只传 triageId 升级，评级/描述/图片由后端从 triage 拉取（前端不重传，FR-4B）。
        if (triageId != null) ...<Widget>[
          const SizedBox(height: AppSpacing.md),
          FilledButton(
            key: const ValueKey('triageConsultVet'),
            onPressed: () => _upgradeToVet(context, ref, triageId!),
            child: Text(l10n.triageConsultVet),
          ),
        ],
        const SizedBox(height: AppSpacing.lg),
        // 前置免责声明（NFR-9）：小号次要色，不干扰主内容。
        Text(result.disclaimer ?? l10n.triageDisclaimer, style: AppTypography.disclaimer),
      ],
    );
  }
}

Future<void> _upgradeToVet(BuildContext context, WidgetRef ref, int triageId) async {
  final l10n = AppLocalizations.of(context);
  try {
    final session = await ref.read(consultRepositoryProvider).createFromUpgrade(triageId);
    if (!context.mounted) return;
    context.go('/consult/waiting/${session.id}');
  } catch (_) {
    if (!context.mounted) return;
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(SnackBar(content: Text(l10n.consultStartFailed)));
  }
}

/// 黄色条件倒计时协议块（FR-2，UX-DR6）：accent-consult 浅底 #EEF4F7，三要素分区视觉可区分。
class _ProtocolBlock extends StatelessWidget {
  const _ProtocolBlock({required this.observation});

  final TriageObservation observation;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      key: const ValueKey('triageProtocolBlock'),
      width: double.infinity,
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: AppColors.triageYellowSurface, // #EEF4F7
        borderRadius: BorderRadius.circular(AppRounded.sm),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(l10n.triageObservationTitle,
              style: AppTypography.body.copyWith(fontWeight: FontWeight.w700)),
          if (observation.indicators.isNotEmpty)
            _Section(label: l10n.triageIndicatorsLabel, lines: observation.indicators),
          if (observation.timeWindow != null && observation.timeWindow!.isNotEmpty)
            _Section(label: l10n.triageTimeWindowLabel, lines: <String>[observation.timeWindow!]),
          if (observation.escalationTriggers.isNotEmpty)
            _Section(label: l10n.triageEscalationLabel, lines: observation.escalationTriggers),
        ],
      ),
    );
  }
}

class _Section extends StatelessWidget {
  const _Section({required this.label, required this.lines});

  final String label;
  final List<String> lines;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: AppSpacing.sm),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(label, style: AppTypography.caption.copyWith(fontWeight: FontWeight.w700)),
          for (final line in lines) Text('· $line', style: AppTypography.body),
        ],
      ),
    );
  }
}

import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../domain/consult_diagnosis.dart';

/// 用户侧「会诊结果」只读平铺视图（会话 CLOSED 后正文常驻）。
///
/// 布局参考兽医端最终诊断填写页 [vet_final_diagnosis_page]：micro 标签 + 边框框住的只读值，
/// 但**不可编辑**（值直接渲染为文本，无输入框 / 无提交栏）。复用 vetDiag* 文案；空字段跳过。
/// 健康数据：仅展示，不落日志。
class ConsultDiagnosisView extends StatelessWidget {
  const ConsultDiagnosisView({super.key, required this.diagnosis});

  final ConsultDiagnosis diagnosis;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final d = diagnosis;
    return ListView(
      key: const ValueKey('consultDiagnosisView'),
      padding: const EdgeInsets.fromLTRB(AppSpacing.md, AppSpacing.md, AppSpacing.md, AppSpacing.xl),
      children: [
        // 标题行（弹层同款）：📋 + 会诊结果。
        Padding(
          padding: const EdgeInsets.only(bottom: AppSpacing.md),
          child: Row(
            children: [
              const Text('📋', style: TextStyle(fontSize: 16)),
              const SizedBox(width: 8),
              Text(l10n.vetDiagTitle,
                  style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.ink)),
            ],
          ),
        ),
        _readField(l10n.vetDiagDiagnosis, d.diagnosis, emphasize: true),
        if (d.generalAdvice.isNotEmpty) _readField(l10n.vetDiagAdvice, d.generalAdvice),
        _medField(l10n),
        if (d.followUp.isNotEmpty) _readField(l10n.vetDiagFollowUp, d.followUp),
        if (d.worseningSigns.isNotEmpty) _readField(l10n.vetDiagWorsening, d.worseningSigns),
        if (d.clinicWithin.isNotEmpty) _readField(l10n.vetDiagClinicWithin, d.clinicWithin),
      ],
    );
  }

  /// 只读字段：micro 标签 + 边框框住的值文本（emphasize=Diagnosa 主诊断，紫底紫字加粗）。
  Widget _readField(String label, String value, {bool emphasize = false}) {
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.md),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label,
              style: AppTypography.micro.copyWith(color: AppColors.textTertiary, letterSpacing: 0.5)),
          const SizedBox(height: 6),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
            decoration: BoxDecoration(
              color: emphasize ? AppColors.vetSurface : AppColors.surface,
              borderRadius: BorderRadius.circular(10),
              border: Border.all(color: emphasize ? AppColors.mint : AppColors.border),
            ),
            child: Text(value,
                style: TextStyle(
                  fontSize: 14,
                  height: 1.5,
                  fontWeight: emphasize ? FontWeight.w700 : FontWeight.w400,
                  color: emphasize ? AppColors.mint : AppColors.ink,
                )),
          ),
        ],
      ),
    );
  }

  /// 是否需用药：只读段控（高亮实选项，不可点）；需药时附药名/频次只读字段。
  Widget _medField(AppLocalizations l10n) {
    final d = diagnosis;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(bottom: AppSpacing.md),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(l10n.vetDiagNeedMed,
                  style: AppTypography.micro.copyWith(color: AppColors.textTertiary, letterSpacing: 0.5)),
              const SizedBox(height: 6),
              Row(
                children: [
                  Expanded(child: _readSeg(l10n.vetDiagYes, d.needsMedication)),
                  const SizedBox(width: 8),
                  Expanded(child: _readSeg(l10n.vetDiagNo, !d.needsMedication)),
                ],
              ),
            ],
          ),
        ),
        if (d.needsMedication) ...[
          if (d.medName.isNotEmpty) _readField(l10n.vetDiagMedName, d.medName),
          if (d.medFrequency.isNotEmpty) _readField(l10n.vetDiagMedFreq, d.medFrequency),
        ],
      ],
    );
  }

  /// 只读段按钮：高亮兽医实际选择项（紫描边紫字），另一项灰；无点击。
  Widget _readSeg(String label, bool active) {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 11),
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: active ? AppColors.mint.withValues(alpha: 0.12) : AppColors.surface,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: active ? AppColors.mint : AppColors.border, width: active ? 1.5 : 1),
      ),
      child: Text(label,
          style: AppTypography.caption.copyWith(
            color: active ? AppColors.mint : AppColors.textSecondary,
            fontWeight: active ? FontWeight.w700 : FontWeight.w400,
          )),
    );
  }
}

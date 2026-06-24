import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../domain/consult_diagnosis.dart';

/// 用户侧「会诊结果」只读弹层（Story C 收尾）。展示兽医结束时定格的最终诊断。
/// 复用 vetDiag* 文案；空字段跳过。健康数据：仅展示，不落日志。
Future<void> showConsultDiagnosisSheet(BuildContext context, ConsultDiagnosis d) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    backgroundColor: AppColors.surface,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
    ),
    builder: (ctx) => _DiagnosisSheet(d: d),
  );
}

class _DiagnosisSheet extends StatelessWidget {
  const _DiagnosisSheet({required this.d});

  final ConsultDiagnosis d;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final medValue = d.needsMedication
        ? [d.medName, d.medFrequency].where((s) => s.isNotEmpty).join(' · ')
        : l10n.vetDiagNo;
    return SafeArea(
      top: false,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: MediaQuery.of(context).size.height * 0.82),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const SizedBox(height: 10),
            Container(
              width: 40,
              height: 5,
              decoration:
                  BoxDecoration(color: AppColors.line, borderRadius: BorderRadius.circular(3)),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 14, 20, 6),
              child: Row(
                children: [
                  const Text('📋', style: TextStyle(fontSize: 18)),
                  const SizedBox(width: 8),
                  Text(l10n.vetDiagTitle,
                      style: const TextStyle(
                          fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.ink)),
                ],
              ),
            ),
            Flexible(
              child: ListView(
                shrinkWrap: true,
                padding: const EdgeInsets.fromLTRB(20, 6, 20, 24),
                children: [
                  _row(l10n.vetDiagDiagnosis, d.diagnosis, emphasize: true),
                  if (d.generalAdvice.isNotEmpty) _row(l10n.vetDiagAdvice, d.generalAdvice),
                  _row(l10n.vetDiagNeedMed, medValue),
                  if (d.followUp.isNotEmpty) _row(l10n.vetDiagFollowUp, d.followUp),
                  if (d.worseningSigns.isNotEmpty) _row(l10n.vetDiagWorsening, d.worseningSigns),
                  if (d.clinicWithin.isNotEmpty) _row(l10n.vetDiagClinicWithin, d.clinicWithin),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _row(String label, String value, {bool emphasize = false}) {
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(13),
      decoration: BoxDecoration(
        color: emphasize ? AppColors.vetSurface : AppColors.cream2,
        borderRadius: BorderRadius.circular(11),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label,
              style: const TextStyle(
                  fontSize: 11, fontWeight: FontWeight.w600, color: AppColors.textSecondary)),
          const SizedBox(height: 4),
          Text(value,
              style: TextStyle(
                  fontSize: 14,
                  height: 1.5,
                  fontWeight: emphasize ? FontWeight.w700 : FontWeight.w400,
                  color: emphasize ? AppColors.mint : AppColors.ink)),
        ],
      ),
    );
  }
}

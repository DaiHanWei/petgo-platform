import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../domain/vet_diagnosis_draft.dart';

/// 兽医最终诊断表单（Story C，原型 `#p-vet-final-diagnosis` 1:1）。
///
/// 结束会话前填写：Diagnosa（必填）+ 一般建议 + 是否需药(+药名/频次) + 复诊 + 恶化征兆 + 恶化就医时限。
/// 提交（Diagnosa 非空才可点）→ `Navigator.pop` 回 [VetDiagnosisDraft]；返回则取消结束。
/// 视觉走兽医薄荷主题（调用方已包 `Theme(data: AppTheme.vet)`）。
class VetFinalDiagnosisPage extends StatefulWidget {
  const VetFinalDiagnosisPage({super.key, this.petName, this.vetName});

  final String? petName;
  final String? vetName;

  @override
  State<VetFinalDiagnosisPage> createState() => _VetFinalDiagnosisPageState();
}

class _VetFinalDiagnosisPageState extends State<VetFinalDiagnosisPage> {
  final _diagnosis = TextEditingController();
  final _advice = TextEditingController();
  final _medName = TextEditingController();
  final _medFreq = TextEditingController();
  final _followUp = TextEditingController();
  final _worsening = TextEditingController();
  final _clinicWithin = TextEditingController();
  bool _needsMed = false;

  @override
  void dispose() {
    for (final c in [_diagnosis, _advice, _medName, _medFreq, _followUp, _worsening, _clinicWithin]) {
      c.dispose();
    }
    super.dispose();
  }

  bool get _canSubmit => _diagnosis.text.trim().isNotEmpty;

  void _submit() {
    if (!_canSubmit) return;
    Navigator.of(context).pop(VetDiagnosisDraft(
      diagnosis: _diagnosis.text.trim(),
      generalAdvice: _advice.text.trim(),
      needsMedication: _needsMed,
      medName: _needsMed ? _medName.text.trim() : '',
      medFrequency: _needsMed ? _medFreq.text.trim() : '',
      followUp: _followUp.text.trim(),
      worseningSigns: _worsening.text.trim(),
      clinicWithin: _clinicWithin.text.trim(),
    ));
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final subtitle = [widget.petName, widget.vetName].where((e) => e != null && e.isNotEmpty).join(' · ');
    return Scaffold(
      backgroundColor: AppColors.vetSurface2,
      appBar: AppBar(
        backgroundColor: AppColors.vetTopBar,
        foregroundColor: Colors.white,
        title: Text(l10n.vetDiagTitle),
      ),
      body: Column(
        children: [
          Expanded(
            child: ListView(
              padding: const EdgeInsets.all(AppSpacing.md),
              children: [
                if (subtitle.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(bottom: AppSpacing.md),
                    child: Text(subtitle, style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
                  ),
                _field(l10n.vetDiagDiagnosis, _diagnosis, required: true, maxLines: 2),
                _field(l10n.vetDiagAdvice, _advice, maxLines: 3),
                const SizedBox(height: AppSpacing.sm),
                _medToggle(l10n),
                if (_needsMed) ...[
                  _field(l10n.vetDiagMedName, _medName),
                  _field(l10n.vetDiagMedFreq, _medFreq),
                ],
                _field(l10n.vetDiagFollowUp, _followUp),
                _field(l10n.vetDiagWorsening, _worsening, maxLines: 2),
                _field(l10n.vetDiagClinicWithin, _clinicWithin),
              ],
            ),
          ),
          _submitBar(l10n),
        ],
      ),
    );
  }

  Widget _field(String label, TextEditingController c, {bool required = false, int maxLines = 1}) {
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.md),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(label,
                  style: AppTypography.micro
                      .copyWith(color: AppColors.textTertiary, letterSpacing: 0.5)),
              if (required)
                const Text(' *', style: TextStyle(color: AppColors.coral, fontWeight: FontWeight.w700)),
            ],
          ),
          const SizedBox(height: 6),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
            decoration: BoxDecoration(
              color: AppColors.surface,
              borderRadius: BorderRadius.circular(10),
              border: Border.all(color: AppColors.border),
            ),
            child: TextField(
              key: required ? const ValueKey('vetDiagInput') : null,
              controller: c,
              maxLines: maxLines,
              onChanged: required ? (_) => setState(() {}) : null,
              decoration: const InputDecoration(border: InputBorder.none, isDense: true),
            ),
          ),
        ],
      ),
    );
  }

  Widget _medToggle(AppLocalizations l10n) {
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.md),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(l10n.vetDiagNeedMed,
              style: AppTypography.micro.copyWith(color: AppColors.textTertiary, letterSpacing: 0.5)),
          const SizedBox(height: 6),
          Row(
            children: [
              Expanded(child: _segBtn(l10n.vetDiagYes, _needsMed, () => setState(() => _needsMed = true))),
              const SizedBox(width: 8),
              Expanded(child: _segBtn(l10n.vetDiagNo, !_needsMed, () => setState(() => _needsMed = false))),
            ],
          ),
        ],
      ),
    );
  }

  Widget _segBtn(String label, bool active, VoidCallback onTap) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(10),
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 11),
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: active ? AppColors.vetPrimary.withValues(alpha: 0.15) : AppColors.surface,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: active ? AppColors.vetPrimary : AppColors.border, width: active ? 1.5 : 1),
        ),
        child: Text(label,
            style: AppTypography.caption.copyWith(
                color: active ? AppColors.vetPrimary : AppColors.textSecondary,
                fontWeight: active ? FontWeight.w700 : FontWeight.w400)),
      ),
    );
  }

  Widget _submitBar(AppLocalizations l10n) {
    return Material(
      color: AppColors.surface,
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.md),
          child: SizedBox(
            width: double.infinity,
            child: FilledButton(
              key: const ValueKey('vetDiagSubmit'),
              onPressed: _canSubmit ? _submit : null,
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.vetPrimary,
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(vertical: 14),
              ),
              child: Text('${l10n.vetDiagSubmit} ✓'),
            ),
          ),
        ),
      ),
    );
  }
}

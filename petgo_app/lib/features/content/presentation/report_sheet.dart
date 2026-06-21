import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../features/auth/domain/auth_guard.dart';
import '../../../l10n/app_localizations.dart';
import '../data/detail_repository.dart';
import '../domain/report_reason.dart';

/// 举报入口（Story 3.7，FR-25）。未登录先触发 FR-0C；登录态弹类型单选 sheet 提交。
/// 详情「···」举报项与 Feed 卡片长按均复用此入口。提交成功 toast「已收到…」。
void openReport(BuildContext context, WidgetRef ref, int postId) {
  // 门控：未登录 → FR-0C，不弹 sheet。
  final allowed = requireLogin(ref, context, onAllowed: () {});
  if (!allowed) return;
  showModalBottomSheet<void>(
    context: context,
    backgroundColor: AppColors.surface,
    isScrollControlled: true,
    builder: (_) => _ReportSheet(postId: postId, ref: ref),
  );
}

class _ReportSheet extends StatefulWidget {
  const _ReportSheet({required this.postId, required this.ref});

  final int postId;
  final WidgetRef ref;

  @override
  State<_ReportSheet> createState() => _ReportSheetState();
}

class _ReportSheetState extends State<_ReportSheet> {
  ReportReason? _selected;
  bool _submitting = false;
  bool _done = false; // 提交成功 → sheet 内成功态（原型 report-type-done）

  String _label(AppLocalizations l10n, ReportReason r) => switch (r) {
        ReportReason.illegal => l10n.reportReasonIllegal,
        ReportReason.misinfo => l10n.reportReasonMisinfo,
        ReportReason.inappropriate => l10n.reportReasonInappropriate,
        ReportReason.harassment => l10n.reportReasonHarassment,
        ReportReason.other => l10n.reportReasonOther,
      };

  Future<void> _submit() async {
    if (_selected == null || _submitting) return;
    setState(() => _submitting = true);
    try {
      await widget.ref.read(detailRepositoryProvider).submitReport(widget.postId, _selected!.wire);
      if (mounted) setState(() => _done = true); // 切 sheet 内成功态（不再 toast）
    } catch (_) {
      if (mounted) setState(() => _submitting = false);
    }
  }

  Widget _handle() => Center(
        child: Container(
          width: 36,
          height: 4,
          margin: const EdgeInsets.only(bottom: 16),
          decoration:
              BoxDecoration(color: AppColors.line, borderRadius: BorderRadius.circular(9999)),
        ),
      );

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(22, 12, 22, 28),
        child: _done ? _doneView(l10n) : _formView(l10n),
      ),
    );
  }

  /// 举报理由表单（原型 report-type-form）：标题 + 副标题 + 5 紫边单选卡 + 提交 + Batal。
  Widget _formView(AppLocalizations l10n) => Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _handle(),
          Text(l10n.reportTitle,
              style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.ink)),
          const SizedBox(height: 4),
          Text(l10n.reportSubtitle,
              style: const TextStyle(fontSize: 12, color: AppColors.textSecondary)),
          const SizedBox(height: 16),
          for (final r in ReportReason.values) _reasonCard(l10n, r),
          const SizedBox(height: 4),
          SizedBox(
            width: double.infinity,
            child: FilledButton(
              key: const ValueKey('reportSubmit'),
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.mint,
                foregroundColor: AppColors.onAccent,
                padding: const EdgeInsets.symmetric(vertical: 14),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              ),
              onPressed: (_selected == null || _submitting) ? null : _submit,
              child: Text(l10n.reportSubmit,
                  style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
            ),
          ),
          const SizedBox(height: 8),
          SizedBox(
            width: double.infinity,
            child: OutlinedButton(
              onPressed: _submitting ? null : () => Navigator.of(context).pop(),
              style: OutlinedButton.styleFrom(
                foregroundColor: AppColors.textSecondary,
                side: const BorderSide(color: AppColors.line, width: 1.5),
                padding: const EdgeInsets.symmetric(vertical: 13),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              ),
              child: Text(l10n.commonCancel),
            ),
          ),
        ],
      );

  /// 紫边单选卡（选中 = 紫边 + 紫浅底 + 实心单选点）。
  Widget _reasonCard(AppLocalizations l10n, ReportReason r) {
    final selected = _selected == r;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: InkWell(
        key: ValueKey('reportReason_${r.name}'),
        onTap: () => setState(() => _selected = r),
        borderRadius: BorderRadius.circular(13),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          decoration: BoxDecoration(
            color: selected ? AppColors.cream2 : AppColors.surface,
            borderRadius: BorderRadius.circular(13),
            border: Border.all(
                color: selected ? AppColors.mint : AppColors.line, width: 1.5),
          ),
          child: Row(
            children: [
              Icon(selected ? Icons.radio_button_checked : Icons.radio_button_unchecked,
                  size: 20, color: selected ? AppColors.mint : AppColors.muted),
              const SizedBox(width: 10),
              Expanded(
                child: Text(_label(l10n, r),
                    style: const TextStyle(fontSize: 13, color: AppColors.ink)),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// 提交成功态（原型 report-type-done）：✅ + 标题 + 正文 + Tutup。
  Widget _doneView(AppLocalizations l10n) => Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          _handle(),
          const SizedBox(height: 8),
          const Text('✅', style: TextStyle(fontSize: 40)),
          const SizedBox(height: 10),
          Text(l10n.reportDoneTitle,
              style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.ink)),
          const SizedBox(height: 6),
          Text(l10n.reportDoneBody,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 13, color: AppColors.textSecondary)),
          const SizedBox(height: 20),
          SizedBox(
            width: double.infinity,
            child: FilledButton(
              key: const ValueKey('reportDoneClose'),
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.muted.withValues(alpha: 0.15),
                foregroundColor: AppColors.textSecondary,
                elevation: 0,
                padding: const EdgeInsets.symmetric(vertical: 13),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              ),
              onPressed: () => Navigator.of(context).pop(),
              child: Text(l10n.commonClose),
            ),
          ),
        ],
      );
}

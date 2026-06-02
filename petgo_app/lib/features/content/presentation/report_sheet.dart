import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
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
    final l10n = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final navigator = Navigator.of(context);
    try {
      await widget.ref.read(detailRepositoryProvider).submitReport(widget.postId, _selected!.wire);
      navigator.pop();
      messenger.showSnackBar(SnackBar(content: Text(l10n.reportSuccess)));
    } catch (_) {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(l10n.reportTitle, style: AppTypography.title),
            const SizedBox(height: AppSpacing.sm),
            RadioGroup<ReportReason>(
              groupValue: _selected,
              onChanged: (v) => setState(() => _selected = v),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  for (final r in ReportReason.values)
                    RadioListTile<ReportReason>(
                      key: ValueKey('reportReason_${r.name}'),
                      value: r,
                      title: Text(_label(l10n, r), style: AppTypography.body),
                      contentPadding: EdgeInsets.zero,
                      activeColor: AppColors.accentGrowth,
                    ),
                ],
              ),
            ),
            const SizedBox(height: AppSpacing.sm),
            FilledButton(
              key: const ValueKey('reportSubmit'),
              onPressed: (_selected == null || _submitting) ? null : _submit,
              child: Text(l10n.reportSubmit),
            ),
          ],
        ),
      ),
    );
  }
}

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/keyboard_safe_area.dart';
import '../data/support_repository.dart';

/// CSAT 满意度问卷（Story 4.7）。1-5 星 + 评论 ≤100（可空）。提交调后端（RESOLVED 未评窗口内），成功回详情刷新（→CLOSED）。
class CsatPage extends ConsumerStatefulWidget {
  const CsatPage({super.key, required this.token});

  final String token;

  @override
  ConsumerState<CsatPage> createState() => _CsatPageState();
}

class _CsatPageState extends ConsumerState<CsatPage> {
  int _score = 0;
  final _commentCtrl = TextEditingController();
  bool _submitting = false;

  @override
  void dispose() {
    _commentCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final l10n = AppLocalizations.of(context);
    if (_score < 1) return;
    setState(() => _submitting = true);
    try {
      await ref
          .read(supportRepositoryProvider)
          .submitCsat(widget.token, score: _score, comment: _commentCtrl.text);
      ref.invalidate(ticketDetailProvider(widget.token));
      ref.invalidate(myTicketsProvider);
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l10n.csatThanks)));
      context.pop();
    } catch (_) {
      if (!mounted) return;
      setState(() => _submitting = false);
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l10n.csatSubmitFailed)));
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(
        backgroundColor: AppColors.surface,
        title: Text(l10n.csatTitle),
      ),
      body: SafeArea(
        child: KeyboardSafeArea(
          child: Padding(
            padding: const EdgeInsets.all(AppSpacing.screenEdge),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  l10n.csatPrompt,
                  style: AppTypography.title.copyWith(
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: AppSpacing.lg),
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    for (int i = 1; i <= 5; i++)
                      IconButton(
                        onPressed: _submitting
                            ? null
                            : () => setState(() => _score = i),
                        icon: Icon(
                          i <= _score
                              ? Icons.star_rounded
                              : Icons.star_outline_rounded,
                          size: 40,
                          color: i <= _score
                              ? AppColors.gold
                              : AppColors.textTertiary,
                        ),
                      ),
                  ],
                ),
                const SizedBox(height: AppSpacing.lg),
                TextField(
                  controller: _commentCtrl,
                  maxLength: 100,
                  maxLines: 3,
                  decoration: InputDecoration(
                    hintText: l10n.csatCommentHint,
                    border: const OutlineInputBorder(),
                  ),
                ),
                const Spacer(),
                FilledButton(
                  onPressed: (_submitting || _score < 1) ? null : _submit,
                  child: _submitting
                      ? const SizedBox(
                          height: 18,
                          width: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : Text(l10n.csatSubmit),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

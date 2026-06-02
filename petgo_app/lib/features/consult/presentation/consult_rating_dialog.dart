import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';

/// 评分结果（Story 5.6）：星级 1-5 必填 + ≤100 字选填。
class RatingResult {
  const RatingResult(this.stars, this.comment);

  final int stars;
  final String? comment;
}

/// 用户评分弹窗（Story 5.6 F2/F3）。1-5 星必填 + ≤100 字选填。客户端预校验仅体验，服务端权威。
///
/// 返回 [RatingResult]（已提交）或 null（关闭）。文案双语、克制（UX-DR14）。
class ConsultRatingDialog extends StatefulWidget {
  const ConsultRatingDialog({super.key});

  static Future<RatingResult?> show(BuildContext context, {bool barrierDismissible = true}) {
    return showDialog<RatingResult>(
      context: context,
      barrierDismissible: barrierDismissible,
      builder: (_) => const ConsultRatingDialog(),
    );
  }

  @override
  State<ConsultRatingDialog> createState() => _ConsultRatingDialogState();
}

class _ConsultRatingDialogState extends State<ConsultRatingDialog> {
  int _stars = 0;
  final _comment = TextEditingController();

  @override
  void dispose() {
    _comment.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return AlertDialog(
      title: Text(l10n.consultRateTitle),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisSize: MainAxisSize.min,
            children: List.generate(5, (i) {
              final filled = i < _stars;
              return IconButton(
                key: ValueKey('ratingStar_${i + 1}'),
                visualDensity: VisualDensity.compact,
                icon: Icon(filled ? Icons.star : Icons.star_border,
                    color: filled ? AppColors.triageYellow : AppColors.textTertiary),
                onPressed: () => setState(() => _stars = i + 1),
              );
            }),
          ),
          TextField(
            key: const ValueKey('ratingComment'),
            controller: _comment,
            maxLength: 100,
            decoration: InputDecoration(hintText: l10n.consultRateCommentHint),
          ),
          if (_stars == 0)
            Text(l10n.consultRateStarsRequired,
                style: AppTypography.disclaimer.copyWith(color: AppColors.danger)),
        ],
      ),
      actions: [
        FilledButton(
          key: const ValueKey('ratingSubmit'),
          // 星必选才可提交（客户端预校验；服务端权威）。
          onPressed: _stars == 0
              ? null
              : () => Navigator.of(context).pop(
                  RatingResult(_stars, _comment.text.trim().isEmpty ? null : _comment.text.trim())),
          child: Text(l10n.consultRateSubmit),
        ),
      ],
    );
  }
}

import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import 'video_spec.dart';
import 'video_spec_messages.dart';

/// 视频规格检查不合格时的提示卡（专题线 video Story 1.5 · FR-7）：逐条列出，并给「重新选择」「移除」。
class VideoSpecErrorCard extends StatelessWidget {
  const VideoSpecErrorCard({
    super.key,
    required this.result,
    required this.onRepick,
    required this.onDismiss,
  });

  final VideoSpecResult result;
  final VoidCallback onRepick;
  final VoidCallback onDismiss;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      key: const ValueKey('videoSpecErrorCard'),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(11),
        border: Border.all(color: AppColors.coral, width: 1.5),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            l10n.videoSpecFailedTitle,
            style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.ink),
          ),
          const SizedBox(height: 6),
          for (final f in result.failures)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('• ', style: TextStyle(fontSize: 12, color: AppColors.coral)),
                  Expanded(
                    child: Text(
                      videoSpecFailureMessage(l10n, f, result),
                      key: ValueKey('videoSpecFailure_${f.name}'),
                      style: const TextStyle(fontSize: 12, height: 1.4, color: AppColors.ink2),
                    ),
                  ),
                ],
              ),
            ),
          const SizedBox(height: 10),
          Row(
            mainAxisAlignment: MainAxisAlignment.end,
            children: [
              TextButton(
                key: const ValueKey('videoSpecDismiss'),
                onPressed: onDismiss,
                child: Text(l10n.videoRemove),
              ),
              const SizedBox(width: 4),
              TextButton(
                key: const ValueKey('videoSpecRepick'),
                onPressed: onRepick,
                child: Text(l10n.videoRepick),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

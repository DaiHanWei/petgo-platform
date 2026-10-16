import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import 'video_spec.dart';
import 'video_spec_messages.dart';

/// 已通过检查的视频预览格（专题线 video Story 1.5）。封面、上传进度在 Story 1.6 接入。
class VideoDraftTile extends StatelessWidget {
  const VideoDraftTile({super.key, required this.draft, required this.onRemove});

  final VideoDraft draft;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      key: const ValueKey('videoDraftTile'),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: AppColors.cream2,
        borderRadius: BorderRadius.circular(11),
        border: Border.all(color: AppColors.line, width: 1.5),
      ),
      child: Row(
        children: [
          Container(
            width: 54,
            height: 72,
            decoration: BoxDecoration(
              color: AppColors.ink2,
              borderRadius: BorderRadius.circular(8),
            ),
            alignment: Alignment.center,
            child: const Icon(Icons.play_circle_outline_rounded, color: Colors.white, size: 26),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  l10n.videoSpecPassed,
                  style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.ink),
                ),
                const SizedBox(height: 4),
                Text(
                  '${formatVideoDuration(draft.durationMs)} · ${draft.width}×${draft.height}',
                  key: const ValueKey('videoDraftMeta'),
                  style: const TextStyle(fontSize: 12, color: AppColors.textSecondary),
                ),
              ],
            ),
          ),
          IconButton(
            key: const ValueKey('videoDraftRemove'),
            tooltip: l10n.videoRemove,
            onPressed: onRemove,
            icon: const Icon(Icons.close_rounded, color: AppColors.ink2),
          ),
        ],
      ),
    );
  }
}

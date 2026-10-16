import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../domain/publish_controller.dart';
import 'video_spec.dart';
import 'video_spec_messages.dart';

/// 已通过检查的视频卡（专题线 video Story 1.5 / 1.6）：封面、上传进度 / 失败重试、换封面、移除。
///
/// 封面预览只用本地字节 —— 上传后的封面是私有的（发布后才公开），拿 URL 拉图会 403。
class VideoDraftTile extends StatelessWidget {
  const VideoDraftTile({
    super.key,
    required this.draft,
    required this.controller,
    required this.onRemove,
    required this.onChangeCover,
  });

  final VideoDraft draft;
  final PublishController controller;
  final VoidCallback onRemove;
  final VoidCallback onChangeCover;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final cover = controller.coverBytes;
    return Container(
      key: const ValueKey('videoDraftTile'),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: AppColors.cream2,
        borderRadius: BorderRadius.circular(11),
        border: Border.all(color: AppColors.line, width: 1.5),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(8),
            child: SizedBox(
              width: 72,
              height: 96,
              child: cover != null
                  ? Image.memory(cover, key: const ValueKey('videoCoverImage'), fit: BoxFit.cover, gaplessPlayback: true)
                  : Container(
                      color: AppColors.ink2,
                      alignment: Alignment.center,
                      child: const Icon(Icons.play_circle_outline_rounded, color: Colors.white, size: 26),
                    ),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '${formatVideoDuration(draft.durationMs)} · ${draft.width}×${draft.height}',
                  key: const ValueKey('videoDraftMeta'),
                  style: const TextStyle(fontSize: 12, color: AppColors.textSecondary),
                ),
                const SizedBox(height: 6),
                _videoStatus(l10n),
                const SizedBox(height: 4),
                _coverStatus(l10n),
                const SizedBox(height: 2),
                TextButton(
                  key: const ValueKey('videoCoverChange'),
                  style: TextButton.styleFrom(padding: EdgeInsets.zero, minimumSize: const Size(0, 32)),
                  onPressed: controller.publishing ? null : onChangeCover,
                  child: Text(cover == null ? l10n.videoCoverPick : l10n.videoCoverChange),
                ),
              ],
            ),
          ),
          IconButton(
            key: const ValueKey('videoDraftRemove'),
            tooltip: l10n.videoRemove,
            onPressed: controller.publishing ? null : onRemove,
            icon: const Icon(Icons.close_rounded, color: AppColors.ink2),
          ),
        ],
      ),
    );
  }

  Widget _videoStatus(AppLocalizations l10n) {
    const style = TextStyle(fontSize: 12, fontWeight: FontWeight.w600);
    switch (controller.videoStatus) {
      case MediaUploadStatus.uploading:
        final pct = (controller.videoProgress * 100).clamp(0, 100).round();
        return Row(
          children: [
            Expanded(
              child: Text(l10n.videoUploading(pct), key: const ValueKey('videoUploadProgress'),
                  style: style.copyWith(color: AppColors.ink2)),
            ),
            GestureDetector(
              key: const ValueKey('videoUploadCancel'),
              onTap: controller.cancelVideoUpload,
              child: Text(l10n.videoUploadCancel, style: style.copyWith(color: AppColors.mint)),
            ),
          ],
        );
      case MediaUploadStatus.success:
        return Text(l10n.videoSpecPassed, style: style.copyWith(color: AppColors.ink2));
      case MediaUploadStatus.failed:
        final msg = switch (controller.videoError) {
          VideoUploadError.permission => l10n.videoPermissionDenied,
          VideoUploadError.quota => l10n.videoQuotaExceeded,
          _ => l10n.videoUploadFailedRetry,
        };
        final retryable = controller.videoError == VideoUploadError.policy ||
            controller.videoError == VideoUploadError.upload;
        return GestureDetector(
          key: const ValueKey('videoUploadRetry'),
          onTap: retryable ? controller.retryVideoUpload : null,
          child: Text(msg, style: style.copyWith(color: AppColors.coral)),
        );
      case MediaUploadStatus.idle:
        return GestureDetector(
          key: const ValueKey('videoUploadStart'),
          onTap: controller.startVideoUpload,
          // idle 几乎只出现在用户主动取消之后：取消不算错误，用中性文案与颜色（code review）。
          child: Text(l10n.videoUploadCanceledRestart, style: style.copyWith(color: AppColors.ink2)),
        );
    }
  }

  Widget _coverStatus(AppLocalizations l10n) {
    const style = TextStyle(fontSize: 11);
    if (controller.coverStatus == MediaUploadStatus.failed) {
      final hasCover = controller.coverBytes != null;
      return GestureDetector(
        key: const ValueKey('videoCoverRetry'),
        onTap: hasCover ? controller.retryCoverUpload : null,
        child: Text(hasCover ? l10n.videoCoverFailedRetry : l10n.videoCoverPick,
            style: style.copyWith(color: AppColors.coral)),
      );
    }
    return const SizedBox.shrink();
  }
}

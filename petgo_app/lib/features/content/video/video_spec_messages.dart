import '../../../l10n/app_localizations.dart';
import 'video_spec.dart';

/// 把一条不合格项翻成「实际值 + 正确要求 + 怎么办」的提示（专题线 video Story 1.5 · FR-7）。
String videoSpecFailureMessage(AppLocalizations l10n, VideoSpecFailure f, VideoSpecResult r) {
  switch (f) {
    case VideoSpecFailure.unreadable:
      return l10n.videoSpecUnreadable;
    case VideoSpecFailure.format:
      return l10n.videoSpecFormat;
    case VideoSpecFailure.duration:
      return l10n.videoSpecDuration(_durationSeconds(r.durationMs ?? 0));
    case VideoSpecFailure.size:
      return l10n.videoSpecSize(_sizeMb(r.sizeBytes ?? 0));
    case VideoSpecFailure.resolution:
      return l10n.videoSpecResolution('${r.displayWidth ?? 0}×${r.displayHeight ?? 0}');
    case VideoSpecFailure.videoCodec:
      return l10n.videoSpecVideoCodec(_codecName(r.videoCodec));
    case VideoSpecFailure.audioCodec:
      return l10n.videoSpecAudioCodec;
    case VideoSpecFailure.hdr:
      return l10n.videoSpecHdr;
    case VideoSpecFailure.faststart:
      return l10n.videoSpecFaststart;
    case VideoSpecFailure.location:
      return l10n.videoSpecLocation;
  }
}

/// 实际时长（秒，1 位小数）。超上限向上取整、低于下限向下取整 —— 四舍五入会把 90.04s 显示成
/// 「90.0s」，配上「需 3–90 秒」看起来像合格（code review）。
String _durationSeconds(int ms) {
  final tenths = ms > kVideoMaxDurationMs ? (ms / 100).ceil() : (ms / 100).floor();
  return (tenths / 10).toStringAsFixed(1);
}

/// 实际体积（MB，1 位小数，向上取整：超限的才会显示，不能被舍成「100.0」）。
String _sizeMb(int bytes) => ((bytes / (1024 * 1024 / 10)).ceil() / 10).toStringAsFixed(1);

String _codecName(String? fourcc) {
  switch (fourcc) {
    case 'hvc1':
    case 'hev1':
      return 'HEVC';
    case 'av01':
      return 'AV1';
    case 'vp09':
      return 'VP9';
    case null:
      return '?';
    default:
      return fourcc;
  }
}

/// `mm:ss`。
String formatVideoDuration(int ms) {
  final s = (ms / 1000).round();
  return '${(s ~/ 60).toString().padLeft(1, '0')}:${(s % 60).toString().padLeft(2, '0')}';
}

/// 视频规格（专题线 video Story 1.5 · FR-7 · addendum A）。
///
/// 第一期 App 不压缩视频（决策 V-33）：运营按《官方视频导出规格》在电脑上导出，App 只**检查**、原样上传。
/// 宽高一律按**显示方向**（旋转后）—— 与后端发帖校验、入库口径一致。
library;

/// 时长下限（含）。
const int kVideoMinDurationMs = 3000;

/// 时长上限（含）。
const int kVideoMaxDurationMs = 90000;

/// 体积上限（含），与后端 PostObject 策略、发帖 HEAD 校验一致（决策 V-30）。
const int kVideoMaxBytes = 104857600;

/// 显示短边上限（含）。
const int kVideoMaxShortSide = 720;

/// 不合格项。顺序即提示顺序。
enum VideoSpecFailure {
  /// 读不懂（不是 MP4 / 结构损坏 / 读文件失败）。出现时不再报其他项。
  unreadable,

  /// 不是 MP4（例如 QuickTime `.mov`）。
  format,

  /// 时长不在 3–90 秒。
  duration,

  /// 体积超过 100MB。
  size,

  /// 显示短边超过 720。
  resolution,

  /// 视频不是 H.264。
  videoCodec,

  /// 音频不是 AAC（没有音频可以）。
  audioCodec,

  /// HDR（PQ / HLG）。
  hdr,

  /// 不是边下边播（moov 在 mdat 之后）。
  faststart,

  /// 带拍摄地点。
  location,
}

/// 检查结果。合格时 1.6 直接用这里的时长、显示宽高、体积发请求。
class VideoSpecResult {
  const VideoSpecResult({
    required this.failures,
    this.durationMs,
    this.displayWidth,
    this.displayHeight,
    this.sizeBytes,
    this.videoCodec,
  });

  /// 不合格项（有序、去重）；空 = 合格。
  final List<VideoSpecFailure> failures;
  final int? durationMs;
  final int? displayWidth;
  final int? displayHeight;
  final int? sizeBytes;

  /// 视频 sample entry 的 fourcc（如 `avc1`、`hvc1`），用于提示里显示实际编码。
  final String? videoCodec;

  bool get ok => failures.isEmpty;

  static const VideoSpecResult unreadable =
      VideoSpecResult(failures: [VideoSpecFailure.unreadable]);
}

/// 已通过检查、等待上传的视频（只存路径，不读进内存 —— 一致性规则 8）。
class VideoDraft {
  const VideoDraft({
    required this.path,
    required this.durationMs,
    required this.width,
    required this.height,
    required this.sizeBytes,
  });

  final String path;
  final int durationMs;

  /// 显示宽（旋转后）。
  final int width;

  /// 显示高（旋转后）。
  final int height;
  final int sizeBytes;
}

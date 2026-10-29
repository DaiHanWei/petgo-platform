/// 视频帖的视频信息（专题线 video Story 2.3 · 对应后端 `VideoView`，Story 2.1 下发）。
///
/// 🔴 判断是不是视频帖**只看 `mediaType`**（`FeedItem.isVideo` 等），不要用 `video != null` 判断
/// （架构「格式」节）。`video` 只是视频帖附带的播放信息。
library;

/// 媒体类型线格式（与后端 `MediaType` 同名同值）。未知值一律按图文处理。
const String kMediaTypeImage = 'IMAGE';
const String kMediaTypeVideo = 'VIDEO';

class VideoInfo {
  const VideoInfo({required this.url, this.durationMs = 0, this.width = 0, this.height = 0});

  /// 播放地址：公开时为视频分发地址，作者看未公开的帖为签名 URL。⚠️ 不进埋点、不进日志（NFR-4）。
  final String url;

  /// 时长（毫秒）。
  final int durationMs;

  /// 显示宽高（按显示方向）。
  final int width;
  final int height;

  /// 缺 `url` 视为没有视频信息（返回 null，调用方按图文降级）。其余缺字段按 0。
  static VideoInfo? fromJson(Object? raw) {
    if (raw is! Map) return null;
    final url = raw['url'];
    if (url is! String || url.isEmpty) return null;
    int n(Object? v) => v is num ? v.toInt() : 0;
    return VideoInfo(
      url: url,
      durationMs: n(raw['durationMs']),
      width: n(raw['width']),
      height: n(raw['height']),
    );
  }
}

/// `mediaType` 线格式 → 是否视频帖（缺省 / 未知值 → false，按图文处理）。
bool isVideoMediaType(Object? mediaType) => mediaType == kMediaTypeVideo;

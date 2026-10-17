import 'dart:convert';

import '../../../core/analytics/analytics.dart';
import '../../../core/storage/prefs.dart';
import 'video_nav.dart';

/// 播放埋点（专题线 video Story 2.4 · FR-21 · AD-10）。
///
/// 事件名按仓库规范改名（动作在词尾、属性 snake_case，见 cloud-rules-video）：
/// `video_play_started{ttff_ms}` · `video_progress_reported{pct}` · `video_play_completed{loop}` ·
/// `video_buffering_started{duration_ms}` · `video_play_failed{error_type}` ·
/// `video_watch_time_reported{seconds, occurred_at}`。每条都带 `post_id`、`source`；App 版本由埋点 SDK 自动带。
/// 🔴 不带视频地址（签名 URL 尤其不能进埋点，NFR-4）。
typedef VideoEventSink = void Function(String event, Map<String, Object> props);

void _defaultSink(String event, Map<String, Object> props) => Analytics.capture(event, props);

/// 本模块上报的全部事件名（埋点规范测试对这张表做命名校验：事件经 sink 间接上报，
/// `v112_events_test` 的源码扫描抓不到它们）。
const List<String> kVideoPlaybackEvents = [
  'video_play_started',
  'video_progress_reported',
  'video_play_completed',
  'video_buffering_started',
  'video_play_failed',
  'video_watch_time_reported',
];

/// 一次进入播放页的观看会话：纯逻辑（时间由调用方传入），L0 可测。
class VideoWatchSession {
  VideoWatchSession({
    required this.postId,
    required this.source,
    required this.durationMs,
    required this.openedAt,
    VideoEventSink? sink,
  }) : _sink = sink ?? _defaultSink;

  /// 帖子 id；App 内分享页（投影刻意没有 id，AD-15）为 null → 事件不带 `post_id`。
  final int? postId;
  final VideoSource source;

  /// 视频时长（毫秒，来自帖子数据；播放器读到真实时长后用 [updateDuration] 校正）。
  int durationMs;
  /// 点开的时刻（起播耗时的起点）。
  final DateTime openedAt;
  final VideoEventSink _sink;

  bool _started = false;
  final Set<int> _progressReported = <int>{};
  int _loops = 0;
  int _lastPositionMs = 0;

  /// 用户拖动的目标位置：在位置回调追上它之前，不做「绕回 = 循环」判定（旧位置回包会误判）。
  int? _seekTargetMs;
  DateTime? _bufferingSince;

  /// 实际播放计时：只在「画面在播、不在缓冲、App 在前台」时累计。
  DateTime? _countingSince;
  int _watchedMs = 0;
  bool _watchReported = false;

  static const List<int> _progressMarks = [25, 50, 75];

  Map<String, Object> _base() => {'post_id': ?postId, 'source': source.wire};

  void updateDuration(int ms) {
    if (ms > 0) durationMs = ms;
  }

  /// 观看秒数上限 = 时长 × 3（防循环挂机虚高）。
  int get watchCapMs => durationMs * 3;

  /// 已累计的实际播放毫秒（含正在计时的这一段，已封顶）。
  int watchedMs(DateTime now) {
    var ms = _watchedMs;
    final since = _countingSince;
    if (since != null) ms += now.difference(since).inMilliseconds;
    return ms > watchCapMs ? watchCapMs : ms;
  }

  /// 首帧出来：上报起播耗时（每次进入只报一次）。
  void onFirstFrame(DateTime now) {
    if (_started) return;
    _started = true;
    _sink('video_play_started', {..._base(), 'ttff_ms': now.difference(openedAt).inMilliseconds});
  }

  /// 播放状态变化：[counting] = 画面在播、不在缓冲、App 在前台。
  void setCounting(bool counting, DateTime now) {
    final since = _countingSince;
    if (counting && since == null) {
      _countingSince = now;
    } else if (!counting && since != null) {
      _watchedMs += now.difference(since).inMilliseconds;
      _countingSince = null;
    }
  }

  /// 缓冲开始 / 结束（首帧前的加载不算缓冲）。结束时报本次缓冲时长。
  void setBuffering(bool buffering, DateTime now) {
    if (!_started) return;
    final since = _bufferingSince;
    if (buffering && since == null) {
      _bufferingSince = now;
    } else if (!buffering && since != null) {
      _bufferingSince = null;
      _sink('video_buffering_started', {..._base(), 'duration_ms': now.difference(since).inMilliseconds});
    }
  }

  /// 播放进度：25 / 50 / 75 各报一次；位置从尾部绕回开头 = 播完一遍（循环）。
  void onPosition(int positionMs) {
    if (durationMs <= 0) return;
    final target = _seekTargetMs;
    if (target != null) {
      if ((positionMs - target).abs() > 1500) return; // 还是拖动前的旧位置
      _seekTargetMs = null;
    }
    // 绕回：上一刻在最后 1.5 秒内，这一刻回到前 1.5 秒内 —— 不是用户往回拖（拖动走 [onSeek]）。
    if (_lastPositionMs >= durationMs - 1500 && positionMs < 1500 && _lastPositionMs > positionMs) {
      _loops++;
      _sink('video_play_completed', {..._base(), 'loop': _loops});
    }
    _lastPositionMs = positionMs;
    final pct = positionMs * 100 ~/ durationMs;
    for (final m in _progressMarks) {
      if (pct >= m && _progressReported.add(m)) {
        _sink('video_progress_reported', {..._base(), 'pct': m});
      }
    }
  }

  /// 用户拖动进度条：只更新位置基准，不算一次循环。
  void onSeek(int positionMs) {
    _lastPositionMs = positionMs;
    _seekTargetMs = positionMs;
  }

  void onError(String errorType) => _sink('video_play_failed', {..._base(), 'error_type': errorType});

  /// 待补报的观看记录（离开前若 App 被杀，下次启动补报）。
  Map<String, Object> pendingWatch(DateTime now) => {
        ..._base(),
        'seconds': watchedMs(now) ~/ 1000,
        'occurred_at': now.toUtc().toIso8601String(),
      };

  /// 离开播放页：上报本次观看秒数（只报一次）。
  void reportWatchTime(DateTime now) {
    if (_watchReported) return;
    _watchReported = true;
    setCounting(false, now);
    _sink('video_watch_time_reported', pendingWatch(now));
  }
}

/// 观看秒数的「App 被杀」兜底：`shared_preferences` 里只存**最后一条**未上报记录（专题线 video Story 2.4）。
class VideoWatchStore {
  VideoWatchStore(this._prefs);

  final AppPrefs _prefs;

  Future<void> savePending(Map<String, Object> record) => _prefs.setPendingVideoWatch(jsonEncode(record));

  Future<void> clear() => _prefs.clearPendingVideoWatch();

  /// 启动时补报上次没报出去的那条，然后删除。坏数据直接删。
  Future<void> replayPending({VideoEventSink? sink}) async {
    final raw = _prefs.pendingVideoWatch;
    if (raw == null) return;
    await _prefs.clearPendingVideoWatch();
    try {
      final m = (jsonDecode(raw) as Map).cast<String, Object>();
      (sink ?? _defaultSink)('video_watch_time_reported', m);
    } catch (_) {
      // 坏数据：已删，放弃这条。
    }
  }
}

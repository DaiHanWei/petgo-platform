import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:video_player/video_player.dart';

/// 播放器状态快照（专题线 video Story 2.4）。与具体播放库解耦，widget 测试注入假实现。
@immutable
class VideoPlaybackState {
  const VideoPlaybackState({
    this.initialized = false,
    this.playing = false,
    this.buffering = false,
    this.position = Duration.zero,
    this.duration = Duration.zero,
    this.aspectRatio = 9 / 16,
    this.error,
  });

  final bool initialized;
  final bool playing;
  final bool buffering;
  final Duration position;
  final Duration duration;
  final double aspectRatio;

  /// 播放失败的类别（`network` / `decode` / `unknown`）；null = 无错误。
  final String? error;

  /// 画面已经出来（首帧）：初始化完成且已开始播（或位置已走过 0）。
  bool get hasFirstFrame => initialized && (playing || position > Duration.zero);
}

/// 播放控制器接口（专题线 video Story 2.4）：播放页只依赖它，换播放库只换实现。
abstract class VideoPlaybackController {
  ValueListenable<VideoPlaybackState> get state;
  Future<void> initialize();
  Future<void> play();
  Future<void> pause();
  Future<void> seekTo(Duration position);
  Future<void> setVolume(double volume);
  Future<void> setLooping(bool looping);

  /// 画面（未初始化时返回空盒）。
  Widget buildView();
  Future<void> dispose();
}

typedef VideoPlaybackControllerFactory = VideoPlaybackController Function(String url);

/// 生产实现的工厂；测试 override 成假控制器。
final videoPlaybackControllerFactoryProvider =
    Provider<VideoPlaybackControllerFactory>((ref) => VideoPlayerPlaybackController.new);

/// 静音（决策 V-23）：**本次打开 App 期间**保持（进程内），App 重启恢复有声 —— 刻意不落盘。
class VideoMutedNotifier extends Notifier<bool> {
  @override
  bool build() => false;

  void toggle() => state = !state;
}

final videoMutedProvider = NotifierProvider<VideoMutedNotifier, bool>(VideoMutedNotifier.new);

/// 基于 `video_player`（系统 ExoPlayer / AVPlayer）的实现。不加磁盘缓存（FR-15）。
class VideoPlayerPlaybackController implements VideoPlaybackController {
  VideoPlayerPlaybackController(String url) : _inner = VideoPlayerController.networkUrl(Uri.parse(url)) {
    _inner.addListener(_sync);
  }

  final VideoPlayerController _inner;
  final ValueNotifier<VideoPlaybackState> _state = ValueNotifier(const VideoPlaybackState());

  @override
  ValueListenable<VideoPlaybackState> get state => _state;

  void _sync() {
    final v = _inner.value;
    _state.value = VideoPlaybackState(
      initialized: v.isInitialized,
      playing: v.isPlaying,
      buffering: v.isBuffering,
      position: v.position,
      duration: v.duration,
      aspectRatio: v.isInitialized && v.aspectRatio > 0 ? v.aspectRatio : 9 / 16,
      error: v.hasError ? _classify(v.errorDescription) : null,
    );
  }

  /// 错误描述只用来分类，不上报原文（可能含地址）。
  static String _classify(String? description) {
    final d = (description ?? '').toLowerCase();
    if (d.contains('network') || d.contains('http') || d.contains('timeout') || d.contains('connect')) {
      return 'network';
    }
    if (d.contains('decod') || d.contains('codec') || d.contains('format')) return 'decode';
    return 'unknown';
  }

  @override
  Future<void> initialize() => _inner.initialize();
  @override
  Future<void> play() => _inner.play();
  @override
  Future<void> pause() => _inner.pause();
  @override
  Future<void> seekTo(Duration position) => _inner.seekTo(position);
  @override
  Future<void> setVolume(double volume) => _inner.setVolume(volume);
  @override
  Future<void> setLooping(bool looping) => _inner.setLooping(looping);
  @override
  Widget buildView() => VideoPlayer(_inner);

  @override
  Future<void> dispose() async {
    _inner.removeListener(_sync);
    await _inner.dispose();
    _state.dispose();
  }
}

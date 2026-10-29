import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/storage/prefs.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_image.dart';
import 'video_analytics.dart';
import 'video_nav.dart';
import 'video_player_controller_provider.dart';

/// 观看记录兜底存储的加载器（测试 override 成返回 null = 不落盘）。
final videoWatchStoreLoaderProvider = Provider<Future<VideoWatchStore?> Function()>(
  (ref) => () async => VideoWatchStore(await AppPrefs.create()),
);

/// 首帧超时（PRD A5）：15 秒内没出画面判失败。
const Duration kVideoFirstFrameTimeout = Duration(seconds: 15);

/// 全屏播放视图（专题线 video Story 2.4 · FR-15）：黑底、按原比例居中、封面 + 转圈、有声自动播放、
/// 单击暂停、进度条可拖、循环、15 秒超时重试、静音会话内记住、后台暂停回前台不续播、离开即释放。
/// 评论抽屉打开时画面占屏幕上方的比例（抽屉高度 = 1 - 该值）。
const double kVideoCompactTopFraction = 0.4;

class VideoPlayerView extends ConsumerStatefulWidget {
  const VideoPlayerView({
    super.key,
    required this.postId,
    required this.url,
    required this.durationMs,
    required this.source,
    this.coverUrl,
    this.overlay,
    this.compact,
    this.initialAspectRatio,
  });

  /// 帖子 id（埋点用）；App 内分享页没有 id → null。
  final int? postId;

  /// ⚠️ 可能是签名 URL：只交给播放器，不进日志 / 埋点。
  final String url;
  final int durationMs;
  final VideoSource source;
  final String? coverUrl;

  /// 播放器初始化前画面区的宽高比（宽 / 高，来自帖子的视频宽高）；null 按 9:16。
  /// 让首帧前的封面就落在最终位置，初始化后不跳。
  final double? initialAspectRatio;

  /// 叠加层（作者、正文、互动 —— Story 2.5）。叠在画面之上、控件之下。
  final Widget? overlay;

  /// 为 true 时把画面收进屏幕上方 [kVideoCompactTopFraction] 区域（评论抽屉打开时，画面不被抽屉挡住）。
  final ValueListenable<bool>? compact;

  @override
  ConsumerState<VideoPlayerView> createState() => _VideoPlayerViewState();
}

class _VideoPlayerViewState extends ConsumerState<VideoPlayerView> with WidgetsBindingObserver {
  VideoPlaybackController? _controller;
  late VideoWatchSession _session;
  VideoWatchStore? _store;
  Timer? _firstFrameTimer;
  Timer? _persistTimer;
  bool _failed = false;
  bool _firstFrameSeen = false;

  /// 本次进入出过首帧（重试换控制器后 [_firstFrameSeen] 会归零，离开时的观看上报看这个）。
  bool _everStarted = false;

  /// 用户拖动后到缓冲结束：这段缓冲是拖动引起的，不算卡顿。
  bool _seeking = false;
  bool _foreground = true;
  bool _wasBuffering = false;
  bool _dragging = false;
  double _dragValue = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _session = VideoWatchSession(
      postId: widget.postId,
      source: widget.source,
      durationMs: widget.durationMs,
      openedAt: DateTime.now(),
    );
    unawaited(ref.read(videoWatchStoreLoaderProvider)().then((s) => _store = s, onError: (Object _) => null));
    _persistTimer = Timer.periodic(const Duration(seconds: 5), (_) => _persistPending());
    _start();
  }

  void _start() {
    final c = ref.read(videoPlaybackControllerFactoryProvider)(widget.url);
    _controller = c;
    _failed = false;
    _firstFrameSeen = false;
    _wasBuffering = false;
    c.state.addListener(_onState);
    _firstFrameTimer?.cancel();
    _firstFrameTimer = Timer(kVideoFirstFrameTimeout, () {
      if (!_firstFrameSeen && mounted) _fail('timeout');
    });
    unawaited(() async {
      try {
        await c.initialize();
        if (!mounted || !identical(_controller, c)) return;
        await c.setLooping(true);
        await c.setVolume(ref.read(videoMutedProvider) ? 0 : 1);
        if (_foreground) await c.play(); // 有声自动播放
      } catch (_) {
        if (mounted && identical(_controller, c)) _fail('network');
      }
    }());
  }

  void _fail(String type) {
    if (_failed) return;
    _firstFrameTimer?.cancel();
    _session.onError(type);
    _session.setCounting(false, DateTime.now());
    setState(() => _failed = true);
    unawaited(_controller?.pause());
  }

  void _onState() {
    final c = _controller;
    if (c == null || !mounted) return;
    final s = c.state.value;
    final now = DateTime.now();
    if (s.error != null) {
      _fail(s.error!);
      return;
    }
    if (s.duration > Duration.zero) _session.updateDuration(s.duration.inMilliseconds);
    if (!_firstFrameSeen && s.hasFirstFrame) {
      _firstFrameSeen = true;
      _everStarted = true;
      _firstFrameTimer?.cancel();
      _session.onFirstFrame(now);
    }
    if (s.buffering != _wasBuffering) {
      _wasBuffering = s.buffering;
      if (!s.buffering) {
        _session.setBuffering(false, now);
        _seeking = false;
      } else if (_firstFrameSeen && !_seeking) {
        // 只算首帧后的卡顿；重试后的加载、拖动引起的缓冲都不算（code review）。
        _session.setBuffering(true, now);
      }
    }
    _session.setCounting(s.playing && !s.buffering && _foreground && _firstFrameSeen, now);
    if (!_dragging) _session.onPosition(s.position.inMilliseconds);
    setState(() {});
  }

  void _persistPending() {
    final store = _store;
    if (store == null || !_everStarted) return;
    unawaited(store.savePending(_session.pendingWatch(DateTime.now())));
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final c = _controller;
    if (c == null) return;
    if (state == AppLifecycleState.resumed) {
      _foreground = true; // 回前台**不自动续播**（FR-15）
      // 例外：初始化期间就进了后台，那时还没开始播 —— 这是首播，不是续播（code review：
      // 否则回来一直转圈，15 秒后误报超时）。
      if (!_firstFrameSeen && !_failed && c.state.value.initialized) {
        unawaited(c.play());
      }
    } else if (state == AppLifecycleState.paused || state == AppLifecycleState.inactive ||
        state == AppLifecycleState.hidden) {
      if (_foreground) {
        _foreground = false;
        unawaited(c.pause());
        _session.setCounting(false, DateTime.now());
        _persistPending();
      }
    }
  }

  void _togglePlay() {
    final c = _controller;
    if (c == null || _failed) return;
    c.state.value.playing ? c.pause() : c.play();
  }

  void _toggleMute() {
    ref.read(videoMutedProvider.notifier).toggle();
    unawaited(_controller?.setVolume(ref.read(videoMutedProvider) ? 0 : 1));
  }

  void _retry() {
    final old = _controller;
    old?.state.removeListener(_onState);
    unawaited(old?.dispose());
    setState(_start);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _firstFrameTimer?.cancel();
    _persistTimer?.cancel();
    if (_everStarted) {
      _session.reportWatchTime(DateTime.now()); // 离开播放页上报观看秒数
      unawaited(_store?.clear());
    }
    final c = _controller;
    c?.state.removeListener(_onState);
    unawaited(c?.dispose()); // 离开即停止并释放
    super.dispose();
  }

  /// 评论抽屉打开时把画面挤到上方（底部留出抽屉高度），关上后动画回到全屏居中。
  Widget _compactFrame(Widget child) {
    final compact = widget.compact;
    if (compact == null) return child;
    return ValueListenableBuilder<bool>(
      valueListenable: compact,
      builder: (context, on, child) {
        final mq = MediaQuery.of(context);
        return AnimatedPadding(
          key: const ValueKey('videoCompactFrame'),
          duration: const Duration(milliseconds: 220),
          curve: Curves.easeOut,
          padding: on
              ? EdgeInsets.only(
                  top: mq.padding.top + 48,
                  bottom: mq.size.height * (1 - kVideoCompactTopFraction),
                )
              : EdgeInsets.zero,
          child: child,
        );
      },
      child: child,
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final muted = ref.watch(videoMutedProvider);
    final s = _controller?.state.value ?? const VideoPlaybackState();
    final durMs = s.duration.inMilliseconds > 0 ? s.duration.inMilliseconds : widget.durationMs;
    final posMs = s.position.inMilliseconds.clamp(0, durMs > 0 ? durMs : 0);
    final showCover = !_firstFrameSeen;
    return ColoredBox(
      color: Colors.black,
      child: Stack(
        fit: StackFit.expand,
        children: [
          _compactFrame(GestureDetector(
            key: const ValueKey('videoTapArea'),
            behavior: HitTestBehavior.opaque,
            onTap: _failed ? _retry : _togglePlay,
            child: Center(
              child: AspectRatio(
                aspectRatio: s.initialized ? s.aspectRatio : (widget.initialAspectRatio ?? s.aspectRatio),
                child: Stack(fit: StackFit.expand, children: [
                  if (_controller != null && s.initialized) _controller!.buildView(),
                  if (showCover && widget.coverUrl != null)
                    // 封面加载失败不影响播放：留黑底（不抛到错误层）。
                    AppImage.widget(widget.coverUrl!,
                        fit: BoxFit.contain, errorBuilder: (_, _, _) => const SizedBox.shrink()),
                ]),
              ),
            ),
          )),
          if (showCover && !_failed)
            const Center(child: CircularProgressIndicator(key: ValueKey('videoLoading'), color: Colors.white)),
          if (_failed)
            Center(
              child: GestureDetector(
                key: const ValueKey('videoRetry'),
                onTap: _retry,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                  decoration: BoxDecoration(color: const Color(0x99000000), borderRadius: BorderRadius.circular(8)),
                  child: Text(l10n.videoPlayFailedRetry, style: const TextStyle(color: Colors.white)),
                ),
              ),
            ),
          if (_firstFrameSeen && !s.playing && !_failed)
            const IgnorePointer(
              child: Center(child: Icon(Icons.play_arrow_rounded, key: ValueKey('videoPausedIcon'), size: 72, color: Colors.white70)),
            ),
          ?widget.overlay,
          Positioned(
            right: 8,
            top: MediaQuery.paddingOf(context).top + 8,
            child: IconButton(
              key: const ValueKey('videoMuteButton'),
              tooltip: muted ? l10n.videoUnmute : l10n.videoMute,
              icon: Icon(muted ? Icons.volume_off_rounded : Icons.volume_up_rounded, color: Colors.white),
              onPressed: _toggleMute,
            ),
          ),
          Positioned(
            left: 8,
            right: 8,
            bottom: MediaQuery.paddingOf(context).bottom + 4,
            child: SliderTheme(
              data: const SliderThemeData(trackHeight: 2, thumbShape: RoundSliderThumbShape(enabledThumbRadius: 6)),
              child: Slider(
                key: const ValueKey('videoProgress'),
                min: 0,
                max: durMs > 0 ? durMs.toDouble() : 1,
                // 真实时长可能比帖子数据略短：一律夹到 [0, max]，避免 Slider 断言（code review）。
                value: (_dragging ? _dragValue : posMs.toDouble()).clamp(0, durMs > 0 ? durMs.toDouble() : 1),
                activeColor: Colors.white,
                inactiveColor: Colors.white24,
                onChangeStart: durMs > 0 && s.initialized ? (v) => setState(() { _dragging = true; _dragValue = v; }) : null,
                onChanged: durMs > 0 && s.initialized ? (v) => setState(() => _dragValue = v) : null,
                onChangeEnd: durMs > 0 && s.initialized
                    ? (v) {
                        setState(() => _dragging = false);
                        _seeking = true;
                        _session.onSeek(v.toInt());
                        unawaited(_controller?.seekTo(Duration(milliseconds: v.toInt())));
                      }
                    : null,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

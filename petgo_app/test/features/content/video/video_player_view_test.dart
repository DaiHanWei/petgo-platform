import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/presentation/content_detail_page.dart';
import 'package:tailtopia/features/content/presentation/detail_providers.dart';
import 'package:tailtopia/features/content/video/video_info.dart';
import 'package:tailtopia/features/content/video/video_nav.dart';
import 'package:tailtopia/features/content/video/video_player_controller_provider.dart';
import 'package:tailtopia/features/content/video/video_player_view.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 假播放器：测试手动推进状态。
class _FakeController implements VideoPlaybackController {
  _FakeController(this.url);

  final String url;
  final ValueNotifier<VideoPlaybackState> _s = ValueNotifier(const VideoPlaybackState());
  final List<String> calls = [];
  double volume = 1;
  bool disposed = false;
  bool failInit = false;

  @override
  ValueListenable<VideoPlaybackState> get state => _s;

  void emit(VideoPlaybackState s) => _s.value = s;

  @override
  Future<void> initialize() async {
    calls.add('init');
    if (failInit) throw StateError('x');
  }

  @override
  Future<void> play() async {
    calls.add('play');
    _s.value = VideoPlaybackState(
        initialized: true, playing: true, position: _s.value.position, duration: const Duration(seconds: 10));
  }

  @override
  Future<void> pause() async {
    calls.add('pause');
    _s.value = VideoPlaybackState(
        initialized: true, playing: false, position: _s.value.position, duration: const Duration(seconds: 10));
  }

  @override
  Future<void> seekTo(Duration position) async => calls.add('seek');
  @override
  Future<void> setVolume(double v) async => volume = v;
  @override
  Future<void> setLooping(bool looping) async => calls.add('loop:$looping');
  @override
  Widget buildView() => const SizedBox(key: ValueKey('fakeVideoView'));
  @override
  Future<void> dispose() async => disposed = true;
}

void main() {
  late List<_FakeController> created;
  late List<(String, Map<String, Object>?)> events;
  late ProviderContainer container;

  setUp(() {
    created = [];
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
    container = ProviderContainer(overrides: [
      videoPlaybackControllerFactoryProvider.overrideWithValue((url) {
        final c = _FakeController(url);
        created.add(c);
        return c;
      }),
      videoWatchStoreLoaderProvider.overrideWithValue(() async => null),
    ]);
  });
  tearDown(() {
    Analytics.debugCaptureSink = null;
    container.dispose();
  });

  Widget app(Widget child) => UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('en'),
          home: Scaffold(body: child),
        ),
      );

  Widget view({int postId = 9}) => VideoPlayerView(
        postId: postId,
        url: 'https://v.test/video/1/k.mp4',
        durationMs: 10000,
        source: VideoSource.feed,
      );

  testWidgets('自动有声播放、循环；首帧前封面 + 转圈，首帧后消失', (tester) async {
    await tester.pumpWidget(app(view()));
    await tester.pump();
    final c = created.single;
    expect(c.calls, containsAllInOrder(['init', 'loop:true', 'play']));
    expect(c.volume, 1);
    // play() 已让 initialized && playing → 首帧
    await tester.pump();
    expect(find.byKey(const ValueKey('videoLoading')), findsNothing);
    expect(events.where((e) => e.$1 == 'video_play_started'), hasLength(1));
  });

  testWidgets('单击暂停 / 继续', (tester) async {
    await tester.pumpWidget(app(view()));
    await tester.pump();
    await tester.pump();
    final c = created.single;
    await tester.tap(find.byKey(const ValueKey('videoTapArea')));
    await tester.pump();
    expect(c.calls.last, 'pause');
    expect(find.byKey(const ValueKey('videoPausedIcon')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('videoTapArea')));
    await tester.pump();
    expect(c.calls.last, 'play');
  });

  testWidgets('静音在本次会话内记住：下一个视频沿用', (tester) async {
    await tester.pumpWidget(app(view()));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('videoMuteButton')));
    await tester.pump();
    expect(created.single.volume, 0);
    await tester.pumpWidget(app(const SizedBox()));
    await tester.pumpWidget(app(view(postId: 10)));
    await tester.pump();
    expect(created.last.volume, 0);
    expect(container.read(videoMutedProvider), isTrue);
  });

  testWidgets('15 秒没出首帧 → 失败提示，点击重试重建播放器', (tester) async {
    container.dispose();
    container = ProviderContainer(overrides: [
      videoPlaybackControllerFactoryProvider.overrideWithValue((url) {
        final c = _FakeController(url)..failInit = created.isEmpty;
        created.add(c);
        return c;
      }),
      videoWatchStoreLoaderProvider.overrideWithValue(() async => null),
    ]);
    await tester.pumpWidget(app(view()));
    await tester.pump();
    expect(find.text('Video failed to load. Tap to retry.'), findsOneWidget);
    expect(events.where((e) => e.$1 == 'video_play_failed'), hasLength(1));
    await tester.tap(find.byKey(const ValueKey('videoRetry')));
    await tester.pump();
    await tester.pump();
    expect(created, hasLength(2));
    expect(created.first.disposed, isTrue);
    expect(find.byKey(const ValueKey('videoRetry')), findsNothing);
  });

  testWidgets('首帧超时 15 秒判失败（error_type=timeout）', (tester) async {
    container.dispose();
    container = ProviderContainer(overrides: [
      videoPlaybackControllerFactoryProvider.overrideWithValue((url) {
        final c = _NeverReady(url);
        created.add(c);
        return c;
      }),
      videoWatchStoreLoaderProvider.overrideWithValue(() async => null),
    ]);
    await tester.pumpWidget(app(view()));
    await tester.pump(const Duration(seconds: 14));
    expect(find.byKey(const ValueKey('videoRetry')), findsNothing);
    await tester.pump(const Duration(seconds: 2));
    expect(find.byKey(const ValueKey('videoRetry')), findsOneWidget);
    expect(events.firstWhere((e) => e.$1 == 'video_play_failed').$2!['error_type'], 'timeout');
  });

  testWidgets('App 进后台暂停，回前台不自动续播；离开页面释放并上报观看时长', (tester) async {
    await tester.pumpWidget(app(view()));
    await tester.pump();
    await tester.pump();
    final c = created.single;
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pump();
    expect(c.calls.last, 'pause');
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    expect(c.calls.last, 'pause', reason: '回前台不自动续播');
    await tester.pumpWidget(app(const SizedBox()));
    expect(c.disposed, isTrue);
    expect(events.where((e) => e.$1 == 'video_watch_time_reported'), hasLength(1));
  });

  testWidgets('初始化期间进后台：回前台补一次首播，不误报超时（code review）', (tester) async {
    container.dispose();
    final gate = Completer<void>();
    container = ProviderContainer(overrides: [
      videoPlaybackControllerFactoryProvider.overrideWithValue((url) {
        final c = _GatedInit(url, gate);
        created.add(c);
        return c;
      }),
      videoWatchStoreLoaderProvider.overrideWithValue(() async => null),
    ]);
    await tester.pumpWidget(app(view()));
    await tester.pump();
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    gate.complete();
    await tester.pump();
    final c = created.single;
    expect(c.calls, isNot(contains('play')));
    c.emit(const VideoPlaybackState(initialized: true, duration: Duration(seconds: 10)));
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    expect(c.calls.last, 'play');
    await tester.pump(const Duration(seconds: 16));
    expect(events.where((e) => e.$1 == 'video_play_failed'), isEmpty);
  });

  testWidgets('首帧后出错重试：重试加载不算卡顿；没等到新首帧就离开仍上报观看时长（code review）', (tester) async {
    container.dispose();
    container = ProviderContainer(overrides: [
      videoPlaybackControllerFactoryProvider.overrideWithValue((url) {
        final c = created.isEmpty ? _FakeController(url) : _NeverReady(url); // 重试后的那个出不了首帧
        created.add(c);
        return c;
      }),
      videoWatchStoreLoaderProvider.overrideWithValue(() async => null),
    ]);
    await tester.pumpWidget(app(view()));
    await tester.pump();
    await tester.pump();
    created.single.emit(const VideoPlaybackState(initialized: true, error: 'network'));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('videoRetry')));
    await tester.pump();
    final retried = created.last;
    retried.emit(const VideoPlaybackState(initialized: false, buffering: true));
    retried.emit(const VideoPlaybackState(initialized: false, buffering: false));
    await tester.pump();
    expect(events.where((e) => e.$1 == 'video_buffering_started'), isEmpty);
    await tester.pumpWidget(app(const SizedBox()));
    expect(events.where((e) => e.$1 == 'video_watch_time_reported'), hasLength(1));
  });

  testWidgets('位置超过时长时进度条不越界（code review）', (tester) async {
    await tester.pumpWidget(app(view()));
    await tester.pump();
    created.single.emit(const VideoPlaybackState(
        initialized: true, playing: true, position: Duration(seconds: 12), duration: Duration(seconds: 10)));
    await tester.pump();
    expect(tester.takeException(), isNull);
  });

  testWidgets('详情页按 mediaType 分流：视频帖渲染播放页，src 透传给埋点', (tester) async {
    final video = ContentDetail(
      id: 9,
      authorId: 1,
      authorDeleted: false,
      type: 'DAILY',
      likeCount: 0,
      commentCount: 0,
      liked: false,
      isAuthor: false,
      createdAt: DateTime.utc(2026, 10, 16),
      imageUrls: const ['https://cdn.test/public/1/c.jpg'],
      mediaType: kMediaTypeVideo,
      video: const VideoInfo(url: 'https://v.test/video/1/k.mp4', durationMs: 10000, width: 720, height: 1280),
    );
    container.dispose();
    container = ProviderContainer(overrides: [
      videoPlaybackControllerFactoryProvider.overrideWithValue((url) {
        final c = _FakeController(url);
        created.add(c);
        return c;
      }),
      videoWatchStoreLoaderProvider.overrideWithValue(() async => null),
      detailProvider.overrideWith((ref, id) async => video),
    ]);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: Locale('en'),
        home: ContentDetailPage(postId: 9, knownVideo: true, videoSource: VideoSource.category),
      ),
    ));
    expect(find.byKey(const ValueKey('videoPostLoading')), findsOneWidget);
    await tester.pump();
    await tester.pump();
    expect(find.byKey(const ValueKey('videoPostPage')), findsOneWidget);
    expect(created.single.url, 'https://v.test/video/1/k.mp4');
    expect(events.firstWhere((e) => e.$1 == 'video_play_started').$2!['source'], 'category');
    await tester.pumpWidget(const SizedBox());
  });
}

/// 初始化要等 [gate] 放行的播放器（测「初始化期间进后台」）。
class _GatedInit extends _FakeController {
  _GatedInit(super.url, this.gate);

  final Completer<void> gate;

  @override
  Future<void> initialize() async {
    calls.add('init');
    await gate.future;
  }
}

/// 永远不就绪的播放器（测首帧超时）。
class _NeverReady extends _FakeController {
  _NeverReady(super.url);

  @override
  Future<void> play() async => calls.add('play');
}


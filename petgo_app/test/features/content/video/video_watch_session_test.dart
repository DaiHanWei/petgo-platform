import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/features/content/video/video_analytics.dart';
import 'package:tailtopia/features/content/video/video_nav.dart';

/// L0：专题线 video Story 2.4 —— 播放埋点纯逻辑（起播 / 进度 / 循环 / 缓冲 / 观看秒数 / 补报）。
void main() {
  final t0 = DateTime.utc(2026, 10, 16, 10);
  DateTime at(int ms) => t0.add(Duration(milliseconds: ms));

  late List<(String, Map<String, Object>)> events;
  VideoWatchSession session({int durationMs = 10000, int? postId = 9}) => VideoWatchSession(
        postId: postId,
        source: VideoSource.feed,
        durationMs: durationMs,
        openedAt: t0,
        sink: (e, p) => events.add((e, p)),
      );
  List<Map<String, Object>> of(String e) => [for (final (n, p) in events) if (n == e) p];

  setUp(() => events = []);

  test('起播耗时：只报一次，带 post_id / source', () {
    final s = session();
    s.onFirstFrame(at(1200));
    s.onFirstFrame(at(5000));
    expect(of('video_play_started'), [
      {'post_id': 9, 'source': 'feed', 'ttff_ms': 1200}
    ]);
  });

  test('进度 25 / 50 / 75 各一次（单次进入内）', () {
    final s = session();
    for (final p in [1000, 2600, 2700, 5100, 7600, 8000, 9000]) {
      s.onPosition(p);
    }
    expect(of('video_progress_reported').map((p) => p['pct']), [25, 50, 75]);
  });

  test('播完绕回开头 = 一次循环；往回拖不算', () {
    final s = session();
    s.onPosition(9600);
    s.onPosition(200); // 自然绕回
    s.onPosition(5000);
    s.onSeek(100); // 用户往回拖
    s.onPosition(300);
    s.onPosition(9700);
    s.onPosition(100); // 第二次绕回
    expect(of('video_play_completed').map((p) => p['loop']), [1, 2]);
  });

  test('缓冲：首帧前不算；结束时报时长', () {
    final s = session();
    s.setBuffering(true, at(0));
    s.setBuffering(false, at(800)); // 首帧前
    s.onFirstFrame(at(900));
    s.setBuffering(true, at(2000));
    s.setBuffering(false, at(2600));
    expect(of('video_buffering_started'), [
      {'post_id': 9, 'source': 'feed', 'duration_ms': 600}
    ]);
  });

  test('观看秒数只计实际播放（暂停 / 后台 / 缓冲不计），离开时报一次', () {
    final s = session();
    s.setCounting(true, at(0));
    s.setCounting(false, at(4000)); // 暂停
    s.setCounting(true, at(10000));
    s.setCounting(false, at(13500)); // 缓冲 / 后台
    s.reportWatchTime(at(20000));
    s.reportWatchTime(at(30000));
    final w = of('video_watch_time_reported');
    expect(w, hasLength(1));
    expect(w.single['seconds'], 7);
    expect(w.single['occurred_at'], at(20000).toIso8601String());
  });

  test('观看秒数封顶 = 时长 × 3', () {
    final s = session(durationMs: 10000);
    s.setCounting(true, at(0));
    s.reportWatchTime(at(600000));
    expect(of('video_watch_time_reported').single['seconds'], 30);
  });

  test('分享页没有帖子 id：事件不带 post_id', () {
    session(postId: null).onFirstFrame(at(100));
    expect(of('video_play_started').single.containsKey('post_id'), isFalse);
  });

  test('失败：error_type', () {
    session().onError('timeout');
    expect(of('video_play_failed').single['error_type'], 'timeout');
  });

  test('App 被杀兜底：只存最后一条，启动补报后删除', () async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await AppPrefs.create();
    final store = VideoWatchStore(prefs);
    final s = session();
    s.setCounting(true, at(0));
    await store.savePending(s.pendingWatch(at(3000)));
    await store.savePending(s.pendingWatch(at(5000))); // 覆盖：只留最后一条
    await store.replayPending(sink: (e, p) => events.add((e, p)));
    expect(of('video_watch_time_reported').single['seconds'], 5);
    expect(prefs.pendingVideoWatch, isNull);
    events.clear();
    await store.replayPending(sink: (e, p) => events.add((e, p)));
    expect(events, isEmpty);
  });

  test('拖动后的旧位置回包不算循环（code review）', () {
    final s = session();
    s.onPosition(9700);
    s.onSeek(200); // 从最后拖回开头
    s.onPosition(9800); // 拖动前发出的旧位置回包
    s.onPosition(300); // 追上目标
    s.onPosition(1000);
    expect(of('video_play_completed'), isEmpty);
  });

  test('事件名符合埋点规范（video_ 前缀、动作在词尾、snake_case）', () {
    final rule = RegExp(r'^video_[a-z0-9_]+_(started|reported|completed|failed)$');
    for (final e in kVideoPlaybackEvents) {
      expect(rule.hasMatch(e), isTrue, reason: e);
    }
  });
}

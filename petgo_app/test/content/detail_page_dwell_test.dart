import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/presentation/content_detail_page.dart';
import 'package:tailtopia/features/content/presentation/detail_providers.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// bug 20260923-539（PRD E-10）：详情页停留时长 `detail_page_dwell`（duration_ms + post_id）。
///
/// 计时器用可手动推进的假实现 —— 真 `Stopwatch` 走墙钟，不受 widget 测试假时间控制。
class _FakeStopwatch implements Stopwatch {
  int _ms = 0;
  bool _running = false;

  /// 模拟真实时间流逝：只有在跑的时候才累计。
  void advance(int ms) {
    if (_running) _ms += ms;
  }

  @override
  void start() => _running = true;
  @override
  void stop() => _running = false;
  @override
  bool get isRunning => _running;
  @override
  int get elapsedMilliseconds => _ms;
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  late List<(String, Map<String, Object>?)> events;
  late _FakeStopwatch watch;

  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
    watch = _FakeStopwatch();
    detailDwellStopwatchFactory = () => watch;
  });
  tearDown(() {
    Analytics.debugCaptureSink = null;
    detailDwellStopwatchFactory = Stopwatch.new;
  });

  List<Map<String, Object>?> dwell() =>
      [for (final (e, p) in events) if (e == 'detail_page_dwell') p];

  Future<void> pumpDetail(WidgetTester tester) async {
    // 详情内容本身不重要（计时壳包在加载 / 错误 / 数据三态外层）—— 用 404 态最省桩。
    await tester.pumpWidget(ProviderScope(
      overrides: [
        detailProvider.overrideWith(
            (ref, id) async => throw const ContentLoadError(ContentLoadErrorKind.gone)),
      ],
      child: const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: ContentDetailPage(postId: 42),
      ),
    ));
    await tester.pumpAndSettle();
  }

  Future<void> leave(WidgetTester tester) async {
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
  }

  void lifecycle(WidgetTester tester, List<AppLifecycleState> states) {
    for (final s in states) {
      tester.binding.handleAppLifecycleStateChanged(s);
    }
  }

  testWidgets('进页计时、离页上报一次：duration_ms + post_id', (tester) async {
    await pumpDetail(tester);
    expect(dwell(), isEmpty, reason: '停留中不报，离页才报');
    watch.advance(3200);
    await leave(tester);

    expect(dwell(), [
      {'duration_ms': 3200, 'post_id': 42},
    ]);
  });

  testWidgets('App 进后台暂停计时、回前台继续（后台那段不算）', (tester) async {
    await pumpDetail(tester);
    watch.advance(1000);
    lifecycle(tester, const [
      AppLifecycleState.inactive,
      AppLifecycleState.hidden,
      AppLifecycleState.paused,
    ]);
    watch.advance(60000); // 锁屏一分钟
    lifecycle(tester, const [
      AppLifecycleState.hidden,
      AppLifecycleState.inactive,
      AppLifecycleState.resumed,
    ]);
    watch.advance(500);
    await leave(tester);

    expect(dwell(), [
      {'duration_ms': 1500, 'post_id': 42},
    ]);
  });

  testWidgets('不设最短阈值：秒退也报', (tester) async {
    await pumpDetail(tester);
    await leave(tester);
    expect(dwell(), [
      {'duration_ms': 0, 'post_id': 42},
    ]);
  });
}

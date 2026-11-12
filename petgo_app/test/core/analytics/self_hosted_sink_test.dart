import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/analytics/anonymous_id.dart';
import 'package:tailtopia/core/analytics/event_queue_file.dart';
import 'package:tailtopia/core/analytics/self_hosted_sink.dart';

/// analytics-pipeline Story 2.1 AC2–AC4、AC6、AC7：自建上报器。
/// 全部用假传输 + 假时钟 + 内存队列，不依赖网络、不真实等待。
void main() {
  final t0 = DateTime.utc(2026, 11, 12, 3, 4, 5, 678);

  late DateTime clock;
  late List<String> bodies;
  late List<Object> replies; // SinkResponse 或要抛出的异常
  late MemoryEventQueueStore store;

  Future<SinkResponse> transport(String body) async {
    bodies.add(body);
    final r = replies.isEmpty ? const SinkResponse(202) : replies.removeAt(0);
    if (r is SinkResponse) return r;
    throw r;
  }

  SelfHostedSink makeSink({bool enabled = true}) => SelfHostedSink.test(
        enabled: enabled,
        transport: transport,
        now: () => clock,
        store: store,
      );

  List<Map<String, dynamic>> batchOf(String body) =>
      ((jsonDecode(body) as Map<String, dynamic>)['batch'] as List).cast<Map<String, dynamic>>();

  Future<void> settle() async {
    for (var i = 0; i < 30; i++) {
      await Future<void>.delayed(Duration.zero);
    }
  }

  setUp(() {
    clock = t0;
    bodies = [];
    replies = [];
    store = MemoryEventQueueStore();
  });

  Future<SelfHostedSink> started({bool enabled = true}) async {
    final s = makeSink(enabled: enabled);
    await s.start();
    await settle();
    addTearDown(s.dispose);
    return s;
  }

  group('信封（AC3）', () {
    test('字段齐全、外层 camelCase、属性原样（snake_case）；发送时同批同一 sentAt', () async {
      final s = await started();
      s.enqueue('toko_add_to_cart_tapped', {'product_token': 'p_1', 'price': 50000});
      s.enqueue('toko_page_viewed', null);
      clock = t0.add(const Duration(seconds: 3));
      await s.flush();
      expect(bodies, hasLength(1));
      final root = jsonDecode(bodies.single) as Map<String, dynamic>;
      expect(root.keys, ['batch']);
      final b = batchOf(bodies.single);
      expect(b, hasLength(2));
      final e = b.first;
      expect(e.keys.toSet(), {
        'messageId', 'event', 'timestamp', 'anonymousId', 'userId', 'properties', 'context', 'sentAt',
      });
      expect(e['messageId'], matches(RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')));
      expect(e['messageId'], isNot(b[1]['messageId']));
      expect(e['event'], 'toko_add_to_cart_tapped');
      expect(e['timestamp'], '2026-11-12T03:04:05.678Z');
      expect(e['sentAt'], '2026-11-12T03:04:08.678Z');
      expect(b[1]['sentAt'], e['sentAt']);
      expect(e['anonymousId'], AnonymousId.value);
      expect(e['userId'], isNull);
      expect(e['context'], <String, dynamic>{});
      expect(e['properties'], {'product_token': 'p_1', 'price': 50000});
      expect(b[1]['properties'], <String, dynamic>{});
    });

    test('isoUtcMillis：非 UTC 输入转 UTC、毫秒三位、带 Z', () {
      expect(isoUtcMillis(DateTime.utc(2026, 1, 2, 3, 4, 5, 6, 789)), '2026-01-02T03:04:05.006Z');
      final local = DateTime(2026, 11, 12, 10);
      expect(isoUtcMillis(local), endsWith('Z'));
      expect(DateTime.parse(isoUtcMillis(local)), local.toUtc());
    });

    test('单条序列化 >32 KB 直接丢弃并计数，不进队列', () async {
      final s = await started();
      final big = {for (var i = 0; i < 700; i++) 'k_$i': 'x' * 60};
      s.enqueue('big_event', big);
      s.enqueue('small_event', {'a': 1});
      expect(s.droppedCount, 1);
      final q = await store.readAll();
      expect(q.map((e) => e['event']), ['small_event']);
    });

    test('属性不可 JSON 编码时丢弃该条，不抛', () async {
      final s = await started();
      expect(() => s.enqueue('weird', {'at': DateTime(2026)}), returnsNormally);
      expect(s.droppedCount, 1);
      expect(await store.readAll(), isEmpty);
    });
  });

  group('攒批触发（AC4）', () {
    test('累计 20 条触发发送；19 条不触发', () async {
      final s = await started();
      bodies.clear();
      for (var i = 0; i < 19; i++) {
        s.enqueue('e', {'i': i});
      }
      await settle();
      expect(bodies, isEmpty);
      s.enqueue('e', {'i': 19});
      await settle();
      expect(bodies, hasLength(1));
      expect(batchOf(bodies.single), hasLength(20));
      expect(await store.readAll(), isEmpty);
    });

    testWidgets('30 秒周期定时器触发（假时间）', (tester) async {
      final s = SelfHostedSink.test(transport: transport, now: () => clock, store: store);
      await s.start();
      await tester.pump();
      s.enqueue('e', null);
      await tester.pump(const Duration(seconds: 29));
      expect(bodies, isEmpty);
      await tester.pump(const Duration(seconds: 1));
      expect(bodies, hasLength(1));
      s.dispose(); // 不取消则框架以「A Timer is still pending」判失败
    });

    test('进后台 paused / hidden 立即发送', () async {
      final s = await started();
      s.enqueue('e1', null);
      s.didChangeAppLifecycleState(AppLifecycleState.paused);
      await settle();
      expect(bodies, hasLength(1));
      s.enqueue('e2', null);
      s.didChangeAppLifecycleState(AppLifecycleState.hidden);
      await settle();
      expect(bodies, hasLength(2));
      s.enqueue('e3', null);
      s.didChangeAppLifecycleState(AppLifecycleState.inactive);
      await settle();
      expect(bodies, hasLength(2));
    });

    test('冷启动：加载上次未发完的事件后立即补发；启动前入队的事件先缓冲、合并后一起发', () async {
      await store.append([
        {
          'messageId': 'left-over',
          'event': 'e_old',
          'timestamp': isoUtcMillis(t0.subtract(const Duration(hours: 1))),
          'anonymousId': AnonymousId.value,
          'userId': null,
          'properties': <String, Object>{},
          'context': <String, Object>{},
        },
      ]);
      final s = makeSink();
      addTearDown(s.dispose);
      s.enqueue('e_before_start', null);
      expect(s.isStarted, isFalse);
      await s.start();
      await settle();
      expect(bodies, hasLength(1));
      expect(batchOf(bodies.single).map((e) => e['event']), ['e_old', 'e_before_start']);
      expect(await store.readAll(), isEmpty);
    });

    test('队列打开失败降级内存队列，事件照发', () async {
      final s = SelfHostedSink.test(transport: transport, now: () => clock, store: _ThrowingStore());
      addTearDown(s.dispose);
      await s.start();
      await settle();
      s.enqueue('e', null);
      await s.flush();
      expect(bodies, hasLength(1));
    });

    test('在途时的触发合并为结束后再查一次（同时只一个请求在途）', () async {
      final gate = Completer<void>();
      var inFlight = 0;
      var maxInFlight = 0;
      final sent = <int>[];
      final s = SelfHostedSink.test(
        transport: (body) async {
          inFlight++;
          maxInFlight = inFlight > maxInFlight ? inFlight : maxInFlight;
          if (sent.isEmpty) await gate.future;
          sent.add(batchOf(body).length);
          inFlight--;
          return const SinkResponse(202);
        },
        now: () => clock,
        store: store,
      );
      addTearDown(s.dispose);
      await s.start();
      s.enqueue('a', null);
      final first = s.flush();
      await settle();
      s.enqueue('b', null);
      unawaited(s.flush());
      unawaited(s.flush());
      gate.complete();
      await first;
      await settle();
      expect(maxInFlight, 1);
      expect(sent, [1, 1]);
      expect(await store.readAll(), isEmpty);
    });

    test('积压分批：每批 ≤100 条且 <450 KB', () async {
      final s = await started();
      // 直接灌队列（绕过 20 条触发），模拟离线积压
      await store.append([
        for (var i = 0; i < 250; i++)
          {
            'messageId': 'm$i',
            'event': 'e',
            'timestamp': isoUtcMillis(t0),
            'anonymousId': AnonymousId.value,
            'userId': null,
            'properties': {'pad': 'x' * 60, 'pad2': 'y' * 60},
            'context': <String, Object>{},
          },
      ]);
      await s.flush();
      expect(bodies.map((b) => batchOf(b).length), [100, 100, 50]);
      for (final b in bodies) {
        expect(utf8.encode(b).length, lessThan(SelfHostedSink.maxBatchBytes));
      }
      expect(await store.readAll(), isEmpty);
    });

    test('体积上限先到：大事件按 450 KB 切批', () async {
      final s = await started();
      await store.append([
        for (var i = 0; i < 40; i++)
          {
            'messageId': 'm$i',
            'event': 'e',
            'timestamp': isoUtcMillis(t0),
            'anonymousId': AnonymousId.value,
            'properties': {for (var k = 0; k < 400; k++) 'k$k': 'v' * 60},
          },
      ]);
      await s.flush();
      expect(bodies.length, greaterThan(1));
      for (final b in bodies) {
        expect(utf8.encode(b).length, lessThan(SelfHostedSink.maxBatchBytes));
      }
      expect(bodies.map((b) => batchOf(b).length).fold<int>(0, (a, n) => a + n), 40);
    });
  });

  group('返回码（AC6）', () {
    Future<SelfHostedSink> withOne() async {
      final s = await started();
      s.enqueue('e', null);
      return s;
    }

    test('202 删除本批', () async {
      final s = await withOne();
      replies.add(const SinkResponse(202));
      await s.flush();
      expect(await store.readAll(), isEmpty);
    });

    test('429 遵守 Retry-After（秒数），暂停期内所有触发都不发，到期后补发', () async {
      final s = await withOne();
      replies.add(const SinkResponse(429, retryAfter: '120'));
      await s.flush();
      expect(bodies, hasLength(1));
      expect(await store.readAll(), hasLength(1));
      expect(s.pauseUntil, t0.add(const Duration(seconds: 120)));

      clock = t0.add(const Duration(seconds: 119));
      for (var i = 0; i < 20; i++) {
        s.enqueue('more', null);
      }
      s.didChangeAppLifecycleState(AppLifecycleState.paused);
      s.didChangeAppLifecycleState(AppLifecycleState.resumed); // 回前台不清 429 暂停
      await s.flush();
      await settle();
      expect(bodies, hasLength(1));

      clock = t0.add(const Duration(seconds: 120));
      await s.flush();
      expect(bodies, hasLength(2));
      expect(batchOf(bodies.last), hasLength(21));
      expect(await store.readAll(), isEmpty);
    });

    test('429 的 Retry-After 为 HTTP 日期', () async {
      final s = await withOne();
      replies.add(SinkResponse(429, retryAfter: HttpDate.format(t0.add(const Duration(seconds: 90)))));
      await s.flush();
      // HTTP 日期只到秒
      expect(s.pauseUntil!.difference(t0).inSeconds, inInclusiveRange(89, 90));
    });

    test('429 缺 Retry-After 或解析失败 → 默认 60 秒', () async {
      final s = await withOne();
      replies.add(const SinkResponse(429));
      await s.flush();
      expect(s.pauseUntil, t0.add(const Duration(seconds: 60)));
      clock = t0.add(const Duration(seconds: 60));
      replies.add(const SinkResponse(429, retryAfter: 'soon'));
      await s.flush();
      expect(s.pauseUntil, clock.add(const Duration(seconds: 60)));
      expect(await store.readAll(), hasLength(1));
    });

    test('5xx / 网络异常：保留并指数退避（5s 起 ×2 封顶 5 分钟），成功一次归零', () async {
      final s = await withOne();
      final waits = <int>[];
      for (var i = 0; i < 8; i++) {
        replies.add(i.isEven ? const SinkResponse(500) : const SocketException('offline'));
        await s.flush();
        waits.add(s.backoffUntil!.difference(clock).inSeconds);
        // 退避期内不发
        final before = bodies.length;
        await s.flush();
        expect(bodies.length, before);
        clock = s.backoffUntil!;
      }
      expect(waits, [5, 10, 20, 40, 80, 160, 300, 300]);
      expect(await store.readAll(), hasLength(1));

      replies.add(const SinkResponse(202));
      await s.flush();
      expect(await store.readAll(), isEmpty);
      expect(s.backoffUntil, isNull);
      s.enqueue('e2', null);
      replies.add(const SinkResponse(502));
      await s.flush();
      expect(s.backoffUntil!.difference(clock).inSeconds, 5, reason: '成功后退避归零');
    });

    test('退避期内回前台（兼作网络恢复）清退避立即重试', () async {
      final s = await withOne();
      replies.add(const SocketException('offline'));
      await s.flush();
      expect(s.backoffUntil, isNotNull);
      s.didChangeAppLifecycleState(AppLifecycleState.resumed);
      await settle();
      expect(bodies, hasLength(2));
      expect(await store.readAll(), isEmpty);
    });

    test('退避带 ±20% 抖动', () async {
      final s = SelfHostedSink.test(transport: transport, now: () => clock, store: store, jitter: () => 1.2);
      addTearDown(s.dispose);
      await s.start();
      await settle();
      s.enqueue('e', null);
      replies.add(const SinkResponse(500));
      await s.flush();
      expect(s.backoffUntil!.difference(clock), const Duration(seconds: 6));
    });

    test('503 带 Retry-After（管道停用）按 429 同样遵守，保留重发；不带则走指数退避', () async {
      final s = await withOne();
      replies.add(const SinkResponse(503, retryAfter: '60'));
      await s.flush();
      expect(s.pauseUntil, t0.add(const Duration(seconds: 60)));
      expect(await store.readAll(), hasLength(1));
      clock = t0.add(const Duration(seconds: 30));
      await s.flush();
      expect(bodies, hasLength(1));

      clock = t0.add(const Duration(seconds: 60));
      replies.add(const SinkResponse(503));
      await s.flush();
      expect(bodies, hasLength(2));
      expect(s.backoffUntil!.difference(clock).inSeconds, 10, reason: '第二次退避');
      expect(await store.readAll(), hasLength(1));
    });

    for (final code in [400, 413]) {
      test('$code 整批丢弃并计数，后续事件不被卡住', () async {
        final s = await withOne();
        s.enqueue('e2', null);
        replies.add(SinkResponse(code));
        await s.flush();
        expect(await store.readAll(), isEmpty);
        expect(s.droppedCount, 2);
        expect(s.backoffUntil, isNull);
      });
    }

    for (final code in [401, 404, 418]) {
      test('$code（非预期）不丢，按 5xx 退避', () async {
        final s = await withOne();
        replies.add(SinkResponse(code));
        await s.flush();
        expect(await store.readAll(), hasLength(1));
        expect(s.droppedCount, 0);
        expect(s.backoffUntil!.difference(clock).inSeconds, 5);
      });
    }

    test('多批中途失败：已成功批次删除，失败批次及之后保留', () async {
      final s = await started();
      await store.append([
        for (var i = 0; i < 150; i++)
          {'messageId': 'm$i', 'event': 'e', 'timestamp': isoUtcMillis(t0), 'anonymousId': AnonymousId.value},
      ]);
      replies.addAll([const SinkResponse(202), const SinkResponse(500)]);
      await s.flush();
      expect(bodies, hasLength(2));
      final left = await store.readAll();
      expect(left.length, 50);
      expect(left.first['messageId'], 'm100');
    });

    test('传输函数抛任意异常不外溢', () async {
      final s = await withOne();
      replies.add(StateError('boom'));
      await expectLater(s.flush(), completes);
      expect(await store.readAll(), hasLength(1));
    });
  });

  group('开关（AC7）', () {
    testWidgets('enabled=false：enqueue 不写队列、start 不起定时器、flush 不发', (tester) async {
      final s = SelfHostedSink.test(enabled: false, transport: transport, now: () => clock, store: store);
      await s.start();
      s.enqueue('e', {'a': 1});
      s.didChangeAppLifecycleState(AppLifecycleState.paused);
      await s.flush();
      await tester.pump(const Duration(minutes: 1));
      expect(s.isStarted, isFalse);
      expect(bodies, isEmpty);
      expect(await store.readAll(), isEmpty);
      // 没有 dispose：若起了周期定时器，框架会以「A Timer is still pending」判失败。
    });

    test('测试环境（debug）下生产单例不发送：Analytics.capture 不留定时器、不抛', () async {
      expect(SelfHostedSink.instance.enabled, isFalse);
      await Analytics.init();
      await Analytics.capture('toko_add_to_cart_tapped', {'price': 1});
      expect(SelfHostedSink.instance.isStarted, isFalse);
    });

    test('parseRetryAfter：秒数 / HTTP 日期 / 负数 / 垃圾值', () {
      expect(parseRetryAfter('30', t0), const Duration(seconds: 30));
      expect(parseRetryAfter(' 0 ', t0), Duration.zero);
      expect(parseRetryAfter('-1', t0), isNull);
      expect(parseRetryAfter(null, t0), isNull);
      expect(parseRetryAfter('', t0), isNull);
      expect(parseRetryAfter('later', t0), isNull);
      final past = HttpDate.format(t0.subtract(const Duration(minutes: 1)));
      expect(parseRetryAfter(past, t0), Duration.zero);
    });
  });

  group('门面换底（AC2）', () {
    late void Function(String, Map<String, Object>?)? savedSink;
    setUp(() => savedSink = Analytics.debugCaptureSink);
    tearDown(() => Analytics.debugCaptureSink = savedSink);

    test('金额键原样进队列（FR-1；原 PostHog 一路剥金额的逻辑已删除），PII 仍被 scrub', () async {
      final s = await started();
      final clean = Analytics.scrub({
        'price': 5000,
        'price_idr': 10000,
        'total_amount': 250000,
        'coin_used': 300,
        'shipping_fee': 12000,
        'order_amount': 285000,
        'amount': 1,
        'af_revenue': 50000,
        'af_price': 50000,
        'receiver_name': 'Budi',
        'email': 'a@b.c',
      });
      s.enqueue('shop_checkout_submitted', clean);
      final q = await store.readAll();
      expect(q.single['properties'], {
        'price': 5000,
        'price_idr': 10000,
        'total_amount': 250000,
        'coin_used': 300,
        'shipping_fee': 12000,
        'order_amount': 285000,
        'amount': 1,
        'af_revenue': 50000,
        'af_price': 50000,
      });
    });

    test('capture 仍先 scrub 再回调 debugCaptureSink（钩子照常可用）', () async {
      final seen = <String, Map<String, Object>?>{};
      Analytics.debugCaptureSink = (e, p) => seen[e] = p;
      await Analytics.capture('toko_add_to_cart_tapped', {'price': 50000, 'nickname': 'x'});
      expect(seen['toko_add_to_cart_tapped'], {'price': 50000});
    });

    test('analytics.dart 的 capture 不再直调 Posthog().capture，且不再有 posthogMoneyKeys', () {
      final src = File('lib/core/analytics/analytics.dart').readAsStringSync();
      expect(src.contains('Posthog().capture'), isFalse);
      expect(src.contains('posthogMoneyKeys'), isFalse);
      expect(src.contains('SelfHostedSink.instance.enqueue(event, clean)'), isTrue);
    });

    test('sink 用独立 Dio + kApiBaseUrl，不用 dioProvider（避免 AuthInterceptor 401 踢游客）', () {
      final src = File('lib/core/analytics/self_hosted_sink.dart').readAsStringSync();
      expect(src.contains(RegExp(r'ref\.(read|watch)\(\s*dioProvider')), isFalse);
      expect(src.contains('core/network/dio_client.dart'), isFalse, reason: '相对 import，只取 kApiBaseUrl');
      expect(src.contains("import '../network/dio_client.dart' show kApiBaseUrl;"), isTrue);
      expect(src.contains('kApiBaseUrl'), isTrue);
      expect(src.contains("'/api/v1/analytics/batch'"), isTrue);
    });
  });
}

class _ThrowingStore implements EventQueueStore {
  @override
  Future<void> append(List<Map<String, Object?>> events) => throw const FileSystemException('x');
  @override
  Future<List<Map<String, Object?>>> readAll() => throw const FileSystemException('x');
  @override
  Future<void> removeByMessageIds(Set<String> ids) => throw const FileSystemException('x');
  @override
  Future<int> trim(DateTime now) => throw const FileSystemException('x');
}

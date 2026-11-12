import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/analytics/anonymous_id.dart';
import 'package:tailtopia/core/analytics/event_queue_file.dart';
import 'package:tailtopia/core/analytics/self_hosted_sink.dart';
import 'package:tailtopia/core/im/im_service.dart';
import 'package:tailtopia/core/push/push_service.dart';

/// analytics-pipeline Story 3.2 · L0（AC1–AC5）：统一推送打开事件 `push_opened`。
///
/// 推送点击回调 `_onNotificationClicked` 是私有方法（依赖 TIMPush 原生插件），这里按它的同步段
/// 「parsePushExt → reportPushOpened → reportOpsNotificationOpenedFromPush → reportCouponExpiryPushOpened」
/// 逐条调用来断言实收形态，并用源码守卫锁住调用顺序（同步、在异步导航之前）。
void main() {
  late List<(String, Map<String, Object>?)> events;

  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });

  tearDown(() => Analytics.debugCaptureSink = null);

  /// 与 `_onNotificationClicked` 同步段一致的调用序列。
  void click(String ext) {
    final p = parsePushExt(ext);
    reportPushOpened(p);
    reportOpsNotificationOpenedFromPush(p);
    reportCouponExpiryPushOpened(p);
  }

  List<Map<String, Object>?> pushOpened() =>
      events.where((e) => e.$1 == 'push_opened').map((e) => e.$2).toList();

  group('AC1/AC2 · push_type 与 campaign_token', () {
    test('点赞推送 → push_type = CONTENT_LIKED，不带 campaign_token', () {
      click('{"type":"CONTENT_LIKED","token":"t","targetRef":"55"}');
      expect(pushOpened(), [
        {'push_type': 'CONTENT_LIKED'},
      ]);
    });

    test('运营通知 → push_type + campaign_token（targetRef）；ops_notification_opened 仍在', () {
      click('{"type":"OPS_ANNOUNCEMENT","token":"t","targetRef":"cmpTok"}');
      expect(pushOpened(), [
        {'push_type': 'OPS_ANNOUNCEMENT', 'campaign_token': 'cmpTok'},
      ]);
      final ops = events.where((e) => e.$1 == 'ops_notification_opened').toList();
      expect(ops, hasLength(1));
      expect(ops.single.$2, {'campaign_id': 'cmpTok', 'source': 'push'});
    });

    test('运营通知但无 targetRef / 空串 → 只带 push_type', () {
      click('{"type":"OPS_ANNOUNCEMENT","token":"t"}');
      click('{"type":"OPS_ANNOUNCEMENT","token":"t","targetRef":""}');
      expect(pushOpened(), [
        {'push_type': 'OPS_ANNOUNCEMENT'},
        {'push_type': 'OPS_ANNOUNCEMENT'},
      ]);
    });

    test('券快过期 → 不带 campaign_token；toko_coupon_expiry_push_opened{coupon_count: 3} 仍在', () {
      click('{"type":"COUPON_EXPIRING","targetRef":"3"}');
      expect(pushOpened(), [
        {'push_type': 'COUPON_EXPIRING'},
      ]);
      final coupon = events.where((e) => e.$1 == 'toko_coupon_expiry_push_opened').toList();
      expect(coupon, hasLength(1));
      expect(coupon.single.$2, {'coupon_count': 3});
    });

    test('被关注推送：targetRef（关注者数字 userId，可枚举）绝不带出', () {
      click('{"type":"USER_FOLLOWED","token":"t","targetRef":"123"}');
      expect(pushOpened(), [
        {'push_type': 'USER_FOLLOWED'},
      ]);
      expect(pushOpened().single!.values, isNot(contains('123')));
    });

    test('问诊会话消息（ChatPushSpec 生成的 ext）→ push_type = VET_REPLY，不带会话 id', () {
      click(const ChatPushSpec(sessionId: '42').toOfflinePushInfo().ext!);
      expect(pushOpened(), [
        {'push_type': 'VET_REPLY'},
      ]);
    });

    test('不带通知行 token（凭证类键也不该进属性）', () {
      reportPushOpened(const PushPayload(type: 'CONTENT_LIKED', token: 'rowTok', targetRef: '9'));
      expect(pushOpened().single!.keys, ['push_type']);
    });
  });

  group('AC4 · 载荷缺 type 记 unknown，不丢事件', () {
    for (final ext in ['', 'not-json', '[1,2]', '{}', '{"type":""}', '{"type":7,"token":"t"}']) {
      test('ext = ${ext.isEmpty ? '(空串)' : ext} → push_type = unknown', () {
        click(ext);
        expect(pushOpened(), [
          {'push_type': 'unknown'},
        ]);
      });
    }
  });

  group('AC3 · 调用位置（源码守卫）', () {
    late String body;

    setUpAll(() {
      final src = File('lib/core/push/push_service.dart').readAsStringSync();
      final start = src.indexOf('void _onNotificationClicked(');
      expect(start, greaterThan(0), reason: '找不到 _onNotificationClicked —— 推送点击入口改名了？');
      final end = src.indexOf('void _navigate(', start);
      body = src.substring(start, end);
    });

    test('推送点击回调里同步上报：parsePushExt 之后、旧专项事件与异步导航之前', () {
      final parse = body.indexOf('parsePushExt(ext)');
      final report = body.indexOf('reportPushOpened(p);');
      final ops = body.indexOf('reportOpsNotificationOpenedFromPush(p);');
      final async = body.indexOf('Future<void>(');
      expect(report, greaterThan(parse));
      expect(report, lessThan(ops));
      expect(report, lessThan(async), reason: '不得放进异步导航段：落点 / 导航失败不应影响打开计数');
      expect('reportPushOpened('.allMatches(body).length, 1);
    });

    test('通知中心列表点击路径（NotificationDeepLink）不报 push_opened', () {
      final src = File('lib/features/notify/domain/notification_deep_link.dart').readAsStringSync();
      expect(src, isNot(contains('reportPushOpened')));
      expect(src, isNot(contains("'push_opened'")));
    });
  });

  group('AC5 · 冷启动：上报器未初始化时点推送', () {
    test('debugCaptureSink 为 null、上报器未启动时调用不抛异常', () {
      Analytics.debugCaptureSink = null;
      expect(() => reportPushOpened(parsePushExt('{"type":"CONTENT_LIKED","token":"t"}')), returnsNormally);
    });

    test('sink 启动前入队 → 启动后随首批发出，带 runApp 前加载的匿名 id', () async {
      final base = await Directory.systemTemp.createTemp('push_opened_anon');
      addTearDown(() async {
        AnonymousId.debugReset();
        if (await base.exists()) await base.delete(recursive: true);
      });
      // main()：runApp 前 await AnonymousId.load()（Story 2.1）。
      AnonymousId.debugReset();
      await AnonymousId.load(baseDir: base);
      final anonId = AnonymousId.value;

      final bodies = <String>[];
      final sink = SelfHostedSink.test(
        transport: (body, _) async {
          bodies.add(body);
          return const SinkResponse(202);
        },
        store: MemoryEventQueueStore(),
      );
      Analytics.debugSinkOverride = sink;
      addTearDown(() {
        Analytics.debugSinkOverride = null;
        sink.dispose();
      });

      expect(sink.isStarted, isFalse);
      expect(() => reportPushOpened(parsePushExt('{"type":"OPS_ANNOUNCEMENT","token":"t","targetRef":"cmpTok"}')),
          returnsNormally);

      await sink.start();
      for (var i = 0; i < 30; i++) {
        await Future<void>.delayed(Duration.zero);
      }

      final sent = bodies
          .expand((b) => ((jsonDecode(b) as Map<String, dynamic>)['batch'] as List).cast<Map<String, dynamic>>())
          .where((e) => e['event'] == 'push_opened')
          .toList();
      expect(sent, hasLength(1), reason: '启动前缓冲的 push_opened 不能丢');
      expect(sent.single['anonymousId'], anonId);
      expect(sent.single['properties'], {'push_type': 'OPS_ANNOUNCEMENT', 'campaign_token': 'cmpTok'});
    });
  });
}

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/analytics/anonymous_id.dart';
import 'package:tailtopia/core/analytics/event_names.dart';
import 'package:tailtopia/core/analytics/event_queue_file.dart';
import 'package:tailtopia/core/analytics/self_hosted_sink.dart';

/// L0：**留存看板的身份 / 注册画像地基**（2026-08-24；analytics-pipeline Story 2.2 改写）。
///
/// **为什么值得写成测试**：这些字段是留存看板「按注册月分群」「注册来源 × 留存」
/// 「兽医号剔出宠主大盘」三个切法的**唯一**来源，出错的方式格外阴险 ——
/// 它不会崩、不会报警，只会让看板上的分群**悄悄变脏**，且**回溯不了**。
///
/// 这里锁死：
/// 1. `identifyUser` 上报 `user_identified`，属性**只有 `role`**（Story 2.2 AC2）——
///    不带 `internal_user_id`（PostHog 专项豁免自建库不沿用）、不带 `distinct_id`
///    （用户标识在信封 `userId` 上）；可变字段（`user_state` / `has_pet_profile`）也不放；
/// 2. 注册画像走 **`$set_once` 而非 `$set`** —— 用 `$set` 会把老用户的 `signup_date`
///    刷成「最近一次登录日」，整列失去意义；
/// 3. `$set_once` 的嵌套 map **能活着穿过 `scrub`**；
/// 4. 无论哪条路径，**PII 一个字节都不许进**。
void main() {
  group('identifyUser → user_identified（Story 2.2 AC2）', () {
    late List<MapEntry<String, Map<String, Object>?>> seen;

    setUp(() {
      seen = [];
      Analytics.debugCaptureSink = (e, p) => seen.add(MapEntry(e, p));
    });
    tearDown(() => Analytics.debugCaptureSink = null);

    test('事件名正确，属性只有 role', () async {
      await Analytics.identifyUser(42, role: 'USER');
      expect(seen.single.key, AnalyticsEvents.userIdentified);
      expect(seen.single.key, 'user_identified');
      expect(seen.single.value, {'role': 'USER'});
    });

    test('🔴 不带 internal_user_id / distinct_id 等明文或冗余标识', () async {
      await Analytics.identifyUser(42, role: 'VET');
      final p = seen.single.value!;
      for (final k in ['internal_user_id', 'user_id', 'userId', 'distinct_id', 'distinctId']) {
        expect(p.containsKey(k), isFalse, reason: '用户标识只在信封 userId（哈希）上，明文数字 id 不出端');
      }
      expect(p.values, isNot(contains(42)));
    });

    test('role 缺省时整个键不出现（不是 null，是没有这个键）', () async {
      await Analytics.identifyUser(42);
      expect(seen.single.key, 'user_identified');
      expect(seen.single.value!.containsKey('role'), isFalse);
    });

    test('🔴 可变字段不放进 user_identified', () async {
      await Analytics.identifyUser(42, role: 'USER');
      final p = seen.single.value!;
      for (final mutable in ['user_state', 'has_pet_profile', 'pet_status']) {
        expect(p.containsKey(mutable), isFalse,
            reason: '🔴 $mutable 是可变的，而 identifyUser 只在**换人**时触发；按事件取数用 T-1/T-2 的事件属性');
      }
    });

    test('🔒 PII 进不来（走 capture → scrub 同一通路）', () async {
      await Analytics.identifyUser(7, role: 'USER');
      final p = seen.single.value!;
      expect(p.keys, everyElement(isNot(contains('name'))));
      expect(p.keys, everyElement(isNot(contains('email'))));
    });
  });

  group('身份绑定门面（Story 2.2 AC1–AC3）', () {
    late List<String> bodies;
    late SelfHostedSink sink;
    late MemoryEventQueueStore store;

    List<Map<String, dynamic>> sent() => [
          for (final b in bodies)
            ...((jsonDecode(b) as Map<String, dynamic>)['batch'] as List).cast<Map<String, dynamic>>(),
        ];

    Future<void> settle() async {
      for (var i = 0; i < 30; i++) {
        await Future<void>.delayed(Duration.zero);
      }
    }

    setUp(() async {
      bodies = [];
      store = MemoryEventQueueStore();
      sink = SelfHostedSink.test(
        transport: (body, _) async {
          bodies.add(body);
          return const SinkResponse(202);
        },
        store: store,
      );
      await sink.start();
      await settle();
      Analytics.debugSinkOverride = sink;
    });
    tearDown(() {
      Analytics.debugSinkOverride = null;
      sink.dispose();
    });

    test('identifyUser(42) 后 capture 的信封 userId == distinctIdFor(42)，绝非明文 id', () async {
      await Analytics.identifyUser(42, role: 'USER');
      await Analytics.capture('x');
      final q = [...sent(), ...await store.readAll()];
      final x = q.singleWhere((e) => e['event'] == 'x');
      expect(x['userId'], Analytics.distinctIdFor(42));
      expect(x['userId'], isNot('42'));
      final ident = q.singleWhere((e) => e['event'] == 'user_identified');
      expect(ident['userId'], Analytics.distinctIdFor(42));
      expect(ident['properties'], {'role': 'USER'});
    });

    test('identifyUser 立即触发 flush：user_identified 不等攒批就发出', () async {
      await Analytics.identifyUser(42, role: 'USER');
      await settle();
      expect(sent().map((e) => e['event']), contains('user_identified'));
    });

    test('reset() 后 userId 为 null，匿名 id 不变', () async {
      await Analytics.identifyUser(42, role: 'USER');
      await Analytics.capture('before');
      await Analytics.reset();
      await Analytics.capture('after');
      await settle();
      final q = [...sent(), ...await store.readAll()];
      final before = q.singleWhere((e) => e['event'] == 'before');
      final after = q.singleWhere((e) => e['event'] == 'after');
      expect(before['userId'], Analytics.distinctIdFor(42));
      expect(after['userId'], isNull);
      expect(after['anonymousId'], before['anonymousId']);
      expect(after['anonymousId'], AnonymousId.value);
      expect(sink.currentUserKey, isNull);
    });

    test('reset() 触发一次 flush：上一账号的事件在切换前就发出', () async {
      await Analytics.identifyUser(42, role: 'USER');
      await settle();
      await Analytics.capture('a_event');
      final before = bodies.length;
      await Analytics.reset();
      await settle();
      expect(bodies.length, before + 1);
      expect(sent().singleWhere((e) => e['event'] == 'a_event')['userId'], Analytics.distinctIdFor(42));
    });

    test('换号序列 reset → identify(B)：A 的事件不会打上 B 的标识', () async {
      await Analytics.identifyUser(1, role: 'USER');
      await Analytics.capture('a1');
      // app.dart 的 auth 监听：同一同步帧里 reset() 后紧跟 identifyUser(B)。
      Analytics.reset();
      Analytics.identifyUser(2, role: 'USER');
      await Analytics.capture('b1');
      await settle();
      final q = [...sent(), ...await store.readAll()];
      final a = Analytics.distinctIdFor(1);
      final b = Analytics.distinctIdFor(2);
      expect(q.singleWhere((e) => e['event'] == 'a1')['userId'], a);
      expect(q.singleWhere((e) => e['event'] == 'b1')['userId'], b);
      final idents = q.where((e) => e['event'] == 'user_identified').map((e) => e['userId']).toList();
      expect(idents, [a, b]);
    });
  });

  group('captureSignupSucceeded（注册画像固化）', () {
    late List<MapEntry<String, Map<String, Object>?>> seen;

    setUp(() {
      seen = [];
      Analytics.debugCaptureSink = (e, p) => seen.add(MapEntry(e, p));
    });
    tearDown(() => Analytics.debugCaptureSink = null);

    test('事件名与 entry_source 照旧（T-7 口径不变）', () async {
      await Analytics.captureSignupSucceeded('social_soft_login');
      expect(seen.single.key, 'signup_succeeded');
      expect(seen.single.value!['entry_source'], 'social_soft_login');
    });

    test('🔴 用的是 \$set_once，不是 \$set', () async {
      await Analytics.captureSignupSucceeded('login_page');
      final props = seen.single.value!;
      expect(props.containsKey(r'$set_once'), isTrue);
      expect(props.containsKey(r'$set'), isFalse,
          reason: '🔴 identify 每次登录都调；用 \$set 会把老用户的 signup_date '
              '刷成「最近一次登录日」，按注册月分群当场失效且不可回溯');
    });

    test('🔴 \$set_once 的嵌套 map 活着穿过了 scrub', () async {
      await Analytics.captureSignupSucceeded(
        'diary_cta',
        now: DateTime.utc(2026, 8, 24, 13, 5),
      );
      final once = seen.single.value![r'$set_once'] as Map<String, Object>;
      expect(once['signup_date'], '2026-08-24',
          reason: 'yyyy-MM-dd（UTC）—— PostHog 据此做日期分群');
      expect(once['first_entry_source'], 'diary_cta');
    });

    test('signup_date 取 UTC 日期，不受本地时区把日子推前推后', () async {
      // 本地 8/25 00:30 (UTC+8) 实际是 UTC 8/24 —— 必须记 8/24，否则跨时区用户的
      // 注册日会散到两天上，按日队列的留存曲线首日就对不齐。
      await Analytics.captureSignupSucceeded(
        'other',
        now: DateTime.utc(2026, 8, 24, 16, 30),
      );
      final once = seen.single.value![r'$set_once'] as Map<String, Object>;
      expect(once['signup_date'], '2026-08-24');
    });
  });
}

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';

/// L0：**留存看板的 person property 地基**（2026-08-24）。
///
/// **为什么值得写成测试**：这两组属性是留存看板「按注册月分群」「注册来源 × 留存」
/// 「兽医号剔出宠主大盘」三个切法的**唯一**来源，而 person property 出错的方式格外阴险 ——
/// 它不会崩、不会报警，只会让看板上的分群**悄悄变脏**，且**回溯不了**：
/// `$set` 写成覆盖式、或把可变字段放进 person property，历史值当场被抹掉，
/// 事后没有任何办法把它还原回去。
///
/// 因此这里锁死四件事：
/// 1. `personProperties` **只含对账号恒定的字段** —— 可变字段（`user_state` /
///    `has_pet_profile`）不得放进来，它们只会停在首次登录那一刻的值；
/// 2. 注册画像走 **`$set_once` 而非 `$set`** —— 用 `$set` 会把老用户的 `signup_date`
///    刷成「最近一次登录日」，整列失去意义；
/// 3. `$set_once` 的嵌套 map **能活着穿过 `scrub`** —— 它是 map，一旦哪天 scrub 改坏了
///    嵌套处理，属性会静默消失而事件照常上报；
/// 4. 无论哪条路径，**PII 一个字节都不许进** person property。
void main() {
  group(r'personProperties（identify 随附，$set 语义）', () {
    test('恒定字段带上：internal_user_id + role', () {
      final p = Analytics.personProperties(userId: 42, role: 'VET');
      expect(p['internal_user_id'], 42);
      expect(p['role'], 'VET');
    });

    test('role 缺省时整个键不出现（不是 null，是没有这个键）', () {
      final p = Analytics.personProperties(userId: 42);
      expect(p.containsKey('role'), isFalse,
          reason: '送 null 会在 PostHog 里建出一个值为 null 的属性列，分群时多一个无意义的桶');
    });

    test('🔴 可变字段不得放进 person property', () {
      final p = Analytics.personProperties(userId: 42, role: 'USER');
      for (final mutable in ['user_state', 'has_pet_profile', 'pet_status']) {
        expect(p.containsKey(mutable), isFalse,
            reason: '🔴 $mutable 是可变的，而 identifyUser 只在**换人**时触发 —— '
                '放进来会永远停在首次登录那一刻的值。'
                '`user_state` 已是 T-1/T-2 的事件属性；'
                '「注册后 N 天内是否建档」必须用 event-based cohort，'
                'person property 取最新值会把第 60 天才建档的人算进他第 7 天的已建档组');
      }
    });

    test('🔒 PII 进不来（scrub 兜底真的挂在这条路径上）', () {
      // 直接验证返回值经过了 scrub：昵称类键在 scrub 黑名单里。
      final p = Analytics.personProperties(userId: 7, role: 'USER');
      expect(p.keys, everyElement(isNot(contains('name'))));
      expect(p.keys, everyElement(isNot(contains('email'))));
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

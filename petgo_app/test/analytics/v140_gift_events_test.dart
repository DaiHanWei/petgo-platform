import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';

/// V1.4.0 ③ 送礼 · Story 1.3 · AC6（L0）：PRD §4 埋点**口径名 → 代码实际名**逐条对账 + 属性键体检。
///
/// ⚠️ 左边是 PRD 的口径名，右边是代码里的实际名。两条不同不是笔误：仓库埋点命名守卫
/// （`v112_events_test.dart`）要求「模块前缀 + 对象 + 动作词尾」，`_paid` / `_amount` 不在词尾白名单里。
/// **改名迁就规则，不放宽规则**（同 `v140_events_inventory_test.dart` 的口径；那个文件是 Toko 线的，不往里加）。
/// 这份映射就是给看板配置用的对照表。`gift_wall_opened` / `gift_sender_list_opened` 由 Story 2.2 补入（礼物页）。
void main() {
  const inventory = <String, String>{
    'gift_panel_opened': 'gift_panel_opened',
    'gift_send_tapped': 'gift_send_tapped',
    'gift_paid': 'gift_payment_succeeded',
    'gift_qris_blocked_low_amount': 'gift_qris_low_amount_shown',
    'gift_locked_block_tapped': 'gift_locked_block_tapped',
    'gift_wall_opened': 'gift_wall_opened',
    'gift_sender_list_opened': 'gift_sender_list_opened',
  };

  /// 每个事件必须带的属性键（PRD §4）。
  const requiredProps = <String, List<String>>{
    'gift_panel_opened': ['source', 'pet_id', 'is_multi_pet_post'],
    'gift_send_tapped': ['gift_id', 'quantity', 'total_amount', 'is_exclusive'],
    'gift_payment_succeeded': ['gift_id', 'quantity', 'total_amount', 'channel'],
    'gift_qris_low_amount_shown': ['total_amount'],
    'gift_locked_block_tapped': ['required_level'],
    'gift_wall_opened': ['is_owner'],
    'gift_sender_list_opened': ['is_owner'],
  };

  /// `lib/features/gift/` 下每个 `Analytics.capture('…', {…})` 调用：事件名 → 属性键。
  Map<String, Set<String>> giftCalls() {
    final out = <String, Set<String>>{};
    for (final f in Directory('lib/features/gift').listSync(recursive: true).whereType<File>()) {
      if (!f.path.endsWith('.dart')) continue;
      final src = f.readAsStringSync();
      for (final m in RegExp(r"Analytics\.capture\(\s*'([a-z0-9_]+)'([^;]*?)\);", dotAll: true)
          .allMatches(src)) {
        final keys = RegExp(r"'([a-z0-9_]+)':\s").allMatches(m.group(2)!).map((k) => k.group(1)!);
        (out[m.group(1)!] ??= <String>{}).addAll(keys);
      }
    }
    return out;
  }

  test('🔴 PRD §4 口径名逐条都有落地实现（声明与实现不许脱节）', () {
    final calls = giftCalls();
    final missing = <String>[
      for (final e in inventory.entries)
        if (!calls.containsKey(e.value)) '${e.key} → ${e.value}',
    ];
    expect(missing, isEmpty, reason: '清单里声明了、代码里找不到上报点');
  });

  test('每个事件带齐 PRD 属性', () {
    final calls = giftCalls();
    requiredProps.forEach((event, props) {
      expect(calls[event], containsAll(props), reason: '$event 缺属性');
    });
  });

  test('改名两条：口径名本身不出现在源码里（防两个名字并存）', () {
    final calls = giftCalls();
    expect(calls.containsKey('gift_paid'), isFalse);
    expect(calls.containsKey('gift_qris_blocked_low_amount'), isFalse);
  });

  test('🔒 NFR-5：送礼事件属性键不带 PII，且不会被 scrub 剥掉', () {
    const forbidden = [
      'name', 'phone', 'email', 'address', 'nickname', 'avatar', 'recipient', 'receiver',
      'token_value', 'jwt', 'password',
    ];
    final calls = giftCalls();
    for (final entry in calls.entries) {
      for (final key in entry.value) {
        final words = key.split('_').toSet();
        for (final bad in forbidden) {
          final hit = bad.contains('_') ? key.contains(bad) : words.contains(bad);
          expect(hit, isFalse, reason: '${entry.key}.$key 命中禁词 $bad');
        }
        // 键若被 scrub 当成 PII / 自由文本丢掉，看板上就是空的。
        expect(Analytics.scrub({key: 1}).containsKey(key), isTrue,
            reason: '${entry.key}.$key 会被 Analytics.scrub 剥掉');
      }
    }
  });

  test('不进 AppsFlyer 白名单（送礼事件只发 PostHog）', () {
    for (final e in inventory.values) {
      expect(Analytics.isAppsFlyerEvent(e), isFalse, reason: e);
    }
  });
}

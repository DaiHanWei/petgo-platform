import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/features/notify/domain/push_permission_change_reporter.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Story 9.3 · L0：**V1.4.0 全量埋点核对**（§5 清单逐条对账 + NFR-5 全局体检）。
///
/// **为什么这条 story 存在**（L-6 前车之鉴）：V1.1.2 把埋点集中为一条收尾 story 且与改版
/// 同版本发布，导致三项核心指标不可得、PRD 的「唯一裁决指标」与处置原则一并失效。
/// 本版本改为**随各 Epic 分散实现**；分散的代价是易漏，所以必须有这么一条逐项核对。
///
/// 🔴 这里断言的是**从 `lib/` 真提取出来的字面量**，不是手抄的一份清单 ——
/// 手抄清单会随代码漂移，然后测试恒绿、看板恒空。
void main() {
  /// §5 清单（epics-v1.4.0）→ 仓库实际事件名的映射。
  ///
  /// ⚠️ 左边是 PRD/epics 里的**口径名**，右边是代码里的**实际名**。两者不同不是笔误：
  /// 仓库的埋点命名守卫要求「模块前缀 + 对象 + 动作词尾」（V1.1.2 AC8）。
  /// **改名迁就规则，而不是放宽规则** —— 这份映射就是给看板配置用的对照表。
  const inventory = <String, String>{
    // Epic 1
    'toko_tab_viewed': 'toko_tab_viewed',
    'product_impression': 'toko_product_shown',
    'product_detail_viewed': 'toko_product_detail_viewed',
    'out_of_stock_viewed': 'toko_out_of_stock_shown',
    // Epic 3
    'add_to_cart': 'toko_add_to_cart_tapped',
    'cart_viewed': 'toko_cart_page_viewed',
    'checkout_started': 'toko_checkout_page_viewed',
    'checkout_blocked_out_of_range': 'toko_checkout_out_of_range_shown',
    'order_submitted': 'toko_order_submitted',
    'payment_succeeded': 'toko_order_payment_succeeded',
    'payment_failed': 'toko_order_payment_failed_shown',
    // Epic 4（履约相关）
    'order_detail_viewed': 'toko_order_detail_viewed',
    'receipt_confirmed': 'toko_order_receipt_confirm_succeeded',
    'tracking_opened': 'toko_order_tracking_site_tapped',
    // Epic 5
    'return_requested': 'toko_return_request_submitted',
    // Epic 6
    'repurchase_card_impression': 'toko_repurchase_card_shown',
    'repurchase_card_clicked': 'toko_repurchase_card_tapped',
    'repurchase_card_dismissed': 'toko_repurchase_card_dismiss_tapped',
    'profile_reco_clicked': 'toko_profile_reco_tapped',
    // Epic 9（跨切口收尾）
    'push_permission_revoked': 'notify_push_permission_toggled',
    'triage_to_category_jump': 'triage_category_jump_tapped',
  };

  test('🔴 §5 清单逐条都有落地实现（声明与实现不许脱节）', () {
    final inSource = _eventNamesInSource();
    expect(inSource, isNotEmpty, reason: '一个事件都没提取到 —— 提取逻辑坏了，不是真没埋点');
    final missing = <String>[];
    inventory.forEach((spec, actual) {
      if (!inSource.contains(actual)) missing.add('$spec → $actual');
    });
    expect(missing, isEmpty,
        reason: '🔴 §5 清单里声明了、代码里找不到上报点 —— 上线后这些指标就是空的。'
            'L-6 就是这么发生的');
  });

  test('🔴 Epic 9 的两条跨切口事件确实存在（本 story 自己的交付物）', () {
    final inSource = _eventNamesInSource();
    expect(inSource, contains('notify_push_permission_toggled'),
        reason: '推送疲劳的终点信号 —— 没有它只能看到打开率跌，说不清有多少人彻底退出');
    expect(inSource, contains('triage_category_jump_tapped'),
        reason: 'FR-110 边界侵蚀监控 —— 问诊→商品的跳转占比要能被单独看见');
    expect(PushPermissionChangeReporter.event, 'notify_push_permission_toggled',
        reason: '常量与源码里的字面量脱节了');
  });

  test('🔒 NFR-5 全局体检：整个 lib/ 的埋点属性名都不带 PII / 健康数据', () {
    // ⚠️ 范围是**整个 lib/**，不只是 shop —— Epic 2 引入收货地址后，
    //    NFR-5 的禁记项多了「收货地址 / 收件人姓名 / 履约电话」，而它们会出现在
    //    结算、订单、退货、地址簿多个模块里。
    const forbidden = [
      'name', 'phone', 'email', 'address', 'lat', 'lng', 'ktp',
      'receiver', 'recipient', 'nickname', 'avatar', 'symptom', 'diagnosis',
      'token_value', 'jwt', 'password',
    ];
    // 白名单：形如 `*_name` 但确定不是 PII 的受控字段。空 = 一个例外都没有。
    const allowedDespiteMatch = <String>{};

    final offenders = <String>[];
    for (final f in Directory('lib').listSync(recursive: true).whereType<File>()) {
      if (!f.path.endsWith('.dart')) continue;
      final src = f.readAsStringSync();
      for (final m
          in RegExp(r"Analytics\.capture\([^;]*?\);", dotAll: true).allMatches(src)) {
        for (final p in RegExp(r"'([a-z0-9_]+)':\s").allMatches(m.group(0)!)) {
          final key = p.group(1)!;
          if (allowedDespiteMatch.contains(key)) continue;
          for (final bad in forbidden) {
            if (key.contains(bad)) offenders.add('${f.path} → $key（命中 "$bad"）');
          }
        }
      }
    }
    expect(offenders, isEmpty, reason: '🔴 NFR-5：埋点严禁 PII / 健康数据 / 令牌 / 签名 URL');
  });

  group('推送疲劳终点信号的判定（Story 9.3）', () {
    setUp(() => SharedPreferences.setMockInitialValues({}));

    Future<AppPrefs> prefs() => AppPrefs.create();

    test('🔴 首次记录只写基线，不上报（否则新装用户凭空贡献一堆假撤销）', () async {
      final seen = <MapEntry<String, Map<String, Object>?>>[];
      Analytics.debugCaptureSink = (e, p) => seen.add(MapEntry(e, p));
      addTearDown(() => Analytics.debugCaptureSink = null);

      final reported = await PushPermissionChangeReporter.record(false,
          fromScreen: 'app_launch', prefs: await prefs());
      expect(reported, isFalse);
      expect(seen, isEmpty);
    });

    test('🔴 granted → denied 上报一次（enabled=false），重复检测不灌水', () async {
      final seen = <MapEntry<String, Map<String, Object>?>>[];
      Analytics.debugCaptureSink = (e, p) => seen.add(MapEntry(e, p));
      addTearDown(() => Analytics.debugCaptureSink = null);

      final p = await prefs();
      await PushPermissionChangeReporter.record(true, fromScreen: 'app_launch', prefs: p);
      expect(seen, isEmpty, reason: '基线那一次不算变化');

      expect(
          await PushPermissionChangeReporter.record(false,
              fromScreen: 'settings_page', prefs: p),
          isTrue);
      expect(seen.single.key, 'notify_push_permission_toggled');
      // 🔒 只带受控值
      expect(seen.single.value, {'enabled': false, 'from_screen': 'settings_page'});

      // 再查一次仍是 denied → 不重复上报（不然一天冷启动五次就是五个假撤销）
      seen.clear();
      expect(
          await PushPermissionChangeReporter.record(false,
              fromScreen: 'app_launch', prefs: p),
          isFalse);
      expect(seen, isEmpty);
    });

    test('又开回来也记（撤销率的分母因此更准）', () async {
      final seen = <MapEntry<String, Map<String, Object>?>>[];
      Analytics.debugCaptureSink = (e, p) => seen.add(MapEntry(e, p));
      addTearDown(() => Analytics.debugCaptureSink = null);

      final p = await prefs();
      await PushPermissionChangeReporter.record(false, fromScreen: 'app_launch', prefs: p);
      await PushPermissionChangeReporter.record(true, fromScreen: 'settings_page', prefs: p);
      expect(seen.single.value, {'enabled': true, 'from_screen': 'settings_page'});
    });
  });
}

/// 从 `lib/` 提取所有 `Analytics.capture('<字面量>'` 的事件名。
Set<String> _eventNamesInSource() {
  final re = RegExp(r"""Analytics\.capture\(\s*'([A-Za-z0-9_]+)'""");
  final out = <String>{};
  for (final f in Directory('lib').listSync(recursive: true).whereType<File>()) {
    if (!f.path.endsWith('.dart')) continue;
    for (final m in re.allMatches(f.readAsStringSync())) {
      out.add(m.group(1)!);
    }
  }
  return out;
}

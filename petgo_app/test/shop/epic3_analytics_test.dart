import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// Story 3.10：Epic 3 埋点收口。
///
/// 🔴 **埋点与功能必须同版本发布** —— 功能上线了埋点没跟上，等于这个版本的效果无法度量，
/// 而 V1.4.0 的整个论证（复购引擎值不值得做，A-16）都建立在这批数据上。
///
/// 🔒 **埋点禁带 PII**（NFR-5）：本类逐个属性名核查，不靠「写的时候注意点」。
void main() {
  /// 从 `lib/` 真提取字面量 —— 断言的对象必须是源码，不是测试里手抄的一份清单
  /// （手抄的那份只能证明「我刚写下的字符串等于我刚写下的字符串」）。
  Set<String> capturedEvents() {
    final result = <String>{};
    final re = RegExp(r"""Analytics\.capture\(\s*'([A-Za-z0-9_]+)'""");
    for (final e in Directory('lib').listSync(recursive: true)) {
      if (e is! File || !e.path.endsWith('.dart')) continue;
      for (final m in re.allMatches(e.readAsStringSync())) {
        result.add(m.group(1)!);
      }
    }
    return result;
  }

  /// AC 原文的七个事件 → 本仓库命名护栏下的实际名字。
  ///
  /// 🔴 **改名不是妥协，是护栏要求**：事件名必须「模块前缀 + 动作词尾」，
  /// 产品要能一眼看出是哪个页面的哪个动作（2026-08-04 用户明确要求）。
  /// `add_to_cart` / `payment_failed` 两条都不合规 —— **放宽规则或塞进 legacyEvents 都不行**。
  const mapping = <String, String>{
    'add_to_cart': 'toko_add_to_cart_tapped',
    'cart_viewed': 'toko_cart_page_viewed',
    'checkout_started': 'toko_checkout_page_viewed',
    'checkout_blocked_out_of_range': 'toko_checkout_out_of_range_shown',
    'order_submitted': 'toko_order_submitted',
    'payment_succeeded': 'toko_order_payment_succeeded',
    // `_failed` 不在允许的动作词尾里；报的本就是「向用户展示了失败」这件事
    'payment_failed': 'toko_order_payment_failed_shown',
  };

  test('🔴 AC 要求的七个事件在源码里都存在（声明与实现不许脱节）', () {
    final inSource = capturedEvents();
    expect(inSource, isNotEmpty, reason: '一个事件都没提取到 —— 提取逻辑坏了，不是代码真没埋点');
    mapping.forEach((acName, actual) {
      expect(inSource, contains(actual),
          reason: 'AC 的 $acName 对应的 $actual 没有在 lib/ 里出现');
    });
  });

  test('🔴 归因链闭合：服务端持久化路径存在（AC 二选一里更可靠的那一个）', () {
    // AC：「更可靠的替代实现是在服务端订单行上持久化加购来源，由后台直接出数
    //      （不受客户端事件丢失与广告拦截影响）—— 二选一，但必须有一个」。
    // 本工作线选了服务端：加购时把 entrySource 一路传到 /me/cart/items。
    final repo = File('lib/features/shop/data/cart_repository.dart').readAsStringSync();
    expect(repo, contains('entrySource'),
        reason: '加购不带来源 → 订单行的 entry_source 恒为 null → AB-13B 算不出转化率');
    final toko = File('lib/features/shop/presentation/toko_page.dart').readAsStringSync();
    expect(toko, contains('TOKO_ALL_FEATURED'));
    expect(toko, contains('TOKO_CATEGORY'),
        reason: '区域④ 与品类页是两个入口，混为一谈就算不出「品类页值不值得做」');
  });

  test('🔒 Epic 3 的事件属性不含 PII / 自由文本（NFR-5）', () {
    // 逐个核对本 Epic 新增事件的属性名。收货人、电话、地址是本 Epic 最容易手滑带上的三样。
    const forbidden = [
      'receiver', 'phone', 'address', 'name', 'email', 'kecamatan', 'kode_pos',
    ];
    final shopSources = Directory('lib/features/shop')
        .listSync(recursive: true)
        .whereType<File>()
        .where((f) => f.path.endsWith('.dart'));
    final captureBlocks = <String>[];
    final re = RegExp(r"Analytics\.capture\((?:[^()]|\([^()]*\))*\)", dotAll: true);
    for (final f in shopSources) {
      captureBlocks.addAll(re.allMatches(f.readAsStringSync()).map((m) => m.group(0)!));
    }
    expect(captureBlocks, isNotEmpty);
    for (final block in captureBlocks) {
      for (final word in forbidden) {
        expect(block.contains("'$word"), isFalse, reason: '埋点属性疑似 PII：$block');
      }
    }
  });

  test('🔴 事件名合命名护栏（模块前缀 toko_ + 允许的动作词尾）', () {
    const allowedSuffixes = [
      '_viewed', '_shown', '_tapped', '_selected', '_toggled', '_switched',
      '_succeeded', '_completed', '_submitted', '_started',
    ];
    for (final e in mapping.values) {
      expect(e.startsWith('toko_'), isTrue, reason: '$e 缺少模块前缀');
      expect(allowedSuffixes.any(e.endsWith), isTrue, reason: '$e 的动作词不在词尾');
    }
  });
}

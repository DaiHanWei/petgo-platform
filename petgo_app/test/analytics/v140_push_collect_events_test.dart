import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// V1.4.0 ④ 推送开关与收藏线 · 埋点清单（Story 1.3 建，2.3 / 2.4 追加）。
///
/// 写法照 `v140_events_inventory_test.dart` 的清单对账：断言**从 `lib/` 真提取出来的字面量**，
/// 不是手抄清单 —— 手抄会随代码漂移，然后测试恒绿、看板恒空。
/// ⚠️ `v140_events_inventory_test.dart` 是旧 Toko 线清单，不往里加；本线 8 个事件保留 PRD 原名（P-8）。
void main() {
  /// PRD 口径名 → 仓库实际名（本线一律同名，P-8）。
  const pushCollectInventory = <String, String>{
    // Story 1.3：通知设置页
    'push_settings_viewed': 'push_settings_viewed',
    'push_category_toggled': 'push_category_toggled',
    // Story 2.3：帖子 / 场所收藏按钮（服务端确认成功后报）
    'content_collected': 'content_collected',
    'content_uncollected': 'content_uncollected',
    'place_collected': 'place_collected',
    'place_uncollected': 'place_uncollected',
  };

  test('🔴 ④ 清单逐条在 lib 里有上报点', () {
    final inSource = _eventNamesInSource();
    expect(inSource, isNotEmpty, reason: '一个事件都没提取到 —— 提取逻辑坏了，不是真没埋点');
    final missing = <String>[
      for (final e in pushCollectInventory.entries)
        if (!inSource.contains(e.value)) '${e.key} → ${e.value}',
    ];
    expect(missing, isEmpty, reason: '清单里声明了、代码里找不到上报点 —— 上线后这些指标就是空的');
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

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// V1.5.0 onboarding-recall 埋点清单（首日与新手任务 PRD §4 / 推送与召回 PRD §4）。
///
/// 🔴 断言的是**从 `lib/` 真提取出来的字面量**（同 `v140_events_inventory_test.dart`）：
/// 声明了、代码里找不到上报点 → 上线后指标为空。后续 story 新增事件时往 [v150Events] 里追加。
void main() {
  const v150Events = <String>[
    // Story 1.2 · FR-173 首日推荐页
    'onboarding_recommend_viewed',
    'onboarding_recommend_action',
    'onboarding_recommend_exited',
    // Story 1.3 · FR-175 游客展示页真实宠物区
    'guest_real_pets_viewed',
    'guest_real_pets_tapped',
  ];

  test('🔴 V1.5.0 onboarding-recall 清单里的事件在 lib/ 里都有字面量上报点', () {
    final inSource = _eventNamesInSource();
    expect(inSource, isNotEmpty);
    final missing = [for (final e in v150Events) if (!inSource.contains(e)) e];
    expect(missing, isEmpty, reason: '清单里声明了、代码里找不到上报点');
  });
}

Set<String> _eventNamesInSource() {
  final pattern = RegExp(r"""Analytics\.capture\(\s*'([A-Za-z0-9_]+)'""");
  final result = <String>{};
  for (final entity in Directory('lib').listSync(recursive: true)) {
    if (entity is! File || !entity.path.endsWith('.dart')) continue;
    for (final m in pattern.allMatches(entity.readAsStringSync())) {
      result.add(m.group(1)!);
    }
  }
  return result;
}

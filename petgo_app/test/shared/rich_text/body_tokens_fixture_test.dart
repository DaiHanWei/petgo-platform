import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/shared/rich_text/body_tokens.dart';

/// L0：App 话题解析与后端 `TopicNameRules` 读**同一份**样例（V1.4.2 social-topic · Story 4.4 · AC1.3 · P-1）。
///
/// 🔴 样例文件不存在直接 fail，不许静默跳过 —— 静默跳过等于两边规则从此可以悄悄分叉。
/// `flutter test` 的工作目录是 `petgo_app/`，样例在后端 test resources 下。
void main() {
  final file = File('../petgo-backend/src/test/resources/fixtures/topic-parse-cases.json');

  test('共用样例文件存在且非空', () {
    expect(file.existsSync(), isTrue, reason: '${file.path} 不存在：话题规则两端无法对齐');
  });

  if (!file.existsSync()) return;
  final Map<String, dynamic> root = jsonDecode(file.readAsStringSync()) as Map<String, dynamic>;
  final List<Map<String, dynamic>> cases =
      (root['cases'] as List).map((e) => (e as Map).cast<String, dynamic>()).toList();

  test('样例条数不为 0', () => expect(cases, isNotEmpty));

  for (final c in cases) {
    final String id = c['id'] as String;
    final String input = c['input'] as String;
    if (c['dartSkip'] == true) {
      test('[$id] 跳过（dartSkip：${c['note'] ?? 'Dart 无 NFC / 特殊大小写映射'}）', () {}, skip: true);
      continue;
    }
    test('[$id] effectiveDistinct 的 display / norm 与 effective 出现处 ranges 与后端一致', () {
      final distinct = effectiveDistinct(input);
      expect(distinct.map((t) => t.display).toList(), (c['topics'] as List).cast<String>(),
          reason: '$id topics');
      expect(distinct.map((t) => t.norm).toList(), (c['norms'] as List).cast<String>(),
          reason: '$id norms');
      final expectedRanges = (c['ranges'] as List)
          .map((r) => [(r as List)[0] as int, r[1] as int])
          .toList();
      final actualRanges =
          topicsIn(input).where((t) => t.effective).map((t) => [t.start, t.end]).toList();
      expect(actualRanges, expectedRanges, reason: '$id ranges');
    });
  }
}

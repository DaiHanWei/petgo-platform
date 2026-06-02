import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// Story 7.2 J2/J3：双语 key 完全对齐 + microcopy 规则守门（≤1 emoji / 问诊文案无感叹号 / 红色预警简短）。
///
/// 直接解析 `lib/l10n/app_{en,id}.arb`（cwd=包根）。规则以可执行测试固化，不靠人工自觉。
void main() {
  final en = _loadArb('lib/l10n/app_en.arb');
  final id = _loadArb('lib/l10n/app_id.arb');

  test('J2：en / id 两套 key 完全对齐（无缺漏即漏译）', () {
    final enKeys = _messageKeys(en);
    final idKeys = _messageKeys(id);
    expect(enKeys.difference(idKeys), isEmpty, reason: 'id 缺这些 key: ${enKeys.difference(idKeys)}');
    expect(idKeys.difference(enKeys), isEmpty, reason: 'en 缺这些 key: ${idKeys.difference(enKeys)}');
  });

  test('J3：每条文案最多 1 个 emoji（UX-DR14）', () {
    for (final arb in [en, id]) {
      _messageKeys(arb).forEach((k) {
        final v = arb[k];
        if (v is String) {
          expect(_emojiCount(v) <= 1, isTrue, reason: 'key=$k emoji 过多: "$v"');
        }
      });
    }
  });

  test('J3：问诊文案克制——triage* key 不含感叹号（含红色预警无修辞）', () {
    for (final arb in [en, id]) {
      _messageKeys(arb).where((k) => k.startsWith('triage')).forEach((k) {
        final v = arb[k];
        if (v is String) {
          expect(v.contains('!') || v.contains('！'), isFalse, reason: 'key=$k 含感叹号: "$v"');
        }
      });
    }
  });

  test('J3：红色预警简短无歧义（triageRed* ≤ 120 字符；就医指引可含必要动作语句）', () {
    for (final arb in [en, id]) {
      _messageKeys(arb).where((k) => k.startsWith('triageRed')).forEach((k) {
        final v = arb[k];
        if (v is String) {
          // 无修辞夸张：长度受限（指引类如「请尽快前往最近动物医院」需完整动作，放宽至 120）。
          expect(v.length <= 120, isTrue, reason: 'key=$k 红色预警过长(${v.length}): "$v"');
        }
      });
    }
  });
}

Map<String, dynamic> _loadArb(String path) =>
    (jsonDecode(File(path).readAsStringSync()) as Map).cast<String, dynamic>();

/// 业务文案 key（排除 @@locale 与 @meta 描述 key）。
Set<String> _messageKeys(Map<String, dynamic> arb) =>
    arb.keys.where((k) => !k.startsWith('@')).toSet();

/// 粗略 emoji 计数：常见 emoji 区块码点。
int _emojiCount(String s) {
  var count = 0;
  for (final r in s.runes) {
    final inEmoji = (r >= 0x1F300 && r <= 0x1FAFF) ||
        (r >= 0x2600 && r <= 0x27BF) ||
        (r >= 0x2B00 && r <= 0x2BFF) ||
        r == 0x2728 ||
        r == 0x2764;
    if (inEmoji) count++;
  }
  return count;
}

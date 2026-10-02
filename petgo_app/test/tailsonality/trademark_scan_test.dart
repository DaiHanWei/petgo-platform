import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// V1.3.2 Story 2.2 · AC4：合规静态扫描（App 侧；后端 `TrademarkScanTest` 另扫服务端）。
///
/// 1. 四字母商标词：`lib/**/*.dart`（含内容表、ApiPaths、埋点事件名与属性键）、`lib/l10n/*.arb`、
///    `assets/**` 的文件与目录**名**，**全文含注释**，不区分大小写。
/// 2. 外部人格测试的角色别名：`lib/features/tailsonality/**` 与 ARB 中 `tailsonality*` 键的值，整词、不区分大小写。
///    翻译撞到这些词 → 换说法，不许加白名单。
void main() {
  // 拆开拼，避免本文件自己成为命中源。
  final trademark = RegExp('m' 'bti', caseSensitive: false);

  List<File> filesUnder(String dir, bool Function(String) keep) => Directory(dir)
      .listSync(recursive: true)
      .whereType<File>()
      .where((f) => keep(f.path))
      .toList();

  test('lib/ 下全部 .dart 与 ARB 不出现四字母商标词', () {
    final files = [
      ...filesUnder('lib', (p) => p.endsWith('.dart')),
      ...filesUnder('lib/l10n', (p) => p.endsWith('.arb')),
    ];
    expect(files, isNotEmpty);
    for (final f in files) {
      expect(trademark.hasMatch(f.path), isFalse, reason: f.path);
      expect(trademark.hasMatch(f.readAsStringSync()), isFalse, reason: f.path);
    }
  });

  test('assets/ 下全部文件与目录名不出现四字母商标词', () {
    final entries = Directory('assets').listSync(recursive: true);
    expect(entries, isNotEmpty);
    for (final e in entries) {
      expect(trademark.hasMatch(e.path), isFalse, reason: e.path);
    }
  });

  const aliases = [
    // EN
    'Architect', 'Logician', 'Commander', 'Debater', 'Advocate', 'Mediator', 'Protagonist', 'Campaigner',
    'Logistician', 'Defender', 'Executive', 'Consul', 'Virtuoso', 'Adventurer', 'Entrepreneur', 'Entertainer',
    // ID
    'Arsitek', 'Ahli Logika', 'Komandan', 'Pendebat', 'Advokat', 'Mediator', 'Protagonis', 'Juru Kampanye',
    'Ahli Logistik', 'Pembela', 'Eksekutif', 'Konsul', 'Virtuoso', 'Petualang', 'Pengusaha', 'Penghibur',
    // 待确认 2.2（2026-10-02）：印尼语媒体实际流通的变体译名（INTP「Logikus」、ENFP「Sang Penggerak」；
    // ENTP 也常直接写英文「Debater」，已在 EN 列）。官网 16personalities.com/id 云端出口被拦，未直接核对。
    'Logikus', 'Penggerak',
  ];
  final aliasPattern = RegExp('\\b(${aliases.map(RegExp.escape).join('|')})\\b', caseSensitive: false);

  test('tailsonality 目录与 ARB tailsonality* 值不出现外部人格测试的角色别名', () {
    final files = filesUnder('lib/features/tailsonality', (_) => true);
    expect(files, isNotEmpty);
    for (final f in files) {
      final m = aliasPattern.firstMatch(f.readAsStringSync());
      expect(m, isNull, reason: '${f.path} 命中「${m?.group(0)}」');
    }
    for (final arb in filesUnder('lib/l10n', (p) => p.endsWith('.arb'))) {
      final map = jsonDecode(arb.readAsStringSync()) as Map<String, dynamic>;
      map.forEach((k, v) {
        if (k.startsWith('tailsonality') && v is String) {
          expect(aliasPattern.hasMatch(v), isFalse, reason: '${arb.path} $k');
        }
      });
    }
  });

  test('别名整词匹配：派生词不误伤', () {
    expect(aliasPattern.hasMatch('petualangan seru'), isFalse);
    expect(aliasPattern.hasMatch('Si petualang kecil'), isTrue);
    expect(aliasPattern.hasMatch('born commander'), isTrue);
  });
}

import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/anonymous_id.dart';

/// analytics-pipeline Story 2.1 AC1：匿名 id 存 `analytics/state.json`（Dai 2026-11-12 拍板，不放 prefs / secure storage）。
void main() {
  late Directory base;

  setUp(() async {
    base = await Directory.systemTemp.createTemp('anon_id_test');
    AnonymousId.debugReset();
  });

  tearDown(() async {
    AnonymousId.debugReset();
    if (base.existsSync()) await base.delete(recursive: true);
  });

  File stateFile() => File('${base.path}/analytics/state.json');

  final v4 = RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$');

  test('uuidV4：版本位 4、变体位 8/9/a/b、小写 8-4-4-4-12，且不重复', () {
    final seen = <String>{};
    for (var i = 0; i < 500; i++) {
      final id = uuidV4();
      expect(id, matches(v4));
      seen.add(id);
    }
    expect(seen.length, 500);
  });

  test('首次 load 生成合法 v4 并落盘到 analytics/state.json', () async {
    await AnonymousId.load(baseDir: base);
    expect(AnonymousId.value, matches(v4));
    final saved = jsonDecode(stateFile().readAsStringSync()) as Map<String, dynamic>;
    expect(saved['anonymousId'], AnonymousId.value);
  });

  test('二次 load（模拟重启）值不变', () async {
    await AnonymousId.load(baseDir: base);
    final first = AnonymousId.value;
    AnonymousId.debugReset();
    await AnonymousId.load(baseDir: base);
    expect(AnonymousId.value, first);
  });

  test('存了非法值时重新生成，并保留 state.json 里的其他字段（供 Story 2.3）', () async {
    await Directory('${base.path}/analytics').create(recursive: true);
    stateFile().writeAsStringSync(jsonEncode({'anonymousId': '1-1-1-1-1', 'lastVersion': '1.4.2'}));
    await AnonymousId.load(baseDir: base);
    expect(AnonymousId.value, matches(v4));
    final saved = jsonDecode(stateFile().readAsStringSync()) as Map<String, dynamic>;
    expect(saved['anonymousId'], AnonymousId.value);
    expect(saved['lastVersion'], '1.4.2');
  });

  test('state.json 损坏（非 JSON）时重新生成，不抛', () async {
    await Directory('${base.path}/analytics').create(recursive: true);
    stateFile().writeAsStringSync('{"anonymousId": "abc');
    await AnonymousId.load(baseDir: base);
    expect(AnonymousId.value, matches(v4));
  });

  test('state.json 存在但读 IO 失败：不覆盖写回（Story 2.3 补复审 R4），值留内存', () async {
    // 同名路径放目录 → 存在但 readAsString 抛 FileSystemException。
    await Directory('${base.path}/analytics/state.json').create(recursive: true);
    final read = await AnonymousId.load(baseDir: base);
    expect(read.readable, isFalse);
    expect(AnonymousId.value, matches(v4));
    expect(FileSystemEntity.isDirectorySync(stateFile().path), isTrue);
    expect(File('${stateFile().path}.tmp').existsSync(), isFalse);
  });

  test('load 之前读取：懒生成内存值不抛；load 后以已落盘值为准', () async {
    final early = AnonymousId.value;
    expect(early, matches(v4));
    await Directory('${base.path}/analytics').create(recursive: true);
    const stored = '0f8fad5b-d9cb-469f-a165-70867728950e';
    stateFile().writeAsStringSync(jsonEncode({'anonymousId': stored}));
    await AnonymousId.load(baseDir: base);
    expect(AnonymousId.value, stored);
  });

  test('load 之前读取过、磁盘没有：沿用该内存值写盘（本进程一致）', () async {
    final early = AnonymousId.value;
    await AnonymousId.load(baseDir: base);
    expect(AnonymousId.value, early);
  });

  test('目录不可写：值留在内存继续用，不抛', () async {
    // baseDir 指向一个普通文件 → 建 analytics/ 目录失败。
    final notDir = File('${base.path}/not_a_dir')..writeAsStringSync('x');
    await AnonymousId.load(baseDir: Directory(notDir.path));
    expect(AnonymousId.value, matches(v4));
    final again = AnonymousId.value;
    expect(AnonymousId.value, again);
  });

  test('不依赖 shared_preferences / secure storage（A3 源码锁定）', () {
    final src = File('lib/core/analytics/anonymous_id.dart').readAsStringSync();
    expect(src.contains('package:shared_preferences'), isFalse);
    expect(src.contains('flutter_secure_storage'), isFalse);
    expect(src.contains('petgo.'), isFalse, reason: 'Story 2.3 用 petgo.* 键判定旧版数据，新键不得用该前缀');
  });
}

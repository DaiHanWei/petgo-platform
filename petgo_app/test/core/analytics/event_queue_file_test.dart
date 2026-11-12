import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/event_queue_file.dart';
import 'package:tailtopia/core/analytics/self_hosted_sink.dart' show isoUtcMillis;

/// analytics-pipeline Story 2.1 AC5：本地队列文件，读写串行；上限 1,000 条或 7 天，超出丢最旧。
void main() {
  late Directory dir;
  late EventQueueFile q;
  final now = DateTime.utc(2026, 11, 12, 3);

  Map<String, Object?> ev(String id, {DateTime? at}) => {
        'messageId': id,
        'event': 'toko_add_to_cart_tapped',
        'timestamp': isoUtcMillis(at ?? now),
        'anonymousId': '0f8fad5b-d9cb-469f-a165-70867728950e',
        'properties': {'price': 50000},
      };

  File file() => File('${dir.path}/queue.jsonl');

  setUp(() async {
    dir = await Directory.systemTemp.createTemp('event_queue_test');
    q = EventQueueFile(dir);
  });

  tearDown(() async {
    if (dir.existsSync()) await dir.delete(recursive: true);
  });

  test('空队列 readAll 为空，不抛', () async {
    expect(await q.readAll(), isEmpty);
  });

  test('串行：并发 append 100 次后条数正确、顺序保持、无坏行', () async {
    await Future.wait([for (var i = 0; i < 100; i++) q.append([ev('m$i')])]);
    final all = await q.readAll();
    expect(all.length, 100);
    expect(all.map((e) => e['messageId']), [for (var i = 0; i < 100; i++) 'm$i']);
    for (final line in file().readAsLinesSync()) {
      expect(() => jsonDecode(line), returnsNormally);
    }
  });

  test('交错的 append / remove / readAll 按提交顺序执行', () async {
    final f1 = q.append([ev('a'), ev('b')]);
    final f2 = q.removeByMessageIds({'a'});
    final f3 = q.append([ev('c')]);
    final read = q.readAll();
    await Future.wait([f1, f2, f3]);
    expect((await read).map((e) => e['messageId']), ['b', 'c']);
  });

  test('坏行跳过（被杀时最后一行可能不完整），trim 时整体重写去掉坏行', () async {
    file().writeAsStringSync('${jsonEncode(ev('ok1'))}\nnot json\n{"no":"id"}\n${jsonEncode(ev('ok2'))}\n{"messageId":"tr');
    final all = await q.readAll();
    expect(all.map((e) => e['messageId']), ['ok1', 'ok2']);
    expect(await q.trim(now), 0);
    expect(file().readAsLinesSync().length, 2);
  });

  test('末行断在多字节 UTF-8 字符中间：只跳过该行，队列照常可读可写（复审修复）', () async {
    final partial = utf8.encode('{"messageId":"tr","p":"é');
    file().writeAsBytesSync([...utf8.encode('${jsonEncode(ev('ok1'))}\n'), ...partial.sublist(0, partial.length - 1)]);
    expect((await q.readAll()).map((e) => e['messageId']), ['ok1']);
    expect(await q.trim(now), 0);
    await q.append([ev('ok2')]);
    expect((await q.readAll()).map((e) => e['messageId']), ['ok1', 'ok2']);
  });

  test('按 messageId 删除，其余保留', () async {
    await q.append([ev('a'), ev('b'), ev('c')]);
    await q.removeByMessageIds({'a', 'c', 'zzz'});
    expect((await q.readAll()).map((e) => e['messageId']), ['b']);
    expect(File('${dir.path}/queue.jsonl.tmp').existsSync(), isFalse, reason: '临时文件 rename 后不应残留');
  });

  test('超过 1,000 条丢最旧', () async {
    await q.append([for (var i = 0; i < 1005; i++) ev('m$i')]);
    final dropped = await q.trim(now);
    expect(dropped, 5);
    final all = await q.readAll();
    expect(all.length, kEventQueueMaxEvents);
    expect(all.first['messageId'], 'm5');
    expect(all.last['messageId'], 'm1004');
  });

  test('早于 now − 7 天的丢弃；时间戳不可解析的丢弃', () async {
    await q.append([
      ev('old', at: now.subtract(const Duration(days: 7, minutes: 1))),
      ev('edge', at: now.subtract(const Duration(days: 6, hours: 23))),
      {...ev('bad'), 'timestamp': 'yesterday'},
      ev('new'),
    ]);
    expect(await q.trim(now), 2);
    expect((await q.readAll()).map((e) => e['messageId']), ['edge', 'new']);
  });

  test('已知条数后，追加超上限 10% 自动就地裁剪（离线积压不无界增长）', () async {
    await q.trim(now); // 建立已知条数
    await q.append([for (var i = 0; i < 1101; i++) ev('m$i', at: DateTime.now().toUtc())]);
    expect((await q.readAll()).length, kEventQueueMaxEvents);
  });

  test('单步 IO 异常吞掉，链不断', () async {
    // 把队列文件位置占成目录 → 读写都失败
    Directory('${dir.path}/queue.jsonl').createSync();
    await q.append([ev('a')]);
    expect(await q.readAll(), isEmpty);
    Directory('${dir.path}/queue.jsonl').deleteSync();
    await q.append([ev('b')]);
    expect((await q.readAll()).map((e) => e['messageId']), ['b']);
  });

  test('MemoryEventQueueStore 与文件实现同一套上限规则', () async {
    final m = MemoryEventQueueStore();
    await m.append([ev('old', at: now.subtract(const Duration(days: 8))), ev('a'), ev('b')]);
    expect(await m.trim(now), 1);
    await m.removeByMessageIds({'a'});
    expect((await m.readAll()).map((e) => e['messageId']), ['b']);
  });
}

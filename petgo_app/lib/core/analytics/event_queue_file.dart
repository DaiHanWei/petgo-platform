import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';

/// 本地事件队列的最小接口（analytics-pipeline Story 2.1，A2）。
///
/// 事件是已组装好的信封 map（见 `SelfHostedSink.enqueue`），以 `messageId` 为键删除。
/// 实现必须串行化全部读写，且任何异常吞掉、不外溢。
abstract class EventQueueStore {
  /// 追加（保持顺序）。
  Future<void> append(List<Map<String, Object?>> events);

  /// 读出全部（最旧在前）。坏数据跳过。
  Future<List<Map<String, Object?>>> readAll();

  /// 按 `messageId` 删除。
  Future<void> removeByMessageIds(Set<String> ids);

  /// 裁剪：丢弃 `timestamp` 早于 now − 7 天的，条数超上限时丢最旧。返回丢弃条数。
  Future<int> trim(DateTime now);
}

/// 队列上限（A2）：1,000 条或 7 天，超出丢最旧。
const int kEventQueueMaxEvents = 1000;
const Duration kEventQueueMaxAge = Duration(days: 7);

/// 纯函数裁剪规则，文件实现与内存实现共用。返回保留的事件。
@visibleForTesting
List<Map<String, Object?>> applyQueueLimits(List<Map<String, Object?>> events, DateTime now) {
  final cutoff = now.toUtc().subtract(kEventQueueMaxAge);
  final kept = <Map<String, Object?>>[];
  for (final e in events) {
    final ts = e['timestamp'];
    final t = ts is String ? DateTime.tryParse(ts) : null;
    // 时间戳解析不了的按「已过期」处理：它到后端也必然被拒。
    if (t == null || t.isBefore(cutoff)) continue;
    kept.add(e);
  }
  if (kept.length > kEventQueueMaxEvents) {
    return kept.sublist(kept.length - kEventQueueMaxEvents);
  }
  return kept;
}

/// 文件实现：`<analytics 目录>/queue.jsonl`，每行一条事件 JSON。
///
/// - 所有操作经一条 `Future` 链串行执行；单步异常吞掉，链不断。
/// - 删除 / 裁剪写临时文件后 `rename` 整体替换，被杀也不会留下半个文件。
/// - 读取逐行解析，坏行跳过（追加写到一半被杀时最后一行可能不完整）。
/// - 日志只打条数，绝不打事件内容。
class EventQueueFile implements EventQueueStore {
  EventQueueFile(Directory dir)
      : _file = File('${dir.path}${Platform.pathSeparator}$fileName'),
        _tmp = File('${dir.path}${Platform.pathSeparator}$fileName.tmp');

  static const String fileName = 'queue.jsonl';

  final File _file;
  final File _tmp;
  Future<void> _tail = Future<void>.value();

  /// 已知条数（不含坏行）；未知时为 null。用于追加时超限触发裁剪。
  int? _count;

  Future<T> _serial<T>(Future<T> Function() op, T fallback) {
    final done = Completer<T>();
    _tail = _tail.then((_) async {
      try {
        done.complete(await op());
      } catch (e) {
        debugPrint('[Analytics] queue io failed: ${e.runtimeType}');
        done.complete(fallback);
      }
    });
    return done.future;
  }

  @override
  Future<void> append(List<Map<String, Object?>> events) => _serial(() async {
        if (events.isEmpty) return;
        final buf = StringBuffer();
        for (final e in events) {
          buf.writeln(jsonEncode(e));
        }
        await _file.writeAsString(buf.toString(), mode: FileMode.append, flush: true);
        final c = _count;
        if (c != null) {
          _count = c + events.length;
          // 离线积压时不让文件无界增长：超上限留 10% 余量后就地裁剪。
          if (_count! > kEventQueueMaxEvents + kEventQueueMaxEvents ~/ 10) {
            await _trimLocked(DateTime.now());
          }
        }
      }, null);

  @override
  Future<List<Map<String, Object?>>> readAll() =>
      _serial(() async => (await _read()).events, const <Map<String, Object?>>[]);

  @override
  Future<void> removeByMessageIds(Set<String> ids) => _serial(() async {
        if (ids.isEmpty) return;
        final all = (await _read()).events;
        final kept = [
          for (final e in all)
            if (!ids.contains(e['messageId'])) e,
        ];
        if (kept.length != all.length) await _replace(kept);
      }, null);

  @override
  Future<int> trim(DateTime now) => _serial(() => _trimLocked(now), 0);

  Future<int> _trimLocked(DateTime now) async {
    final read = await _read();
    final kept = applyQueueLimits(read.events, now);
    final dropped = read.events.length - kept.length;
    if (dropped > 0 || read.badLines > 0) {
      await _replace(kept);
    }
    if (dropped > 0) debugPrint('[Analytics] queue trimmed: dropped=$dropped');
    if (read.badLines > 0) debugPrint('[Analytics] queue bad lines skipped: ${read.badLines}');
    return dropped;
  }

  Future<({List<Map<String, Object?>> events, int badLines})> _read() async {
    if (!_file.existsSync()) {
      _count = 0;
      return (events: <Map<String, Object?>>[], badLines: 0);
    }
    // allowMalformed：被杀时末行可能断在多字节 UTF-8 字符中间，严格解码会让整个文件读失败、
    // 队列永久读不出（且只增不减）。宽松解码后那一行只是一条坏行，下面跳过。
    final lines = await _file.readAsLines(encoding: const Utf8Codec(allowMalformed: true));
    final out = <Map<String, Object?>>[];
    var bad = 0;
    for (final line in lines) {
      if (line.trim().isEmpty) continue;
      try {
        final v = jsonDecode(line);
        if (v is Map<String, dynamic> && v['messageId'] is String) {
          out.add(v);
        } else {
          bad++;
        }
      } catch (_) {
        bad++;
      }
    }
    _count = out.length;
    return (events: out, badLines: bad);
  }

  Future<void> _replace(List<Map<String, Object?>> events) async {
    final buf = StringBuffer();
    for (final e in events) {
      buf.writeln(jsonEncode(e));
    }
    await _tmp.writeAsString(buf.toString(), flush: true);
    await _tmp.rename(_file.path);
    _count = events.length;
  }
}

/// 内存实现：队列文件打不开时的进程内兜底，也供测试注入。
class MemoryEventQueueStore implements EventQueueStore {
  final List<Map<String, Object?>> _events = [];

  @override
  Future<void> append(List<Map<String, Object?>> events) async {
    _events.addAll(events);
    if (_events.length > kEventQueueMaxEvents + kEventQueueMaxEvents ~/ 10) {
      await trim(DateTime.now());
    }
  }

  @override
  Future<List<Map<String, Object?>>> readAll() async => List.of(_events);

  @override
  Future<void> removeByMessageIds(Set<String> ids) async =>
      _events.removeWhere((e) => ids.contains(e['messageId']));

  @override
  Future<int> trim(DateTime now) async {
    final kept = applyQueueLimits(_events, now);
    final dropped = _events.length - kept.length;
    _events
      ..clear()
      ..addAll(kept);
    return dropped;
  }
}

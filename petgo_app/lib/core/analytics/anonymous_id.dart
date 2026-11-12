import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

/// 埋点本地数据目录：`getApplicationSupportDirectory()/analytics/`（A3）。
///
/// 匿名 id 的 `state.json` 与本地队列 `queue.jsonl` 都放这里。Android 备份规则
/// （`res/xml/backup_rules.xml` / `data_extraction_rules.xml`）整目录排除，
/// 卸载重装后匿名 id 必然重新生成。目录不存在时创建。
Future<Directory> analyticsDirectory({Directory? baseDir}) async {
  final base = baseDir ?? await getApplicationSupportDirectory();
  final dir = Directory('${base.path}${Platform.pathSeparator}analytics');
  if (!dir.existsSync()) {
    await dir.create(recursive: true);
  }
  return dir;
}

/// UUIDv4（小写 8-4-4-4-12）。**不加依赖**：`uuid` 只是传递依赖，直接 import 会被
/// `depend_on_referenced_packages` 拦下。匿名 id 与事件 `messageId` 共用。
String uuidV4() {
  final rnd = Random.secure();
  final b = List<int>.generate(16, (_) => rnd.nextInt(256));
  b[6] = (b[6] & 0x0f) | 0x40; // 版本位 4
  b[8] = (b[8] & 0x3f) | 0x80; // 变体位 10xx
  final h = b.map((x) => x.toRadixString(16).padLeft(2, '0')).join();
  return '${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-'
      '${h.substring(16, 20)}-${h.substring(20)}';
}

final RegExp _uuidPattern = RegExp(
  r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
);

/// 是否标准 UUID 字符串（小写；与后端 `IngestValidator.requiredUuid` 的判定一致）。
bool isValidUuid(String? s) => s != null && _uuidPattern.hasMatch(s);

/// `analytics/state.json` 的一次读取结果（Story 2.3；补 Story 2.1 复审 R4）。
///
/// 字段：`anonymousId`（Story 2.1）、`installedVersion`（Story 2.3 安装标记 = 上次版本号）。
class AnalyticsStateRead {
  const AnalyticsStateRead._(this.file, this.data, {required this.readable});

  /// 文件路径；`null` = 连 `analytics/` 目录都拿不到。
  final File? file;

  /// 读到的内容；不可读 / 不存在 / 内容损坏时为空。
  final Map<String, Object?> data;

  /// `false`：目录不可用，或文件**存在但读 IO 失败** —— 内容未知，调用方**不得写回**
  /// （以空内容写回会抹掉其他字段：安装标记丢失 → 下次启动误判新装、误报 Installed）。
  /// 文件不存在、或内容损坏（非 JSON / 非对象）时为 `true`：没有可保留的东西，可以覆盖。
  final bool readable;
}

/// 读 `state.json`，不抛异常。见 [AnalyticsStateRead.readable]。
Future<AnalyticsStateRead> readAnalyticsState({Directory? baseDir}) async {
  final File file;
  try {
    final dir = await analyticsDirectory(baseDir: baseDir);
    file = File('${dir.path}${Platform.pathSeparator}${AnonymousId.fileName}');
  } catch (e) {
    debugPrint('[Analytics] state dir unavailable: ${e.runtimeType}');
    return const AnalyticsStateRead._(null, {}, readable: false);
  }
  final String raw;
  try {
    // 用 typeSync 而非 File.existsSync：同名路径是目录等非文件时也算「存在」，按读失败处理。
    if (FileSystemEntity.typeSync(file.path) == FileSystemEntityType.notFound) {
      return AnalyticsStateRead._(file, {}, readable: true);
    }
    raw = await file.readAsString();
  } catch (e) {
    debugPrint('[Analytics] state read failed: ${e.runtimeType}');
    return AnalyticsStateRead._(file, {}, readable: false);
  }
  try {
    final decoded = jsonDecode(raw);
    if (decoded is Map<String, dynamic>) {
      return AnalyticsStateRead._(file, Map<String, Object?>.of(decoded), readable: true);
    }
  } catch (e) {
    debugPrint('[Analytics] state corrupt: ${e.runtimeType}');
  }
  return AnalyticsStateRead._(file, {}, readable: true);
}

/// 把 [fields] 合并进 `state.json`（先重读、保留其他字段，临时文件 + `rename`）。
/// 读不出来（[AnalyticsStateRead.readable] 为 false）时**不写**，返回 false；不抛异常。
Future<bool> updateAnalyticsState(Map<String, Object?> fields, {Directory? baseDir}) async =>
    _writeMerged(await readAnalyticsState(baseDir: baseDir), fields);

Future<bool> _writeMerged(AnalyticsStateRead base, Map<String, Object?> fields) async {
  final file = base.file;
  if (!base.readable || file == null) {
    debugPrint('[Analytics] state write skipped: unreadable');
    return false;
  }
  try {
    final state = {...base.data, ...fields};
    final tmp = File('${file.path}.tmp');
    await tmp.writeAsString(jsonEncode(state), flush: true);
    await tmp.rename(file.path);
    return true;
  } catch (e) {
    debugPrint('[Analytics] state write failed: ${e.runtimeType}');
    return false;
  }
}

/// App 设备匿名 id（analytics-pipeline Story 2.1，A3）。
///
/// - 首次启动生成 UUIDv4，存在 `analytics/state.json` 的 `anonymousId` 字段；
///   **不放 secure storage**（BAD_DECRYPT 历史坑），**也不放 `shared_preferences`**
///   （Android prefs 只能整文件备份，排除不掉；Dai 2026-11-12 拍板）。
/// - 卸载前不变；登出**不重置**，本类不提供 reset。
/// - `state.json` 里的其他字段（Story 2.3 的安装标记、上次版本号）原样保留。
/// - [load] 在 `main.dart` 首个事件入队前 await；之后 [value] 同步可读。
abstract final class AnonymousId {
  static const String fileName = 'state.json';
  static const String field = 'anonymousId';

  static String? _value;

  /// 同步读取。[load] 之前被调用（理论上不会发生）时懒生成一个内存值，不抛异常；
  /// 随后 [load] 以已落盘值为准。
  static String get value => _value ??= uuidV4();

  /// 读 `state.json`；不存在或值非法时生成新值并写回。任何 IO 失败都吞掉，
  /// 值留在内存里继续用（本次进程内一致）。
  ///
  /// 文件存在但读失败时**不写回**（Story 2.3 补 2.1 复审 R4）：否则会以空内容覆盖、抹掉安装标记。
  /// 返回本次读到的内容，供 `main.dart` 取生命周期快照复用（Story 2.3，只读一次文件）。
  static Future<AnalyticsStateRead> load({Directory? baseDir}) async {
    final read = await readAnalyticsState(baseDir: baseDir);
    final stored = read.data[field];
    if (stored is String && isValidUuid(stored)) {
      _value = stored;
      return read;
    }
    final fresh = _value ?? uuidV4();
    _value = fresh;
    await _writeMerged(read, {field: fresh});
    return read;
  }

  @visibleForTesting
  static void debugReset() => _value = null;
}

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
  static Future<void> load({Directory? baseDir}) async {
    Map<String, Object?> state = {};
    File? file;
    try {
      final dir = await analyticsDirectory(baseDir: baseDir);
      file = File('${dir.path}${Platform.pathSeparator}$fileName');
      if (file.existsSync()) {
        final decoded = jsonDecode(await file.readAsString());
        if (decoded is Map<String, dynamic>) state = decoded;
      }
    } catch (e) {
      debugPrint('[Analytics] anonymous id read failed: ${e.runtimeType}');
    }
    final stored = state[field];
    if (stored is String && isValidUuid(stored)) {
      _value = stored;
      return;
    }
    final fresh = _value ?? uuidV4();
    _value = fresh;
    if (file == null) return;
    try {
      state[field] = fresh;
      final tmp = File('${file.path}.tmp');
      await tmp.writeAsString(jsonEncode(state), flush: true);
      await tmp.rename(file.path);
    } catch (e) {
      debugPrint('[Analytics] anonymous id write failed: ${e.runtimeType}');
    }
  }

  @visibleForTesting
  static void debugReset() => _value = null;
}

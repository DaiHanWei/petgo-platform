import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

import '../../../core/network/dio_client.dart';
import '../domain/content/ts_roles.dart';

/// Tailsonality 16 张角色卡（V1.3.2 · 2026-10-05 产品定）：**不打进 App 包**，放后端公开静态目录
/// `static/tailsonality/role_<四字母>.webp`，用户测出结果时按需下载那一张、落盘缓存（下次离线也能看）。
///
/// - 只认 [kTsRoles] 里的 16 个四字母，别的一律 null（不拼出奇怪的 URL / 文件路径）；
/// - 失败（断网 / 404 / 无文件系统）一律返回 null，调用方画代码占位，**不抛**；失败不记忆，下次重试；
/// - 换图时把 [version] +1：URL 带 `?v=` 绕开 CDN 缓存，落盘目录也随之换新，旧缓存自然弃用。
class TsRoleArt {
  TsRoleArt._();

  static const int version = 1;

  static String url(String letters, {String baseUrl = kApiBaseUrl}) =>
      '$baseUrl/tailsonality/role_$letters.webp?v=$version';

  /// 测试缝：非 null 时替代「读盘 + 下载」（`test/flutter_test_config.dart` 默认装成「取不到」，测试不联网）。
  @visibleForTesting
  static Future<Uint8List?> Function(String letters)? debugLoader;

  static final Map<String, Uint8List> _memory = {};
  static final Map<String, Future<Uint8List?>> _inflight = {};

  static final Dio _dio = Dio(BaseOptions(
    connectTimeout: const Duration(seconds: 10),
    receiveTimeout: const Duration(seconds: 30),
    responseType: ResponseType.bytes,
  ));

  /// 已在内存里的图（同步取，首帧不闪占位）。
  static Uint8List? peek(String letters) => _memory[letters];

  /// 取图：内存 → 磁盘 → 网络。同一张并发只发一次请求。
  static Future<Uint8List?> load(String letters) {
    if (!kTsRoles.containsKey(letters)) return Future.value();
    final hit = _memory[letters];
    if (hit != null) return Future.value(hit);
    return _inflight[letters] ??= () async {
      try {
        final loader = debugLoader;
        final bytes = loader != null ? await loader(letters) : await _diskThenNetwork(letters);
        if (bytes != null && bytes.isNotEmpty) _memory[letters] = bytes;
        return _memory[letters];
      } finally {
        _inflight.remove(letters);
      }
    }();
  }

  static Future<Uint8List?> _diskThenNetwork(String letters) async {
    File? file;
    try {
      final dir = await getApplicationSupportDirectory();
      file = File('${dir.path}/tailsonality_role_art_v$version/role_$letters.webp');
      if (await file.exists()) {
        final cached = await file.readAsBytes();
        if (cached.isNotEmpty) return cached;
      }
    } catch (_) {
      file = null; // 没有可用的文件系统：只走网络、不落盘。
    }
    try {
      final res = await _dio.get<List<int>>(url(letters));
      final data = res.data;
      if (data == null || data.isEmpty) return null;
      final bytes = Uint8List.fromList(data);
      if (file != null) {
        try {
          await file.parent.create(recursive: true);
          await file.writeAsBytes(bytes, flush: true);
        } catch (_) {
          // 落盘失败不影响本次显示。
        }
      }
      return bytes;
    } catch (_) {
      return null;
    }
  }

  @visibleForTesting
  static void debugReset() {
    _memory.clear();
    _inflight.clear();
  }
}

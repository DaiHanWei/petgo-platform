import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

import '../../../core/network/dio_client.dart';
import '../domain/content/ts_roles.dart';

/// Tailsonality 远程素材（V1.3.2 · 2026-10-05 产品定）：16 张角色卡 + 5 张配型卡**不打进 App 包**，
/// 放后端公开静态目录 `static/tailsonality/<名字>.webp`，用户测出结果时按需下载那一张、落盘缓存（下次离线也能看）。
///
/// 名字（不含扩展名）只有两种：[role] = `role_<四字母>`、[match] = `match_tier<1..5>`。
///
/// - 只认 [known] 里的 21 个名字，别的一律 null（不拼出奇怪的 URL / 文件路径）；
/// - 失败（断网 / 404 / 无文件系统）一律返回 null，调用方画代码占位，**不抛**；失败不记忆，下次重试；
/// - 换图时把 [version] +1：URL 带 `?v=` 绕开 CDN 缓存，落盘目录也随之换新，旧缓存自然弃用。
class TsRemoteArt {
  TsRemoteArt._();

  static const int version = 1;

  /// 角色卡（按四字母，16 张共用、不分物种）。
  static String role(String letters) => 'role_$letters';

  /// 配型卡（档号 1 = 4/4 … 5 = 0/4，见 `TsMatch.tier`）。
  static String match(int tier) => 'match_tier$tier';

  static final Set<String> known = {
    for (final letters in kTsRoles.keys) role(letters),
    for (var tier = 1; tier <= 5; tier++) match(tier),
  };

  static String url(String name, {String baseUrl = kApiBaseUrl}) =>
      '$baseUrl/tailsonality/$name.webp?v=$version';

  /// 测试缝：非 null 时替代「读盘 + 下载」（`test/flutter_test_config.dart` 默认装成「取不到」，测试不联网）。
  @visibleForTesting
  static Future<Uint8List?> Function(String name)? debugLoader;

  static final Map<String, Uint8List> _memory = {};
  static final Map<String, Future<Uint8List?>> _inflight = {};

  static final Dio _dio = Dio(BaseOptions(
    connectTimeout: const Duration(seconds: 10),
    receiveTimeout: const Duration(seconds: 30),
    responseType: ResponseType.bytes,
  ));

  /// 已在内存里的图（同步取，首帧不闪占位）。
  static Uint8List? peek(String name) => _memory[name];

  /// 取图：内存 → 磁盘 → 网络。同一张并发只发一次请求。
  static Future<Uint8List?> load(String name) {
    if (!known.contains(name)) return Future.value();
    final hit = _memory[name];
    if (hit != null) return Future.value(hit);
    return _inflight[name] ??= () async {
      try {
        final loader = debugLoader;
        final bytes = loader != null ? await loader(name) : await _diskThenNetwork(name);
        if (bytes != null && bytes.isNotEmpty) _memory[name] = bytes;
        return _memory[name];
      } finally {
        _inflight.remove(name);
      }
    }();
  }

  static Future<Uint8List?> _diskThenNetwork(String name) async {
    File? file;
    try {
      final dir = await getApplicationSupportDirectory();
      file = File('${dir.path}/tailsonality_art_v$version/$name.webp');
      if (await file.exists()) {
        final cached = await file.readAsBytes();
        if (cached.isNotEmpty) return cached;
      }
    } catch (_) {
      file = null; // 没有可用的文件系统：只走网络、不落盘。
    }
    try {
      final res = await _dio.get<List<int>>(url(name));
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


import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../../pet_passport/presentation/default_stamp_assets.dart';
import '../domain/place_summary.dart';

/// 取盖章动画的「章面」PNG 字节（V1.3.4）。
///
/// - 有 [url]：用无鉴权的裸 dio 取字节（章图是公开 OSS 地址，不能把登录令牌带出去）；
/// - 否则按 [type] 读包内默认章 asset；
/// - 超时 / 非 2xx / 空字节 / 任何异常 → null（调用方走兜底，不抛）。
///
/// [dio]、[bundle] 仅供测试注入。
Future<Uint8List?> loadStampFace({
  String? url,
  PlaceType? type,
  Duration timeout = const Duration(seconds: 2),
  Dio? dio,
  AssetBundle? bundle,
}) async {
  try {
    final Uint8List? bytes;
    if (url != null && url.isNotEmpty) {
      final client = dio ?? Dio();
      final resp = await client.get<List<int>>(
        url,
        options: Options(
          responseType: ResponseType.bytes,
          receiveTimeout: timeout,
          sendTimeout: timeout,
          validateStatus: (s) => s != null && s >= 200 && s < 300,
        ),
      ).timeout(timeout);
      final data = resp.data;
      bytes = data == null ? null : Uint8List.fromList(data);
    } else {
      final asset = defaultStampAssetFor(type);
      if (asset == null) return null;
      final bd = await (bundle ?? rootBundle).load(asset).timeout(timeout);
      bytes = bd.buffer.asUint8List(bd.offsetInBytes, bd.lengthInBytes);
    }
    if (bytes == null || bytes.isEmpty) return null;
    return bytes;
  } catch (e) {
    debugPrint('loadStampFace failed: $e');
    return null;
  }
}

import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/ops_popup.dart';

/// 运营弹窗数据层（V1.3.4 Story 5.4 · AC4，消费 Story 5.1 两个接口）。
///
/// 🔴 **失败即空、绝不抛**（NFR5）：候选接口异常 / 超时 / 非 2xx 都视为「没有弹窗」，
/// 不打扰启动、不出任何错误提示。曝光上报失败静默、不重试。
class OpsPopupRepository {
  OpsPopupRepository({required this.dio});

  final Dio dio;

  static const Duration fetchTimeout = Duration(seconds: 5);

  /// 候选列表（服务端已按优先级排序）。响应 `{"items": [...]}`；单条不合法丢弃。
  Future<List<OpsPopupCandidate>> fetchCandidates() async {
    try {
      final resp = await dio.get<Object>(ApiPaths.opsPopupCandidates).timeout(fetchTimeout);
      final code = resp.statusCode ?? 0;
      if (code < 200 || code >= 300) return const [];
      final data = resp.data;
      if (data is! Map) return const [];
      final items = data['items'];
      if (items is! List) return const [];
      return [
        for (final raw in items) ?OpsPopupCandidate.fromJson(raw),
      ];
    } catch (_) {
      return const [];
    }
  }

  /// 曝光上报（仅登录用户调用，Story 5.1 AC4）。吞一切异常。
  Future<void> reportImpression(String token) async {
    try {
      await dio.post<void>(ApiPaths.opsPopupImpressions(token));
    } catch (_) {
      // 静默、不重试。
    }
  }
}

final opsPopupRepositoryProvider =
    Provider<OpsPopupRepository>((ref) => OpsPopupRepository(dio: ref.read(dioProvider)));

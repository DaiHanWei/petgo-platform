import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';

/// 用户侧问诊数据层（Story 5.2 起）。本故事仅「是否有兽医在线」可用性查询。
class ConsultRepository {
  ConsultRepository({required this.dio});

  final Dio dio;

  /// 是否有兽医在线（只回 bool，不含人数）。失败时保守返回 false（离线态）。
  Future<bool> vetOnline() async {
    try {
      final resp = await dio.get<Map<String, dynamic>>(ApiPaths.consultAvailability);
      return (resp.data!['vetOnline'] ?? false) as bool;
    } on DioException {
      return false;
    }
  }
}

final consultRepositoryProvider =
    Provider<ConsultRepository>((ref) => ConsultRepository(dio: ref.read(dioProvider)));

/// 兽医咨询可用性（FutureProvider，入口渲染前读取）。
final consultAvailabilityProvider =
    FutureProvider.autoDispose<bool>((ref) => ref.read(consultRepositoryProvider).vetOnline());

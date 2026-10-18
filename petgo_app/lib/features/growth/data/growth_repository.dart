import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/growth_models.dart';

/// Tailrent Level 数据层（V1.4.0 ① Story 1.5）：等级页首屏 + EXP 明细分页。JWT / 续期由 `AuthInterceptor` 处理。
class GrowthRepository {
  GrowthRepository({required this.dio});

  final Dio dio;

  Future<GrowthSummary> fetchPage() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.meGrowth);
    return GrowthSummary.fromJson(resp.data!);
  }

  Future<GrowthLedgerPage> fetchLedger({String? cursor}) async {
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.meGrowthLedger,
      queryParameters: {'cursor': ?cursor},
    );
    return GrowthLedgerPage.fromJson(resp.data!);
  }
}

final growthRepositoryProvider =
    Provider<GrowthRepository>((ref) => GrowthRepository(dio: ref.read(dioProvider)));

/// 等级页首屏。autoDispose：只在等级页内使用、离开即回收，无需登记 `resetUserScopedCaches`。
final growthPageProvider = FutureProvider.autoDispose<GrowthSummary>(
  (ref) => ref.read(growthRepositoryProvider).fetchPage(),
);

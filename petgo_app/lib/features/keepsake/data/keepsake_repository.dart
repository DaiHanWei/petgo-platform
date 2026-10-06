import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/keepsake_pricing.dart';

/// 一次性解锁公共数据层（V1.3.2 Story 3.2；3.4 / 3.5 复用）。价格与 KTP 同一接口，独立解析（不改 `hdPrice`）。
class KeepsakeRepository {
  KeepsakeRepository({required this.dio});

  final Dio dio;

  Future<KeepsakePricing> pricing() async {
    final res = await dio.get<Map<String, dynamic>>(ApiPaths.meIdCardHdPricing);
    return KeepsakePricing.fromJson(res.data ?? const {});
  }
}

final keepsakeRepositoryProvider =
    Provider<KeepsakeRepository>((ref) => KeepsakeRepository(dio: ref.read(dioProvider)));

/// 当前四价。**无本地兜底**：失败为 AsyncError，购买入口显示重试并禁付款。
final keepsakePricingProvider = FutureProvider.autoDispose<KeepsakePricing>(
    (ref) => ref.read(keepsakeRepositoryProvider).pricing());

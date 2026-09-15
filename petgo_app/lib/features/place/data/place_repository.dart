import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/place_summary.dart';

/// 场所数据层（V1.3.0 batch-b1 Story 1.1，消费 `GET /api/v1/places`）。
///
/// 🔒 **本接口对游客开放**（后端 `SecurityConfig` 已放行 GET）。因此这里
/// **不做任何登录判断、不触发登录引导** —— 场所列表是「这个功能里已经攒了些什么地方」
/// 的展示面，用登录墙拦它没有意义（同 Toko 商品列表的既定取舍）。
///
/// 错误以 [DioException] 抛给页面（F13 统一口径：保留已加载内容 + 给重试入口）；
/// 401 由 AuthInterceptor 处理，repository 不自理（与 `shop_repository.dart` 同范式）。
class PlaceRepository {
  PlaceRepository({required this.dio});

  final Dio dio;

  /// 场所列表。
  ///
  /// ⚠️ Story 1.1 只有「按最新」一条路径 —— 坐标参数由 Story 1.2 加，
  /// 届时**新增可选入参**、由服务端下发的 `sortMode` 决定界面显示不显示距离位。
  Future<PlaceListResult> fetchPlaces() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.places);
    final data = resp.data;
    if (data == null) {
      // 空响应体当作空列表（走空态），而不是抛错 —— 空库是正常态，不是故障。
      return const PlaceListResult(items: [], sortMode: PlaceSortMode.recent);
    }
    return PlaceListResult.fromJson(data);
  }
}

final placeRepositoryProvider =
    Provider<PlaceRepository>((ref) => PlaceRepository(dio: ref.read(dioProvider)));

/// 场所列表。
///
/// `autoDispose`：场所列表是从首页入口推进来的一次性页面，退出后没必要留着。
final placeListProvider = FutureProvider.autoDispose<PlaceListResult>((ref) async {
  return ref.read(placeRepositoryProvider).fetchPlaces();
});

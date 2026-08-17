import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/shop_product.dart';

/// Toko 数据层（Story 1.6，消费 1-1 的只读接口）。
///
/// 🔒 **本接口对游客开放**（Story 1.1 已在 `SecurityConfig` 放行 GET，实测游客 200）。
/// 因此这里**不做任何登录判断、不触发登录引导**——FR-93A 的整个意思就是这一层不设门槛。
///
/// 错误以 [DioException] 抛给控制器；401 由 AuthInterceptor 处理，repository 不自理
/// （与 `order_repository.dart` 同范式）。
class ShopRepository {
  ShopRepository({required this.dio});

  final Dio dio;

  /// 拉商品列表。[category] 为空 = 全部精选（区域④）。
  Future<List<ShopProductSummary>> fetchProducts({ShopCategory? category}) async {
    final resp = await dio.get<List<dynamic>>(
      ApiPaths.shopProducts,
      queryParameters: {'category': ?category?.api},
    );
    final rows = resp.data ?? const [];
    return rows
        .whereType<Map<String, dynamic>>()
        .map(ShopProductSummary.fromJson)
        .toList(growable: false);
  }
}

final shopRepositoryProvider =
    Provider<ShopRepository>((ref) => ShopRepository(dio: ref.read(dioProvider)));

/// 商品列表，按品类分族（null = 全部精选，即区域④）。
///
/// 选中态由页面自己的 State 持有 —— 一个纯 UI 筛选没必要提升成全局 provider，
/// 也就顺带避开了 Riverpod 3 已移除 `StateProvider` 的问题。
final shopProductsProvider = FutureProvider.autoDispose
    .family<List<ShopProductSummary>, ShopCategory?>((ref, category) async {
  return ref.read(shopRepositoryProvider).fetchProducts(category: category);
});

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/shop_banner.dart';
import '../domain/shop_product.dart';
import '../domain/shop_product_detail.dart';

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

  /// Toko 顶部 banner（2026-08-27）。**没有可展示的 banner 时返回 null**。
  ///
  /// 🔴 判据是 **204 No Content**，不是"data 为空"：后端在拼不出 URL 时也回 204，
  /// 把"有没有 banner"收敛成了一个明确的状态码。这里照着它判，不要改成判 data ——
  /// 判 data 会把网络层的空响应也误当成"没有 banner"。
  ///
  /// ⚠️ 拉取失败一律当作**没有 banner**（返回 null）而不是抛错：banner 是锦上添花的
  /// 展示位，它挂了不该让整个 Toko 页进入错误态 —— 那是主次颠倒。
  Future<ShopBanner?> fetchBanner() async {
    try {
      final resp = await dio.get<Map<String, dynamic>>(ApiPaths.shopBanner);
      if (resp.statusCode == 204 || resp.data == null) return null;
      return ShopBanner.fromJson(resp.data!);
    } catch (_) {
      return null;
    }
  }

  /// 商品详情（Story 1.7）。未上架/不存在 → 后端 404（`DioException` 抛给页面）。
  /// 🔒 同样对游客开放，不做登录判断。
  Future<ShopProductDetail> fetchDetail(String token) async {
    final resp = await dio.get<Map<String, dynamic>>('${ApiPaths.shopProducts}/$token');
    return ShopProductDetail.fromJson(resp.data!);
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

/// Toko 顶部 banner。null = 没有可展示的 banner（页面据此显示白色顶栏）。
///
/// 🔴 **不用 autoDispose**：banner 变动极少，而 Toko 是高频进出的 Tab ——
/// 每次进出都重拉一次纯属浪费，且会让顶部在每次返回时闪一下。
final shopBannerProvider = FutureProvider<ShopBanner?>((ref) async {
  return ref.read(shopRepositoryProvider).fetchBanner();
});

/// 商品详情（按 token）。
final shopProductDetailProvider =
    FutureProvider.autoDispose.family<ShopProductDetail, String>((ref, token) async {
  return ref.read(shopRepositoryProvider).fetchDetail(token);
});

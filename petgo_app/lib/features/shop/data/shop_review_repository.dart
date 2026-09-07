import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/shop_review.dart';

/// 商品评价数据层（Story 7.3 读侧；7.1 的提交入口一并备好，供 7.2 补稿后接）。
class ShopReviewRepository {
  ShopReviewRepository({required this.dio});

  final Dio dio;

  /// 🔒 对游客开放（与商品详情同策略）。
  Future<ProductReviews> forProduct(String productToken) async {
    final resp = await dio
        .get<Map<String, dynamic>>(ApiPaths.shopProductReviews(productToken));
    return ProductReviews.fromJson(resp.data!);
  }

  /// 提交评价。返回体带 `reviewStatus` —— 同步过滤当场出结论，前端据此渲染三态。
  Future<ShopReviewItem> submit({
    required String orderToken,
    required int orderLineId,
    required int rating,
    String? content,
    List<String>? imageKeys,
  }) async {
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.meShopReviews,
      data: {
        'orderToken': orderToken,
        'orderLineId': orderLineId,
        'rating': rating,
        'content': content,
        'imageKeys': imageKeys,
      },
    );
    return ShopReviewItem.fromJson(resp.data!);
  }

  /// 被拦截后改内容重提。🔴 覆盖原记录 —— 唯一约束在订单行上，新建必 409。
  Future<ShopReviewItem> resubmit({
    required int reviewId,
    required int rating,
    String? content,
    List<String>? imageKeys,
  }) async {
    final resp = await dio.post<Map<String, dynamic>>(
      '${ApiPaths.meShopReviews}/$reviewId',
      data: {'rating': rating, 'content': content, 'imageKeys': imageKeys},
    );
    return ShopReviewItem.fromJson(resp.data!);
  }
}

final Provider<ShopReviewRepository> shopReviewRepositoryProvider =
    Provider<ShopReviewRepository>((ref) => ShopReviewRepository(dio: ref.read(dioProvider)));

/// 详情页评价区（按商品 token）。
final productReviewsProvider =
    FutureProvider.autoDispose.family<ProductReviews, String>((ref, productToken) async {
  return ref.read(shopReviewRepositoryProvider).forProduct(productToken);
});

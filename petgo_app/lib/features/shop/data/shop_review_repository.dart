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

  /// 全部评价页（v1.4.2 shop Story 6.1 / App 8.2 B12）的一页。[cursor] 为 null = 第一页。
  /// 🔒 游客可读；昵称已由服务端打码，运营隐藏的评价服务端已过滤。
  Future<ProductReviews> page(String productToken, {String? cursor, int limit = 20}) async {
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.shopProductReviews(productToken),
      queryParameters: {'limit': limit, 'cursor': ?cursor},
    );
    return ProductReviews.fromJson(resp.data ?? const {});
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

/// 全部评价页的累积状态（B12）。
class ReviewListState {
  const ReviewListState({
    required this.total,
    required this.items,
    required this.hasMore,
    this.averageRating,
    this.cursor,
    this.loadingMore = false,
    this.loadMoreFailed = false,
  });

  final int total;
  final double? averageRating;

  /// 🔴 累积（第一页 + 已加载的后续页）。
  final List<ShopReviewItem> items;
  final String? cursor;
  final bool hasMore;
  final bool loadingMore;

  /// 上一次「加载更多」失败（列表底部给重试，不把整页打成错误态）。
  final bool loadMoreFailed;

  ReviewListState copyWith({bool? loadingMore, bool? loadMoreFailed}) => ReviewListState(
        total: total,
        averageRating: averageRating,
        items: items,
        cursor: cursor,
        hasMore: hasMore,
        loadingMore: loadingMore ?? this.loadingMore,
        loadMoreFailed: loadMoreFailed ?? this.loadMoreFailed,
      );
}

/// 全部评价页控制器（游标分页，触底加载）。同 `ShopProductListController` 的并发 / 到底 / 回收纪律。
class ReviewListController extends AsyncNotifier<ReviewListState> {
  ReviewListController(this._productToken);

  final String _productToken;

  @override
  Future<ReviewListState> build() async {
    final p = await ref.read(shopReviewRepositoryProvider).page(_productToken);
    return ReviewListState(
      total: p.total,
      averageRating: p.averageRating,
      items: p.items,
      cursor: p.nextCursor,
      hasMore: p.hasMore && p.nextCursor != null,
    );
  }

  Future<void> loadMore() async {
    final current = state.asData?.value;
    if (current == null || !current.hasMore || current.loadingMore) return;
    state = AsyncData(current.copyWith(loadingMore: true, loadMoreFailed: false));
    final ProductReviews p;
    try {
      p = await ref.read(shopReviewRepositoryProvider).page(_productToken, cursor: current.cursor);
    } catch (_) {
      if (!ref.mounted) return;
      state = AsyncData(current.copyWith(loadingMore: false, loadMoreFailed: true));
      return;
    }
    if (!ref.mounted) return;
    state = AsyncData(ReviewListState(
      total: current.total,
      averageRating: current.averageRating,
      items: [...current.items, ...p.items],
      cursor: p.nextCursor,
      hasMore: p.hasMore && p.nextCursor != null,
    ));
  }
}

final reviewListProvider = AsyncNotifierProvider.autoDispose
    .family<ReviewListController, ReviewListState, String>(ReviewListController.new);

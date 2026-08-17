/// 商品评价域模型（Story 7.3 详情页评价区，FR-106）。
///
/// 🔒 **不含评价者身份**：详情页只需要星级、文字、图、时间。
/// 下发昵称/头像会让「谁买了什么」变成公开信息 —— 而宠物用品的购买记录本身带健康暗示。
library;

class ShopReviewItem {
  const ShopReviewItem({
    required this.id,
    required this.rating,
    required this.imageUrls,
    this.content,
    this.createdAt,
    this.reviewStatus,
  });

  final int id;
  final int rating;
  final String? content;
  final List<String> imageUrls;
  final DateTime? createdAt;

  /// 仅「我的评价」有值：`PUBLISHED` / `BLOCKED` / `PENDING`。详情页列表恒为 null。
  final String? reviewStatus;

  factory ShopReviewItem.fromJson(Map<String, dynamic> j) => ShopReviewItem(
        id: (j['id'] as num?)?.toInt() ?? 0,
        rating: (j['rating'] as num?)?.toInt() ?? 0,
        content: j['content']?.toString(),
        imageUrls: j['imageUrls'] is List
            ? (j['imageUrls'] as List).map((e) => e.toString()).toList(growable: false)
            : const [],
        createdAt: _time(j['createdAt']),
        reviewStatus: j['reviewStatus']?.toString(),
      );
}

/// 详情页的评价区。
///
/// 🔴 **无评价时 [total] 为 0 且 [averageRating] 为 null** —— 前端据此渲染空态。
/// ⚠️ **不伪造或预填评价**（FR-106）：一个刚上架的商品就有五星好评，
/// 是最快毁掉整个评价区可信度的做法。
///
/// 🔴 [averageRating] 是 null 而**不是 0**：0 会被渲染成「零分」。
class ProductReviews {
  const ProductReviews({
    required this.total,
    required this.items,
    this.averageRating,
  });

  final int total;
  final double? averageRating;
  final List<ShopReviewItem> items;

  bool get isEmpty => items.isEmpty;

  factory ProductReviews.fromJson(Map<String, dynamic> j) => ProductReviews(
        total: (j['total'] as num?)?.toInt() ?? 0,
        averageRating: (j['averageRating'] as num?)?.toDouble(),
        items: j['items'] is List
            ? (j['items'] as List)
                .whereType<Map<String, dynamic>>()
                .map(ShopReviewItem.fromJson)
                .toList(growable: false)
            : const [],
      );

  static const ProductReviews empty = ProductReviews(total: 0, items: []);
}

DateTime? _time(Object? v) {
  if (v is! String || v.isEmpty) return null;
  return DateTime.tryParse(v)?.toLocal();
}

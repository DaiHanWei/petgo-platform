/// 商品评价域模型（Story 7.3 详情页评价区，FR-106）。
///
/// 🔒 **不含评价者身份**：详情页只需要星级、文字、图、时间。
/// 下发昵称/头像会让「谁买了什么」变成公开信息 —— 而宠物用品的购买记录本身带健康暗示。
/// ⚠️ v1.4.2 shop 起（决策 C-9）服务端下发**已打码**的昵称（首词 + 末词首字母），仍不下发头像 / 用户 token；
/// App 原样显示，不自行拼接或还原。
library;

class ShopReviewItem {
  const ShopReviewItem({
    required this.id,
    required this.rating,
    required this.imageUrls,
    this.content,
    this.createdAt,
    this.reviewStatus,
    this.nickname,
    this.specName,
  });

  final int id;
  final int rating;
  final String? content;
  final List<String> imageUrls;
  final DateTime? createdAt;

  /// 仅「我的评价」有值：`PUBLISHED` / `BLOCKED` / `PENDING`。详情页列表恒为 null。
  final String? reviewStatus;

  /// v1.4.2 shop Story 6.1（C-9）：**服务端打码后**的昵称（如「Dewi S.」）；注销 / 封号 / 我的评价 → null（不画该行）。
  final String? nickname;

  /// v1.4.2 shop Story 6.1（C-9）：所评订单行的规格名快照；缺失 → null（不画「Varian」行）。
  final String? specName;

  factory ShopReviewItem.fromJson(Map<String, dynamic> j) => ShopReviewItem(
        id: (j['id'] as num?)?.toInt() ?? 0,
        rating: (j['rating'] as num?)?.toInt() ?? 0,
        content: j['content']?.toString(),
        imageUrls: j['imageUrls'] is List
            ? (j['imageUrls'] as List).map((e) => e.toString()).toList(growable: false)
            : const [],
        createdAt: _time(j['createdAt']),
        reviewStatus: j['reviewStatus']?.toString(),
        nickname: _blank(j['nickname']),
        specName: _blank(j['specName']),
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
    this.displayEnabled = false,
    this.nextCursor,
    this.hasMore = false,
  });

  final int total;
  final double? averageRating;
  final List<ShopReviewItem> items;

  /// v1.4.2 shop Story 6.1：后台评价列表展示开关；false（默认）时 [items] 恒为空，条数与均分照给。
  final bool displayEnabled;

  /// 全部评价页（B12）的下一页游标；末页 / 开关关 → null。
  final String? nextCursor;

  /// 还有下一页。🔴 「到底了」的唯一判据。
  final bool hasMore;

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
        displayEnabled: j['displayEnabled'] == true,
        nextCursor: _blank(j['nextCursor']),
        hasMore: j['hasMore'] == true,
      );

  static const ProductReviews empty = ProductReviews(total: 0, items: []);
}

String? _blank(Object? v) => (v == null || v.toString().isEmpty) ? null : v.toString();

DateTime? _time(Object? v) {
  if (v is! String || v.isEmpty) return null;
  return DateTime.tryParse(v)?.toLocal();
}

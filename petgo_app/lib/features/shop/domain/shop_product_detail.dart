/// 商品详情域模型（Story 1.7，消费 1-1 的 `GET /api/v1/shop/products/{token}`）。
library;

import 'shop_product.dart';

/// 退货规则**三值**（C-13 砍掉换货后收敛）。
///
/// 🔴 措辞是 UX-DR10 定的**二义三值**：`可退` / `开封不退` / `不可退`。
/// 原型里的「可退可换」「不可退换」**已作废** —— 换货在两份 PRD 中零实现，
/// 标出「可换」等于让用户端展示**平台无法兑现的承诺**。
enum ReturnPolicy {
  returnable('RETURNABLE'),
  noReturnAfterOpen('NO_RETURN_AFTER_OPEN'),
  noReturn('NO_RETURN');

  const ReturnPolicy(this.api);

  final String api;

  static ReturnPolicy fromApi(String? raw) {
    for (final v in ReturnPolicy.values) {
      if (v.api == raw) return v;
    }
    // 未知值按最保守的一档处理：宁可少承诺，不可多承诺
    return ReturnPolicy.noReturn;
  }
}

/// 库存展示三态（后端 `StockStatus`，由可售库存与运营阈值算出，不落库）。
enum StockStatus {
  inStock('IN_STOCK'),
  lowStock('LOW_STOCK'),
  outOfStock('OUT_OF_STOCK');

  const StockStatus(this.api);

  final String api;

  static StockStatus fromApi(String? raw) {
    for (final v in StockStatus.values) {
      if (v.api == raw) return v;
    }
    // 🔴 未知态按售罄处理：宁可挡住一次可能成立的购买，也不能放过一次超卖
    return StockStatus.outOfStock;
  }

  bool get purchasable => this != StockStatus.outOfStock;
}

/// 一个规格（SKU）。
class ShopSku {
  const ShopSku({
    required this.token,
    required this.specName,
    required this.price,
    required this.returnPolicy,
    required this.stockStatus,
    this.netWeightG,
    this.remaining,
  });

  final String token;
  final String specName;
  final int price;

  /// 🔴 **同商品的不同 SKU 可有不同退货规则** —— 切换规格必须同步刷新该标识。
  final ReturnPolicy returnPolicy;
  final StockStatus stockStatus;
  final int? netWeightG;

  /// 真实剩余可售数。🔴 展示 `Sisa {n}` 时取它，**不虚构数字**（FR-95）。
  final int? remaining;

  factory ShopSku.fromJson(Map<String, dynamic> json) {
    final price = json['price'];
    final remaining = json['remaining'];
    final weight = json['netWeightG'];
    return ShopSku(
      token: json['token']?.toString() ?? '',
      specName: json['specName']?.toString() ?? '',
      price: price is num ? price.toInt() : 0,
      returnPolicy: ReturnPolicy.fromApi(json['returnPolicy']?.toString()),
      stockStatus: StockStatus.fromApi(json['stockStatus']?.toString()),
      netWeightG: weight is num ? weight.toInt() : null,
      remaining: remaining is num ? remaining.toInt() : null,
    );
  }
}

/// 每日建议喂量的一行（区间 → 克/天）。
class FeedingGuideEntry {
  const FeedingGuideEntry({
    required this.minWeightKg,
    required this.maxWeightKg,
    required this.gramsPerDay,
  });

  final int minWeightKg;
  final int maxWeightKg;
  final int gramsPerDay;

  static FeedingGuideEntry? fromJson(Map<String, dynamic> json) {
    final a = json['minWeightKg'], b = json['maxWeightKg'], g = json['gramsPerDay'];
    if (a is! num || b is! num || g is! num) return null;
    return FeedingGuideEntry(
      minWeightKg: a.toInt(),
      maxWeightKg: b.toInt(),
      gramsPerDay: g.toInt(),
    );
  }
}

class ShopProductDetail {
  const ShopProductDetail({
    required this.token,
    required this.name,
    required this.brand,
    required this.returnPolicy,
    required this.skus,
    this.category,
    this.mainImageUrl,
    this.galleryUrls = const [],
    this.detailHtml,
    this.shelfLifeNote,
    this.feedingGuide = const [],
  });

  final String token;
  final String name;
  final String brand;
  final ShopCategory? category;
  final String? mainImageUrl;

  /// 图集（后端已限 ≤8）。空 = 只有主图。
  final List<String> galleryUrls;
  final String? detailHtml;
  final String? shelfLifeNote;

  /// 商品级退货规则。未选规格时展示它；选中后以该 SKU 的为准。
  final ReturnPolicy returnPolicy;
  final List<FeedingGuideEntry> feedingGuide;
  final List<ShopSku> skus;

  /// 起价。无 SKU → null。
  int? get minPrice =>
      skus.isEmpty ? null : skus.map((s) => s.price).reduce((a, b) => a < b ? a : b);

  /// 🔴 **单一规格才直通加购**；多规格必须先选（FR-94A，防误购）。
  bool get isSingleSku => skus.length == 1;

  factory ShopProductDetail.fromJson(Map<String, dynamic> json) {
    List<String> strList(Object? raw) =>
        raw is List ? raw.map((e) => e.toString()).where((e) => e.isNotEmpty).toList() : const [];

    final rawSkus = json['skus'];
    final rawFeeding = json['feedingGuide'];
    return ShopProductDetail(
      token: json['token']?.toString() ?? '',
      name: json['name']?.toString() ?? '',
      brand: json['brand']?.toString() ?? '',
      category: ShopCategory.fromApi(json['category']?.toString()),
      mainImageUrl: _blankToNull(json['mainImageUrl']?.toString()),
      galleryUrls: strList(json['galleryUrls']),
      detailHtml: _blankToNull(json['detailHtml']?.toString()),
      shelfLifeNote: _blankToNull(json['shelfLifeNote']?.toString()),
      returnPolicy: ReturnPolicy.fromApi(json['returnPolicy']?.toString()),
      feedingGuide: rawFeeding is List
          ? rawFeeding
              .whereType<Map<String, dynamic>>()
              .map(FeedingGuideEntry.fromJson)
              .whereType<FeedingGuideEntry>()
              .toList()
          : const [],
      skus: rawSkus is List
          ? rawSkus.whereType<Map<String, dynamic>>().map(ShopSku.fromJson).toList()
          : const [],
    );
  }

  static String? _blankToNull(String? s) => (s == null || s.isEmpty) ? null : s;
}

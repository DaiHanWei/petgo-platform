/// Toko 商品域模型（Story 1.6，消费 1-1 的 `GET /api/v1/shop/products`）。
///
/// 🔴 `minPrice` 与 `mainImageUrl` 均**可空**：
/// - `minPrice` 为 null = 该商品尚无 SKU（后端契约），价格位显占位而非 0；
/// - `mainImageUrl` 为 null = 未配 CDN base 或无主图，卡片降级到占位图而非白屏。
///
/// 两者都不是异常路径——运营录商品时先建商品后配 SKU 是正常节奏。
library;

/// 四个固定品类（对应后端 `ProductCategory`）。
///
/// 🔴 **固定四值，不做「全部品类」之外的动态扩展**——品类是 FR-93 的信息架构，
/// 不是运营可配项。新增品类须走 PRD 变更，不是加一行枚举。
enum ShopCategory {
  makanan('MAKANAN'),
  obatVitamin('OBAT_VITAMIN'),
  camilan('CAMILAN'),
  perawatan('PERAWATAN');

  const ShopCategory(this.api);

  /// 后端枚举字面量（UPPER_SNAKE，命名映射链）。
  final String api;

  static ShopCategory? fromApi(String? raw) {
    if (raw == null) return null;
    for (final c in ShopCategory.values) {
      if (c.api == raw) return c;
    }
    return null;
  }
}

/// 商品列表项。字段与后端 `ShopProductSummaryView` 一一对应。
class ShopProductSummary {
  const ShopProductSummary({
    required this.token,
    required this.name,
    required this.brand,
    this.category,
    this.mainImageUrl,
    this.minPrice,
  });

  /// 不可枚举对外标识（NFR-3）。🔴 后端不下发自增 id，前端也不该需要。
  final String token;
  final String name;
  final String brand;
  final ShopCategory? category;

  /// 公开桶 CDN 全 URL。null → 用占位图。
  final String? mainImageUrl;

  /// 起价（最小币种单位，IDR 无小数）。null → 无 SKU。
  final int? minPrice;

  factory ShopProductSummary.fromJson(Map<String, dynamic> json) {
    final price = json['minPrice'];
    return ShopProductSummary(
      token: json['token']?.toString() ?? '',
      name: json['name']?.toString() ?? '',
      brand: json['brand']?.toString() ?? '',
      category: ShopCategory.fromApi(json['category']?.toString()),
      mainImageUrl: _blankToNull(json['mainImageUrl']?.toString()),
      minPrice: price is num ? price.toInt() : null,
    );
  }

  static String? _blankToNull(String? s) => (s == null || s.isEmpty) ? null : s;
}

/// IDR 金额格式化：`285000` → `Rp 285.000`。
///
/// 🔴 印尼盾**无小数**、千分位用**点**（与中英文的逗号相反）。
/// 用逗号会让本地用户读成小数点——这是会真实误导的错，不是排版偏好。
String formatIdr(int amount) {
  final digits = amount.abs().toString();
  final buf = StringBuffer();
  for (var i = 0; i < digits.length; i++) {
    if (i > 0 && (digits.length - i) % 3 == 0) buf.write('.');
    buf.write(digits[i]);
  }
  return 'Rp ${amount < 0 ? '-' : ''}$buf';
}

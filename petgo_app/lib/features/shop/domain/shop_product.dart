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
    this.mainImageW,
    this.mainImageH,
    this.minPrice,
  });

  /// 不可枚举对外标识（NFR-3）。🔴 后端不下发自增 id，前端也不该需要。
  final String token;
  final String name;
  final String brand;
  final ShopCategory? category;

  /// 公开桶 CDN 全 URL。null → 用占位图。
  final String? mainImageUrl;

  /// 主图原始像素宽高（2026-08-27）。
  ///
  /// 🔴 **存量商品恒为 null** —— 尺寸列是上传时才测的，存量图早在对象存储里、不回填
  /// （与内容侧「存量内容永远是 null」同一处理）。所以占位兜底不可取消。
  final int? mainImageW;
  final int? mainImageH;

  /// 瀑布流卡片的图片宽高比（w / h）。**null ⇒ 调用方必须走占位兜底**。
  ///
  /// 🔴 收敛到 [kShopImageRatioMin] ~ [kShopImageRatioMax]，与内容侧 Feed 同一区间
  /// （见 `feed_image_layout.dart` 的三段口径）：不收敛的话，一张 1:3 的长图会把卡片
  /// 拉到三倍列宽那么高，后面的商品全被挤出首屏。
  ///
  /// ⚠️ 收敛发生在**客户端**，服务端只给原始宽高 —— 两边都 clamp 就是双重裁切。
  double? get mainImageAspect {
    final w = mainImageW;
    final h = mainImageH;
    if (w == null || h == null || w <= 0 || h <= 0) return null;
    return (w / h).clamp(kShopImageRatioMin, kShopImageRatioMax);
  }

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
      mainImageW: _posIntOrNull(json['mainImageW']),
      mainImageH: _posIntOrNull(json['mainImageH']),
      minPrice: price is num ? price.toInt() : null,
    );
  }

  static String? _blankToNull(String? s) => (s == null || s.isEmpty) ? null : s;

  /// 宽高只接受正整数；0 / 负数 / 非数字一律当"测不出来"。
  /// ⚠️ 后端已挡过离谱值，这里是最后一道防线（同 `ImageSize.isUsable` 的判据）。
  static int? _posIntOrNull(Object? raw) {
    final n = raw is num ? raw.toInt() : null;
    return (n != null && n > 0) ? n : null;
  }
}

/// 商品图比例的收敛区间。**与内容侧 Feed 的 `kFeedRatioMin/Max` 取同值**：
/// 同一个 App 里两处图片流用不同的比例区间，用户会觉得其中一处"图被压过"。
/// 0.75 = 3:4 竖拍（最常见的竖图比例），1.34 ≈ 4:3 横图。
const double kShopImageRatioMin = 0.75;
const double kShopImageRatioMax = 1.34;

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

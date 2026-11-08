/// Toko 首页 banner（2026-08-27 立；v1.4.2 shop Story 8.1 起多张轮播）。
///
/// V1.4.1 契约：后端下发 [bannerId]（不可枚举 token，埋点 `banner_id`）与点击跳转 [target]。
/// v1.4.2（D-16 / FR-132）：`GET /shop/banners` 下发 `COUPON`（领券）与 `TOKO_HOME`（旧品类跳转改落首页）；
/// `CATEGORY` 不再出现在新端点，本包按未知值落 [ShopBannerTarget.none]（不可点）。
library;

/// banner 点击跳转类型（后端 `BannerTargetType` 的新端点下发值）。未知值一律按 [none]（不可点）——
/// 新后端加了新类型，旧包不认识就别让它可点，否则点了没反应。
enum ShopBannerTarget {
  none('NONE', 'none'),
  product('PRODUCT', 'product_detail'),
  coupon('COUPON', 'coupon'),
  tokoHome('TOKO_HOME', 'toko_home'),
  external('EXTERNAL', 'external_link');

  const ShopBannerTarget(this.wire, this.analytics);

  final String wire;

  /// 埋点 `target_type` 取值（snake_case 词表）；[none] 不可点，实际不会产生点击事件。
  final String analytics;

  static ShopBannerTarget fromWire(String? raw) {
    for (final t in values) {
      if (t.wire == raw) return t;
    }
    return none;
  }
}

/// 无尺寸时的兜底比例。
///
/// 🔴 3:1 是横幅的常见比例；真正的意义不在于"准"，而在于**图到达前后高度不变** ——
/// 手填 objectKey 的兜底路径给不出尺寸，此时用固定比例占位，好过让 banner 区
/// 从 0 高度弹开把整页内容推下去。
const double kShopBannerFallbackAspect = 3.0;

/// 轮播最多张数（D-16；服务端已限，这里兜底）。
const int kShopBannerMax = 6;

class ShopBanner {
  const ShopBanner({
    required this.imageUrl,
    this.imageW,
    this.imageH,
    this.bannerId,
    this.target = ShopBannerTarget.none,
    this.targetValue,
  });

  /// 埋点 `banner_id`（后端 public token）；旧后端为 null。
  final String? bannerId;
  final ShopBannerTarget target;

  /// 跳转目标值：商品 token / 券模板 token / https 链接；`TOKO_HOME` / `NONE` 为 null。
  final String? targetValue;

  /// 是否可点：
  /// - [ShopBannerTarget.tokoHome]：恒可点（留在首页，只记一次点击；后端不下发值）；
  /// - 商品 / 领券：目标值非空；外链额外要求 https（后台已校验，这里兜底）；
  /// - [ShopBannerTarget.none]：不可点。
  bool get tappable {
    final v = targetValue ?? '';
    return switch (target) {
      ShopBannerTarget.none => false,
      ShopBannerTarget.tokoHome => true,
      ShopBannerTarget.external => v.isNotEmpty && (Uri.tryParse(v)?.scheme ?? '') == 'https',
      ShopBannerTarget.product || ShopBannerTarget.coupon => v.isNotEmpty,
    };
  }

  /// 公开桶 CDN 全 URL。
  ///
  /// 🔴 后端在单一来源里已剔除拼不出 URL 的 banner；仓库层仍把空 URL 的条目丢掉兜底。
  final String imageUrl;

  /// 原始像素宽高；null = 未知（手填 objectKey 的兜底路径）。
  final int? imageW;
  final int? imageH;

  /// 宽高比（w / h）。未知时回落到 [kShopBannerFallbackAspect]。
  ///
  /// ⚠️ 与商品图不同，这里**不做区间收敛**：banner 是运营精心裁过的横幅，
  /// 比例本身就是设计的一部分，clamp 会把一张精心做成 4:1 的长横幅压成 1.78，
  /// 主视觉直接被裁掉。商品图要 clamp 是因为那些图来源杂乱、比例不可控。
  double get aspect {
    final w = imageW;
    final h = imageH;
    if (w == null || h == null || w <= 0 || h <= 0) {
      return kShopBannerFallbackAspect;
    }
    return w / h;
  }

  factory ShopBanner.fromJson(Map<String, dynamic> j) => ShopBanner(
        imageUrl: j['imageUrl']?.toString() ?? '',
        imageW: _posIntOrNull(j['imageW']),
        imageH: _posIntOrNull(j['imageH']),
        bannerId: j['bannerId']?.toString(),
        target: ShopBannerTarget.fromWire(j['targetType']?.toString()),
        targetValue: j['targetValue']?.toString(),
      );

  static int? _posIntOrNull(Object? raw) {
    final n = raw is num ? raw.toInt() : null;
    return (n != null && n > 0) ? n : null;
  }
}

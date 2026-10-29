/// Toko 顶部 banner（2026-08-27）。
///
/// V1.4.1 契约变更：后端下发 [bannerId]（不可枚举 token，埋点 `banner_id`）与点击跳转
/// [target]（不可点 / 商品详情 / 品类 / 外部链接）。旧后端不下发 → 不可点、不报 banner_id，与改动前一致。
/// 「领券」没有对应功能，后端不会下发这一类，这里也不建枚举值。
library;

/// banner 点击跳转类型（后端 `BannerTargetType`）。未知值一律按 [none]（不可点）—— 新后端加了新类型，
/// 旧包不认识就别让它可点，否则点了没反应。
enum ShopBannerTarget {
  none('NONE', null),
  product('PRODUCT', 'product_detail'),
  category('CATEGORY', 'category_list'),
  external('EXTERNAL', 'external_link');

  const ShopBannerTarget(this.wire, this.analytics);

  final String wire;

  /// 埋点 `target_type` 取值（snake_case 词表）；[none] 不可点，没有点击事件。
  final String? analytics;

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

  /// 跳转目标值：商品 token / 品类字面量 / https 链接。
  final String? targetValue;

  /// 是否可点：类型可识别且目标值非空；外链额外要求 https（后台已校验，这里兜底）。
  bool get tappable {
    final v = targetValue ?? '';
    if (v.isEmpty) return false;
    return switch (target) {
      ShopBannerTarget.none => false,
      ShopBannerTarget.external => (Uri.tryParse(v)?.scheme ?? '') == 'https',
      _ => true,
    };
  }

  /// 公开桶 CDN 全 URL。
  ///
  /// 🔴 后端保证它非空：拼不出 URL 时后端直接回 204（当作"没有 banner"），
  /// 不会下发一个 imageUrl 为 null 的空壳。所以这里不必再判一次 null。
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

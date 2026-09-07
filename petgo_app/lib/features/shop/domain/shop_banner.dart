/// Toko 顶部 banner（2026-08-27）。
///
/// 🔴 **纯展示、不可点**：本版本后端不下发任何跳转目标，所以这里也没有 target 字段。
/// 要加跳转时是一次明确的契约变更，不该靠一个恒为 null 的字段先占位 ——
/// 那会让人写出永远走不到的分支。
library;

/// 无尺寸时的兜底比例。
///
/// 🔴 3:1 是横幅的常见比例；真正的意义不在于"准"，而在于**图到达前后高度不变** ——
/// 手填 objectKey 的兜底路径给不出尺寸，此时用固定比例占位，好过让 banner 区
/// 从 0 高度弹开把整页内容推下去。
const double kShopBannerFallbackAspect = 3.0;

class ShopBanner {
  const ShopBanner({required this.imageUrl, this.imageW, this.imageH});

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
  /// 比例本身就是设计的一部分，clamp 会把一张精心做成 4:1 的长横幅压成 1.34，
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
      );

  static int? _posIntOrNull(Object? raw) {
    final n = raw is num ? raw.toInt() : null;
    return (n != null && n > 0) ? n : null;
  }
}
